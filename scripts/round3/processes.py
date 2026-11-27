"""Called only by accept.py while its private MySQL and Redis are alive; no standalone external URL input."""
import concurrent.futures, contextlib, json, os, pathlib, secrets, socket, subprocess, threading, time, urllib.request, xml.etree.ElementTree as ET

def verify(root, build, env, dbsql, redis_port, redis_password, output):
    reports=list((build/'surefire-reports').glob('TEST-*.xml'))
    props={p.attrib['name']:p.attrib.get('value','') for p in ET.parse(reports[0]).findall('properties/property')}
    classpath=props['java.class.path'];children=[];snapshots=[]
    if not env['SPRING_DATASOURCE_URL'].startswith('jdbc:mysql://127.0.0.1:'):raise RuntimeError('owned loopback database required')
    dbsql((root/'server/src/test/resources/reset-mysql.sql').read_text())
    dbname=env['SPRING_DATASOURCE_URL'].split('/',3)[3].split('?')[0]
    dbsql((root/'database/init_data.sql').read_text().replace('yoyi_pilates',dbname))
    # Remove only disposable sample schedules, then construct legal nonoverlapping test lessons.
    dbsql("DELETE FROM course_schedule; INSERT INTO course_coach(course_id,coach_id,status) VALUES(1,1,'APPROVED');")
    ids=[]
    for hour in [9,11,12,13,14,15,16]:
        ids.append(int(dbsql("INSERT INTO course_schedule(course_id,coach_id,classroom_id,course_date,start_time,end_time,max_capacity) VALUES(1,1,1,CURRENT_DATE+INTERVAL 1 DAY,'"+str(hour)+":00:00','"+str(hour+1)+":00:00',8); SELECT LAST_INSERT_ID();")))
    def free_port():
        with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
    def http(port,method,path,token=None,body=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request,timeout=60) as r:return json.load(r)
    def redis(*command):
        result=subprocess.run(['redis-cli','-h','127.0.0.1','-p',str(redis_port),'-n','15','--raw',*map(str,command)],env={**os.environ,'REDISCLI_AUTH':redis_password},capture_output=True,text=True)
        if result.returncode:raise RuntimeError('owned Redis command failed')
        return result.stdout.strip()
    def snapshot(name,attempts,successes,failures):
        data=dbsql('SELECT booked_count,max_capacity,(SELECT COUNT(*) FROM booking WHERE schedule_id='+str(ids[0])+" AND deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')) FROM course_schedule WHERE id="+str(ids[0])).split('\t')
        snapshots.append({'name':name,'attempts':attempts,'successes':successes,'failures':failures,'databaseBooked':int(data[0]),'capacity':int(data[1]),'occupiedCount':int(data[2]),'redisStock':redis('GET',stock_key)})
    # Keep keys identical to RedisKeys.java; verified by a direct snapshot after booking.
    keys_source=(root/'server/src/main/java/com/yoyi/pilates/common/constant/RedisKeys.java').read_text()
    import re
    prefix=re.search(r'PREFIX = "([^"]+)"',keys_source).group(1)
    assert 'return PREFIX + "stock:" + scheduleId;' in keys_source
    stock_key=prefix+'stock:'+str(ids[0])
    def race(work):
        barrier=threading.Barrier(len(work))
        def run(task):barrier.wait(timeout=20);return task()
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(work)) as pool:return list(pool.map(run,work))
    def tally(responses):
        failures={}
        for r in responses:
            if r['code']!=0:
                reason=str(r['code'])+':'+r.get('message','');failures[reason]=failures.get(reason,0)+1
        return sum(r['code']==0 for r in responses),failures
    java=str(pathlib.Path(env['JAVA_HOME'])/'bin/java')
    with contextlib.ExitStack() as stack:
        def start(index):
            port=free_port();log=stack.enter_context((build/('process-'+str(index)+'.log')).open('w'))
            process=subprocess.Popen([java,'-Xmx384m','-cp',classpath,'com.yoyi.pilates.PilatesApplication','--spring.profiles.active=junit','--spring.sql.init.mode=never','--server.address=127.0.0.1','--server.port='+str(port),'--spring.main.banner-mode=off'],env=env,cwd=root,stdout=log,stderr=log);children.append(process)
            for _ in range(300):
                if process.poll() is not None:raise RuntimeError('owned backend process exited')
                try:
                    if http(port,'GET','/api/booking-rules')['code']==0:return process,port
                except Exception:time.sleep(.1)
            raise RuntimeError('owned backend process timeout')
        try:
            p1,port1=start(1);p2,port2=start(2)
            tokens=[]
            for i in range(100):
                response=http(port1 if i%2==0 else port2,'POST','/api/auth/wx-login',body={'code':'process_'+secrets.token_hex(16)})
                if response['code']!=0:raise AssertionError('isolated Mock login failed')
                tokens.append(response['data']['token'])
            responses=race([lambda p=port1 if i%2==0 else port2,t=t:http(p,'POST','/api/bookings',t,{'scheduleId':ids[0]}) for i,t in enumerate(tokens)])
            successes,failures=tally(responses);assert successes==8, ("100-member winners",successes,failures)
            print('Two OS processes: 100 requests, 8 bookings; checking database and Redis',flush=True)
            snapshot('100 real HTTP members / two OS processes',100,successes,failures);snapshots[-1]['redisStock']=redis('GET',stock_key)
            assert snapshots[-1]['databaseBooked']==snapshots[-1]['occupiedCount']==8 and snapshots[-1]['redisStock']=='0'
            winner=next(i for i,r in enumerate(responses) if r['code']==0);token=tokens[winner];old=responses[winner]['data']['bookingId'];assert http(port2,'DELETE','/api/bookings/'+str(old),token)['code']==0
            responses=race([lambda p=port1 if i%2==0 else port2:http(p,'POST','/api/bookings',token,{'scheduleId':ids[0]}) for i in range(8)])
            successes,failures=tally(responses);assert successes==1, ("rebook winners",successes,failures)
            snapshot('same member concurrent rebook after cancellation',8,successes,failures);snapshots[-1]['redisStock']=redis('GET',stock_key)
            new=next(r['data']['bookingId'] for r in responses if r['code']==0);assert new!=old
            assert http(port1,'DELETE','/api/bookings/'+str(old),token)['code']==0
            responses=race([lambda p=port1 if i%2==0 else port2,s=s:http(p,'POST','/api/bookings',token,{'scheduleId':s}) for i,s in enumerate(ids[1:])])
            successes,failures=tally(responses);assert successes==2, ("daily-limit winners",successes,failures)
            snapshot('same member cross-schedule daily maximum three',6,successes,failures);snapshots[-1]['redisStock']=redis('GET',stock_key)
            p2.terminate();p2.wait(timeout=15);redis('SET',stock_key,'999');p2,port2=start(3)
            detail=http(port2,'GET','/api/course-schedules/'+str(ids[0]))
            assert detail['code']==0 and detail['data']['remaining']==0 and detail['data']['bookedCount']==8
            assert http(port2,'DELETE','/api/bookings/'+str(new),token)['code']==0
            assert redis('GET',stock_key)=='1'
            rebook=http(port1,'POST','/api/bookings',token,{'scheduleId':ids[0]});assert rebook['code']==0 and rebook['data']['bookingId']!=new
            snapshot('OS process restart / DB authoritative read / next write repairs corrupted Redis',2,2,{});snapshots[-1]['redisStock']=redis('GET',stock_key)
            assert snapshots[-1]['databaseBooked']==snapshots[-1]['occupiedCount']==8 and snapshots[-1]['redisStock']=='0'
            output.write_text(json.dumps({'status':'passed','backendProcesses':2,'restartActuallyPerformed':True,'loginType':'development mock HTTP; not real WeChat','snapshots':snapshots},ensure_ascii=False,indent=2)+'\n')
        finally:
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
                    try:child.wait(timeout=15)
                    except subprocess.TimeoutExpired:child.kill();child.wait()
    # Both owned backend processes are now stopped. Exercise the exact operational dry-run/apply entry.
    mysql_client=str(pathlib.Path(dbsql('SELECT @@basedir'))/'bin/mysql')
    arguments=['python3',str(root/'scripts/round3/reconcile-stock.py'),'--mysql-client',mysql_client,'--defaults-file',str(build.parent/'client.cnf'),'--database',dbname,'--expected-version','8.0.46','--redis-host','127.0.0.1','--redis-port',str(redis_port),'--redis-db','15']
    op_env={**os.environ,'REDISCLI_AUTH':redis_password}
    terminal_ids=dbsql('SELECT id FROM course_schedule WHERE booked_count=0 ORDER BY id LIMIT 2').splitlines()
    assert len(terminal_ids)==2
    dbsql("UPDATE course_schedule SET status='CANCELLED' WHERE id="+terminal_ids[0]+"; UPDATE course_schedule SET status='FINISHED',course_date=CURRENT_DATE-INTERVAL 1 DAY WHERE id="+terminal_ids[1]+";")
    for sid in terminal_ids:redis('SET',prefix+'stock:'+sid,'8')
    redis('SET','round3:unrelated','keep')
    dry=subprocess.run(arguments,capture_output=True,text=True,env=op_env,timeout=60)
    assert dry.returncode==0 and json.loads(dry.stdout)['status']=='dry_run','stock dry run failed'
    applied=subprocess.run(arguments+['--apply-paused-writes'],capture_output=True,text=True,env=op_env,timeout=60)
    assert applied.returncode==0 and json.loads(applied.stdout)['status']=='passed','stock apply/final check failed'
    assert redis('GET','round3:unrelated')=='keep','unrelated Redis key changed'
    before=redis('GET',stock_key)
    dbsql('UPDATE course_schedule SET booked_count=booked_count+1 WHERE id='+str(ids[0]))
    rejected=subprocess.run(arguments+['--apply-paused-writes'],capture_output=True,text=True,env=op_env,timeout=60)
    assert rejected.returncode==2 and json.loads(rejected.stdout)['anomalyCount']==1 and redis('GET',stock_key)==before,'mismatch must stop before any Redis mutation'
    dbsql('UPDATE course_schedule SET booked_count=booked_count-1 WHERE id='+str(ids[0]))
    (output.parent/'operations-check.json').write_text(json.dumps({'status':'passed','dryRun':json.loads(dry.stdout),'apply':json.loads(applied.stdout),'mismatchStopsWithoutRedisWrites':True,'unrelatedRedisKeyPreserved':True},ensure_ascii=False,indent=2)+'\n')
