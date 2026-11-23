#!/usr/bin/env python3
"""Persistent, loopback-only development stack. Never connects to existing DB/Redis."""
import fcntl, hashlib, json, os, pathlib, re, secrets, signal, socket, subprocess, sys, tempfile, time, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = pathlib.Path.home()/'Library/Application Support/yoyi-pilates-local'/hashlib.sha256(str(ROOT).encode()).hexdigest()[:10]
TOOLS = pathlib.Path(os.environ.get('PILATES_LOCAL_TOOLS', '/tmp/pilates-round3-tools'))
JAVA = pathlib.Path(os.environ.get('PILATES_LOCAL_JAVA_HOME', '/opt/homebrew/opt/openjdk@21'))
MYSQL = TOOLS/'mysql-8.0.46-macos15-arm64/bin'
REDIS = TOOLS/'redis-7.4.10/src'
os.umask(0o077)
STATE.mkdir(parents=True, exist_ok=True)
def save(name, value):
    p = STATE/name
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n'); p.chmod(0o600)
def read(name):
    p = STATE/name
    return json.loads(p.read_text()) if p.exists() else {}
def live(pid):
    if not pid: return False
    r = subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
    return r.returncode == 0 and str(pathlib.Path(__file__).resolve())+' serve' in r.stdout

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0)); return s.getsockname()[1]
def run(args, **kw):
    return subprocess.run([str(a) for a in args],capture_output=True,text=True,**kw)
def http(path, body=None):
    req = urllib.request.Request('http://127.0.0.1:8090'+path, data=json.dumps(body).encode() if body is not None else None, headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=3) as response: return json.load(response)
def serve():
    lock=(STATE/'supervisor.lock').open('a')
    try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError: raise SystemExit('Another local supervisor already owns this workspace')
    children=[]; streams=[]; temp=None
    def shutdown(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, shutdown); signal.signal(signal.SIGINT, shutdown)
    def spawn(args, log, env=None):
        stream=(STATE/log).open('a'); streams.append(stream)
        proc=subprocess.Popen([str(a) for a in args],stdin=subprocess.DEVNULL,stdout=stream,stderr=stream,env=env)
        children.append(proc); return proc
    def wait(proc, check, seconds=90):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if proc.poll() is not None: raise RuntimeError('Owned service exited; inspect private logs: '+str(STATE))
            try:
                if check(): return
            except (OSError, ValueError): pass
            time.sleep(.4)
        raise RuntimeError('Service readiness timeout; private logs: '+str(STATE))
    try:
        with socket.socket() as probe: probe.bind(('127.0.0.1',8090))
        for p in [MYSQL/'mysqld', MYSQL/'mysql', REDIS/'redis-server', JAVA/'bin/java']:
            if not p.is_file(): raise RuntimeError('Missing dependency: '+str(p)+'; see docs/LOCAL-DEVELOPMENT.md')
        save('status.json', {'status':'starting','pid':os.getpid(),'url':'http://127.0.0.1:8090'})
        credentials=read('credentials.json')
        if not credentials:
            credentials={k:secrets.token_hex(32) for k in ('root','app','redis','jwt')}; save('credentials.json',credentials)
        data=STATE/'mysql-data'; fresh=not data.exists()
        if fresh:
            result=run([MYSQL/'mysqld','--no-defaults','--initialize-insecure','--datadir='+str(data)],timeout=120)
            if result.returncode: raise RuntimeError('Owned MySQL initialization failed')
        temp=tempfile.TemporaryDirectory(prefix='yoyi-dev-'); sock=pathlib.Path(temp.name)/'mysql.sock'
        config=STATE/'client.cnf'
        def client_config(password):
            config.write_text('[client]\nuser=root\nprotocol=SOCKET\nsocket='+str(sock)+'\n'+('password='+password+'\n' if password else '')); config.chmod(0o600)
        client_config('' if fresh else credentials['root'])
        command=[MYSQL/'mysql','--defaults-file='+str(config),'--protocol=SOCKET','--socket='+str(sock),'--user=root','--batch','--skip-column-names','--default-character-set=utf8mb4']
        def sql(statement):
            result=run(command,input=statement,timeout=90)
            if result.returncode: raise RuntimeError('Isolated SQL operation failed; no existing database was used')
            return result.stdout.strip()
        baseline=json.loads((ROOT/'scripts/round3/mysql-baseline.json').read_text())
        mysql_port=port()
        mysql=spawn([MYSQL/'mysqld','--no-defaults','--datadir='+str(data),'--socket='+str(sock),'--bind-address=127.0.0.1','--port='+str(mysql_port),'--mysqlx=OFF','--innodb-buffer-pool-size=64M','--character-set-server='+baseline['charset'],'--collation-server='+baseline['collation'],'--sql-mode='+baseline['sqlMode'],'--transaction-isolation='+baseline['isolation'],'--default-time-zone=+08:00','--log-error='+str(STATE/'mysql-error.log')], 'mysql.log')
        wait(mysql, lambda: run(command+['-e','SELECT 1']).returncode == 0)
        if pathlib.Path(sql('SELECT @@datadir')).resolve()!=data.resolve(): raise RuntimeError('Datadir ownership mismatch')
        if sql('SELECT VERSION()') != baseline['version']: raise RuntimeError('MySQL version mismatch')
        if fresh:
            sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+credentials['root']+"';"); client_config(credentials['root'])
        db='yoyi_pilates_local_'+STATE.name
        # Existing unmarked databases require manual inspection; never replay initialization over them.
        if not (STATE/'initialized.json').exists():
            if sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+db+"'") != '0':
                raise RuntimeError('Incomplete earlier initialization: inspect private database; no seed replayed')
            sql((ROOT/'database/schema.sql').read_text().replace('yoyi_pilates',db))
            sql((ROOT/'database/init_data.sql').read_text().replace('yoyi_pilates',db))
            sql('USE `'+db+"`; INSERT INTO course_coach(course_id,coach_id,status,qualification_source,approved_by,approved_at,remark) VALUES(1,1,'APPROVED','ADMIN',1,NOW(),'本地隔离联调'),(7,1,'APPROVED','ADMIN',1,NOW(),'本地隔离联调');")
            sql("CREATE USER 'local_app'@'localhost' IDENTIFIED BY '"+credentials['app']+"'; GRANT ALL ON `"+db+"`.* TO 'local_app'@'localhost';")
            save('initialized.json',{'database':db,'mysqlVersion':baseline['version']})
        # Only our marked, datadir-verified local fixture may receive this additive schema extension.
        if sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"+db+"' AND table_name='sys_user' AND column_name='auth_version'") == '0':
            if sql('USE `'+db+"`; SELECT COUNT(*) FROM (SELECT user_id,biz_type,biz_id,deleted FROM notification WHERE biz_type IS NOT NULL AND biz_id IS NOT NULL GROUP BY user_id,biz_type,biz_id,deleted HAVING COUNT(*)>1) x") != '0':
                raise RuntimeError('Local notifications need review before delivery migration; no history changed')
            sql('USE `'+db+'`;'+(ROOT/'database/production/V003__delivery.sql').read_text())
        # A partially applied extension always stops; never silently starts a mismatched app.
        if sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+db+"' AND table_name IN ('file_object','event_outbox','notification_delivery')") != '3':
            raise RuntimeError('Incomplete local delivery extension; inspect schema before starting')
        ledger_tables=re.findall(r"(?m)^CREATE TABLE (studio_\w+)",(ROOT/'database/production/V004__studio_ledger.sql').read_text())
        table_list=','.join("'"+name+"'" for name in ledger_tables)
        count=int(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+db+"' AND table_name IN ("+table_list+")"))
        nullable=sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema='"+db+"' AND table_name='user' AND column_name='openid'")
        if count == 0 and nullable == 'NO':
            sql('USE `'+db+'`;'+(ROOT/'database/production/V004__studio_ledger.sql').read_text())
        elif count != len(ledger_tables) or nullable != 'YES':
            raise RuntimeError('Incomplete local studio ledger extension; inspect schema before starting')
        redis_port=port(); redis_config=STATE/'redis.conf'
        redis_config.write_text('bind 127.0.0.1\nport '+str(redis_port)+'\nrequirepass '+credentials['redis']+'\nsave ""\nappendonly no\ndir "'+str(STATE)+'"\n'); redis_config.chmod(0o600)
        redis=spawn([REDIS/'redis-server',redis_config], 'redis.log')
        wait(redis, lambda:run([REDIS/'redis-cli','-h','127.0.0.1','-p',redis_port,'PING'],env={**os.environ,'REDISCLI_AUTH':credentials['redis']}).stdout.strip()=='PONG')
        env={**os.environ,'JAVA_HOME':str(JAVA),'SPRING_PROFILES_ACTIVE':'dev','SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'8090','SPRING_DATASOURCE_URL':f'jdbc:mysql://127.0.0.1:{mysql_port}/{db}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','SPRING_DATASOURCE_DRIVER_CLASS_NAME':'com.mysql.cj.jdbc.Driver','SPRING_DATASOURCE_USERNAME':'local_app','SPRING_DATASOURCE_PASSWORD':credentials['app'],'SPRING_SQL_INIT_MODE':'never','STUDIO_LEDGER_ENABLED':'true','SPRING_DATA_REDIS_HOST':'127.0.0.1','SPRING_DATA_REDIS_PORT':str(redis_port),'SPRING_DATA_REDIS_PASSWORD':credentials['redis'],'SPRING_DATA_REDIS_DATABASE':'0','YOYI_MQ_TYPE':'memory','YOYI_SCHEDULING_ENABLED':'false','YOYI_WX_MOCK_LOGIN':'true','YOYI_PAY_MOCK_ENABLED':'false','YOYI_SMS_MOCK_ENABLED':'false','YOYI_SALES_PACKAGE_ENABLED':'false','YOYI_JWT_SECRET':credentials['jwt'],'JWT_SECRET':credentials['jwt'],'YOYI_UPLOAD_DIR':str(STATE/'uploads'),'LOGGING_LEVEL_COM_YOYI_PILATES':'warn'}
        build=STATE/'build'
        with (STATE/'build.log').open('w') as output:
            result=subprocess.run(['mvn','-o','-q','-f',str(ROOT/'backend/pom.xml'),'-Dpilates.build.directory='+str(build),'-Dmaven.test.skip=true','package'],env=env,stdout=output,stderr=output)
        if result.returncode: raise RuntimeError('Build failed; inspect private build.log')
        backend=spawn([JAVA/'bin/java','-Xmx512m','-jar',build/'pilates-server.jar'], 'backend.log',env)
        wait(backend, lambda:http('/api/booking-rules').get('code')==0,120)
        login=http('/api/auth/wx-login',{'code':'round3_local_member'})
        if login.get('code')!=0: raise RuntimeError('Local mock login check failed')
        save('status.json',{'status':'running','pid':os.getpid(),'url':'http://127.0.0.1:8090','mysqlVersion':baseline['version'],'mysqlPort':mysql_port,'redisPort':redis_port,'database':db,'isolated':True,'mockLoginVerified':True,'children':[p.pid for p in children]})
        while True:
            if any(p.poll() is not None for p in children): raise RuntimeError('A development service stopped')
            time.sleep(2)
    except KeyboardInterrupt: save('status.json',{'status':'stopped'})
    except Exception as exc:
        save('status.json',{'status':'failed','reason':str(exc)}); print(str(exc),flush=True)
    finally:
        for proc in reversed(children):
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=25)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        for stream in streams: stream.close()
        if temp: temp.cleanup()

def main():
    action=sys.argv[1] if len(sys.argv)>1 else 'start'
    current=read('status.json')
    if action=='serve': serve(); return
    if action=='status':
        current['supervisorAlive']=live(current.get('pid')); print(json.dumps(current,ensure_ascii=False,indent=2)); return
    if action=='stop':
        if live(current.get('pid')):
            os.kill(current['pid'],signal.SIGTERM)
            for _ in range(150):
                if not live(current['pid']): break
                time.sleep(.5)
        print('本地隔离服务已停止；测试数据库保留。'); return
    if action!='start': raise SystemExit('Usage: python3 scripts/dev-local.py [start|status|stop]')
    if live(current.get('pid')): print('已有本地服务：'+current.get('url','启动中')); return
    with (STATE/'supervisor.log').open('a') as output:
        proc=subprocess.Popen([sys.executable,str(pathlib.Path(__file__).resolve()),'serve'],stdin=subprocess.DEVNULL,stdout=output,stderr=output,start_new_session=True)
    for _ in range(360):
        current=read('status.json')
        if current.get('pid')==proc.pid and current.get('status')=='running':
            print('已启动 http://127.0.0.1:8090；隔离 MySQL 8.0.46 / Redis；本地模拟登录验证通过。'); return
        if proc.poll() is not None: raise SystemExit('启动失败：'+str(read('status.json')))
        time.sleep(.5)
    raise SystemExit('仍在启动，请执行 status 并查看 '+str(STATE))
if __name__=='__main__': main()
