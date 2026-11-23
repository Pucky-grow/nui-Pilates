#!/usr/bin/env python3
"""Disposable native MySQL 8 / Redis delivery rehearsal. Never accepts a database URL.
Owns both source and restoration instances. Protected credentials are removed on exit.
"""
import argparse,contextlib,datetime,hashlib,json,os,pathlib,secrets,socket,subprocess,tempfile,time,urllib.request,urllib.error,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql-bin',required=True,type=pathlib.Path);p.add_argument('--jar',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
report={'status':'running','scope':'owned native staging HTTP; not Aliyun or WeChat','productionTouched':False,'cases':[]}
def save(): (a.output/'delivery.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def passed(name,**extra):report['cases'].append({'name':name,'status':'passed',**extra});save();print('PASS:',name,flush=True)
def run(cmd,**kw):return subprocess.run(list(map(str,cmd)),capture_output=True,text=True,**kw)
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def private(path,data):path.write_text(data);path.chmod(0o600);return path
@contextlib.contextmanager
def process(command,log,env=None):
 with open(log,'w') as stream:
  proc=subprocess.Popen(list(map(str,command)),stdout=stream,stderr=stream,env=env)
  try:yield proc
  finally:
   proc.terminate()
   try:proc.wait(timeout=35)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
def wait(proc,check):
 for _ in range(400):
  if proc.poll() is not None:raise RuntimeError('owned service stopped; inspect redacted local log')
  try:
   if check():return
  except (OSError,ValueError,urllib.error.URLError):pass
  time.sleep(.1)
 raise RuntimeError('owned service readiness timeout')
@contextlib.contextmanager
def mysql(temp,name):
 data=temp/name;data.mkdir();sock=temp/(name+'.sock');tcp=port();cfg=private(temp/(name+'.cnf'),'[client]\nuser=root\nprotocol=SOCKET\nsocket='+str(sock)+'\n')
 assert run([a.mysql_bin/'mysqld','--no-defaults','--initialize-insecure','--datadir='+str(data)],timeout=120).returncode==0
 client=[a.mysql_bin/'mysql','--defaults-file='+str(cfg),'--batch','--skip-column-names','--default-character-set=utf8mb4']
 def sql(statement,fail=False):
  r=run(client,input=statement,timeout=120)
  if fail:assert r.returncode!=0;return r.stderr
  if r.returncode:raise RuntimeError('owned SQL failed; statement and credentials withheld')
  return r.stdout.strip()
 command=[a.mysql_bin/'mysqld','--no-defaults','--datadir='+str(data),'--socket='+str(sock),'--port='+str(tcp),'--bind-address=127.0.0.1','--mysqlx=OFF','--innodb-buffer-pool-size=64M','--character-set-server=utf8mb4','--collation-server=utf8mb4_general_ci','--default-time-zone=+08:00']
 with process(command,temp/(name+'.log')) as proc:
  wait(proc,lambda:run(client,input='SELECT 1').returncode==0)
  assert pathlib.Path(sql('SELECT @@datadir')).resolve()==data.resolve()
  root=secrets.token_hex(32);sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+root+"'");private(cfg,cfg.read_text()+'password='+root+'\n')
  yield {'sql':sql,'port':tcp,'uuid':sql('SELECT @@server_uuid'),'password':root,'cfg':cfg,'proc':proc}
try:
 with tempfile.TemporaryDirectory(prefix='pilates-delivery-') as directory:
  temp=pathlib.Path(directory);temp.chmod(0o700)
  with contextlib.ExitStack() as stack:
   source=stack.enter_context(mysql(temp,'source'));restore=stack.enter_context(mysql(temp,'restore'));sql=source['sql'];db='pilates_delivery_'+secrets.token_hex(4)
   report['database']=dict(zip(['version','comment','charset','collation','sqlMode','isolation','timezone'],sql('SELECT VERSION(),@@version_comment,@@character_set_server,@@collation_server,@@sql_mode,@@transaction_isolation,@@session.time_zone').split('\t')));assert report['database']['version']=='8.0.46'
   sql('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci');dbsql=lambda s:sql('USE `'+db+'`;'+s)
   runtime=secrets.token_hex(32);migrator=secrets.token_hex(32);password=secrets.token_hex(32)
   config=private(temp/'database.properties',f"url=jdbc:mysql://127.0.0.1:{source['port']}/{db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\ndatabase={db}\nuser=root\npassword={source['password']}\nbootstrap.username=delivery_owner\nbootstrap.name=StagingOwner\nruntime.username=delivery_app\nruntime.password={runtime}\nruntime.host=localhost\nmigrator.username=delivery_migrator\nmigrator.password={migrator}\nmigrator.host=localhost\n")
   tool=['java','-Duser.timezone=Asia/Shanghai','-Dloader.main=com.yoyi.pilates.infra.tools.DatabaseTool','-cp',a.jar,'org.springframework.boot.loader.launch.PropertiesLauncher','--config',config,'--expect-server-uuid',source['uuid'],'--directory',ROOT/'database/production']
   result=run(tool+['--mode','fresh','--fail-before-step','2']);assert result.returncode!=0
   assert dbsql("SELECT COUNT(*) FROM schema_history WHERE state='DIRTY'")=='1'
   assert run(tool+['--mode','fresh']).returncode!=0
   assert run(tool+['--mode','resume-verified']).returncode==0
   assert run(tool+['--mode','fresh']).returncode==0
   assert int(dbsql("SELECT COUNT(*) FROM schema_history WHERE state='DONE'"))==len(list((ROOT/'database/production').glob('V*.sql')))
   for table in ['sys_user','user','member','course','course_schedule','coach','booking']:assert dbsql('SELECT COUNT(*) FROM `'+table+'`')=='0'
   passed('clean versioned migration, injected interruption, explicit recovery, repeat skip; no demo accounts/business rows')
   assert dbsql("SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code='COACH' AND p.code IN ('coach:course:view','coach:course:apply','coach:course:proposal:create','coach:course:proposal:update','coach:course:proposal:submit','coach:course:update')")=='6'
   assert dbsql("SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code IN ('COACH','STAFF') AND (p.code LIKE 'coach:home:%' OR p.code IN ('system:manage','finance:view','finance:export','member:update'))")=='0'
   passed('clean COACH course/proposal permissions retained; no global operations, finance, account or member mutation grants to COACH/STAFF')
   recovery=db+'_seed_recovery';sql('CREATE DATABASE `'+recovery+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci');recovery_sql=lambda statement:sql('USE `'+recovery+'`;'+statement)
   recovery_cfg=private(temp/'seed-recovery.properties',config.read_text().replace('/'+db+'?', '/'+recovery+'?').replace('database='+db+'\n','database='+recovery+'\n'))
   recovery_tool=[str(recovery_cfg) if str(x)==str(config) else x for x in tool]
   assert run(recovery_tool+['--mode','fresh','--fail-version','V002__base.sql','--fail-before-step','10']).returncode!=0
   assert recovery_sql('SELECT COUNT(*) FROM sys_role')=='0'
   assert run(recovery_tool+['--mode','fresh']).returncode!=0
   assert run(recovery_tool+['--mode','resume-verified']).returncode==0
   assert int(recovery_sql("SELECT id FROM sys_role WHERE code='ADMIN'"))>1
   assert recovery_sql("SELECT COUNT(*) FROM sys_role_permission rp LEFT JOIN sys_role r ON r.id=rp.role_id LEFT JOIN sys_permission p ON p.id=rp.permission_id WHERE r.id IS NULL OR p.id IS NULL")=='0'
   assert int(recovery_sql("SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id WHERE r.code='ADMIN'"))==int(recovery_sql('SELECT COUNT(*) FROM sys_permission'))
   assert recovery_sql("SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code='COACH' AND p.code LIKE 'coach:course:%'")=='6'
   assert run(recovery_tool+['--mode','bootstrap'],input=password+'\n').returncode==0
   assert run(recovery_tool+['--mode','fresh']).returncode==0
   passed('V002 mid-transaction failure rolls back all seeds; resume handles consumed auto-increment IDs with correct role-code grants and bootstrap')
   legacy=db+'_legacy';sql('CREATE DATABASE `'+legacy+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci');legacysql=lambda statement:sql('USE `'+legacy+'`;'+statement)
   legacysql((ROOT/'database/production/V001__structure.sql').read_text());legacysql((ROOT/'database/production/V002__base.sql').read_text())
   legacycfg=private(temp/'legacy.properties',config.read_text().replace('/'+db+'?', '/'+legacy+'?').replace('database='+db+'\n','database='+legacy+'\n'))
   legacytool=[str(legacycfg) if str(x)==str(config) else x for x in tool]
   before=legacysql('SELECT role_id,permission_id FROM sys_role_permission ORDER BY role_id,permission_id')
   assert run(legacytool+['--mode','fresh']).returncode!=0
   assert run(legacytool+['--mode','adopt-round3']).returncode==0
   assert legacysql("SELECT COUNT(*) FROM schema_history WHERE state='BASELINED'")=='2'
   assert before==legacysql('SELECT role_id,permission_id FROM sys_role_permission ORDER BY role_id,permission_id')
   assert run(legacytool+['--mode','fresh']).returncode==0
   passed('unversioned Round3 adoption rejects automatic fresh init, checks full table/column vocabulary, preserves grants, applies only delivery extension')

   assert run(tool+['--mode','bootstrap'],input=password+'\n').returncode==0
   assert run(tool+['--mode','bootstrap'],input=password+'\n').returncode!=0
   assert run(tool+['--mode','provision']).returncode==0
   appcfg=private(temp/'runtime.cnf',f"[client]\nuser=delivery_app\npassword={runtime}\nprotocol=SOCKET\nsocket={temp/'source.sock'}\ndatabase={db}\n")
   runtimecmd=[a.mysql_bin/'mysql','--defaults-file='+str(appcfg),'--batch','--skip-column-names']
   assert run(runtimecmd,input='SELECT COUNT(*) FROM sys_user').returncode==0
   assert run(runtimecmd,input='CREATE TABLE forbidden(id INT)').returncode!=0
   assert run(runtimecmd,input="UPDATE schema_history SET state='DONE'").returncode!=0
   passed('one-time administrator and scoped runtime account; DDL/schema-history writes rejected')
   redis_port=port();redis_password=secrets.token_hex(32);redcfg=private(temp/'redis.conf',f'bind 127.0.0.1\nport {redis_port}\nrequirepass {redis_password}\nsave ""\nappendonly yes\ndir {temp}\n')
   red=stack.enter_context(process(['redis-server',redcfg],temp/'redis.log'))
   renv={**os.environ,'REDISCLI_AUTH':redis_password};redcli=['redis-cli','-p',str(redis_port)];wait(red,lambda:run(redcli+['PING'],env=renv).stdout.strip()=='PONG')
   api=port();management=port();jwt=secrets.token_hex(40);files=temp/'files'
   properties=private(temp/'application.properties',f"server.address=127.0.0.1\nserver.port={api}\nmanagement.server.address=127.0.0.1\nmanagement.server.port={management}\nspring.datasource.url=jdbc:mysql://127.0.0.1:{source['port']}/{db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\nspring.datasource.username=delivery_app\nspring.datasource.password={runtime}\nspring.data.redis.host=127.0.0.1\nspring.data.redis.port={redis_port}\nspring.data.redis.password={redis_password}\nyoyi.jwt.secret={jwt}\nyoyi.public-base-url=https://localhost.invalid\nyoyi.wx.login-enabled=false\nyoyi.storage.type=local\nyoyi.storage.local-for-isolated-test=true\nyoyi.upload.dir={files}\nyoyi.scheduling.enabled=false\nyoyi.delivery.workers-enabled=false\n")
   command=['java','-Duser.timezone=Asia/Shanghai','-jar',a.jar,'--spring.profiles.active=staging','--spring.config.additional-location='+str(properties)]
   def http(path,body=None,token=None,raw=False,method=None):
    data=None if body is None else json.dumps(body).encode();headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(f'http://127.0.0.1:{api}'+path,data=data,headers=headers,method=method)
    with urllib.request.urlopen(request,timeout=40) as response:
     content=response.read();return content if raw else json.loads(content)
   def ready():return http('/health')['code']==0
   def check_booking_flow():
    token=http('/api/auth/admin-login',{'username':'delivery_owner','password':password})['data']['token']
    assert http('/api/capabilities')['data']['packageSales'] is False
    assert http('/api/auth/wx-login',{'code':'not-a-real-WeChat-code'})['code']!=0
    # All synthetic entities are isolated and deliberately minimal.
    dbsql("INSERT INTO course_category(code,name,status) VALUES('DELIVERY','合成分类','ACTIVE'); INSERT INTO coach(name,years,status) VALUES('合成教练',1,'ACTIVE'); INSERT INTO classroom(name,type,capacity,status) VALUES('合成教室','GROUP',20,'ACTIVE'); INSERT INTO course(category_id,name,type,duration_minutes,difficulty,credits_cost,status) VALUES(1,'合成免费课','GROUP',60,'BEGINNER',1,'ON_SHELF');INSERT INTO course_coach(course_id,coach_id,status) VALUES(1,1,'APPROVED');INSERT INTO course_schedule(course_id,coach_id,classroom_id,course_date,start_time,end_time,max_capacity,booked_count,status) VALUES(1,1,1,DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY),'12:00','13:00',8,0,'SCHEDULED');INSERT INTO `user`(openid,nickname,status) VALUES('isolated_staging_member','合成会员','ACTIVE');INSERT INTO member(user_id,member_no,level_id,total_sessions,status) VALUES(1,'DELIVERY-0001',1,0,'ACTIVE');")
    for n in range(3):
     result=http('/api/admin/bookings',{'userId':1,'scheduleId':1,'reason':'隔离验收'},token);assert result['code']==0;booking=result['data']
     if n<2:assert http(f'/api/admin/bookings/{booking}/cancel',{'reason':'隔离重约'},token)['code']==0
    assert dbsql("SELECT COUNT(*),SUM(active_user_id IS NOT NULL) FROM booking")=='3\t1'
    assert dbsql("SELECT COUNT(*) FROM event_outbox WHERE state='PENDING'")=='5'
    return token
   with process(command,temp/'backend-first.log') as app:
    wait(app,ready);token=check_booking_flow();passed('real staging HTTP on least-privilege account: FREE, three bookings, old history and five durable pending events')
   private(properties,properties.read_text().replace('yoyi.delivery.workers-enabled=false','yoyi.delivery.workers-enabled=true'))
   with process(command,temp/'backend-restarted.log') as app:
    wait(app,ready)
    for _ in range(150):
     if dbsql("SELECT COUNT(*) FROM event_outbox WHERE state='PENDING'")=='0':break
     time.sleep(.1)
    assert dbsql("SELECT COUNT(*) FROM event_outbox WHERE state='DONE'")=='5'
    assert dbsql("SELECT COUNT(*) FROM notification")=='5'
    assert http('/api/auth/me',token=token)['code']==0
    passed('backend restart drains all persisted events exactly once and preserves existing sessions')
    from zoneinfo import ZoneInfo
    start_at=datetime.datetime.now(ZoneInfo('Asia/Shanghai')).replace(microsecond=0)+datetime.timedelta(minutes=2);end_at=start_at+datetime.timedelta(minutes=10)
    assert start_at.date()==end_at.date(),'run the check-in rehearsal away from midnight'
    dbsql(f"UPDATE course_schedule SET course_date='{start_at.date()}',start_time='{start_at.time()}',end_time='{end_at.time()}' WHERE id=1")
    last=int(dbsql("SELECT id FROM booking WHERE status='BOOKED'"))
    assert http(f'/api/admin/bookings/{last}/check-in',{'reason':'隔离签到'},token)['code']==0
    assert dbsql("SELECT COUNT(*) FROM attendance")=='1'
    assert http('/api/admin/schedules/1/cancel?reason=isolated',{},token)['code']!=0
    assert dbsql("SELECT status FROM booking WHERE id="+str(last))=='SIGNED_IN'
    passed('real staging check-in persists one attendance; cancelling a signed-in class is rejected')

    # Data and file reference for a full restore comparison (no private files are exposed).
    files.mkdir(exist_ok=True);sample=files/'restore-fixture.txt';sample.write_text('isolated restore bytes\n');checksum=hashlib.sha256(sample.read_bytes()).hexdigest()
    dbsql("INSERT INTO file_object(public_id,object_key,owner_type,owner_id,uploader_type,uploader_id,purpose,visibility,content_type,byte_size,sha256) VALUES('00000000-0000-4000-a000-000000000001','restore-fixture.txt','MP_USER',1,'MP_USER',1,'BODY_RECORD','PRIVATE','text/plain',23,'"+checksum+"')")
    # Stop application writes before the database/file snapshot; scheduled workers also stop on process exit.
   start=time.monotonic();checkpoint=datetime.datetime.now(datetime.timezone.utc).isoformat()
   dump=run([a.mysql_bin/'mysqldump','--defaults-file='+str(source['cfg']),'--single-transaction','--no-tablespaces','--set-gtid-purged=OFF',db],timeout=120);assert dump.returncode==0
   restore['sql']('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;USE `'+db+'`;'+dump.stdout)
   queries=["SELECT id,booking_no,user_id,schedule_id,status,active_user_id FROM booking ORDER BY id","SELECT role_id,permission_id,deleted FROM sys_role_permission ORDER BY role_id,permission_id","SELECT id,booked_count,max_capacity,status FROM course_schedule ORDER BY id","SELECT * FROM attendance ORDER BY id","SELECT public_id,object_key,sha256 FROM file_object ORDER BY id","SELECT event_key,state,attempts FROM event_outbox ORDER BY id"]
   for query in queries:assert dbsql(query)==restore['sql']('USE `'+db+'`;'+query)
   import shutil;shutil.copytree(files,temp/'restored-files');assert hashlib.sha256((temp/'restored-files/restore-fixture.txt').read_bytes()).hexdigest()==checksum
   assert source['uuid']!=restore['uuid']
   report['restore']={'status':'passed','separateInstance':True,'seconds':round(time.monotonic()-start,3),'recoverableCheckpointUtc':checkpoint,'rpoTarget':'operator confirmation required','rtoTarget':'operator confirmation required'}
   passed('backup restored into a separate owned MySQL instance; booking/attendance/capacity/permissions/outbox/file references and file checksums match')
   if __import__('shutil').which('age') and __import__('shutil').which('age-keygen'):
    identity=temp/'restore-identity';keygen=run(['age-keygen','-o',identity]);assert keygen.returncode==0
    import re
    recipient=re.search(r'Public key: (age1[0-9a-z]+)',keygen.stderr).group(1)
    backup=temp/'encrypted-backup'
    result=run(['python3',ROOT/'scripts/production/backup.py','--mysql-bin',a.mysql_bin,'--client-config',source['cfg'],'--expect-server-uuid',source['uuid'],'--database',db,'--files',files,'--recipient',recipient,'--output',backup,'--writes-paused']);assert result.returncode==0,result.stderr[-500:]
    result=run(['python3',ROOT/'scripts/production/restore-isolated.py','--mysql-bin',a.mysql_bin,'--client-config',restore['cfg'],'--expect-server-uuid',restore['uuid'],'--backup',backup,'--identity-file',identity,'--new-database',db+'_encrypted','--new-files',temp/'encrypted-restored-files','--confirm-isolated-target','--report',a.output/'encrypted-restore.json']);assert result.returncode==0,result.stderr[-500:]
    passed('encrypted backup and isolated restore operator scripts execute; all restored file references verified')

   # Sanitized diagnostics only. Never retain the dump, client config, token, or unredacted backend logs.
   for name in ['backend-first.log','backend-restarted.log']:
    text=(temp/name).read_text(errors='replace')
    for secret in [runtime,migrator,password,source['password'],restore['password'],jwt,redis_password]:text=text.replace(secret,'[REDACTED]')
    import re;text=re.sub(r'eyJ[\w-]+\.[\w-]+\.[\w-]+','[REDACTED_JWT]',text)
    (a.output/name).write_text(text)
 report['status']='passed';save()
except Exception as error:
 report['status']='failed';report['error']=type(error).__name__+': '+str(error);save();print('FAIL:',report['error'],flush=True);raise
