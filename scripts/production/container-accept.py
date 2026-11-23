#!/usr/bin/env python3
"""Owned Linux compose rehearsal. Creates only a unique local project; no cloud targets.
Credentials/certificates live in a temporary 0700 directory and are removed on exit.
"""
import argparse,datetime,hashlib,json,os,pathlib,secrets,socket,ssl,subprocess,tempfile,time,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--docker-context',required=True);p.add_argument('--mysql-bin',required=True,type=pathlib.Path);p.add_argument('--release',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--mysql-series',choices=['8.0','8.4'],default='8.0');p.add_argument('--rollback-image',help='Explicit compatible candidate image ID; no baseline/unknown rollback');p.add_argument('--redis-tls',action='store_true',help='Use real TLS with a private fixture CA for application Redis and Redisson');p.add_argument('--skip-browser',action='store_true',help='Backend-only scope; never counts as browser acceptance');p.add_argument('--web-fixture-release',type=pathlib.Path,help='Explicit unchanged admin/edge images used only as local test fixtures');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
manifest=json.loads((a.release/'manifest.json').read_text());pins=json.loads((ROOT/'deploy/image-lock.json').read_text())['images'];mysql_pin=pins['mysql' if a.mysql_series=='8.0' else 'mysql8.4'];docker=['docker','--context',a.docker_context];project='pilates-verify-'+secrets.token_hex(4)
test_images=dict(manifest['images'])
web_fixture=None
if a.web_fixture_release:
 fixture=json.loads((a.web_fixture_release/'manifest.json').read_text())
 web_build_files = {
  'frontend/admin/package.json', 'frontend/admin/package-lock.json', 'frontend/admin/nginx.conf',
  'deploy/Dockerfile.admin.artifact', 'deploy/Dockerfile.edge', 'deploy/nginx.conf',
 }
 for name,expected in fixture['sourceHashes'].items():
  source_name = 'frontend/' + name if name.startswith('admin/') else name
  if source_name.startswith('frontend/admin/src/') or source_name in web_build_files:
   source = ROOT / source_name
   if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
    raise SystemExit('STOP: web fixture sources changed; rebuild fixtures first')
 for component in ['admin','edge']:test_images[component]=fixture['images'][component]
 web_fixture={'release':fixture['version'],'components':['admin','edge'],'purpose':'Unchanged local proxy/static fixtures only; not part of new server image build'}
report={'status':'running','scope':'local Linux containers; not Aliyun/WeChat or actual cloud MySQL/COS','project':project,'cases':[],'releaseImages':test_images,'webFixture':web_fixture,'browserAcceptance':'skipped-by-scope' if a.skip_browser else 'pending'}
def run(cmd,**kw):return subprocess.run(list(map(str,cmd)),capture_output=True,text=True,**kw)
def private(path,value,mode=0o600):path.write_text(value);path.chmod(mode);return path
def save(): (a.output/'containers.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def passed(name,**details):report['cases'].append({'name':name,'status':'passed',**details});save();print('PASS:',name,flush=True)
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
(ROOT/'.secrets').mkdir(mode=0o700,exist_ok=True)
candidate=json.loads(run(docker+['image','inspect',test_images['server']['imageId']]).stdout)[0]
report['serverPlatform']=candidate['Os']+'/'+candidate['Architecture']
if manifest.get('targetPlatform') and report['serverPlatform']!=manifest['targetPlatform']:raise SystemExit('STOP: actual server image platform differs from release')
try:
 with tempfile.TemporaryDirectory(prefix='pilates-containers-',dir=ROOT/'.secrets') as directory:
  temp=pathlib.Path(directory);temp.chmod(0o700);tls=temp/'certs';tls.mkdir();https=port();mysql_tcp=port();root=secrets.token_hex(32);runtime=secrets.token_hex(32);migrator=secrets.token_hex(32);admin_password=secrets.token_hex(32);redis_password=secrets.token_hex(32);jwt=secrets.token_hex(40)
  assert run(['openssl','req','-x509','-nodes','-days','2','-newkey','rsa:2048','-keyout',tls/'privkey.pem','-out',tls/'fullchain.pem','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,DNS:redis,IP:127.0.0.1']).returncode==0
  (tls/'privkey.pem').chmod(0o444) # parent is 0700; bind-mounted only into own edge container.
  rootfile=private(temp/'mysql-password',root+'\n',0o444);clientfile=private(temp/'mysql-client.cnf','[client]\nuser=root\npassword='+root+'\n',0o444)
  redisfile=private(temp/'redis.conf','bind 0.0.0.0\nprotected-mode yes\nrequirepass '+redis_password+'\nappendonly yes\ndir /data\n',0o444)
  appfile=private(temp/'application.properties',f"spring.datasource.url=jdbc:mysql://mysql:3306/pilates_container?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\nspring.datasource.username=delivery_app\nspring.datasource.password={runtime}\nspring.data.redis.host=redis\nspring.data.redis.password={redis_password}\nyoyi.jwt.secret={jwt}\nyoyi.public-base-url=https://127.0.0.1:{https}\nyoyi.cors.allowed-origins=https://127.0.0.1:{https}\nyoyi.security.trusted-proxies=172.29.88.10/32\nyoyi.wx.login-enabled=false\nyoyi.storage.type=local\nyoyi.storage.local-for-isolated-test=true\nyoyi.upload.dir=/data/uploads\nyoyi.scheduling.enabled=false\nyoyi.delivery.workers-enabled=true\n",0o444)
  override={'services':{'mysql':{'image':mysql_pin['reference'],'environment':{'MYSQL_ROOT_PASSWORD_FILE':'/run/secrets/mysql-password','MYSQL_ROOT_HOST':'%','MYSQL_DATABASE':'pilates_container','TZ':'Asia/Shanghai'},'volumes':[f'{rootfile}:/run/secrets/mysql-password:ro',f'{clientfile}:/run/secrets/mysql-client.cnf:ro','mysql-data:/var/lib/mysql'],'ports':[{'target':3306,'published':str(mysql_tcp),'host_ip':'127.0.0.1'}],'command':['--character-set-server=utf8mb4','--collation-server=utf8mb4_general_ci','--default-time-zone=+08:00'],'networks':['private'],'mem_limit':'512m','logging':{'driver':'json-file','options':{'max-size':'5m','max-file':'2'}}},'redis':{'image':pins['redis']['reference'],'command':['redis-server','/run/redis.conf'],'volumes':[f'{redisfile}:/run/redis.conf:ro','redis-data:/data'],'networks':['private'],'mem_limit':'128m','logging':{'driver':'json-file','options':{'max-size':'5m','max-file':'2'}}}},'volumes':{'mysql-data':{},'redis-data':{}}}
  if a.redis_tls:
   redisfile.chmod(0o600);appfile.chmod(0o600)
   redisfile.write_text('bind 0.0.0.0\nport 0\ntls-port 6379\ntls-auth-clients no\ntls-cert-file /run/fullchain.pem\ntls-key-file /run/privkey.pem\ntls-ca-cert-file /run/fullchain.pem\nprotected-mode yes\nrequirepass '+redis_password+'\nappendonly yes\ndir /data\n')
   override['services']['redis']['volumes'] += [f'{tls}/fullchain.pem:/run/fullchain.pem:ro',f'{tls}/privkey.pem:/run/privkey.pem:ro']
   override['services']['server']={'volumes':[f'{tls}/fullchain.pem:/run/fixture/ca.pem:ro']}
   appfile.write_text(appfile.read_text()+'spring.data.redis.ssl.enabled=true\nspring.data.redis.ssl.bundle=tencent-redis\nspring.ssl.bundle.pem.tencent-redis.truststore.certificate=file:/run/fixture/ca.pem\n')
   redisfile.chmod(0o444);appfile.chmod(0o444)
   report['redisTransport']='TLS with private CA and endpoint verification; local fixture only'
  overridepath=private(temp/'local.json',json.dumps(override))
  env={**os.environ,'SERVER_IMAGE':test_images['server']['imageId'],'ADMIN_IMAGE':test_images['admin']['imageId'],'EDGE_IMAGE':test_images['edge']['imageId'],'APP_CONFIG_FILE':str(appfile),'TLS_DIRECTORY':str(tls),'HTTPS_PORT':str(https)}
  compose=docker+['compose','-p',project,'-f',ROOT/'deploy/docker-compose.yml','-f',overridepath]
  def dc(args,check=True,**kwargs):
   result=run(compose+args,env=env,timeout=180,**kwargs)
   if check and result.returncode:
    detail=result.stderr
    for secret in [root,runtime,migrator,admin_password,redis_password,jwt]:detail=detail.replace(secret,'[REDACTED]')
    raise RuntimeError('owned compose command failed: '+','.join(args[:2])+': '+detail[-1200:])
   return result
  try:
   dc(['up','-d','mysql','redis'])
   published=dc(['port','mysql','3306']).stdout.strip();assert published.startswith('127.0.0.1:');tcp=published.rsplit(':',1)[1]
   hostclient=private(temp/'host-client.cnf',f'[client]\nuser=root\npassword={root}\nhost=127.0.0.1\nport={tcp}\nprotocol=TCP\n')
   client=[a.mysql_bin/'mysql','--defaults-file='+str(hostclient),'--batch','--skip-column-names']
   def sql(statement):
    r=run(client,input=statement,timeout=60)
    if r.returncode:raise RuntimeError('owned container SQL failed; values withheld')
    return r.stdout.strip()
   for _ in range(180):
    if run(client,input='SELECT 1').returncode==0:break
    time.sleep(.5)
   assert sql('SELECT VERSION()')==mysql_pin['tag'];uuid=sql('SELECT @@server_uuid');report['mysqlVersion']=sql('SELECT VERSION(),@@version_comment,@@session.time_zone')
   report['mysqlParameters']=dict(zip(['version','comment','charset','collation','sqlMode','isolation','globalTimeZone','sessionTimeZone','systemTimeZone'],sql('SELECT VERSION(),@@version_comment,@@character_set_server,@@collation_server,@@sql_mode,@@transaction_isolation,@@global.time_zone,@@session.time_zone,@@system_time_zone').split('\t')))
   config=private(temp/'database.properties',f'url=jdbc:mysql://127.0.0.1:{tcp}/pilates_container?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\ndatabase=pilates_container\nuser=root\npassword={root}\nbootstrap.username=container_owner\nbootstrap.name=ContainerOwner\nruntime.username=delivery_app\nruntime.password={runtime}\nruntime.host=%\nmigrator.username=delivery_migrator\nmigrator.password={migrator}\nmigrator.host=%\n')
   tool=['java','-Duser.timezone=Asia/Shanghai','-Dloader.main=com.yoyi.pilates.infra.tools.DatabaseTool','-cp',a.release/'pilates-server.jar','org.springframework.boot.loader.launch.PropertiesLauncher','--config',config,'--expect-server-uuid',uuid,'--directory',ROOT/'database/production']
   for mode in ['fresh','provision','bootstrap']:
    result=run(tool+['--mode',mode],input=admin_password+'\n');assert result.returncode==0,result.stderr[:200]
   passed('Linux MySQL clean migration, runtime account and one-time bootstrap')
   dc(['up','-d','server','admin','nginx'])
   tlsctx=ssl.create_default_context(cafile=str(tls/'fullchain.pem'))
   def request(path,body=None,token=None,method=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(f'https://localhost:{https}'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    with urllib.request.urlopen(req,context=tlsctx,timeout=25) as response:return response.status,response.headers,response.read()
   for _ in range(200):
    try:
     if json.loads(request('/health')[2])['code']==0:break
    except (OSError,ValueError,urllib.error.URLError):pass
    time.sleep(.5)
   assert json.loads(request('/health')[2])['code']==0
   assert b'<div id="app">' in request('/accounts')[2]
   html=request('/')[2].decode();import re;asset=re.search(r'src="([^"]+\.js)"',html).group(1);assert request(asset)[0]==200
   try:request('/actuator/prometheus');raise AssertionError('public monitor exposure')
   except urllib.error.HTTPError as error:assert error.code==404
   auth=json.loads(request('/api/auth/admin-login',{'username':'container_owner','password':admin_password})[2]);assert auth['code']==0;token=auth['data']['token'];assert json.loads(request('/api/admin/accounts',token=token)[2])['code']==0
   passed('trusted local HTTPS, Nginx real API login, SPA deep link/assets and public actuator rejection')
   if not a.skip_browser:
    browser=run(['node',ROOT/'scripts/check-web-mount.mjs'],env={**os.environ,'MOUNT_URL':f'https://127.0.0.1:{https}/login','PILATES_LOCAL_TLS':'true','PILATES_BROWSER_DRIVER':str(ROOT/'scripts/production/web-driver.js'),'PILATES_BROWSER_INPUT':json.dumps({'username':'container_owner','password':admin_password,'testPassword':secrets.token_hex(32)})},timeout=75)
    (a.output/'web-browser.json').write_text(browser.stdout)
    assert browser.returncode==0,'actual Web UI failed; see safe browser report'
    report['browserAcceptance']='passed'
    passed('actual Chrome Web build: login, persisted rules form and staff account creation through real HTTPS backend')
   def api(path,body=None,method=None):return json.loads(request(path,body,token,method)[2])
   def dbsql(query):return sql('USE pilates_container;'+query)
   dbsql("INSERT INTO course_category(code,name,status) VALUES('CONTAINER','合成分类','ACTIVE');INSERT INTO coach(name,years,status) VALUES('合成教练',1,'ACTIVE');INSERT INTO classroom(name,type,capacity,status) VALUES('合成教室','GROUP',20,'ACTIVE');INSERT INTO course(category_id,name,type,duration_minutes,difficulty,credits_cost,status) VALUES(1,'合成免费课','GROUP',60,'BEGINNER',1,'ON_SHELF');INSERT INTO course_coach(course_id,coach_id,status) VALUES(1,1,'APPROVED');INSERT INTO course_schedule(course_id,coach_id,classroom_id,course_date,start_time,end_time,max_capacity,booked_count,status) VALUES(1,1,1,DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),'12:00','13:00',8,0,'SCHEDULED')")
   for n in range(100):dbsql(f"INSERT INTO `user`(openid,nickname,status) VALUES('container_member_{n}','合成会员{n}','ACTIVE');INSERT INTO member(user_id,member_no,level_id,total_sessions,status) VALUES({n+1},'CONTAINER-{n:04}',1,0,'ACTIVE')")
   # Real image/HTTP regression of the existing file boundary, independent of the COS SDK wire fixture.
   import struct,zlib,urllib.parse
   def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
   png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x20\x40\x60'))+chunk(b'IEND',b'')
   def upload(purpose,owner=None):
    boundary='fixture-'+secrets.token_hex(12)
    payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode()+png+(f'\r\n--{boundary}--\r\n').encode()
    path='/api/upload?purpose='+purpose+('' if owner is None else '&ownerId='+str(owner))
    req=urllib.request.Request(f'https://localhost:{https}'+path,data=payload,headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary})
    with urllib.request.urlopen(req,context=tlsctx,timeout=25) as response:result=json.loads(response.read())
    assert result['code']==0;return result['data']
   def media_path(url):
    parsed=urllib.parse.urlparse(url);return parsed.path+('' if not parsed.query else '?'+parsed.query)
   private_image=upload('AVATAR',1);public_image=upload('PUBLIC_ASSET')
   for image in [private_image,public_image]:assert image['url'].startswith('/media/ref/')
   assert request(media_path(public_image['previewUrl']))[2]==png
   private_response=request(media_path(private_image['previewUrl']));assert private_response[2]==png and 'no-store' in private_response[1].get('Cache-Control','')
   assert json.loads(request('/media/public/'+private_image['objectId'])[2])['code']==40300
   parsed=urllib.parse.urlparse(private_image['previewUrl']);query=urllib.parse.parse_qs(parsed.query);query['expires']=[str(int(query['expires'][0])-601)]
   assert json.loads(request(parsed.path+'?'+urllib.parse.urlencode(query,doseq=True))[2])['code']==40300
   assert api('/api/admin/accounts',{'username':'file_boundary_coach','name':'合成文件边界教练','password':admin_password,'role':'COACH','coachId':1})['code']==0
   coach_auth=json.loads(request('/api/auth/admin-login',{'username':'file_boundary_coach','password':admin_password})[2]);assert coach_auth['code']==0
   coach_token=coach_auth['data']['token']
   assert json.loads(request('/api/files/'+private_image['objectId']+'/access',token=coach_token)[2])['code']==40300
   assert api('/api/files/'+private_image['objectId']+'/access')['code']==0
   passed('real image multipart public/private uploads, stable refs, signed expiry and unserved coach denial remain enforced',storageFixture='explicit isolated local provider; not real COS',uploadedObjects=2)
   import concurrent.futures,threading,collections
   barrier=threading.Barrier(100)
   def reserve(uid):barrier.wait(timeout=30);return api('/api/admin/bookings',{'userId':uid,'scheduleId':1,'reason':'隔离并发验收'})
   with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:responses=list(pool.map(reserve,range(1,101)))
   successes=sum(r['code']==0 for r in responses);failures=collections.Counter(str(r['code'])+':'+r.get('message','') for r in responses if r['code']!=0)
   assert successes==8
   db_count=int(dbsql('SELECT booked_count FROM course_schedule WHERE id=1'));occupied=int(dbsql("SELECT COUNT(*) FROM booking WHERE schedule_id=1 AND deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')"));assert db_count==occupied==8
   stock=dc(['exec','-T','redis','sh','-c',("read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli "+("--tls --cacert /run/fullchain.pem " if a.redis_tls else "")+"GET pilates:stock:1")],input=redis_password+'\n').stdout.strip();assert stock=='0'
   passed('100 simultaneous synthetic members reserve 8 seats through real TLS/admin HTTP; DB/occupied/Redis agree',attempts=100,successes=successes,failures=dict(failures),databaseCount=db_count,occupiedCount=occupied,redisStock=int(stock))
   first=int(dbsql("SELECT MIN(id) FROM booking WHERE status='BOOKED'"));uid=int(dbsql('SELECT user_id FROM booking WHERE id='+str(first)))
   for n in range(3):
    assert api(f'/api/admin/bookings/{first}/cancel',{'reason':'隔离多次重约'})['code']==0
    newer=api('/api/admin/bookings',{'userId':uid,'scheduleId':1,'reason':'隔离多次重约'});assert newer['code']==0;newid=newer['data']
    assert api(f'/api/admin/bookings/{first}/cancel',{'reason':'旧预约号重试'})['code']==0
    assert dbsql('SELECT status FROM booking WHERE id='+str(newid))=='BOOKED';first=newid
   day=dbsql('SELECT course_date FROM course_schedule WHERE id=1');body={'courseId':1,'coachId':1,'classroomId':1,'courseDate':day,'startTime':'12:00:00','endTime':'13:00:00','maxCapacity':5}
   assert api('/api/admin/schedules/1',body,'PUT')['code']!=0;body['maxCapacity']=10;assert api('/api/admin/schedules/1',body,'PUT')['code']==0
   free=[int(x) for x in dbsql("SELECT id FROM `user` WHERE id NOT IN (SELECT user_id FROM booking WHERE status='BOOKED') LIMIT 2").splitlines()]
   for member in free:assert api('/api/admin/bookings',{'userId':member,'scheduleId':1,'reason':'隔离扩容'})['code']==0
   assert dbsql('SELECT booked_count FROM course_schedule WHERE id=1')=='10'
   assert api('/api/admin/schedules/1/cancel?reason=isolated',{})['code']==0
   assert dbsql("SELECT COUNT(*) FROM booking WHERE schedule_id=1 AND status='BOOKED'")=='0'
   passed('multiple rebookings preserve old IDs; shrink rejected, expansion seats usable, whole-class cancellation atomic')


   ids={name:dc(['ps','-q',name]).stdout.strip() for name in ['server','admin','nginx']}
   def execserver(command):return dc(['exec','-T','server']+command)
   assert execserver(['id','-u']).stdout.strip()=='10001'
   report['serverProcessArchitecture']=execserver(['uname','-m']).stdout.strip()
   if report['serverPlatform']=='linux/amd64':assert report['serverProcessArchitecture']=='x86_64'
   info=json.loads(run(docker+['inspect',ids['server']]).stdout)[0];assert info['HostConfig']['ReadonlyRootfs'];assert info['HostConfig']['Memory']>0;assert info['HostConfig']['LogConfig']['Config']['max-size']=='10m';assert info['HostConfig']['RestartPolicy']['Name']=='unless-stopped'
   image=json.loads(run(docker+['image','inspect',test_images['server']['imageId']]).stdout)[0]
   report['serverPlatform']=image['Os']+'/'+image['Architecture']
   def health(kind):return execserver(['curl','-s','-o','/dev/null','-w','%{http_code}',f'http://127.0.0.1:9091/actuator/health/{kind}']).stdout.strip()
   assert health('liveness')=='200' and health('readiness')=='200'
   dc(['stop','redis']);assert health('liveness')=='200';assert health('readiness')=='503';dc(['start','redis'])
   for _ in range(100):
    if health('readiness')=='200':break
    time.sleep(.2)
   assert health('readiness')=='200'
   passed('non-root/read-only/limits/rotation/restart settings effective; Redis outage fails readiness without killing liveness; reconnect succeeds')
   dc(['stop','mysql']);assert health('liveness')=='200';assert health('readiness')=='503';dc(['start','mysql'])
   for _ in range(100):
    if health('readiness')=='200':break
    time.sleep(.2)
   assert health('readiness')=='200';passed('database outage fails readiness, preserves liveness and recovers connections/data after restart')

   metrics=execserver(['curl','-fs','http://127.0.0.1:9091/actuator/prometheus']).stdout;assert 'pilates_outbox_pending' in metrics and 'hikaricp' in metrics
   dc(['exec','-T','server','sh','-c','printf "owned persistence fixture" > /data/uploads/container-fixture'])
   before=hashlib.sha256(execserver(['cat','/data/uploads/container-fixture']).stdout.encode()).hexdigest()
   dc(['up','-d','--force-recreate','server']);
   for _ in range(150):
    try:
     if health('readiness')=='200':break
    except RuntimeError:pass
    time.sleep(.4)
   assert health('readiness')=='200';assert hashlib.sha256(execserver(['cat','/data/uploads/container-fixture']).stdout.encode()).hexdigest()==before
   assert json.loads(request('/api/auth/me',token=token)[2])['code']==0
   assert request(media_path(public_image['previewUrl']))[2]==png
   refreshed=api('/api/files/'+private_image['objectId']+'/access');assert refreshed['code']==0;assert request(media_path(refreshed['data']['url']))[2]==png
   passed('container recreation preserves file volume, runtime database and sessions; internal Prometheus metrics scrape succeeds')
   # The same reviewed artifact can be restarted without a schema downgrade; this is the safe application rollback mechanism.
   dc(['restart','server']);
   for _ in range(150):
    try:
     if health('readiness')=='200':break
    except RuntimeError:pass
    time.sleep(.4)
   assert health('readiness')=='200';passed('same-schema application restart rehearsal; previous-image binary rollback remains conditional on a compatible approved image')
   if a.rollback_image:
    metadata=json.loads(run(docker+['image','inspect',a.rollback_image]).stdout)[0]
    entrypoint=metadata.get('Config',{}).get('Entrypoint') or []
    assert entrypoint and pathlib.Path(entrypoint[0]).name=='java','rollback candidate must be a Java backend image'
    assert a.rollback_image.startswith('sha256:') and len(a.rollback_image)==71
    history=dbsql('SELECT COUNT(*),SUM(id) FROM booking');schema=dbsql('SELECT version,checksum,state FROM schema_history ORDER BY version');current=env['SERVER_IMAGE']
    for image in [a.rollback_image,current]:
     env['SERVER_IMAGE']=image;dc(['up','-d','--force-recreate','server'])
     for _ in range(150):
      try:
       if health('readiness')=='200':break
      except RuntimeError:pass
      time.sleep(.4)
     assert health('readiness')=='200';assert api('/api/auth/me')['code']==0;assert dbsql('SELECT COUNT(*),SUM(id) FROM booking')==history;assert dbsql('SELECT version,checksum,state FROM schema_history ORDER BY version')==schema
    passed('explicit compatible candidate binary rollback and return preserve post-migration booking history and schema; no structural downgrade',candidate=a.rollback_image)

   strict_mode=dbsql('SELECT @@global.sql_mode')
   # Connector/J normally adds STRICT_TRANS_TABLES for JDBC truncation checks.
   # Disable that automatic repair only in this owned negative-startup fixture.
   original_app=appfile.read_text();appfile.chmod(0o600)
   appfile.write_text(original_app.replace('serverTimezone=Asia/Shanghai','serverTimezone=Asia/Shanghai&jdbcCompliantTruncation=false'));appfile.chmod(0o444)
   dbsql("SET GLOBAL sql_mode='NO_ENGINE_SUBSTITUTION'")
   dc(['up','-d','--force-recreate','server'])
   # QEMU AMD64 cold startup can exceed the old 30-second native-ARM deadline.
   # Wait for the same rejection evidence; do not relax the application's strict-mode guard.
   for _ in range(400):
    logs=dc(['logs','--no-color','server'],False).stdout
    if 'Production database readiness rejected' in logs:break
    time.sleep(.3)
   assert 'Production database readiness rejected' in logs
   dbsql("SET GLOBAL sql_mode='"+strict_mode+"'");appfile.chmod(0o600);appfile.write_text(original_app);appfile.chmod(0o444);dc(['up','-d','--force-recreate','server'])
   for _ in range(150):
    try:
     if health('readiness')=='200':break
    except RuntimeError:pass
    time.sleep(.4)
   assert health('readiness')=='200'
   passed('non-strict MySQL startup rejected before readiness; explicit strict-mode restoration recovers without data changes')
   report['status']='passed';save()
  finally:
   logs=dc(['logs','--no-color'],False).stdout
   for secret in [root,runtime,migrator,admin_password,redis_password,jwt]:logs=logs.replace(secret,'[REDACTED]')
   import re;logs=re.sub(r'eyJ[\w-]+\.[\w-]+\.[\w-]+','[REDACTED_JWT]',logs);(a.output/'compose.log').write_text(logs)
   # Only this random project; volumes were created by this run. Never a shared Redis or business DB.
   dc(['down','--volumes','--remove-orphans'],False)
except Exception as error:report['status']='failed';report['error']=type(error).__name__+': '+str(error);save();print('FAIL:',report['error'],flush=True);raise
