#!/usr/bin/env python3
"""Run independent COS/JDBC probes against an explicitly confirmed isolated target.

Uses the reviewed Java artifact's actual SDK/driver, never starts Spring or Redis.
Passwords stay in protected properties files, never command arguments or captured logs.
"""
import argparse
import contextlib
import datetime
import hashlib
import io
import json
import os
import pathlib
import stat
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA_DIR = ROOT / 'scripts/cloudrun/java'
MAIN = {
    'cos': 'com.yoyi.pilates.infra.storage.CosConnectionProbe',
    'mysql': 'com.yoyi.pilates.infra.tools.MySqlConnectionProbe',
}
SOURCE = {'cos': 'CosConnectionProbe.java', 'mysql': 'MySqlConnectionProbe.java'}


def protected_config(path, bundle):
    config = path.absolute()
    if config.is_symlink() or not config.is_file() or config.resolve().is_relative_to(bundle.resolve()):
        raise ValueError('keep a protected regular config outside the upload bundle')
    if stat.S_IMODE(config.stat().st_mode) not in (0o400, 0o600) or config.stat().st_size > 65536:
        raise ValueError('config requires mode 0400/0600 and a bounded size')
    config.read_text(encoding='ascii')
    resolved = config.resolve()
    if resolved.is_relative_to(ROOT):
        private_root = ROOT / '.secrets'
        if private_root.is_symlink() or not resolved.is_relative_to(private_root):
            raise ValueError('keep real configuration outside the worktree or in its ignored private secrets directory')
        ignored = subprocess.run(['git', 'check-ignore', '-q', '--',
                                  str(resolved.relative_to(ROOT))], cwd=ROOT,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if ignored.returncode != 0:
            raise ValueError('the private configuration must be ignored by Git')
        directory = resolved.parent
        while True:
            if directory.is_symlink() or not directory.is_dir() or stat.S_IMODE(directory.stat().st_mode) != 0o700:
                raise ValueError('every secrets parent directory requires mode 0700')
            if directory == private_root:
                break
            directory = directory.parent
    return resolved


class OwnedReport:
    """Reserve new evidence once and only update that open inode, never a replacement path."""
    def __init__(self, path):
        self.path = path
        flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, 'O_NOFOLLOW', 0)
        self.fd = os.open(str(path), flags, 0o600)
        os.fchmod(self.fd, 0o600)
        self.identity = self._identity(os.fstat(self.fd))

    @staticmethod
    def _identity(value):
        return value.st_dev, value.st_ino

    def write(self, result):
        info = self.path.lstat()
        if not stat.S_ISREG(info.st_mode) or self._identity(info) != self.identity:
            raise ValueError('reserved evidence path was replaced; refusing to overwrite')
        payload = (json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')
        os.lseek(self.fd, 0, os.SEEK_SET)
        remaining = memoryview(payload)
        while remaining:
            size = os.write(self.fd, remaining)
            if size <= 0:
                raise OSError('report write incomplete')
            remaining = remaining[size:]
        os.ftruncate(self.fd, len(payload))
        os.fsync(self.fd)

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


def read_probe_report(path, service):
    if not path.exists():
        return None
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_size > 1_000_000:
        raise ValueError('unsafe or unbounded private probe report')
    result = json.loads(path.read_text(encoding='utf-8'))
    expected = {'cos': 'real-cos-connection-probe', 'mysql': 'real-jdbc-mysql-connection'}[service]
    if not isinstance(result, dict) or result.get('type') != expected or result.get('status') not in {'passed', 'failed', 'incomplete'}:
        raise ValueError('probe report identity or status mismatch')
    return result


def finish_result(result, service, returncode, timed_out=False):
    """Preserve a timeout checkpoint, but never accept a passed result from a failed process."""
    if result is None:
        result = {'type': 'cloud-connection-wrapper-failure', 'service': service, 'status': 'failed',
                  'failureStage': 'probe-execution', 'detail': 'No safe probe report; raw output withheld.'}
    if timed_out:
        result['status'] = 'incomplete' if result.get('writeAttempted') else 'failed'
        result['failureStage'] = 'probe-timeout'
        result['timedOut'] = True
    elif returncode != 0:
        result['status'] = 'failed'
        result.setdefault('failureStage', 'probe-exit')
    elif result.get('status') != 'passed':
        result.setdefault('failureStage', 'probe-status-exit-mismatch')
    result['processExitCode'] = returncode
    result['wrapperExitCode'] = 0 if not timed_out and returncode == 0 and result.get('status') == 'passed' else 1
    return result


def reviewed_bundle(bundle):
    from verify import verify
    bundle = bundle.resolve()
    with contextlib.redirect_stdout(io.StringIO()):
        verify(bundle)
    manifest = json.loads((bundle / 'manifest.json').read_text())
    jar = bundle / 'java-upload/pilates-server.jar'
    if hashlib.sha256(jar.read_bytes()).hexdigest() != manifest['jarSha256']:
        raise ValueError('reviewed Java artifact mismatch')
    return bundle, manifest, jar


def extract_classpath(jar, temporary):
    classes = temporary / 'application'
    libs = temporary / 'lib'
    classes.mkdir()
    libs.mkdir()
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            prefix = 'BOOT-INF/classes/'
            if entry.is_dir():
                continue
            if entry.filename.startswith(prefix):
                destination = classes / entry.filename[len(prefix):]
            elif entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
                name = entry.filename[len('BOOT-INF/lib/'):]
                if '/' in name:
                    raise ValueError('unexpected nested library')
                destination = libs / name
            else:
                continue
            if not destination.resolve().is_relative_to(temporary.resolve()):
                raise ValueError('unsafe Java archive entry')
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(entry))
    return str(classes) + os.pathsep + str(libs / '*')


def compile_probes(java_home, jar, temporary, service, offline=False):
    javac, java = java_home / 'bin/javac', java_home / 'bin/java'
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True).stderr
    if not any('version "' + x + '.' in version for x in ('17', '21')):
        raise ValueError('use an explicit installed JDK 17 or 21')
    classpath = extract_classpath(jar, temporary)
    output = temporary / 'probe'
    output.mkdir()
    sources = [JAVA_DIR / SOURCE[service]]
    if offline:
        sources.append(JAVA_DIR / (SOURCE[service][:-5] + 'OfflineTest.java'))
    completed = subprocess.run([str(javac), '--release', '17', '-encoding', 'UTF-8', '-proc:none',
                                '-cp', classpath, '-d', str(output), *map(str, sources)],
                               capture_output=True, text=True, timeout=60)
    if completed.returncode:
        raise ValueError('probe compilation failed; raw diagnostics withheld')
    return str(java), str(output) + os.pathsep + classpath, version.splitlines()[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', choices=sorted(MAIN), required=True)
    parser.add_argument('--bundle', type=pathlib.Path, required=True)
    parser.add_argument('--java-home', type=pathlib.Path, required=True)
    parser.add_argument('--config', type=pathlib.Path)
    parser.add_argument('--report', type=pathlib.Path, required=True)
    parser.add_argument('--mode', choices=['preflight', 'write-read'], default='preflight')
    parser.add_argument('--confirm-test-bucket')
    parser.add_argument('--confirm-region')
    parser.add_argument('--expect-server-uuid')
    parser.add_argument('--confirm-isolated-database', choices=['uni_pilates_test'])
    parser.add_argument('--offline-check', action='store_true', help='Only synthetic boundary tests; no real config or network')
    args = parser.parse_args()
    report = args.report.absolute()
    if args.config and report.resolve() == args.config.resolve():
        parser.error('the report cannot replace the configuration')
    report.parent.mkdir(parents=True, exist_ok=True)
    try:
        evidence = OwnedReport(report)
    except OSError:
        parser.error('use a new report path; existing evidence is never overwritten')
    stage = 'bundle-integrity'
    try:
        evidence.write({'type': 'cloud-connection-wrapper', 'service': args.service, 'status': 'incomplete',
                        'stage': stage, 'offlineOnly': args.offline_check})
        bundle, manifest, jar = reviewed_bundle(args.bundle)
        stage = 'configuration'
        if args.offline_check:
            if args.config:
                raise ValueError('offline checks do not accept real credentials')
            config = None
        else:
            if not args.config:
                raise ValueError('a protected configuration file is required')
            config = protected_config(args.config, bundle)
            if args.service == 'cos' and (not args.confirm_test_bucket or not args.confirm_region):
                raise ValueError('explicit isolated bucket and actual region are required')
            if args.service == 'mysql' and (not args.expect_server_uuid or not args.confirm_isolated_database):
                raise ValueError('explicit isolated schema and server UUID are required')
        with tempfile.TemporaryDirectory(prefix='pilates-cloud-connection-') as directory:
            temporary = pathlib.Path(directory)
            temporary.chmod(0o700)
            stage = 'compile-probe'
            java, classpath, jdk = compile_probes(args.java_home.resolve(), jar, temporary, args.service, args.offline_check)
            command = [java, '-Duser.timezone=Asia/Shanghai', '-Djava.awt.headless=true', '-cp', classpath]
            private_report = temporary / 'probe-report.json'
            if args.offline_check:
                command.append(MAIN[args.service] + 'OfflineTest')
            else:
                command += [MAIN[args.service], '--config', str(config), '--report', str(private_report)]
                if args.service == 'cos':
                    command += ['--confirm-test-bucket', args.confirm_test_bucket, '--confirm-region', args.confirm_region, '--mode', args.mode]
                else:
                    command += ['--expect-server-uuid', args.expect_server_uuid,
                                '--confirm-isolated-database', args.confirm_isolated_database,
                                '--migration-directory', str(bundle / 'database/production')]
            stage = 'offline-boundaries' if args.offline_check else 'probe-execution'
            evidence.write({'type': 'cloud-connection-wrapper', 'service': args.service, 'status': 'incomplete',
                            'stage': stage, 'offlineOnly': args.offline_check,
                            'recoveryCheckpoint': None if args.offline_check else str(private_report)})
            # Raw SDK/JDBC output may contain signed identifiers. Never echo or persist it.
            timed_out = False
            try:
                completed = subprocess.run(command, capture_output=True, text=True, timeout=150)
            except subprocess.TimeoutExpired:
                completed = None
                timed_out = True
            if args.offline_check:
                if completed is None or completed.returncode:
                    raise ValueError('offline probe boundary checks failed')
                result = json.loads(completed.stdout)
                expected = {'cos': 'offline-cos-probe-boundaries', 'mysql': 'offline-mysql-probe-boundaries'}[args.service]
                if not isinstance(result, dict) or result.get('type') != expected or result.get('status') != 'passed':
                    raise ValueError('offline probe result identity mismatch')
            else:
                # Read the checkpoint before TemporaryDirectory cleanup, including timeout/uncertain PUT.
                result = read_probe_report(private_report, args.service)
            result = finish_result(result, args.service, None if completed is None else completed.returncode, timed_out)
            result['reviewedArtifact'] = manifest['reviewedArtifact']
            result['jarSha256'] = manifest['jarSha256']
            result['probeSourceSha256'] = hashlib.sha256((JAVA_DIR / SOURCE[args.service]).read_bytes()).hexdigest()
            result['buildJdk'] = jdk
            result['checkedAtUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            evidence.write(result)
            print(json.dumps({'service': args.service, 'status': result['status'],
                              'report': str(report), 'offlineOnly': args.offline_check}, ensure_ascii=False))
            return result['wrapperExitCode']
    except Exception:
        try:
            evidence.write({'service': args.service, 'status': 'failed', 'failureStage': stage,
                            'detail': 'Configuration, SDK/JDBC output and exception values withheld.',
                            'offlineOnly': args.offline_check})
        except Exception:
            pass  # A replaced path is not ours; do not overwrite or print its contents.
        print(json.dumps({'service': args.service, 'status': 'failed', 'failureStage': stage,
                          'report': str(report), 'valuesWithheld': True}))
        return 1
    finally:
        evidence.close()


if __name__ == '__main__':
    raise SystemExit(main())
