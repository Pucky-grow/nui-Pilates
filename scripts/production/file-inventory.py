#!/usr/bin/env python3
"""Preview-only local file inventory; never moves, publishes or deletes a file.
Compare the protected report to a reviewed database reference export before any migration.
"""
import argparse,hashlib,json,pathlib
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args();root=a.directory.resolve();rows=[]
for file in sorted(root.rglob('*')):
 if file.is_symlink() or not file.is_file():continue
 digest=hashlib.sha256()
 with file.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
 rows.append({'relativePath':str(file.relative_to(root)),'bytes':file.stat().st_size,'sha256':digest.hexdigest(),'referenceStatus':'requires reviewed database export','visibility':'PRIVATE until explicitly classified'})
a.output.write_text(json.dumps({'mode':'preview-only','files':rows},ensure_ascii=False,indent=2)+'\n');a.output.chmod(0o600);print('Preview inventory saved; no source files changed.')
