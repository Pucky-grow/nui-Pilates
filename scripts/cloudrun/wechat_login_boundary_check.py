#!/usr/bin/env python3
"""Negative-only checks of the isolated uni-test WeChat login boundary.

No real wx.login code, AppSecret or member identity is accepted by this command.
A passing result never means a real WeChat login or device acceptance passed.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess

ORIGIN = 'https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com'


def request(path, body=None, token=None):
    config = ['url = ' + json.dumps(ORIGIN + path),
              'header = "Accept: application/json"']
    if body is not None:
        config += ['header = "Content-Type: application/json"',
                   'data-binary = ' + json.dumps(json.dumps(body))]
    if token:
        config += ['header = ' + json.dumps('Authorization: Bearer ' + token)]
    result = subprocess.run(
        ['curl', '-sS', '--connect-timeout', '5', '--max-time', '25',
         '--config', '-', '--write-out', '\n%{http_code}'],
        input='\n'.join(config) + '\n', text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('HTTPS request failed; response and diagnostics withheld')
    raw, _, status = result.stdout.rpartition('\n')
    return int(status), json.loads(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    report = {'type': 'real-cloud-wechat-negative-boundary', 'origin': ORIGIN,
              'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'status': 'running', 'checks': [], 'realWechatCodeUsed': False,
              'realWechatLoginPassed': False, 'deviceTested': False,
              'secretsOrIdentityEmitted': False, 'isProductionAcceptance': False}
    stage = 'health'
    try:
        status, data = request('/health')
        assert status == 200 and data.get('code') == 0
        assert data.get('data', {}).get('service') == 'pilates-server'
        report['checks'].append({'name': stage, 'status': 'passed', 'http': status})
        stage = 'empty-login-code'
        status, data = request('/api/auth/wx-login', {})
        assert status == 200 and data.get('code') == 40000
        report['checks'].append({'name': stage, 'status': 'passed', 'http': status, 'businessCode': data['code']})
        stage = 'synthetic-code-must-not-become-member'
        status, data = request('/api/auth/wx-login', {'code': 'round3_local_member'})
        if data.get('code') == 0:
            token = data.get('data', {}).get('token')
            if token:
                _, logout = request('/api/auth/logout', {}, token)
                report['unexpectedSessionRevoked'] = logout.get('code') == 0
            raise AssertionError('Synthetic login was accepted; stop user testing')
        report['checks'].append({'name': stage, 'status': 'passed' if status == 200 and data.get('code') == 40100 else 'failed',
                                 'http': status, 'businessCode': data.get('code')})
        assert status == 200 and data.get('code') == 40100
        report['status'] = 'passed-negative-only'
        report['limitation'] = 'Generic rejection cannot distinguish invalid code, AppSecret or upstream WeChat failure; real wx.login code is still required.'
    except Exception as error:
        report.update(status='failed', failedStage=stage, errorType=type(error).__name__, rawErrorWithheld=True)
    finally:
        with os.fdopen(fd, 'w') as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write('\n')
    print(json.dumps({'status': report['status'], 'checks': len(report['checks']),
                      'realWechatLoginPassed': False, 'report': str(args.report)}))
    return 0 if report['status'] == 'passed-negative-only' else 1


if __name__ == '__main__':
    raise SystemExit(main())
