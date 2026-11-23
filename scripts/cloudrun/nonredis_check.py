#!/usr/bin/env python3
"""Validate the real AMD64 preflight image without Redis or access to any cloud network."""
import argparse
import datetime
import json
import pathlib
import secrets
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=pathlib.Path, required=True)
    parser.add_argument('--docker-context', required=True)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.artifact / 'manifest.json').read_text())
    image = manifest['images']['server']['tag']
    docker = ['docker', '--context', args.docker_context]
    owned = []
    cases = []
    prefix = 'yoyi-nonredis-' + secrets.token_hex(4)

    def run(command, **kwargs):
        return subprocess.run(command, capture_output=True, text=True, timeout=120, **kwargs)

    def passed(name):
        cases.append({'name': name, 'status': 'passed'})

    try:
        # Colima shares the workspace; macOS /var/folders temporary directories
        # are not reliable bind-mount sources for the Linux VM.
        with tempfile.TemporaryDirectory(prefix=prefix, dir=ROOT / '.secrets') as directory:
            directory = pathlib.Path(directory)
            base = dict(line.split('=', 1) for line in (ROOT / 'deploy/wechat-cloudrun/preflight.env.example').read_text().splitlines() if line and not line.startswith('#'))
            base.update(MYSQL_PASSWORD='synthetic-' + secrets.token_hex(16), COS_SECRET_ID='synthetic-id', COS_SECRET_KEY='synthetic-key')

            def container(suffix, changes=None):
                name = prefix + '-' + suffix
                environment = base | (changes or {})
                config = directory / (suffix + '.env')
                config.write_text(''.join(key + '=' + value + '\n' for key, value in environment.items()))
                config.chmod(0o600)
                result = run(docker + ['run', '-d', '--name', name, '--network', 'none', '--read-only',
                    '--tmpfs', '/tmp:rw,nosuid,nodev,size=64m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                    '--memory', '768m', '--env-file', str(config), image])
                if result.returncode: raise RuntimeError('isolated fixture start rejected; values withheld')
                owned.append(name)
                return name

            def stopped(name, marker):
                for _ in range(45):
                    info = run(docker + ['inspect', '--format', '{{.State.Running}} {{.State.ExitCode}}', name]).stdout.strip()
                    if info.startswith('false '):
                        logs = run(docker + ['logs', name])
                        if info.endswith(' 0') or marker not in logs.stdout + logs.stderr:
                            raise RuntimeError('negative fixture did not fail at expected boundary; logs withheld')
                        return
                    time.sleep(1)
                raise RuntimeError('negative fixture failed to stop; values withheld')

            name = container('preflight')

            def http(path, method='GET'):
                # The fixture has --network none. Only container loopback is reachable.
                command = "exec 3<>/dev/tcp/127.0.0.1/8080; printf '" + method + ' ' + path + " HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; cat <&3"
                return run(docker + ['exec', name, 'bash', '-c', command])

            for _ in range(45):
                response = http('/health')
                if response.returncode == 0 and '200 OK' in response.stdout: break
                time.sleep(1)
            else: raise RuntimeError('preflight health did not become reachable; logs withheld')
            assert '"businessReady":false' in response.stdout
            assert '503 Service Unavailable' in http('/ready').stdout
            passed('real-amd64-container-liveness-up-dependencies-not-ready-with-no-network')
            for path in ['/api/auth/wx-login', '/api/bookings', '/api/upload', '/health/other']:
                assert '404 Not Found' in http(path).stdout
            assert '405 Method Not Allowed' in http('/ready', 'POST').stdout
            passed('preflight-rejects-all-business-paths-and-non-get-methods')
            stopped(container('wrong-target', {'DATABASE_URL': base['DATABASE_URL'].replace('10.39.100.38', '203.0.113.10')}), 'configuration rejected')
            passed('wrong-target-exits-before-network')
            stopped(container('wrong-mode', {'PILATES_RUN_MODE': 'unexpected'}), 'unsupported run mode')
            passed('unknown-run-mode-fails-closed')
            stopped(container('no-redis-business', {'PILATES_RUN_MODE': 'application', 'REDIS_HOST': '127.0.0.1', 'REDIS_PASSWORD': '', 'JWT_SECRET': secrets.token_hex(32), 'WX_LOGIN_ENABLED': 'false', 'PUBLIC_BASE_URL': 'https://fixture.invalid'}), 'Production startup rejected: credentials')
            passed('business-mode-still-rejects-missing-redis-credentials')

            mysql = json.loads((ROOT / 'deploy/image-lock.json').read_text())['images']['mysql']['reference']
            # The 0700 parent protects the synthetic password on the host. The MySQL
            # entrypoint switches to uid 999 before reading this individual bind mount.
            password = directory / 'mysql-password'; password.write_text(secrets.token_hex(32)); password.chmod(0o444)
            options = directory / 'mysql.cnf'; options.write_text('[client]\nuser=root\npassword=' + password.read_text() + '\n'); options.chmod(0o600)
            mysql_name = prefix + '-mysql'
            result = run(docker + ['run', '-d', '--name', mysql_name, '--network', 'none',
                '-v', str(password) + ':/run/test-password:ro', '-v', str(options) + ':/run/test-client.cnf:ro',
                '-e', 'MYSQL_ROOT_PASSWORD_FILE=/run/test-password', mysql])
            if result.returncode: raise RuntimeError('local MySQL SQL-syntax fixture failed to start; logs withheld')
            owned.append(mysql_name)
            def sql(text):
                return run(docker + ['exec', '-i', mysql_name, 'mysql', '--defaults-extra-file=/run/test-client.cnf', '-N', '-B'], input=text)
            for _ in range(60):
                if sql('SELECT 1;').returncode == 0: break
                time.sleep(1)
            else:
                logs = run(docker + ['logs', mysql_name])
                safe = (logs.stdout + logs.stderr).replace(password.read_text(), '[REDACTED]')
                (args.report.parent / 'mysql-syntax-fixture-failure.log').write_text(safe)
                raise RuntimeError('local MySQL fixture did not become ready; sanitized local fixture log saved')
            draft = (ROOT / 'deploy/wechat-cloudrun/grant-test-runtime.draft.sql').read_text()
            installed = sql('CREATE DATABASE uni_pilates_test;\nUSE uni_pilates_test;\nDELIMITER $$\n' + draft.rstrip(';\n') + '\n$$\nDELIMITER ;\n')
            if installed.returncode: raise RuntimeError('grant procedure syntax rejected; raw SQL output withheld')
            rejected = sql('USE uni_pilates_test; CALL cloudrun_grant_test_runtime();')
            assert rejected.returncode != 0 and 'wrong isolated target' in rejected.stderr
            assert sql("SELECT COUNT(*) FROM mysql.user WHERE user='uni_pilates_runtime';").stdout.strip() == '0'
            passed('mysql8-local-grant-draft-compiles-and-wrong-uuid-blocks-all-grants')
    finally:
        for name in reversed(owned): run(docker + ['rm', '-f', '-v', name])

    result = {'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'type': 'local-amd64-nonredis-deployment-boundaries', 'status': 'passed',
        'image': image, 'imageId': manifest['images']['server']['imageId'],
        'backendArchitecture': 'linux/amd64', 'localMysqlVersion': '8.0.46; SQL draft syntax/negative target only',
        'redisStartedOrInspected': False, 'cloudNetworkAccess': False, 'ownedContainersCleaned': True,
        'cases': cases, 'doesNotProve': ['Cloud MySQL8.0.30 JDBC', 'actual runtime account/grants', 'cloud deployment', 'Redis', 'business HTTP', 'WeChat device acceptance']}
    with args.report.open('x') as output: json.dump(result, output, indent=2); output.write('\n')
    print(json.dumps({'status': 'passed', 'cases': len(cases), 'report': str(args.report)}))


if __name__ == '__main__': main()
