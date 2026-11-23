#!/usr/bin/env python3
"""Synthetic wrapper boundary checks. No SDK/JDBC clients, credentials or cloud connections."""
import argparse
import contextlib
import io
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
from unittest import mock

import connection_accept as wrapper


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=pathlib.Path)
    args = parser.parse_args()
    cases = []

    def check(name, condition):
        if not condition:
            raise AssertionError('offline wrapper boundary failed: ' + name)
        cases.append({'name': name, 'status': 'passed'})

    def rejected(name, operation):
        try:
            operation()
        except (OSError, ValueError):
            check(name, True)
            return
        raise AssertionError('unsafe wrapper operation accepted: ' + name)

    with tempfile.TemporaryDirectory(prefix='pilates-wrapper-boundary-') as directory:
        temp = pathlib.Path(directory)
        bundle = temp / 'bundle'
        bundle.mkdir()
        config = temp / 'synthetic.properties'
        config.write_text('cos.secret-key=synthetic-not-a-real-secret\n', encoding='ascii')
        config.chmod(0o600)
        check('outside-worktree-protected-config-accepted', wrapper.protected_config(config, bundle.resolve()) == config.resolve())
        config.chmod(0o640)
        rejected('group-readable-config-rejected', lambda: wrapper.protected_config(config, bundle))
        config.chmod(0o600)
        link = temp / 'linked.properties'
        link.symlink_to(config)
        rejected('symlink-config-rejected', lambda: wrapper.protected_config(link, bundle))
        bundled = bundle / 'synthetic.properties'
        bundled.write_bytes(config.read_bytes())
        bundled.chmod(0o600)
        rejected('upload-bundle-config-rejected', lambda: wrapper.protected_config(bundled, bundle))
        with tempfile.TemporaryDirectory(prefix='connection-boundary-', dir=wrapper.ROOT) as repository:
            unignored = pathlib.Path(repository) / 'synthetic.properties'
            unignored.write_bytes(config.read_bytes())
            unignored.chmod(0o600)
            rejected('untracked-worktree-config-rejected', lambda: wrapper.protected_config(unignored, bundle))
        secrets_root = wrapper.ROOT / '.secrets'
        created_root = not secrets_root.exists()
        if created_root:
            secrets_root.mkdir(mode=0o700)
        try:
            with tempfile.TemporaryDirectory(prefix='connection-boundary-', dir=secrets_root) as private_directory:
                private = pathlib.Path(private_directory)
                private.chmod(0o700)
                private_config = private / 'synthetic.properties'
                private_config.write_bytes(config.read_bytes())
                private_config.chmod(0o600)
                check('ignored-private-secrets-config-accepted', wrapper.protected_config(private_config, bundle) == private_config)
                private.chmod(0o750)
                rejected('non-private-secrets-parent-rejected', lambda: wrapper.protected_config(private_config, bundle))
                private.chmod(0o700)
        finally:
            if created_root:
                secrets_root.rmdir()

        existing = temp / 'existing.json'
        original = '{"status":"passed","type":"old-evidence"}\n'
        existing.write_text(original)
        rejected('old-evidence-reservation-rejected', lambda: wrapper.OwnedReport(existing))
        check('old-evidence-unchanged', existing.read_text() == original)
        symlink_report = temp / 'symlink-report.json'
        symlink_report.symlink_to(existing)
        rejected('symlink-evidence-reservation-rejected', lambda: wrapper.OwnedReport(symlink_report))
        own_path = temp / 'own.json'
        own = wrapper.OwnedReport(own_path)
        try:
            own.write({'status': 'incomplete', 'detail': 'bounded synthetic progress'})
            check('reserved-report-owner-only', stat.S_IMODE(own_path.stat().st_mode) == 0o600)
            own.write({'status': 'passed'})
            check('own-fd-update-truncates-cleanly', json.loads(own_path.read_text()) == {'status': 'passed'})
            replacement = temp / 'replacement.json'
            replacement.write_text(original)
            os.replace(replacement, own_path)
            rejected('replaced-inode-update-rejected', lambda: own.write({'status': 'failed'}))
            check('replacement-evidence-unchanged', own_path.read_text() == original)
        finally:
            own.close()

        private_report = temp / 'probe.json'
        checkpoint = {'type': 'real-cos-connection-probe', 'status': 'incomplete', 'writeAttempted': True,
                      'writeConfirmed': False, 'objectKey': 'objects/acceptance/synthetic.png'}
        private_report.write_text(json.dumps(checkpoint))
        private_report.chmod(0o600)
        loaded = wrapper.read_probe_report(private_report, 'cos')
        timeout = wrapper.finish_result(loaded, 'cos', None, True)
        check('timeout-preserves-object-key', timeout['objectKey'] == checkpoint['objectKey'])
        check('timeout-remains-incomplete', timeout['status'] == 'incomplete' and timeout['wrapperExitCode'] != 0)
        rejected('wrong-service-report-rejected', lambda: wrapper.read_probe_report(private_report, 'mysql'))
        private_report.chmod(0o644)
        rejected('group-readable-child-report-rejected', lambda: wrapper.read_probe_report(private_report, 'cos'))
        private_report.chmod(0o600)
        private_report.unlink()
        private_report.symlink_to(existing)
        rejected('symlink-child-report-rejected', lambda: wrapper.read_probe_report(private_report, 'cos'))
        nonzero = wrapper.finish_result({'status': 'passed'}, 'cos', 2)
        check('nonzero-process-never-passes', nonzero['status'] == 'failed' and nonzero['wrapperExitCode'] != 0)
        mismatch = wrapper.finish_result({'status': 'failed'}, 'mysql', 0)
        check('failed-report-zero-exit-rejected', mismatch['status'] == 'failed' and mismatch['wrapperExitCode'] != 0)
        success = wrapper.finish_result({'status': 'passed'}, 'mysql', 0)
        check('consistent-success-accepted', success['status'] == 'passed' and success['wrapperExitCode'] == 0)
        missing = wrapper.finish_result(None, 'mysql', 1)
        check('missing-report-fails', missing['status'] == 'failed' and missing['wrapperExitCode'] != 0)

        # Exercise the real wrapper state machine with a fake child: no compiler/SDK/JDBC is invoked.
        for scenario, process_code, expected_status in [('success', 0, 'passed'),
                                                        ('nonzero', 2, 'failed'),
                                                        ('timeout', None, 'incomplete')]:
            final = temp / ('wrapper-' + scenario + '.json')
            raw = 'synthetic-raw-sdk-secret-must-not-be-output'
            child_paths = []

            def fake_child(command, **kwargs):
                destination = pathlib.Path(command[command.index('--report') + 1])
                child_paths.append(destination)
                payload = {'type': 'real-cos-connection-probe',
                           'status': 'incomplete' if scenario == 'timeout' else 'passed',
                           'writeAttempted': True, 'writeConfirmed': scenario != 'timeout',
                           'objectKey': 'objects/acceptance/synthetic-wrapper.png'}
                destination.write_text(json.dumps(payload))
                destination.chmod(0o600)
                if scenario == 'timeout':
                    raise subprocess.TimeoutExpired(command, 150, output=raw, stderr=raw)
                return subprocess.CompletedProcess(command, process_code, stdout=raw, stderr=raw)

            argv = ['connection_accept.py', '--service', 'cos', '--bundle', str(bundle),
                    '--java-home', str(temp), '--config', str(config), '--report', str(final),
                    '--mode', 'write-read', '--confirm-test-bucket', 'synthetic-1500000000',
                    '--confirm-region', 'ap-shanghai']
            out, error = io.StringIO(), io.StringIO()
            with mock.patch.object(sys, 'argv', argv), \
                    mock.patch.object(wrapper, 'reviewed_bundle', return_value=(bundle,
                                      {'reviewedArtifact': 'synthetic', 'jarSha256': '0' * 64}, bundle / 'unused.jar')), \
                    mock.patch.object(wrapper, 'compile_probes', return_value=('unused-java', 'unused-classpath', 'synthetic-jdk')), \
                    mock.patch.object(wrapper.subprocess, 'run', side_effect=fake_child), \
                    contextlib.redirect_stdout(out), contextlib.redirect_stderr(error):
                exit_code = wrapper.main()
            result = json.loads(final.read_text())
            check('wrapper-' + scenario + '-status-consistent', result['status'] == expected_status
                  and (exit_code == 0) == (scenario == 'success'))
            check('wrapper-' + scenario + '-raw-output-withheld', raw not in out.getvalue() + error.getvalue() + final.read_text())
            check('wrapper-' + scenario + '-checkpoint-key-preserved', result['objectKey'] == 'objects/acceptance/synthetic-wrapper.png')
            check('wrapper-' + scenario + '-private-child-report-cleaned', len(child_paths) == 1 and not child_paths[0].exists())
    result = {'type': 'offline-cloud-connection-wrapper-boundaries', 'status': 'passed',
              'networkConnected': False, 'cloudObjectsWritten': False, 'cases': cases}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        output = wrapper.OwnedReport(args.report.absolute())
        try:
            output.write(result)
        finally:
            output.close()
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
