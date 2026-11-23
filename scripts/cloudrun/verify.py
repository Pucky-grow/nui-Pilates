#!/usr/bin/env python3
"""Verify prepared archive boundaries and provenance; this is not a cloud acceptance test."""
import argparse
import hashlib
import json
import pathlib
import struct
import zipfile


def verify(bundle):
    root = bundle.resolve()
    manifest = json.loads((root / 'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
            raise ValueError('unsafe or missing bundle entry')
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('bundle checksum mismatch')
    allowed = {'Dockerfile', '.dockerignore', 'container.config.json', 'pilates-server.jar'}
    if {path.name for path in (root / 'java-upload').iterdir()} != allowed:
        raise ValueError('build directory must contain only four approved entries')
    with zipfile.ZipFile(root / 'java-upload.zip') as archive:
        if set(archive.namelist()) != allowed or len(archive.namelist()) != len(allowed):
            raise ValueError('upload archive must contain only four approved entries')
        for name in allowed:
            if hashlib.sha256(archive.read(name)).hexdigest() != manifest['files']['java-upload/' + name]:
                raise ValueError('archive differs from reviewed build context')
    cfg = json.loads((root / 'java-upload/container.config.json').read_text())
    if cfg['containerPort'] != 8080 or cfg['minNum'] != 1 or cfg['maxNum'] != 1 or cfg['envParams']:
        raise ValueError('review cloud runtime configuration before proceeding')
    with zipfile.ZipFile(root / 'java-upload/pilates-server.jar') as jar:
        ca = manifest.get('bundledRedisCa')
        ca_entry = 'BOOT-INF/classes/certificates/tencent-redis-ca.pem'
        if not ca or ca.get('entry') != ca_entry or ca_entry not in jar.namelist():
            raise ValueError('public Redis CA missing from reviewed packaged JAR; rebuild the artifact')
        ca_content = jar.read(ca_entry)
        if hashlib.sha256(ca_content).hexdigest() != ca.get('sha256') \
                or b'BEGIN CERTIFICATE' not in ca_content or b'PRIVATE KEY' in ca_content:
            raise ValueError('packaged Redis CA identity or public-certificate boundary mismatch')
        if ca.get('imageTrustManagerCheck') != {'status': 'passed', 'sha256': ca['sha256'],
                                               'trustManagerCreated': True, 'networkUsed': False}:
            raise ValueError('reviewed image has not passed its offline Redis CA trust-manager check')
        clazz = jar.read('BOOT-INF/classes/com/yoyi/pilates/PilatesApplication.class')
        magic, minor, major = struct.unpack('>IHH', clazz[:8])
        if magic != 0xCAFEBABE or major != 61:
            raise ValueError('review Java 17 bytecode compatibility')
        for name in ['application-prod.yml', 'application-staging.yml']:
            profile = jar.read('BOOT-INF/classes/' + name).decode()
            if 'mode: never' not in profile or 'mq.type: outbox' not in profile:
                raise ValueError('reviewed environment protections are missing')
    result = {
        'type': 'static-preparation', 'status': 'passed', 'version': manifest['version'],
        'checks': ['all bundle checksums', 'exact four-file upload allowlist',
                   'archive/context identity', 'Java 17 bytecode',
                   'public Redis CA identity inside JAR and final-image trust-manager evidence',
                   'reviewed profiles retain SQL-init-never and outbox',
                   'no credentials in container config'],
        'doesNotProve': ['AMD64 image build/start', 'cloud MySQL compatibility',
                         'cloud networking', 'Redis integration', 'WeChat login/device acceptance'],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=pathlib.Path)
    p.add_argument('--report', type=pathlib.Path)
    a = p.parse_args()
    result = verify(a.bundle)
    if a.report:
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
