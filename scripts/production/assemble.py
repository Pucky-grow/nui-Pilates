#!/usr/bin/env python3
"""Assemble a local deployment bundle bound to tested images. Never pushes or deploys."""
import argparse, hashlib, json, pathlib, subprocess, tarfile, io, gzip

ROOT = pathlib.Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--release', required=True, type=pathlib.Path)
a = p.parse_args()
release = a.release.resolve()
manifest = json.loads((release / 'manifest.json').read_text())
digest = lambda value: hashlib.sha256(value).hexdigest()
if digest((release / 'pilates-server.jar').read_bytes()) != manifest['jarSha256']:
    raise SystemExit('STOP: artifact checksum mismatch')
runtime_prefixes = ('server/src/main/', 'admin/src/', 'miniprogram/', 'database/production/')
runtime_files = {'server/pom.xml', 'admin/package.json', 'admin/package-lock.json', 'admin/nginx.conf',
                 'deploy/Dockerfile.server.artifact', 'deploy/Dockerfile.admin.artifact',
                 'deploy/Dockerfile.edge', 'deploy/nginx.conf'}
for name, expected in manifest['sourceHashes'].items():
    if name.startswith(runtime_prefixes) or name in runtime_files:
        file = ROOT / name
        if not file.is_file() or digest(file.read_bytes()) != expected:
            raise SystemExit('STOP: runtime source differs from tested build: ' + name)
subprocess.run(['python3', str(ROOT / 'scripts/production/secret-scan.py')], check=True)
target = release / 'deployment'
target.mkdir()  # Refuse to overwrite any existing bundle.
names = set(subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=ROOT).split(b'\0'))
hashes = {}
for raw in sorted(names):
    if not raw:
        continue
    name = raw.decode()
    if not (name == 'README.md' or name.startswith(('deploy/', 'database/production/', 'database/check_', 'scripts/production/',
             'scripts/round3/', 'docs/PRODUCTION', 'docs/production-readiness', 'docs/ALIYUN-',
             'docs/GO-LIVE-', 'docs/WECHAT-', 'docs/evidence/production/'))):
        continue
    source = ROOT / name
    if not source.is_file() or source.is_symlink():
        continue
    value = source.read_bytes()
    dest = target / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(value)
    hashes[name] = digest(value)
report = {'runtimeSourcesMatchTestedBuild': True, 'version': manifest['version'],
          'images': manifest['images'], 'jarSha256': manifest['jarSha256'],
          'deploymentSnapshotSha256': digest(json.dumps(hashes, sort_keys=True).encode()),
          'files': hashes, 'note': 'Deployment/test/document updates after image build are recorded separately; no push or deployment.'}
(release / 'bundle-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
archive = release / 'source.tar.gz'
with archive.open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed, tarfile.open(fileobj=compressed, mode='w') as tar:
    for name in sorted(names):
        if not name:
            continue
        relative = name.decode()
        source = ROOT / relative
        if not source.is_file() or source.is_symlink():
            continue
        value = source.read_bytes()
        info = tarfile.TarInfo(relative)
        info.size = len(value)
        info.mode = 0o755 if source.stat().st_mode & 0o111 else 0o644
        tar.addfile(info, io.BytesIO(value))
(release / 'source-archive.json').write_text(json.dumps({'file': 'source.tar.gz', 'sha256': digest(archive.read_bytes()),
    'scope': 'Reviewed packaging-time Git candidates; includes development source, no Git internals or ignored secrets. Runtime source equality verified separately.',
    'buildSnapshot': manifest['sourceTreeSha256']}, indent=2) + '\n')
print('Local deployment bundle assembled; runtime sources verified against tested build.')
