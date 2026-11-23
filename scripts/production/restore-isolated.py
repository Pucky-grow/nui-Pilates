#!/usr/bin/env python3
"""Restore an encrypted rehearsal backup to a DIFFERENT server and NEW database/directory.
Refuses to overwrite any existing database/files. Never clears Redis or drops a database.
"""
import argparse,hashlib,json,pathlib,re,subprocess,tarfile,tempfile,time
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql-bin',required=True,type=pathlib.Path);p.add_argument('--client-config',required=True,type=pathlib.Path);p.add_argument('--expect-server-uuid',required=True);p.add_argument('--backup',required=True,type=pathlib.Path);p.add_argument('--identity-file',required=True,type=pathlib.Path);p.add_argument('--new-database',required=True);p.add_argument('--new-files',required=True,type=pathlib.Path);p.add_argument('--confirm-isolated-target',action='store_true',required=True);p.add_argument('--report',required=True,type=pathlib.Path);a=p.parse_args();started=time.monotonic()
for file in [a.client_config,a.identity_file]:
 if not file.is_file() or file.is_symlink() or file.stat().st_mode&0o077:raise SystemExit('STOP: private configuration/key must be a regular 0600 file')
if not re.fullmatch('[a-zA-Z][a-zA-Z0-9_]{0,63}',a.new_database):raise SystemExit('STOP: invalid new database')
manifest=json.loads((a.backup/'manifest.json').read_text());assert manifest['status']=='passed'
for name,meta in manifest['artifacts'].items():
 if name not in ['database.sql.age','files.tar.age']:raise SystemExit('STOP: unexpected backup artifact')
 digest=hashlib.sha256()
 with (a.backup/name).open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
 if digest.hexdigest()!=meta['sha256']:raise SystemExit('STOP: encrypted artifact checksum mismatch')
client=[str(a.mysql_bin/'mysql'),'--defaults-file='+str(a.client_config.resolve()),'--batch','--skip-column-names']
def sql(statement):
 r=subprocess.run(client,input=statement,capture_output=True,text=True)
 if r.returncode:raise RuntimeError('restore query failed; credentials and data withheld')
 return r.stdout.strip()
if sql('SELECT @@server_uuid')!=a.expect_server_uuid or a.expect_server_uuid==manifest['sourceServerUuid']:raise SystemExit('STOP: target must be the explicitly identified different instance')
if sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+a.new_database+"'")!='0' or a.new_files.exists():raise SystemExit('STOP: restoration requires an absent database and absent directory')
report={'status':'running','sourceCheckpointUtc':manifest['checkpointUtc'],'separateServerVerified':True,'noExistingDatabaseOverwritten':True,'rpoTarget':'operator confirmation required','rtoTarget':'operator confirmation required'}
try:
 sql('CREATE DATABASE `'+a.new_database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
 decrypt=subprocess.Popen(['age','--decrypt','--identity',str(a.identity_file),str(a.backup/'database.sql.age')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 load=subprocess.Popen(client+['--database='+a.new_database],stdin=decrypt.stdout,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE);decrypt.stdout.close();_,failure=load.communicate();_,error=decrypt.communicate()
 if load.returncode or decrypt.returncode:raise RuntimeError('SQL restore failed; retain isolated partial target for review, do not auto-delete')
 a.new_files.mkdir(mode=0o700,parents=True)
 decrypt=subprocess.Popen(['age','--decrypt','--identity',str(a.identity_file),str(a.backup/'files.tar.age')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 with tarfile.open(fileobj=decrypt.stdout,mode='r|') as archive:
  for member in archive:
   target=(a.new_files/member.name).resolve()
   if not target.is_relative_to(a.new_files.resolve()) or member.issym() or member.islnk() or not(member.isdir() or member.isfile()):raise RuntimeError('unsafe archive member')
   archive.extract(member,a.new_files,set_attrs=False)
 _,failure=decrypt.communicate()
 if decrypt.returncode:raise RuntimeError('file restore failed')
 queries={ 'booking':'SELECT COUNT(*),COALESCE(SUM(active_user_id IS NOT NULL),0),COALESCE(SUM(id),0) FROM booking','attendance':'SELECT COUNT(*),COALESCE(SUM(booking_id),0) FROM attendance','capacity':'SELECT COUNT(*),COALESCE(SUM(booked_count),0),COALESCE(SUM(max_capacity),0) FROM course_schedule','permissions':'SELECT COUNT(*),COALESCE(SUM(role_id),0),COALESCE(SUM(permission_id),0) FROM sys_role_permission','files':'SELECT COUNT(*),COALESCE(SUM(byte_size),0) FROM file_object'}
 checks={k:sql('USE `'+a.new_database+'`;'+query) for k,query in queries.items()}
 if checks!=manifest['checks']:raise RuntimeError('restored database aggregate mismatch')
 references=sql('USE `'+a.new_database+'`;SELECT object_key,sha256 FROM file_object').splitlines();verified=0
 for row in references:
  key,expected=row.split('\t');path=(a.new_files/'files'/key).resolve()
  if not path.is_relative_to((a.new_files/'files').resolve()) or not path.is_file():raise RuntimeError('referenced file missing/invalid')
  if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise RuntimeError('referenced file checksum mismatch')
  verified+=1
 report.update(status='passed',seconds=round(time.monotonic()-started,3),aggregateChecksMatched=True,fileReferencesVerified=verified)
except Exception as error:report.update(status='failed',error=type(error).__name__);raise
finally:a.report.write_text(json.dumps(report,indent=2)+'\n');a.report.chmod(0o600)
print('Isolated restoration and reference checks passed. No existing database was overwritten.')
