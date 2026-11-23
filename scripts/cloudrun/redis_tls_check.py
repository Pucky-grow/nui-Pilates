#!/usr/bin/env python3
"""Run real TLS Redis client tests against an owned local container; never a cloud target."""
import argparse,datetime,json,os,pathlib,secrets,subprocess,tempfile,time
ROOT=pathlib.Path(__file__).resolve().parents[2]

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--docker-context',required=True);p.add_argument('--java-home',required=True)
 p.add_argument('--report',required=True,type=pathlib.Path);a=p.parse_args()
 docker=['docker','--context',a.docker_context];name='yoyi-redis-tls-'+secrets.token_hex(5)
 pin=json.loads((ROOT/'deploy/image-lock.json').read_text())['images']['redis']['reference']
 def run(cmd,**kw):return subprocess.run(list(map(str,cmd)),capture_output=True,text=True,timeout=180,**kw)
 a.report.parent.mkdir(parents=True,exist_ok=True)
 result={'type':'local-real-TLS-Redis-clients','status':'running','cloudRedisVerified':False,'fixtureImage':pin}
 with tempfile.TemporaryDirectory(prefix=name,dir=ROOT/'.secrets') as d:
  d=pathlib.Path(d);d.chmod(0o700);password=secrets.token_hex(32)
  cert=run(['openssl','req','-x509','-nodes','-days','2','-newkey','rsa:2048','-keyout',d/'key.pem','-out',d/'ca.pem','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'])
  if cert.returncode:raise RuntimeError('synthetic certificate generation failed')
  (d/'key.pem').chmod(0o444)
  (d/'redis.conf').write_text('bind 0.0.0.0\nport 0\ntls-port 6379\ntls-cert-file /run/fixture/ca.pem\ntls-key-file /run/fixture/key.pem\ntls-ca-cert-file /run/fixture/ca.pem\ntls-auth-clients no\nprotected-mode yes\nrequirepass '+password+'\nsave ""\nappendonly no\n')
  (d/'redis.conf').chmod(0o444)
  try:
   start=run(docker+['run','-d','--name',name,'-p','127.0.0.1::6379','--cap-drop','ALL','--security-opt','no-new-privileges','--user','999:999','--mount','type=bind,src='+str(d)+',dst=/run/fixture,readonly',pin,'redis-server','/run/fixture/redis.conf'])
   if start.returncode:raise RuntimeError('local TLS Redis fixture could not start; values withheld')
   mapped=run(docker+['port',name,'6379']).stdout.strip()
   if not mapped.startswith('127.0.0.1:'):raise RuntimeError('fixture must bind loopback only')
   port=mapped.rsplit(':',1)[1]
   for _ in range(30):
    ready=run(docker+['exec',name,'redis-cli','--tls','--cacert','/run/fixture/ca.pem','PING'])
    if 'NOAUTH' in ready.stdout or 'NOAUTH' in ready.stderr:break
    time.sleep(.5)
   else:raise RuntimeError('local TLS fixture not ready; values withheld')
   env={**os.environ,'JAVA_HOME':a.java_home,'PILATES_REDIS_TLS_FIXTURE':'true','PILATES_REDIS_TLS_PASSWORD':password,'PILATES_REDIS_TLS_PORT':port,'PILATES_REDIS_TLS_CA':str(d/'ca.pem')}
   with (a.report.parent/'redis-tls-maven.log').open('w') as log:
    tested=subprocess.run(['mvn','-B','-ntp','-f',str(ROOT/'backend/pom.xml'),'-Dtest=RedissonTlsTest','test'],env=env,stdout=log,stderr=log,timeout=240)
   if tested.returncode:raise RuntimeError('TLS regression tests failed; see local test report')
   result.update(status='passed',cases=['Lettuce authenticated TLS PING','Redisson authenticated TLS lock/unlock','wrong server address rejected','untrusted CA rejected','TLS/ACL/empty-auth configuration boundaries'])
  except Exception as e:
   result.update(status='failed',failureType=type(e).__name__);raise
  finally:
   run(docker+['rm','-f','-v',name]);result['ownedContainerCleaned']=True
   result['checkedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
   a.report.write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'status':result['status'],'report':str(a.report)}))
if __name__=='__main__':main()
