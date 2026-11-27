#!/usr/bin/env python3
"""Only the payment regression affected by environment gates; H2 is not MySQL compatibility evidence."""
import pathlib,os,subprocess,tempfile,shutil,re
root=pathlib.Path(__file__).resolve().parents[2];out=root/'server/target-round3/h2';out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='p3-h2-') as directory:
 build=pathlib.Path(directory)/'build';env={**os.environ,'PILATES_TEST_BUILD_DIR':str(build)}
 with (out/'run.log').open('w') as log:r=subprocess.run(['python3',str(root/'scripts/test-isolated.py'),'-q','-Dtest=PaymentIdempotencyTest'],cwd=root,env=env,stdout=log,stderr=log)
 if (build/'surefire-reports').exists():shutil.copytree(build/'surefire-reports',out/'surefire-reports',dirs_exist_ok=True)
 for path in [out/'run.log',*out.glob('surefire-reports/*')]:
  if path.is_file():path.write_text(re.sub(r'(Using generated security password:) [^\s<]+',r'\1 [REDACTED]',path.read_text(errors='replace')))
print('Payment H2 regression:', 'passed' if r.returncode==0 else 'failed', '(isolated Redis; no real payment)')
raise SystemExit(r.returncode)
