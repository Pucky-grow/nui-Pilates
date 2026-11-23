#!/usr/bin/env python3
"""Prepare a minimal WeChat CloudRun Java/MySQL bundle. No cloud calls or deployments."""
import argparse
import datetime
import hashlib
import json
import pathlib
import shutil
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(artifact, output_root):
    artifact = artifact.resolve()
    source_manifest = json.loads((artifact / 'manifest.json').read_text())
    jar = artifact / 'pilates-server.jar'
    if jar.is_symlink() or digest(jar) != source_manifest['jarSha256']:
        raise ValueError('reviewed jar checksum mismatch')
    listed = subprocess.check_output([
        'git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z',
        '--', 'backend/src/main', 'backend/pom.xml',
    ], cwd=ROOT).decode().split('\0')
    current = {name for name in listed if name and (ROOT / name).is_file()}
    expected = {
        name for name in source_manifest['sourceHashes']
        if name.startswith('backend/src/main/') or name == 'backend/pom.xml'
    }
    if current != expected or any(digest(ROOT / name) != source_manifest['sourceHashes'][name] for name in expected):
        raise ValueError('server source changed; build and verify a new Java artifact first')
    template = ROOT / 'deploy/wechat-cloudrun'
    for name, expected_hash in source_manifest.get('cloudrunBuildContextHashes', {}).items():
        candidate = jar if name == 'pilates-server.jar' else template / name
        if not candidate.is_file() or digest(candidate) != expected_hash:
            raise ValueError('CloudRun build context changed after image build; rebuild and validate first')
    mapping = {
        'java-upload/Dockerfile': template / 'Dockerfile',
        'java-upload/.dockerignore': template / '.dockerignore',
        'java-upload/container.config.json': template / 'container.config.json',
        'java-upload/pilates-server.jar': jar,
        'database/00-readonly.sql': template / 'database-readonly.sql',
        'database/01-create-test-database.sql': template / 'create-test-database.sql',
        'private-configuration/database-tool.properties.example': template / 'database-tool.properties.example',
        'private-configuration/runtime.env.example': template / 'runtime.env.example',
        'private-configuration/preflight.env.example': template / 'preflight.env.example',
        'private-configuration/runtime-private-test.env.example': template / 'runtime-private-test.env.example',
        'private-configuration/cos-runtime-policy.draft.json': template / 'cos-runtime-policy.draft.json',
        'database/grant-test-runtime.draft.sql': template / 'grant-test-runtime.draft.sql',
        'database/grant-studio-ledger.draft.sql': template / 'grant-studio-ledger.draft.sql',
        'CLOUDRUN-TEST-DEPLOYMENT.md': ROOT / 'docs/CLOUDRUN-TEST-DEPLOYMENT.md',
        'tools/account_draft.py': ROOT / 'scripts/cloudrun/account_draft.py',
        'tools/database.py': ROOT / 'scripts/cloudrun/database.py',
        'tools/verify.py': ROOT / 'scripts/cloudrun/verify.py',
        'README.md': ROOT / 'docs/WECHAT-CLOUDRUN-PREPARATION.md',
        'WECHAT-CLOUDRUN-AMD64-COS.md': ROOT / 'docs/WECHAT-CLOUDRUN-AMD64-COS.md',
    }
    for name in ['V001__structure.sql', 'V002__base.sql', 'V003__delivery.sql', 'V004__studio_ledger.sql']:
        original = ROOT / 'database/production' / name
        if digest(original) != source_manifest['sourceHashes']['database/production/' + name]:
            raise ValueError('reviewed migration checksum mismatch')
        mapping['database/production/' + name] = original
    hashes = {name: digest(path) for name, path in sorted(mapping.items())}
    version = 'cloudrun-prep-' + hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()[:12]
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    output = output_root / version
    if output.exists():
        raise FileExistsError('immutable bundle already exists; verify the existing output')
    output.mkdir()
    for name, source in mapping.items():
        dest = output / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
    archive = output / 'java-upload.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted((output / 'java-upload').iterdir()):
            info = zipfile.ZipInfo(path.name, (2026, 11, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, path.read_bytes())
    hashes['java-upload.zip'] = digest(archive)
    manifest = {
        'format': 1, 'version': version, 'status': 'PREPARATION_ONLY',
        'createdAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'reviewedArtifact': source_manifest['version'],
        'jarSha256': source_manifest['jarSha256'], 'verifiedServerFiles': len(expected),
        'bundledRedisCa': source_manifest.get('bundledRedisCa'),
        'targetPlatform': 'linux/amd64',
        'buildStatus': source_manifest['status'] if source_manifest.get('targetPlatform') == 'linux/amd64' and source_manifest.get('images', {}).get('server') else 'NOT_BUILT_FOR_AMD64',
        'reviewedLocalImage': source_manifest.get('images', {}).get('server') if source_manifest.get('targetPlatform') == 'linux/amd64' else None,
        'cloudStatus': 'NOT_DEPLOYED', 'cloudMySQL': 'VERIFY_EXISTING_TARGET_SEPARATELY',
        'redis': 'PURCHASED_TLS_ENABLED_PENDING_CLOUD_APPLICATION_CONNECTION',
        'businessRuntimeRequires': ['MySQL 8 + V001–V003; V004 before enabling studio ledger', 'authenticated Redis',
                                    'private persistent object storage', 'protected runtime configuration'],
        'files': hashes,
    }
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    subprocess.run(['python3', ROOT / 'scripts/cloudrun/verify.py', '--bundle', output], check=True)
    (output_root / 'current-cloudrun-preparation-path.txt').write_text(str(output) + '\n')
    print('PREPARED', output)
    return output


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--artifact', type=pathlib.Path, help='Reviewed local build directory')
    p.add_argument('--output-root', type=pathlib.Path, default=ROOT / 'build-artifacts')
    a = p.parse_args()
    if a.artifact is None:
        current = ROOT / 'build-artifacts/current-cloudrun-release-path.txt'
        if not current.is_file():
            current = ROOT / 'build-artifacts/current-production-path.txt'
        if not current.is_file():
            p.error('--artifact is required when there is no recorded local build')
        a.artifact = pathlib.Path(current.read_text().strip())
    prepare(a.artifact, a.output_root)
