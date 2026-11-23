#!/usr/bin/env python3
"""Build reviewed working-tree artifacts. No pushes/tags overwritten, no cloud operations."""
import argparse,hashlib,json,os,pathlib,shutil,subprocess,tempfile,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2];p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--docker-context',required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def run(cmd,**kwargs):subprocess.run(list(map(str,cmd)),check=True,**({"cwd":ROOT}|kwargs))
run(['python3',ROOT/'scripts/production/secret-scan.py'])
files=subprocess.check_output(['git','ls-files','-c','-o','--exclude-standard','-z'],cwd=ROOT).split(b'\0');hashes={};captured={}
for name in sorted(set(files)):
 if not name:continue
 path=ROOT/os.fsdecode(name)
 if path.is_file():
  value=path.read_bytes();captured[os.fsdecode(name)]=value;hashes[os.fsdecode(name)]=hashlib.sha256(value).hexdigest()
source=hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest();version='staging-'+source[:12]
manifest={'version':version,'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sourceTreeSha256':source,'sourceHashes':hashes,'createdAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'images':{},'architecture':subprocess.check_output(['uname','-m'],text=True).strip()}
with tempfile.TemporaryDirectory(prefix='pilates-build-') as tempdir:
 temp=pathlib.Path(tempdir);snapshot=temp/'source';snapshot.mkdir();
 for name,value in captured.items():
  path=snapshot/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(value)
 server=temp/'server';admin=temp/'admin';edge=temp/'edge';server.mkdir();admin.mkdir();edge.mkdir()
 run(['mvn','-B','-ntp','-f',snapshot/'backend/pom.xml','-Dpilates.build.directory='+str(server/'target'),'-Dproject.build.outputTimestamp=2026-10-16T00:00:00Z','-Dmaven.test.skip=true','package'])
 shutil.copy(server/'target/pilates-server.jar',server/'pilates-server.jar');shutil.copy(snapshot/'deploy/Dockerfile.server.artifact',server/'Dockerfile')
 # npm ci uses the committed/working-tree lock file, never silently resolves a new version.
 run(['npm','ci'],cwd=snapshot/'frontend/admin')
 run(['npm','run','build'],cwd=snapshot/'frontend/admin')
 shutil.copytree(snapshot/'frontend/admin/dist',admin/'dist');shutil.copy(snapshot/'frontend/admin/nginx.conf',admin/'nginx.conf');shutil.copy(snapshot/'deploy/Dockerfile.admin.artifact',admin/'Dockerfile')
 shutil.copy(snapshot/'deploy/Dockerfile.edge',edge/'Dockerfile');shutil.copy(snapshot/'deploy/nginx.conf',edge/'nginx.conf')
 for name,context in [('server',server),('admin',admin),('edge',edge)]:
  tag='pilates-'+name+':'+version;docker=['docker','--context',a.docker_context]
  if subprocess.run(docker+['image','inspect',tag],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise RuntimeError('refusing to overwrite existing release tag')
  run(docker+['build','--label','org.opencontainers.image.revision='+manifest['commit'],'--label','com.yoyi.source.sha256='+source,'--label','com.yoyi.component='+name,'-t',tag,context])
  manifest['images'][name]={'tag':tag,'imageId':subprocess.check_output(docker+['image','inspect','--format','{{.Id}}',tag],text=True).strip()}
 shutil.copy(server/'pilates-server.jar',a.output/'pilates-server.jar');manifest['jarSha256']=hashlib.sha256((a.output/'pilates-server.jar').read_bytes()).hexdigest()
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print('BUILT',version)
