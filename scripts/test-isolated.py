#!/usr/bin/env python3
"""Own a password-protected, nonpersistent Redis and an H2-only Maven test run.
No connection to the configured development/production MySQL or Redis is made.
"""
import os, pathlib, secrets, shutil, socket, subprocess, sys, tempfile, time
root = pathlib.Path(__file__).resolve().parents[1]
if not shutil.which('redis-server') or not shutil.which('mvn'):
    raise SystemExit('redis-server and mvn are required')
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 0))
    port = probe.getsockname()[1]
password = secrets.token_hex(24)
with tempfile.TemporaryDirectory(prefix='pilates-isolated-') as directory:
    with open(pathlib.Path(directory) / 'redis.log', 'w+') as log:
        redis_config = pathlib.Path(directory) / 'redis.conf'
        redis_config.write_text('bind 127.0.0.1\nport '+str(port)+'\nrequirepass '+password+'\nsave ""\nappendonly no\ndir '+directory+'\n')
        redis_config.chmod(0o600)
        process = subprocess.Popen(['redis-server', str(redis_config)], stdout=log, stderr=log)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    log.seek(0); raise SystemExit(log.read())
                check = subprocess.run(['redis-cli', '-h', '127.0.0.1', '-p', str(port), 'PING'], capture_output=True, text=True, env={**os.environ, 'REDISCLI_AUTH':password})
                if check.returncode == 0 and check.stdout.strip() == 'PONG': break
                time.sleep(.05)
            else: raise SystemExit('isolated Redis did not start')
            env = os.environ.copy()
            # Maven may use a newer Homebrew JDK than the java selected on PATH.
            settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True)
            java_home = next((line.split('=', 1)[1].strip() for line in settings.stderr.splitlines() if 'java.home =' in line), None)
            if java_home: env['JAVA_HOME'] = os.environ.get('PILATES_TEST_JAVA_HOME', java_home)
            env.update(PILATES_TEST_REDIS_PORT=str(port), PILATES_TEST_REDIS_PASSWORD=password)
            command = ['mvn', '-o', '-f', str(root / 'backend/pom.xml'),
                '-Dpilates.build.directory=' + os.environ.get('PILATES_TEST_BUILD_DIR', str(root / 'backend/target-round2')), '-Dspring.profiles.active=junit',
                '-Dspring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL;NON_KEYWORDS=USER,ORDER,MEMBER,TYPE;DB_CLOSE_DELAY=-1',
                '-Dspring.datasource.driver-class-name=org.h2.Driver', '-Dspring.datasource.username=sa', '-Dspring.datasource.password=',
                '-Dspring.data.redis.host=127.0.0.1', '-Dspring.data.redis.port=' + str(port), '-Dspring.data.redis.password=${PILATES_TEST_REDIS_PASSWORD}',
                '-Dspring.data.redis.database=15', '-Dyoyi.scheduling.enabled=false'] + sys.argv[1:] + ['test']
            print('Isolated tests: H2 memory + owned Redis on port', port, flush=True)
            code = subprocess.run(command, cwd=root, env=env).returncode
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
    sys.exit(code)
