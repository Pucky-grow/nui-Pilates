#!/usr/bin/env python3
"""Run the reviewed native login probe after the official WeChat IDE readiness check.

This uses the authorized automation_evaluate tool, not browser cookies or private
IDE files. Invoke outside the sandbox with the required desktop permission. A
successful adapter invocation is not itself successful WeChat authentication.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PROBE_TYPE = 'devtools-native-wechat-to-cloud-login'


def find_report(value):
    if isinstance(value, dict):
        if value.get('probeType') == PROBE_TYPE:
            return value
        for nested in value.values():
            found = find_report(nested)
            if found is not None:
                return found
    if isinstance(value, list):
        for nested in value:
            found = find_report(nested)
            if found is not None:
                return found
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    target = args.report.resolve()
    if not target.is_relative_to(ROOT / 'docs/evidence/wechat'):
        parser.error('report must be within docs/evidence/wechat')
    target.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    source = (ROOT / 'scripts/cloudrun/wechat_native_real_login_probe.js').read_text()
    # The IDE function parser rejects prefixed comments and trailing whitespace.
    function = source[source.index('function ()'):].strip()
    report = {'probeType': PROBE_TYPE, 'status': 'tool-failed',
              'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'codeTokenOrMemberIdentityEmitted': False, 'rawWithheld': True}
    try:
        result = subprocess.run(['wechatide', '-c', 'Codex', 'automation_evaluate',
                                 '--project', str(ROOT), '--fn-source', function],
                                capture_output=True, text=True, timeout=75)
        payload = json.loads(result.stdout[result.stdout.index('{'):])
        proof = find_report(payload)
        if proof is not None:
            report.update(proof)
        else:
            report.update({key: payload[key] for key in ['errorType', 'reason', 'taskId', 'status']
                           if key in payload})
    except subprocess.TimeoutExpired:
        report['timedOut'] = True
    except (ValueError, OSError):
        report['adapterFailed'] = True
    finally:
        with os.fdopen(fd, 'w') as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write('\n')
    print(json.dumps({'status': report['status'], 'report': str(target),
                      'realWechatLoginPassed': report.get('realWechatLoginPassed', False),
                      'failedStage': report.get('failedStage'),
                      'failureBusinessCode': report.get('failureBusinessCode')}))
    return 0 if report['status'] == 'passed-scoped' else 1


if __name__ == '__main__':
    raise SystemExit(main())
