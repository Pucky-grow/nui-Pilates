#!/usr/bin/env python3
"""Exercise offline migration-wrapper boundaries with synthetic properties, never a database."""
import argparse
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=pathlib.Path)
    p.add_argument('--report', required=True, type=pathlib.Path)
    a = p.parse_args()
    bundle = a.bundle.resolve()
    cases = []
    with tempfile.TemporaryDirectory(prefix='cloudrun-wrapper-check-') as td:
        temp = pathlib.Path(td)
        config = temp / 'test.properties'
        value = '0' * 64
        original = ('url=jdbc:mysql://127.0.0.1:1/uni_pilates_test\n'
                    'database=uni_pilates_test\nuser=synthetic_migrator\npassword=' + value + '\n')

        def case(name, text=original, permissions=0o600, chosen_config=None,
                 chosen_bundle=bundle, expected=2):
            config.write_text(text)
            config.chmod(permissions)
            result = subprocess.run([
                'python3', str(ROOT / 'scripts/cloudrun/database.py'),
                '--bundle', str(chosen_bundle), '--config', str(chosen_config or config),
                '--expect-server-uuid', '00000000-0000-0000-0000-000000000000',
                '--confirm-isolated-database', 'uni_pilates_test', '--mode', 'fresh', '--check-only',
            ], capture_output=True, text=True)
            if result.returncode != expected or value in result.stdout + result.stderr:
                raise RuntimeError('wrapper verification failed; configuration values withheld')
            cases.append({'name': name, 'status': 'passed', 'exitCode': result.returncode,
                          'databaseConnected': False})

        case('valid-dry-run-without-database', expected=0)
        case('wrong-database-rejected', text=original.replace('database=uni_pilates_test', 'database=business_database'))
        case('group-readable-config-rejected', permissions=0o640)
        link = temp / 'linked.properties'
        link.symlink_to(config)
        case('symlink-config-rejected', chosen_config=link)
        case('duplicate-key-rejected', text=original + 'database=uni_pilates_test\n')
        case('properties-escape-rejected', text=original + 'data\\u0062ase=business_database\n')
        case('missing-credentials-rejected', text=original.replace('user=synthetic_migrator', 'user='))
        damaged = temp / 'damaged-bundle'
        (damaged / 'java-upload').mkdir(parents=True)
        (damaged / 'database/production').mkdir(parents=True)
        (damaged / 'java-upload/pilates-server.jar').write_text('damaged synthetic artifact')
        (damaged / 'manifest.json').write_text(json.dumps({
            'files': {'java-upload/pilates-server.jar': '0' * 64},
        }))
        case('damaged-artifact-rejected', chosen_bundle=damaged)
    report = {'type': 'offline-wrapper-boundaries', 'status': 'passed',
              'environment': 'local Python; synthetic properties, no database connections', 'cases': cases}
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
