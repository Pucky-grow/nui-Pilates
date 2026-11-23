#!/usr/bin/env python3
"""Snapshot, test and build the CloudRun Java image for linux/amd64. Never pushes/deploys."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--docker-context', required=True)
    p.add_argument('--java-home', required=True, type=pathlib.Path)
    p.add_argument('--output-root', type=pathlib.Path, default=ROOT / 'build-artifacts')
    a = p.parse_args()
    java_home = a.java_home.resolve()
    java = java_home / 'bin/java'
    settings = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True).stderr
    if not any('version "' + version + '.' in settings for version in ('17', '21')):
        p.error('use an explicit installed JDK 17 or 21; artifact bytecode targets Java 17')
    subprocess.run(['python3', str(ROOT / 'scripts/production/secret-scan.py')], check=True)
    selected = ['.gitignore', 'backend/pom.xml', 'backend/src', 'database/production', 'deploy/wechat-cloudrun', 'scripts/cloudrun']
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z', '--', *selected], cwd=ROOT).decode().split('\0')
    captured = {}
    for name in sorted(set(filter(None, names))):
        path = ROOT / name
        if path.is_symlink():
            raise ValueError('symlinks are not allowed in the source snapshot')
        if path.is_file():
            captured[name] = path.read_bytes()
    hashes = {name: digest(value) for name, value in captured.items()}
    ca_source = 'backend/src/main/resources/certificates/tencent-redis-ca.pem'
    ca_entry = 'BOOT-INF/classes/certificates/tencent-redis-ca.pem'
    if ca_source not in captured:
        raise ValueError('public Redis CA excluded from source snapshot; review the exact gitignore exception')
    ca = captured[ca_source]
    if b'BEGIN CERTIFICATE' not in ca or b'PRIVATE KEY' in ca:
        raise ValueError('Redis trust resource must contain public certificates only')
    source = digest(json.dumps(hashes, sort_keys=True).encode())
    version = 'cloudrun-' + source[:12]
    output = a.output_root.resolve() / version
    output.mkdir(parents=True, exist_ok=False)
    docker = ['docker', '--context', a.docker_context]
    tag = 'yoyi-server:' + version
    manifest = {'version': version, 'status': 'BUILDING',
                'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'createdAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'sourceTreeSha256': source, 'sourceHashes': hashes, 'images': {},
                'targetPlatform': 'linux/amd64', 'javaBytecodeTarget': 17,
                'buildJdk': settings.splitlines()[0], 'dockerContext': a.docker_context,
                'cloudDeployed': False, 'realCosVerified': False}

    def save():
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

    save()
    try:
        if subprocess.run(docker + ['image', 'inspect', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            raise ValueError('refusing to overwrite an existing version tag')
        with tempfile.TemporaryDirectory(prefix='pilates-cloudrun-build-') as directory:
            snapshot = pathlib.Path(directory)
            for name, value in captured.items():
                dest = snapshot / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(value)
            env = {**os.environ, 'JAVA_HOME': str(java_home)}
            with (output / 'maven.log').open('w') as log:
                command = ['mvn', '-B', '-ntp', '-f', str(snapshot / 'backend/pom.xml'),
                           '-Dpilates.build.directory=' + str(output / 'target'),
                           '-Dproject.build.outputTimestamp=2026-11-01T00:00:00Z',
                           '-Dtest=CosObjectStorageTest,ProductionGuardTest,CloudrunPrivateMysqlPolicyTest,CloudrunPreflightTest,RedissonTlsTest,WxMpClientTest,Round3ConfigurationTest,HomePlacementSqlTest,SystemConfigCacheCommitTest,CourseCategoryCompatibilityTest', 'package']
                subprocess.run(command, env=env, stdout=log, stderr=log, check=True)
            jar = output / 'target/pilates-server.jar'
            with zipfile.ZipFile(jar) as archive:
                bytecode = archive.read('BOOT-INF/classes/com/yoyi/pilates/PilatesApplication.class')
                if int.from_bytes(bytecode[6:8], 'big') != 61:
                    raise ValueError('wrong Java bytecode target')
                if ca_entry not in archive.namelist() or digest(archive.read(ca_entry)) != hashes[ca_source]:
                    raise ValueError('Redis CA missing or changed inside the packaged JAR')
            manifest['bundledRedisCa'] = {'entry': ca_entry, 'sha256': hashes[ca_source]}
            shutil.copyfile(jar, output / 'pilates-server.jar')
            manifest['jarSha256'] = digest(jar.read_bytes())
            context = output / 'java-upload'
            context.mkdir()
            for name in ('Dockerfile', '.dockerignore', 'container.config.json'):
                shutil.copyfile(snapshot / 'deploy/wechat-cloudrun' / name, context / name)
            shutil.copyfile(jar, context / 'pilates-server.jar')
            manifest['cloudrunBuildContextHashes'] = {path.name: digest(path.read_bytes()) for path in sorted(context.iterdir())}
            with (output / 'docker-build.log').open('w') as log:
                subprocess.run(docker + ['buildx', 'build', '--platform', 'linux/amd64', '--load',
                               '--label', 'org.opencontainers.image.revision=' + manifest['commit'],
                               '--label', 'com.yoyi.source.sha256=' + source, '--tag', tag, str(context)],
                               stdout=log, stderr=log, check=True)
        image = json.loads(subprocess.check_output(docker + ['image', 'inspect', tag], text=True))[0]
        if image['Os'] != 'linux' or image['Architecture'] != 'amd64' or image['Config']['User'] != '10001:10001':
            raise ValueError('wrong image platform/user')
        if set(image['Config'].get('ExposedPorts', {})) != {'8080/tcp'}:
            raise ValueError('only application port 8080 may be declared')
        manifest['images']['server'] = {'tag': tag, 'imageId': image['Id'], 'os': image['Os'],
                                        'architecture': image['Architecture'], 'user': image['Config']['User']}
        # Exercise the actual nested JAR and Spring PEM loader in the final non-root image.
        checked = subprocess.run(docker + ['run', '--rm', '--network', 'none', '--read-only',
                                 '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                                 '--memory', '256m', '--cpus', '1', '--entrypoint', 'java', tag,
                                 '-Dloader.main=com.yoyi.pilates.infra.tools.RedisTrustCheck',
                                 '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                                 capture_output=True, text=True, timeout=90, check=True)
        ca_check = json.loads(checked.stdout.strip())
        if ca_check != {'status': 'passed', 'sha256': hashes[ca_source],
                        'trustManagerCreated': True, 'networkUsed': False}:
            raise ValueError('packaged Redis CA trust-manager check did not match the reviewed public CA')
        manifest['bundledRedisCa']['imageTrustManagerCheck'] = ca_check
        (output / 'redis-ca-image-check.json').write_text(json.dumps(ca_check, indent=2) + '\n')
        manifest['status'] = 'BUILT_PENDING_RUNTIME_AND_SCAN_VALIDATION'
        save()
        (a.output_root / 'current-cloudrun-release-path.txt').write_text(str(output) + '\n')
        print('BUILT', output)
    except Exception as error:
        manifest['status'] = 'FAILED'
        manifest['failureType'] = type(error).__name__
        save()
        raise


if __name__ == '__main__':
    main()
