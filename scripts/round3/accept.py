#!/usr/bin/env python3
"""Own every writable endpoint: native mysqld datadir, loopback port, Redis and JUnit HTTP.
Never accepts a database URL or existing service; no Docker/production compose invocation.
"""
import argparse, contextlib, json, os, pathlib, re, secrets, shutil, socket, subprocess, sys, tempfile, time
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = json.loads((ROOT/'scripts/round3/mysql-baseline.json').read_text())
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--mysql-bin', required=True, type=pathlib.Path, help='Directory containing owned MySQL binaries (not a server address)')
p.add_argument('--output-directory', type=pathlib.Path, help='Separate evidence directory; default retains Round3 location')
p.add_argument('--probe-only', action='store_true', help='Record SQL version of another locally owned disposable engine, no compatibility assertion')
p.add_argument('--sql-only', action='store_true')
p.add_argument('--online-maven', action='store_true', help='Allow resolving new pinned dependency/plugin versions from Maven Central')
p.add_argument('--report-file', default='mysql8-acceptance.json', help='Report filename under server/target-round3')
p.add_argument('--skip-processes', action='store_true', help='Skip the additional two-OS-process booking/restart check; report scope explicitly')
p.add_argument('--tests', default='RoundOneConsistencyTest,SchedulingAndFreeFlowTest,MemberHttpBoundaryTest,MemberAndTrainingBoundaryTest,Round3AcceptanceTest,Round3HttpTest,Round3ConfigurationTest,Round3FreshDatabaseTest')
a = p.parse_args()
if pathlib.Path(a.report_file).name != a.report_file:p.error('--report-file must be a filename')
OUT = a.output_directory.resolve() if a.output_directory else ROOT/'server/target-round3'; OUT.mkdir(parents=True,exist_ok=True)
report = {'testSelection':a.tests,'status':'running','productionDatabaseTouched':False,'isolated':True,'baseline':BASE,'cases':[]}
def redact_outputs():
    for pattern in ('*.log','surefire-reports/*.xml','surefire-reports/*.txt'):
        for path in OUT.glob(pattern):
            value=path.read_text(errors='replace')
            value=re.sub(r'(Using generated security password:) [^\s<]+',r'\1 [REDACTED]',value)
            value=re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+','[REDACTED_JWT]',value)
            for key in ('root_password','redis_password','password'):
                secret=globals().get(key)
                if secret:value=value.replace(secret,'[REDACTED]')
            path.write_text(value)
def save(): (OUT/('local-engine-probe.json' if a.probe_only else a.report_file)).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def case(name, **extra): report['cases'].append({'name':name,'status':'passed',**extra}); save(); print('PASS:',name,flush=True)
def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0)); return s.getsockname()[1]
def run(cmd, **kw): return subprocess.run([str(v) for v in cmd],capture_output=True,text=True,**kw)
def private_file(path, value): path.write_text(value); path.chmod(0o600)
@contextlib.contextmanager
def owned_process(command, log):
    with log.open('w') as stream:
        proc=subprocess.Popen([str(v) for v in command],stdout=stream,stderr=stream)
        try: yield proc
        finally:
            proc.terminate()
            try: proc.wait(timeout=20)
            except subprocess.TimeoutExpired: proc.kill();proc.wait()
def wait_for(proc, ping):
    for _ in range(300):
        if proc.poll() is not None: raise RuntimeError('Owned service exited; inspect temporary service log while debugging')
        if ping(): return
        time.sleep(.1)
    raise RuntimeError('Owned service startup timeout')
try:
 with tempfile.TemporaryDirectory(prefix='p3-') as directory:
    temp=pathlib.Path(directory); build=temp/'build'; data=temp/'data'; sock=temp/'mysql.sock'; mysql_port=port()
    server=a.mysql_bin/'mysqld'; client=a.mysql_bin/'mysql'
    if not server.is_file() or not client.is_file(): raise RuntimeError('mysqld/mysql missing in explicit binary directory')
    cli=run([server,'--no-defaults','--version']); report['binaryVersion']=cli.stdout.strip()
    if not a.probe_only and not re.search(r'Ver '+re.escape(BASE['version'])+r'\b',cli.stdout): raise RuntimeError('Expected exact baseline '+BASE['version']+'; no fallback to another version')
    init=run([server,'--no-defaults','--initialize-insecure','--datadir='+str(data)],timeout=120)
    if init.returncode: raise RuntimeError('MySQL initialize failed: '+init.stderr[-2000:])
    config=temp/'client.cnf'; private_file(config,'[client]\nuser=root\nprotocol=SOCKET\nsocket='+str(sock)+'\n')
    command=[client,'--defaults-file='+str(config),'--protocol=SOCKET','--socket='+str(sock),'--user=root','--batch','--skip-column-names','--default-character-set=utf8mb4']
    def sql(statement, error=None):
        r=run(command,input=statement,timeout=90)
        if error:
            if not r.returncode or ('ERROR '+str(error)+' ') not in r.stderr: raise AssertionError('Expected SQL error '+str(error)+': '+r.stderr[:400])
        elif r.returncode: raise AssertionError(r.stderr[:1200])
        return r.stdout.strip()
    options=[server,'--no-defaults','--datadir='+str(data),'--socket='+str(sock),'--bind-address=127.0.0.1','--port='+str(mysql_port),'--mysqlx=OFF','--innodb-buffer-pool-size=64M','--character-set-server='+BASE['charset'],'--collation-server='+BASE['collation'],'--sql-mode='+BASE['sqlMode'],'--transaction-isolation='+BASE['isolation'],'--default-time-zone='+BASE['timeZone'],'--log-error='+str(temp/'mysql-error.log')]
    if a.probe_only: options += ['--skip-networking']
    with owned_process(options,temp/'mysql-process.log') as proc:
        wait_for(proc,lambda:run(command+['-e','SELECT 1']).returncode==0)
        observed_data=pathlib.Path(sql('SELECT @@datadir;')).resolve()
        assert observed_data==data.resolve(), 'Refusing to mutate a MySQL datadir not created by this run'
        report['ownedTargetConfirmedBeforeMutation']=True
        values=sql('SELECT VERSION(),@@version_comment,@@character_set_server,@@collation_server,@@sql_mode,@@transaction_isolation,@@global.time_zone,@@session.time_zone,@@system_time_zone;').split('\t')
        report['sqlEnvironment']=dict(zip(['version','productComment','charset','collation','sqlMode','isolation','globalTimeZone','sessionTimeZone','systemTimeZone'],values))
        if a.probe_only:
            report['status']='observed_only'; save(); print(json.dumps(report['sqlEnvironment'],ensure_ascii=False)); sys.exit(0)
        assert values[0]==BASE['version'] and values[1]=='MySQL Community Server - GPL', values[:2]
        case('exact SQL product/version and baseline settings')
        root_password=secrets.token_hex(24)
        sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+root_password+"';")
        private_file(config,config.read_text()+'password='+root_password+'\n')
        db='yoyi_pilates_round3_'+secrets.token_hex(5)
        def dbsql(statement, **kw): return sql('USE `'+db+'`;\n'+statement, **kw)
        fresh_db=db+'_fresh'
        schema=(ROOT/'database/schema.sql').read_text().replace('yoyi_pilates',db)
        sql(schema);dbsql((ROOT/'database/init_data.sql').read_text().replace('yoyi_pilates',db))
        assert dbsql("SELECT config_value FROM system_config WHERE config_key='booking.paymentMode'")=='FREE'
        assert dbsql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='booking' AND index_name='uk_user_schedule'")=='0'
        case('fresh schema/init already contains new booking index; booking migration skipped')
        sql((ROOT/'database/schema.sql').read_text().replace('yoyi_pilates',fresh_db));sql('USE `'+fresh_db+'`;\n'+(ROOT/'database/init_data.sql').read_text().replace('yoyi_pilates',fresh_db))
        migration=(ROOT/'database/migration_round2_booking.sql').read_text()
        before=dbsql('SHOW CREATE TABLE booking;');dbsql(migration,error=1091); assert dbsql('SHOW CREATE TABLE booking;')==before
        case('fresh/repeated booking DDL safely fails atomically with 1091; structure unchanged')
        permission=(ROOT/'database/migration_round2_permissions.sql').read_text()
        def mappings(): return dbsql('SELECT role_id,permission_id,deleted FROM sys_role_permission ORDER BY role_id,permission_id,deleted')
        grants_before=mappings();dbsql(permission);dbsql(permission);assert mappings()==grants_before
        assert dbsql("SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id AND rp.deleted=0 JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code='STAFF' AND p.code IN ('schedule:create','schedule:update','schedule:cancel')")=='0'
        case('permission repeat preserves all existing grants and gives no STAFF writes')
        # A legacy structure, retaining existing role maps and legal historical relationships.
        dbsql('ALTER TABLE booking DROP INDEX uk_active_user_schedule, DROP COLUMN active_user_id, ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted);')
        dbsql("INSERT INTO `user`(openid,nickname) VALUES('round3_history_a','隔离历史会员A'),('round3_history_b','隔离历史会员B'); INSERT INTO course_coach(course_id,coach_id,status) SELECT DISTINCT course_id,coach_id,'APPROVED' FROM course_schedule;")
        dbsql("INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('history-cancelled',1,1,0,'CANCELLED'),('history-booked',2,1,0,'BOOKED'); UPDATE course_schedule SET booked_count=1 WHERE id=1;")
        dbsql("INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('legacy-rebook',1,1,0,'BOOKED');",error=1062)
        case('legal legacy cancelled/live history and old rebooking blocker reproduced')
        pre=dbsql((ROOT/'database/check_round3_readonly.sql').read_text())
        anomalies=[line for line in pre.splitlines() if re.match(r'^[a-z_]+\t[0-9]+$',line)]
        assert anomalies and all(line.endswith('\t0') for line in anomalies),anomalies
        case('legacy read-only prechecks have zero anomalies',rules=len(anomalies))
        dbsql(migration); assert mappings()==grants_before
        case('legacy booking migration preserves history and existing role mappings')
        for n in range(4):
            dbsql("INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('round3-rebook-"+str(n)+"',1,1,0,'BOOKED');")
            dbsql("INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('duplicate',1,1,0,'BOOKED');",error=1062)
            if n<3: dbsql("UPDATE booking SET status='CANCELLED' WHERE booking_no='round3-rebook-"+str(n)+"';")
        assert dbsql("SELECT COUNT(*),SUM(active_user_id IS NOT NULL) FROM booking WHERE user_id=1")=='5\t1'
        old= dbsql("SELECT id FROM booking WHERE booking_no='history-cancelled'")
        dbsql("UPDATE booking SET status='CANCELLED' WHERE id="+old+" AND status='BOOKED';")
        assert dbsql("SELECT status FROM booking WHERE booking_no='round3-rebook-3'")=='BOOKED'
        case('four rebookings preserve old IDs and exactly one occupied booking')
        # Every occupied status conflicts; cancelled/refunded/deleted rows have NULL generated key.
        for status in ['SIGNED_IN','COMPLETED','NO_SHOW']:
            dbsql("UPDATE booking SET status='"+status+"' WHERE booking_no='round3-rebook-3';")
            dbsql("INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('duplicate',1,1,0,'BOOKED');",error=1062)
        dbsql("UPDATE booking SET status='REFUNDED' WHERE booking_no='round3-rebook-3'; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status,deleted) VALUES('deleted',1,1,0,'BOOKED',1),('active-final',1,1,0,'BOOKED',0);")
        case('SIGNED_IN COMPLETED NO_SHOW occupy; CANCELLED REFUNDED logical deletion release unique key')
        before=dbsql('SHOW CREATE TABLE booking;');dbsql(migration,error=1091); assert before==dbsql('SHOW CREATE TABLE booking;')
        case('repeat upgrade fails atomically without partially changed structure')
        # Failed DDL with invalid duplicate history must not remove the old structure or edit rows.
        dbsql('CREATE TABLE booking_bad LIKE booking; ALTER TABLE booking_bad DROP INDEX uk_active_user_schedule, DROP COLUMN active_user_id; INSERT INTO booking_bad(booking_no,user_id,schedule_id,credits_used,status) VALUES (\'bad-a\',1,1,0,\'BOOKED\'),(\'bad-b\',1,1,0,\'SIGNED_IN\');')
        before_bad=dbsql('SHOW CREATE TABLE booking_bad;')
        dbsql(migration.replace('ALTER TABLE booking','ALTER TABLE booking_bad').replace('  DROP INDEX uk_user_schedule,',''),error=1062)
        assert before_bad==dbsql('SHOW CREATE TABLE booking_bad;') and dbsql('SELECT COUNT(*) FROM booking_bad')=='2'
        case('invalid duplicate history stops atomic DDL; no automatic historical repair')
        # Rollback restricted by multiple cancellation history; prove both allowed and forbidden DDL.
        assert dbsql('SELECT COUNT(*) FROM (SELECT user_id,schedule_id,deleted FROM booking GROUP BY user_id,schedule_id,deleted HAVING COUNT(*)>1) x')!='0'
        dbsql('ALTER TABLE booking ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted);',error=1062)
        dbsql("CREATE TABLE booking_rollback LIKE booking; INSERT INTO booking_rollback(booking_no,user_id,schedule_id,credits_used,status) VALUES('legal-cancelled',1,1,0,'CANCELLED'),('legal-booked',2,1,0,'BOOKED'); ALTER TABLE booking_rollback DROP INDEX uk_active_user_schedule, DROP COLUMN active_user_id, ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted);")
        assert dbsql('SELECT COUNT(*) FROM booking_rollback')=='2'
        case('old unique rollback rejected with multi-history; legal single-history structure rollback preserves both rows')
        # A full logical backup is restored only into another owned disposable database.
        dbsql("UPDATE course_schedule s SET booked_count=(SELECT COUNT(*) FROM booking b WHERE b.schedule_id=s.id AND b.deleted=0 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')) WHERE id=1;")
        dump=run([a.mysql_bin/'mysqldump','--defaults-file='+str(config),'--single-transaction','--routines','--events','--triggers','--no-tablespaces','--set-gtid-purged=OFF','--databases',db],timeout=90)
        if dump.returncode:raise RuntimeError('Owned logical backup failed')
        restored=db+'_restore';sql(dump.stdout.replace('`'+db+'`','`'+restored+'`'))
        fingerprints="SELECT COUNT(*),SUM(active_user_id IS NOT NULL),SUM(status='CANCELLED'),SUM(id) FROM booking;"
        assert sql('USE `'+restored+'`;'+fingerprints)==dbsql(fingerprints), 'backup booking aggregate mismatch'
        source_ddl=dbsql('SHOW CREATE TABLE booking;'); restored_ddl=sql('USE `'+restored+'`;SHOW CREATE TABLE booking;')
        columns="SELECT column_name,column_type,is_nullable,COALESCE(column_default,'<NULL>'),extra,COALESCE(character_set_name,''),COALESCE(collation_name,''),generation_expression FROM information_schema.columns WHERE table_schema='{db}' AND table_name='booking' ORDER BY ordinal_position;"
        indexes="SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema='{db}' AND table_name='booking' ORDER BY index_name,seq_in_index;"
        assert sql(columns.format(db=restored))==sql(columns.format(db=db)), 'backup canonical column metadata mismatch'
        assert sql(indexes.format(db=restored))==sql(indexes.format(db=db)), 'backup canonical index metadata mismatch'
        # mysqldump may make the per-column CHARACTER SET explicit; compare semantic metadata above.
        report['backupRestore']={'status':'passed','columnAndIndexMetadataMatch':True,'showCreateFormattingDifference':source_ddl!=restored_ddl}
        assert sql('USE `'+restored+'`;SELECT role_id,permission_id,deleted FROM sys_role_permission ORDER BY role_id,permission_id,deleted;')==mappings(), 'backup role mapping mismatch'
        history="SELECT id,booking_no,user_id,schedule_id,status,deleted,active_user_id FROM booking ORDER BY id;"
        assert sql('USE `'+restored+'`;'+history)==dbsql(history), 'backup booking row mismatch'
        case('owned full logical backup restored; booking history/index and role mapping checks match')
        (OUT/'booking-show-create.txt').write_text(dbsql('SHOW CREATE TABLE booking;')+'\n'+dbsql('SHOW INDEX FROM booking;')+'\n')
        if not a.sql_only:
            redis_port=port(); redis_password=secrets.token_hex(24);redis_conf=temp/'redis.conf'
            private_file(redis_conf,'bind 127.0.0.1\nport '+str(redis_port)+'\nrequirepass '+redis_password+'\nsave ""\nappendonly no\ndir '+directory+'\n')
            with owned_process(['redis-server',redis_conf],temp/'redis.log') as red:
                red_env={**os.environ,'REDISCLI_AUTH':redis_password}
                wait_for(red,lambda:run(['redis-cli','-h','127.0.0.1','-p',str(redis_port),'PING'],env=red_env).stdout.strip()=='PONG')
                password=secrets.token_hex(24)
                dbsql("CREATE USER 'round3_tests'@'localhost' IDENTIFIED BY '"+password+"'; GRANT ALL ON `"+db+"`.* TO 'round3_tests'@'localhost'; GRANT ALL ON `"+fresh_db+"`.* TO 'round3_tests'@'localhost';")
                url='jdbc:mysql://127.0.0.1:'+str(mysql_port)+'/'+db+'?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
                env={**os.environ,'PILATES_TEST_MYSQL_URL':url,'PILATES_TEST_MYSQL_FRESH_URL':url.replace('/'+db+'?','/'+fresh_db+'?'),'SPRING_DATASOURCE_URL':url,'SPRING_DATASOURCE_DRIVER_CLASS_NAME':'com.mysql.cj.jdbc.Driver','SPRING_DATASOURCE_USERNAME':'round3_tests','SPRING_DATASOURCE_PASSWORD':password,'SPRING_SQL_INIT_MODE':'never','PILATES_TEST_REDIS_PORT':str(redis_port),'PILATES_TEST_REDIS_PASSWORD':redis_password,'SPRING_DATA_REDIS_HOST':'127.0.0.1','SPRING_DATA_REDIS_PORT':str(redis_port),'SPRING_DATA_REDIS_PASSWORD':redis_password,'SPRING_DATA_REDIS_DATABASE':'15','SPRING_PROFILES_ACTIVE':'junit','YOYI_MQ_TYPE':'memory','YOYI_WX_MOCK_LOGIN':'true','YOYI_PAY_MOCK_ENABLED':'true','YOYI_SMS_MOCK_ENABLED':'false','YOYI_SCHEDULING_ENABLED':'false'}
                settings=run(['java','-XshowSettings:properties','-version']); env['JAVA_HOME']=os.environ.get('PILATES_TEST_JAVA_HOME',re.search(r'java.home = (.+)',settings.stderr).group(1))
                command_mvn=['mvn',* ([] if a.online_maven else ['-o']),'-q','-f',str(ROOT/'server/pom.xml'),'-Dpilates.build.directory='+str(build),'-Dtest='+a.tests,'test']
                report['redisBinaryVersion']=run(['redis-server','--version']).stdout.strip()
                print('Running related JUnit and real HTTP tests on owned MySQL '+BASE['version']+' / Redis (loopback only)',flush=True)
                with (OUT/'integration.log').open('w') as log:
                    result=subprocess.run(command_mvn,cwd=ROOT,env=env,stdout=log,stderr=log)
                # Surefire includes environment-backed property placeholders, never secrets in argv.
                for name in ['surefire-reports','case-evidence.jsonl','web-acceptance.json','vite-browser.json','nginx-browser.json','web-run.log']:
                    source=build/name
                    if source.is_dir():
                        destination=OUT/name;destination.mkdir(exist_ok=True)
                        for file in source.iterdir():
                            if file.is_file(): shutil.copy2(file,destination/file.name)
                    elif source.is_file():
                        if name=='case-evidence.jsonl' and (OUT/name).exists():
                            records={entry['test']:entry for entry in map(json.loads,(OUT/name).read_text().splitlines())}
                            records.update({entry['test']:entry for entry in map(json.loads,source.read_text().splitlines())})
                            (OUT/name).write_text(''.join(json.dumps(value,ensure_ascii=False)+'\n' for value in records.values()))
                        else:shutil.copy2(source,OUT/name)
                redact_outputs()
                if result.returncode: raise RuntimeError('JUnit failed; see server/target-round3/integration.log and surefire reports')
                case('selected JUnit suites completed against the owned test environment',command='python3 scripts/round3/accept.py --mysql-bin <MySQL8 bin>')
                if not a.skip_processes:
                    sys.path.insert(0,str(ROOT/'scripts/round3'))
                    from processes import verify
                    print('Verifying two independent Java OS processes and actual process restart',flush=True)
                    verify(ROOT,build,env,dbsql,redis_port,redis_password,OUT/'process-acceptance.json')
                    case('two OS processes share MySQL/Redis; real HTTP races and actual process restart')
                else: report['processes']={'status':'skipped','reason':'--skip-processes'}
        report['status']='passed';save();print('Owned MySQL / Redis stopped on exit; report:',OUT/a.report_file,flush=True)
except Exception as error:
 report['status']='failed';report['error']=type(error).__name__+': '+str(error);save();print('FAIL:',error,file=sys.stderr);sys.exit(1)
