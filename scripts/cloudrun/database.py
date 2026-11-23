#!/usr/bin/env python3
"""Run the existing offline DatabaseTool against an explicitly confirmed isolated test DB."""
import argparse
import hashlib
import json
import pathlib
import re
import stat
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=pathlib.Path)
    p.add_argument('--config', required=True, type=pathlib.Path)
    p.add_argument('--expect-server-uuid', required=True)
    p.add_argument('--confirm-isolated-database', required=True, choices=['uni_pilates_test'])
    p.add_argument('--mode', required=True, choices=['fresh', 'provision', 'bootstrap'])
    p.add_argument('--check-only', action='store_true')
    p.add_argument('--java', default='java')
    a = p.parse_args()
    root = a.bundle.resolve()
    config = a.config.absolute()
    if not config.is_file() or config.is_symlink() or stat.S_IMODE(config.stat().st_mode) not in (0o400, 0o600):
        p.error('config must be a protected regular file with mode 0400/0600')
    # Reject a secret file inside a deliverable, even when .dockerignore would hide it.
    if config.is_relative_to(root):
        p.error('keep the real config outside the deliverable directory')
    if not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', a.expect_server_uuid):
        p.error('a confirmed MySQL server UUID is required')
    settings = {}
    try:
        text = config.read_text(encoding='ascii')
    except (UnicodeError, OSError):
        p.error('use ASCII properties and an ASCII bootstrap display name; values withheld')
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')):
            continue
        # A strict subset avoids a second, inconsistent Java-properties interpretation.
        if '=' not in line or '\\' in line:
            p.error('use plain key=value properties without escapes or continuations; values withheld')
        key, value = line.split('=', 1)
        key = key.strip()
        if not re.fullmatch(r'[a-z][a-z.]*', key) or key in settings:
            p.error('invalid or duplicate configuration key; values withheld')
        settings[key] = value.strip()
    if settings.get('database') != a.confirm_isolated_database:
        p.error('database must match the explicitly confirmed isolated target')
    if not settings.get('url', '').startswith('jdbc:mysql:') or not settings.get('user') or not settings.get('password'):
        p.error('private MySQL connection settings are incomplete; values withheld')
    jar = root / 'java-upload/pilates-server.jar'
    migrations = root / 'database/production'
    if not jar.is_file() or not migrations.is_dir():
        p.error('prepared bundle is incomplete')
    try:
        manifest = json.loads((root / 'manifest.json').read_text())
        for name, expected_hash in manifest['files'].items():
            path = root / name
            if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
                raise ValueError('unsafe bundle entry')
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
                raise ValueError('bundle checksum mismatch')
    except (KeyError, ValueError, OSError):
        p.error('bundle integrity check failed; do not run a modified migration package')
    if a.check_only:
        print('VALIDATED protected config and explicit target; no database connection attempted.')
        return
    subprocess.run([
        a.java, '-Duser.timezone=Asia/Shanghai',
        '-Dloader.main=com.yoyi.pilates.infra.tools.DatabaseTool',
        '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
        '--config', str(config), '--expect-server-uuid', a.expect_server_uuid,
        '--directory', str(migrations), '--mode', a.mode,
    ], check=True)


if __name__ == '__main__':
    main()
