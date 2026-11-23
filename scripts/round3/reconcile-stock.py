#!/usr/bin/env python3
"""Dry-run by default. Applying requires an explicit operator assertion that all application writers/tasks are stopped.
Only reads MySQL; only SET/DEL pilates:stock:<existing schedule id> in the explicitly selected Redis database.
Never FLUSHDB, FLUSHALL, KEYS or wildcard deletion. Credentials are files/environment, not command arguments.
"""
import argparse,json,os,pathlib,re,subprocess,sys
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--mysql-client',required=True);p.add_argument('--defaults-file',required=True,type=pathlib.Path);p.add_argument('--database',required=True)
p.add_argument('--expected-version',required=True);p.add_argument('--redis-host',required=True);p.add_argument('--redis-port',required=True,type=int);p.add_argument('--redis-db',required=True,type=int)
p.add_argument('--apply-paused-writes',action='store_true');a=p.parse_args()
if not re.fullmatch('[A-Za-z0-9_]+',a.database):p.error('invalid database identifier')
if not a.defaults_file.is_file() or a.defaults_file.stat().st_mode & 0o077:p.error('client defaults file must exist with mode 0600')
cmd=[a.mysql_client,'--defaults-file='+str(a.defaults_file),'--database='+a.database,'--batch','--skip-column-names']
def sql(query):
 r=subprocess.run(cmd,input=query,capture_output=True,text=True,timeout=90)
 if r.returncode:raise RuntimeError('read-only database query failed; no Redis mutation attempted')
 return r.stdout.strip()
try:
 assert sql('SELECT DATABASE(),VERSION();')==a.database+'\t'+a.expected_version,'database/version mismatch'
 rows=sql("SELECT s.id,s.status,s.deleted,s.max_capacity,s.booked_count,COUNT(b.id) FROM course_schedule s LEFT JOIN booking b ON b.schedule_id=s.id AND b.deleted=0 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW') GROUP BY s.id,s.status,s.deleted,s.max_capacity,s.booked_count ORDER BY s.id;")
 actions=[];anomalies=0
 for line in rows.splitlines():
  sid,status,deleted,capacity,booked,occupied=line.split('\t');capacity,booked,occupied=int(capacity),int(booked),int(occupied)
  if int(deleted)==0 and (capacity<1 or booked!=occupied or occupied>capacity or booked<0 or (status=='CANCELLED' and occupied>0)):anomalies+=1
  actions.append(('SET','pilates:stock:'+sid,str(capacity-occupied)) if status=='SCHEDULED' and deleted=='0' else ('DEL','pilates:stock:'+sid))
 result={'status':'blocked' if anomalies else 'dry_run','databaseVersion':a.expected_version,'scheduleCount':len(actions),'setCount':sum(x[0]=='SET' for x in actions),'deleteCount':sum(x[0]=='DEL' for x in actions),'anomalyCount':anomalies,'applied':0,'sharedRedisCleared':False}
 if anomalies:print(json.dumps(result));raise SystemExit(2)
 if a.apply_paused_writes:
  for action in actions:
   r=subprocess.run(['redis-cli','-h',a.redis_host,'-p',str(a.redis_port),'-n',str(a.redis_db),'--raw',*action],capture_output=True,text=True,timeout=15)
   if r.returncode or r.stdout.startswith(('ERR','NOAUTH','WRONGPASS')):raise RuntimeError('Redis synchronization failed after '+str(result['applied'])+' operations; keep writes paused and rerun')
   result['applied']+=1
  # Final exact per-key verification; never rely only on the SET/DEL return codes.
  for action in actions:
   r=subprocess.run(['redis-cli','-h',a.redis_host,'-p',str(a.redis_port),'-n',str(a.redis_db),'--raw','GET',action[1]],capture_output=True,text=True,timeout=15)
   expected=action[2] if action[0]=='SET' else ''
   if r.returncode or r.stdout.strip()!=expected:raise RuntimeError('Redis final verification mismatch; keep writes paused')
  result['status']='passed'
 print(json.dumps(result,ensure_ascii=False))
except Exception as error:
 print(json.dumps({'status':'failed','reason':str(error),'sharedRedisCleared':False},ensure_ascii=False));sys.exit(1)
