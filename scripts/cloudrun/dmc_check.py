#!/usr/bin/env python3
"""Check unsafe DMC target arguments fail before emitting any SQL or contacting a DB."""
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
UUID = 'd1f6adf9-d5d5-11f1-9afc-0c42a17335be'
cases = [
    ('malformed-uuid', 'wrong', '8.0.30'),
    ('mysql57-rejected', UUID, '5.7.18'),
    ('version-injection-rejected', UUID, "8.0.30'; DROP DATABASE x"),
]
results = []
with tempfile.TemporaryDirectory(prefix='pilates-dmc-boundaries-') as directory:
    for name, uuid, version in cases:
        output = pathlib.Path(directory) / name
        result = subprocess.run([
            'python3', str(ROOT / 'scripts/cloudrun/dmc_prepare.py'),
            '--bundle', str(pathlib.Path(directory) / 'nonexistent-bundle'),
            '--expect-server-uuid', uuid, '--full-version', version,
            '--confirm-isolated-database', 'uni_pilates_test', '--output', str(output),
        ], capture_output=True, text=True)
        expected = 'server UUID is required' if name == 'malformed-uuid' else 'full version is required'
        passed = result.returncode != 0 and expected in result.stderr and not output.exists()
        results.append({'name': name, 'status': 'passed' if passed else 'failed'})
report = {'type': 'local-generator-input-boundaries', 'noDatabaseConnection': True,
          'command': 'python3 scripts/cloudrun/dmc_check.py', 'cases': results}
print(json.dumps(report, indent=2))
raise SystemExit(0 if all(case['status'] == 'passed' for case in results) else 1)
