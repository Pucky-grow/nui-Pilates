#!/usr/bin/env python3
"""Scan candidate tracked source without printing matched secret values. Not a replacement for gitleaks."""
import pathlib,re,subprocess,json,sys
root=pathlib.Path(__file__).resolve().parents[2]
files=subprocess.run(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root,capture_output=True,check=True).stdout.decode().split('\0')
patterns={'private_key':r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----','aliyun_access_key':r'(?<![A-Za-z0-9])LTAI[A-Za-z0-9]{16,}(?![A-Za-z0-9])','github_token':r'gh[pousr]_[A-Za-z0-9]{30,}','aws_access_key':r'AKIA[A-Z0-9]{16}','jwt_literal':r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}'}
findings=[]
for name in sorted(set(files)):
 p=root/name
 if not name or not p.is_file() or p.stat().st_size>2_000_000:continue
 try:text=p.read_text()
 except UnicodeError:continue
 for number,line in enumerate(text.splitlines(),1):
  for rule,pattern in patterns.items():
   if re.search(pattern,line):
    # Java PEM format parser constants do not contain private key material.
    if rule=='private_key' and ('replace(' in line or 'patterns=' in line):continue
    findings.append({'path':name,'line':number,'rule':rule})
print(json.dumps({'scanner':'source-pattern-precheck','files':len([x for x in files if x]),'status':'failed' if findings else 'passed','findings':findings,'scope':'recognized key/token patterns; full scanner result recorded separately'},indent=2))
sys.exit(1 if findings else 0)
