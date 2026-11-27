#!/usr/bin/env python3
"""Owned Vite + two-tier production-style Nginx; ephemeral credentials arrive only in environment/CDP."""
import argparse, contextlib, json, os, pathlib, re, signal, socket, ssl, subprocess, tempfile, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--backend-port',type=int,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--vite-port',type=int);p.add_argument('--https-port',type=int);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True);children=[];report={'passed':False,'vite':{'status':'running'},'nginx':{'status':'blocked','reason':'PILATES_NGINX_BIN not configured'}}
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def fetch(url,data=None,context=None):
 body=None if data is None else json.dumps(data).encode();req=urllib.request.Request(url,body,{'Content-Type':'application/json'})
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
 with opener.open(req,timeout=15) as r:return r.status,r.read().decode()
def wait(url,proc):
 for _ in range(150):
  if proc.poll() is not None:raise RuntimeError('owned proxy exited')
  try:fetch(url);return
  except Exception:time.sleep(.1)
 raise RuntimeError('owned proxy timeout')
def browser(url,name,extra=None):
 env={**os.environ,'MOUNT_URL':url,**(extra or {})}
 r=subprocess.run(['node',str(ROOT/'scripts/check-web-mount.mjs')],env=env,capture_output=True,text=True,timeout=85)
 # Never include stdout/stderr on parse failure: browser exceptions may contain DOM values.
 if r.returncode:raise RuntimeError(name+' browser failed; '+r.stdout[:180] if r.stdout.startswith('{') else name+' browser failed')
 result=json.loads(r.stdout);(a.output/(name+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');assert result['passed'];return result
try:
 with tempfile.TemporaryDirectory(prefix='p3-web-') as directory,contextlib.ExitStack() as stack:
  temp=pathlib.Path(directory);vport=a.vite_port or port();env={**os.environ,'VITE_API_TARGET':'http://127.0.0.1:'+str(a.backend_port)}
  log=stack.enter_context((temp/'vite.log').open('w'))
  vite=subprocess.Popen(['node',str(ROOT/'admin/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(vport),'--strictPort'],cwd=ROOT/'admin',env=env,stdout=log,stderr=log);children.append(vite)
  wait('http://127.0.0.1:'+str(vport),vite)
  result=browser('http://127.0.0.1:'+str(vport)+'/tests/round3.html','vite-browser')
  report['vite']={'status':'passed','checks':result['results'],'scheduleId':result['scheduleId'],'bookingId':result['bookingId'],'proxy':'actual Vite /api to owned backend'}
  boundaries=browser('http://127.0.0.1:'+str(vport)+'/tests/round3-boundaries.html','boundary-components')
  report['componentAdapter']={'status':'passed','checks':boundaries['results'],'scope':'HTTP adapter only; not backend authorization proof'}
  nginx=os.environ.get('PILATES_NGINX_BIN')
  if nginx:
   nginx=pathlib.Path(nginx).resolve();http_port,https_port,admin_port=port(),a.https_port or port(),port()
   build=subprocess.run(['npm','run','build'],cwd=ROOT/'admin',capture_output=True,text=True,timeout=60)
   if build.returncode:raise RuntimeError('admin production build failed')
   openssl=subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(temp/'key.pem'),'-out',str(temp/'cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1,DNS:localhost'],capture_output=True,text=True)
   if openssl.returncode:raise RuntimeError('local test certificate generation failed')
   inner=(ROOT/'admin/nginx.conf').read_text().replace('listen 80;','listen 127.0.0.1:'+str(admin_port)+';').replace('/usr/share/nginx/html',str(ROOT/'admin/dist'))
   outer=(ROOT/'deploy/nginx.conf').read_text().replace('/etc/nginx/mime.types',str(nginx.parent.parent/'conf/mime.types')).replace('listen 80;','listen 127.0.0.1:'+str(http_port)+';').replace('listen 443 ssl;','listen 127.0.0.1:'+str(https_port)+' ssl;').replace('/etc/nginx/certs/fullchain.pem',str(temp/'cert.pem')).replace('/etc/nginx/certs/privkey.pem',str(temp/'key.pem')).replace('http://server:8080','http://127.0.0.1:'+str(a.backend_port)).replace('http://admin:80','http://127.0.0.1:'+str(admin_port)).replace('https://$host$request_uri','https://$host:'+str(https_port)+'$request_uri').replace('http {','http {\naccess_log off;\n'+inner,1)
   cfg=temp/'nginx.conf';cfg.write_text('pid '+str(temp/'nginx.pid')+';\nerror_log '+str(temp/'nginx-error.log')+';\n'+outer)
   log=stack.enter_context((temp/'nginx-process.log').open('w'))
   check=subprocess.run([str(nginx),'-t','-p',str(temp),'-c',str(cfg)],capture_output=True,text=True)
   if check.returncode:raise RuntimeError('transformed repository Nginx config rejected: '+check.stderr[:500])
   process=subprocess.Popen([str(nginx),'-p',str(temp),'-c',str(cfg),'-g','daemon off;'],stdout=log,stderr=log);children.append(process)
   wait('http://127.0.0.1:'+str(admin_port),process)
   ctx=ssl.create_default_context(cafile=str(temp/'cert.pem'));base='https://127.0.0.1:'+str(https_port)
   _,html=fetch(base+'/schedules',context=ctx);assert '<div id="app">' in html
   assets=re.findall(r'(?:src|href)="(/assets/[^"]+)"',html);assert assets
   for asset in assets:assert fetch(base+asset,context=ctx)[0]==200
   login=json.loads(fetch(base+'/api/auth/admin-login',json.loads(os.environ['PILATES_BROWSER_INPUT'])|{},ctx)[1]);assert login['code']==0
   request=urllib.request.Request(base+'/api/admin/schedules/options',headers={'Authorization':'Bearer '+login['data']['token']})
   with urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ctx)).open(request) as response:assert json.load(response)['code']==0
   prod=browser(base+'/login','nginx-browser',{'PILATES_BROWSER_DRIVER':str(ROOT/'scripts/round3/production-driver.js'),'PILATES_LOCAL_TLS':'true'})
   version=subprocess.run([str(nginx),'-v'],capture_output=True,text=True).stderr.strip()
   report['nginx']={'status':'passed','version':version,'checks':['repository outer /api and /uploads upstream routing configuration','repository inner SPA /schedules fallback','production hashed assets '+str(len(assets)),'HTTPS login and authenticated management API','real production browser login and schedules names'],'browser':prod,'certificate':'owned ephemeral localhost self-signed certificate; browser trust bypass restricted to loopback'}
  report['passed']=True
except Exception as e:
 report['error']=str(e);print('Web acceptance failed:',e)
finally:
 for child in reversed(children):
  child.terminate()
  try:child.wait(timeout=10)
  except subprocess.TimeoutExpired:child.kill();child.wait()
 (a.output/'web-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
raise SystemExit(0 if report['passed'] else 1)
