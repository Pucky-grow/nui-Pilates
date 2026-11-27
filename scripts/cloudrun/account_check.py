#!/usr/bin/env python3
"""Verify generated account drafts in one owned local MySQL 8 container; never accesses cloud."""
import argparse,hashlib,json,os,pathlib,secrets,subprocess,tempfile,time
ROOT=pathlib.Path(__file__).resolve().parents[2]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--docker-context',required=True)
 p.add_argument('--artifact',type=pathlib.Path,required=True);p.add_argument('--java',required=True)
 p.add_argument('--report',type=pathlib.Path,required=True);a=p.parse_args()
 docker=['docker','--context',a.docker_context];name='yoyi-account-check-'+secrets.token_hex(5)
 pin=json.loads((ROOT/'deploy/image-lock.json').read_text())['images']['mysql']['reference']
 def run(cmd,**kw):return subprocess.run(list(map(str,cmd)),capture_output=True,text=True,timeout=90,**kw)
 result={'type':'local-generated-account-sql','status':'running','cloudAccountsCreated':False,'fixtureImage':pin}
 with tempfile.TemporaryDirectory(prefix=name,dir=ROOT/'.secrets') as directory:
  directory=pathlib.Path(directory);directory.chmod(0o700)
  rootpw=secrets.token_hex(32);runtime=secrets.token_hex(20)+"'\\GRANTS_ADMIN_LITERAL!";admin=secrets.token_hex(32)
  pw=directory/'rootpw';pw.touch(mode=0o600);pw.write_text(rootpw)
  cnf=directory/'mysql.cnf';cnf.touch(mode=0o600);cnf.write_text('[client]\nuser=root\npassword='+rootpw+'\n')
  inp=directory/'input.env';inp.write_text('MYSQL_PASSWORD='+runtime+'\nADMIN_PASSWORD='+admin+'\nWX_SECRET=\n');inp.chmod(0o600)
  out=directory/'draft'
  generated=run(['python3',ROOT/'scripts/cloudrun/account_draft.py','--input',inp,'--artifact',a.artifact,'--java',a.java,'--output',out])
  if generated.returncode:raise RuntimeError('account draft generation failed; values withheld')
  try:
   start=run(docker+['run','-d','--name',name,'--network','none','-v',str(pw)+':/run/rootpw:ro','-v',str(cnf)+':/run/client.cnf:ro','-e','MYSQL_ROOT_PASSWORD_FILE=/run/rootpw',pin])
   if start.returncode:raise RuntimeError('owned local MySQL fixture failed to start')
   def sql(s):return run(docker+['exec','--user','0:0','-i',name,'mysql','--defaults-extra-file=/run/client.cnf','-N','-B'],input=s)
   for _ in range(60):
    if sql('SELECT 1;').returncode==0:break
    time.sleep(.5)
   else:raise RuntimeError('owned local MySQL fixture not ready')
   assert sql('CREATE DATABASE uni_pilates_test;').returncode==0
   versions=[]
   for f in sorted((ROOT/'database/production').glob('V00[1-3]__*.sql')):
    text=f.read_text();r=sql('USE uni_pilates_test;\n'+text)
    if r.returncode:raise RuntimeError('local schema fixture rejected; SQL withheld')
    # Same reviewed splitter as the migration tool; this local count is known
    # from the three reviewed migration checksums and not inferred from lines.
    versions.append((f.name,hashlib.sha256(text.encode()).hexdigest(),{'V001__structure.sql':55,'V002__base.sql':24,'V003__delivery.sql':5}[f.name]))
   history='CREATE TABLE schema_history(version VARCHAR(100) PRIMARY KEY,checksum CHAR(64),state VARCHAR(20),applied_steps INT,total_steps INT);\n'
   history+='\n'.join("INSERT INTO schema_history VALUES('%s','%s','DONE',%d,%d);"%(v,h,n,n) for v,h,n in versions)
   assert sql('USE uni_pilates_test;'+history).returncode==0
   draft=(out/'01-install-private-routine.sql').read_text()
   def install(s):return sql('USE uni_pilates_test;\nDELIMITER $$\n'+s.rstrip(';\n')+'\n$$\nDELIMITER ;\n')
   if install(draft).returncode:raise RuntimeError('generated procedure syntax rejected; output withheld')
   refused=sql('USE uni_pilates_test; CALL cloudrun_initialize_test_accounts();')
   assert refused.returncode and 'wrong target' in refused.stderr
   assert sql("SELECT COUNT(*) FROM mysql.user WHERE user='uni_pilates_runtime';").stdout.strip()=='0'
   assert sql('SELECT COUNT(*) FROM uni_pilates_test.sys_user;').stdout.strip()=='0'
   assert sql('DROP PROCEDURE uni_pilates_test.cloudrun_initialize_test_accounts;').returncode==0
   uuid=sql('SELECT @@server_uuid;').stdout.strip();version=sql('SELECT VERSION();').stdout.strip()
   # Change only identity constants in a test copy. The delivered draft keeps
   # the fixed cloud UUID/version; never relax the real deployment guard.
   fixture=draft.replace('d1f6adf9-d5d5-11f1-9afc-0c42a17335be',uuid).replace('8.0.30-cynos-3.1.19.002',version)
   if install(fixture).returncode:raise RuntimeError('local fixture procedure installation rejected')
   created=sql('USE uni_pilates_test; CALL cloudrun_initialize_test_accounts();')
   if created.returncode or 'CREATED_TEST_ACCOUNTS' not in created.stdout:raise RuntimeError('local account procedure failed; values withheld')
   checks=sql("SELECT COUNT(*) FROM information_schema.table_privileges WHERE GRANTEE=CONCAT(CHAR(39),'uni_pilates_runtime',CHAR(39),'@',CHAR(39),'%',CHAR(39)) AND TABLE_SCHEMA='uni_pilates_test'; SELECT COUNT(*) FROM uni_pilates_test.sys_user u JOIN uni_pilates_test.sys_user_role ur ON ur.user_id=u.id JOIN uni_pilates_test.sys_role r ON r.id=ur.role_id WHERE u.username='yoyi_owner' AND r.code='ADMIN';")
   assert checks.returncode==0 and checks.stdout.strip().splitlines()==['229','1']
   repeated=sql('USE uni_pilates_test; CALL cloudrun_initialize_test_accounts();')
   assert repeated.returncode and 'accounts exist' in repeated.stderr
   assert sql('SELECT COUNT(*) FROM uni_pilates_test.sys_user;').stdout.strip()=='1'
   # Confirm escaping preserves quotes/backslashes and placeholder-like text.
   native_hash='*'+hashlib.sha1(hashlib.sha1(runtime.encode()).digest()).hexdigest().upper()
   expected=sql("SELECT authentication_string=0x"+native_hash.encode().hex()+" FROM mysql.user WHERE user='uni_pilates_runtime' AND host='%';")
   if expected.returncode:raise RuntimeError('local password verification query rejected')
   assert expected.stdout.strip()=='1'
   result.update(status='passed',mysqlVersion=version,cases=['new procedure compiles','real target guard refuses local UUID without writes','test copy creates scoped runtime and one ADMIN','57 four-DML grants plus schema_history SELECT only','credential escaping preserves original password','repeat initialization refused without duplicates'])
  except Exception as error:
   result.update(status='failed',failureType=type(error).__name__);raise
  finally:
   run(docker+['rm','-f','-v',name]);result['ownedContainerCleaned']=True
   a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'status':result['status'],'report':str(a.report)}))
if __name__=='__main__':main()
