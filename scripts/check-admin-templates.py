#!/usr/bin/env python3
"""Native WXML/WXSS compilation only; does not claim WeChat IDE UI/device acceptance."""
import argparse,json,pathlib,subprocess,tempfile
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--compiler-dir',type=pathlib.Path,default=pathlib.Path('/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec'))
p.add_argument('--scope',choices=['admin','all'],default='admin')
a=p.parse_args();root=pathlib.Path(__file__).resolve().parents[1]/'frontend/miniprogram';results=[]
with tempfile.TemporaryDirectory(prefix='pilates-templates-') as directory:
 for binary,extension in [('wcc','wxml'),('wcsc','wxss')]:
    tool=a.compiler_dir/binary;files=sorted(str(f.relative_to(root)) for f in (root if a.scope=='all' else root/'admin').rglob('*.'+extension))
    if not tool.is_file():results.append({'compiler':binary,'status':'blocked','reason':'Compiler binary unavailable'});continue
    command=[str(tool),'-o',str(pathlib.Path(directory)/(binary+'.out')),*files]
    r=subprocess.run(command,cwd=root,capture_output=True,text=True)
    results.append({'compiler':binary,'scope':a.scope,'files':len(files),'exitCode':r.returncode,'status':'passed' if r.returncode==0 else 'failed','diagnostics':r.stderr[:3000]})
print(json.dumps({'verificationType':'native_template_compilation','deviceAcceptance':'pending','results':results},ensure_ascii=False,indent=2))
raise SystemExit(0 if all(r['status']=='passed' for r in results) else 1)
