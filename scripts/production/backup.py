#!/usr/bin/env python3
"""Read-only backup; requires an explicitly identified server and paused application writes.
Only encrypted SQL/files are retained. No database mutation, Redis reset, or file removal.
"""
import argparse,datetime,hashlib,json,os,pathlib,subprocess,tarfile
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql-bin',required=True,type=pathlib.Path);p.add_argument('--client-config',required=True,type=pathlib.Path);p.add_argument('--expect-server-uuid',required=True);p.add_argument('--database',required=True);p.add_argument('--files',required=True,type=pathlib.Path);p.add_argument('--recipient',required=True,help='age public recipient, never a private key');p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--writes-paused',action='store_true',required=True);a=p.parse_args()
if a.client_config.is_symlink() or a.client_config.stat().st_mode&0o077:raise SystemExit('STOP: client config must be a protected regular file')
import re
if not re.fullmatch('[a-zA-Z][a-zA-Z0-9_]{0,63}',a.database):raise SystemExit('STOP: invalid explicit database name')
a.output.mkdir(mode=0o700,parents=True,exist_ok=False)
client=[str(a.mysql_bin/'mysql'),'--defaults-file='+str(a.client_config.resolve()),'--batch','--skip-column-names']
def sql(statement):
 r=subprocess.run(client,input=statement,capture_output=True,text=True,check=False)
 if r.returncode:raise SystemExit('STOP: read-only query failed; details withheld')
 return r.stdout.strip()
if sql('SELECT @@server_uuid')!=a.expect_server_uuid:raise SystemExit('STOP: wrong source server')
metadata={'status':'running','sourceServerUuid':a.expect_server_uuid,'database':a.database,'checkpointUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'productVersion':sql('SELECT VERSION()'),'writesPausedByOperator':True,'artifacts':{}}
queries={
 'booking':'SELECT COUNT(*),COALESCE(SUM(active_user_id IS NOT NULL),0),COALESCE(SUM(id),0) FROM booking',
 'attendance':'SELECT COUNT(*),COALESCE(SUM(booking_id),0) FROM attendance',
 'capacity':'SELECT COUNT(*),COALESCE(SUM(booked_count),0),COALESCE(SUM(max_capacity),0) FROM course_schedule',
 'permissions':'SELECT COUNT(*),COALESCE(SUM(role_id),0),COALESCE(SUM(permission_id),0) FROM sys_role_permission',
 'files':'SELECT COUNT(*),COALESCE(SUM(byte_size),0) FROM file_object'}
metadata['checks']={k:sql('USE `'+a.database+'`;'+query) for k,query in queries.items()}
try:
 dump=a.output/'database.sql.age'
 with dump.open('wb') as output:
  encrypt=subprocess.Popen(['age','--encrypt','--recipient',a.recipient],stdin=subprocess.PIPE,stdout=output,stderr=subprocess.PIPE)
  source=subprocess.Popen([str(a.mysql_bin/'mysqldump'),'--defaults-file='+str(a.client_config.resolve()),'--single-transaction','--quick','--no-tablespaces','--set-gtid-purged=OFF',a.database],stdout=encrypt.stdin,stderr=subprocess.PIPE)
  encrypt.stdin.close();encrypt.stdin=None;_,error=source.communicate();_,encryption_error=encrypt.communicate()
  if source.returncode or encrypt.returncode:raise RuntimeError('encrypted SQL backup failed; diagnostics withheld')
 dump.chmod(0o600)
 files=a.output/'files.tar.age'
 with files.open('wb') as output:
  encrypt=subprocess.Popen(['age','--encrypt','--recipient',a.recipient],stdin=subprocess.PIPE,stdout=output,stderr=subprocess.PIPE)
  with tarfile.open(fileobj=encrypt.stdin,mode='w|') as archive:archive.add(a.files.resolve(),arcname='files',recursive=True)
  encrypt.stdin.close();encrypt.stdin=None;_,error=encrypt.communicate()
  if encrypt.returncode:raise RuntimeError('encrypted files backup failed')
 files.chmod(0o600)
 after={k:sql('USE `'+a.database+'`;'+query) for k,query in queries.items()}
 if after!=metadata['checks']:raise RuntimeError('source counts changed; discard as an inconsistent backup and pause all writers')
 for artifact in [dump,files]:
  digest=hashlib.sha256()
  with artifact.open('rb') as stream:
   for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
  metadata['artifacts'][artifact.name]={'sha256':digest.hexdigest(),'bytes':artifact.stat().st_size}
 metadata['status']='passed'
except Exception as error:metadata['status']='failed';metadata['error']=type(error).__name__;raise
finally:
 path=a.output/'manifest.json';path.write_text(json.dumps(metadata,indent=2)+'\n');path.chmod(0o600)
print('Encrypted backup created; verify by restoring into a separate isolated instance.')
