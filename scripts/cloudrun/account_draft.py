#!/usr/bin/env python3
"""Create protected DMC account drafts locally. Does not connect to any database or submit credentials."""
import argparse,hashlib,json,os,pathlib,re,subprocess,tempfile,zipfile
HERE=pathlib.Path(__file__).resolve()
ROOT=HERE.parents[2] if (HERE.parents[2]/'deploy/wechat-cloudrun').is_dir() else HERE.parents[1]

def protected_values(path):
 if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:raise ValueError('input must be a protected regular 0600 file')
 values={}
 for line in path.read_text().splitlines():
  if not line or line.startswith('#'):continue
  key,value=line.split('=',1)
  if key in values:raise ValueError('duplicate setting')
  values[key]=value
 return values

def literal(value):
 if any(ch in value for ch in ['\0','\r','\n']):raise ValueError('multiline/NUL credentials are not supported')
 return "'"+value.replace('\\','\\\\').replace("'","''")+"'"

def private(path,text):
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w') as f:f.write(text)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--input',type=pathlib.Path,required=True)
 p.add_argument('--artifact',type=pathlib.Path,required=True);p.add_argument('--java',type=pathlib.Path,required=True)
 p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
 v=protected_values(a.input)
 mysql=v.get('MYSQL_PASSWORD','');admin=v.get('ADMIN_PASSWORD','')
 if len(mysql)<16 or len(mysql.encode())>72 or len(admin)<14 or len(admin.encode())>72:raise ValueError('strong runtime and administrator credentials required; values withheld')
 if mysql.lower() in {'replace_with_your_strong_password','changemeplease123456'}:raise ValueError('placeholder rejected')
 manifest=json.loads((a.artifact/'manifest.json').read_text());jar=a.artifact/'pilates-server.jar'
 if hashlib.sha256(jar.read_bytes()).hexdigest()!=manifest['jarSha256']:raise ValueError('artifact hash mismatch')
 a.output.mkdir(mode=0o700,parents=True,exist_ok=False)
 with tempfile.TemporaryDirectory(prefix='account-hash-',dir=a.output) as d:
  d=pathlib.Path(d);cp=[]
  with zipfile.ZipFile(jar) as z:
   for n in z.namelist():
    if n.startswith('BOOT-INF/lib/') and re.search(r'/(spring-security-crypto|spring-jcl|commons-logging)-[^/]+\.jar$',n):
     f=d/pathlib.Path(n).name;f.write_bytes(z.read(n));cp.append(str(f))
  if not any('spring-security-crypto-' in n for n in cp):raise ValueError('reviewed password encoder unavailable')
  source=d/'HashAdministrator.java'
  source.write_text('import java.io.*; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; class HashAdministrator { public static void main(String[] x)throws Exception { String p=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine(); System.out.print(new BCryptPasswordEncoder(12).encode(p)); } }')
  hashed=subprocess.run([str(a.java),'-cp',os.pathsep.join(cp),str(source)],input=admin+'\n',text=True,capture_output=True,timeout=30)
  if hashed.returncode or not re.fullmatch(r'\$2[aby]\$12\$[./A-Za-z0-9]{53}',hashed.stdout):raise ValueError('password hash generation failed; output withheld')
  bcrypt=hashed.stdout
 grant_path=ROOT/'deploy/wechat-cloudrun/grant-test-runtime.draft.sql'
 if not grant_path.exists():grant_path=ROOT/'database/grant-test-runtime.draft.sql'
 grant=grant_path.read_text()
 start=grant.index('  GRANT SELECT,INSERT,UPDATE,DELETE')
 grants=grant[start:grant.rindex('END;')]
 # Credential creation is left to the user in DMC. The entire procedure is pinned
 # to one test instance; no dynamic SQL or credentials appear in stdout/arguments.
 sql="""-- PRIVATE: contains a new credential and password hash. Do not share or upload as application code.
-- Install through DMC > uni_pilates_test > Stored procedures > New. SQL SECURITY INVOKER.
CREATE PROCEDURE cloudrun_initialize_test_accounts()
SQL SECURITY INVOKER
BEGIN
  DECLARE acquired INT DEFAULT 0;
  DECLARE role_id_value BIGINT;
  DECLARE new_owner_id BIGINT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    IF acquired=1 THEN DO RELEASE_LOCK('pilates-test-account-bootstrap'); END IF;
    RESIGNAL;
  END;
  IF DATABASE()<>'uni_pilates_test' OR @@server_uuid<>'d1f6adf9-d5d5-11f1-9afc-0c42a17335be' OR VERSION()<>'8.0.30-cynos-3.1.19.002' OR @@sql_mode LIKE '%NO_BACKSLASH_ESCAPES%' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wrong target or unsupported SQL mode';
  END IF;
  SELECT GET_LOCK('pilates-test-account-bootstrap',0) INTO acquired;
  IF acquired<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: account initialization is busy'; END IF;
  IF (SELECT COUNT(*) FROM mysql.user WHERE user='uni_pilates_runtime')<>0 OR (SELECT COUNT(*) FROM sys_user)<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: accounts exist; do not repeat or overwrite';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE')<>58 OR (SELECT COUNT(*) FROM schema_history)<>3 OR
    (SELECT COUNT(*) FROM schema_history WHERE state='DONE' AND applied_steps=total_steps AND ((version='V001__structure.sql' AND checksum='5131773b57da5f67bed85972b6767c70b65f178c2e12919b5eb67d33a2a01346' AND total_steps=55) OR (version='V002__base.sql' AND checksum='182dc86bc23c0e95ffa984000deece015a64b96d9c418feb2171da1d4ff43b76' AND total_steps=24) OR (version='V003__delivery.sql' AND checksum='ea579d8b6c1385e14020f4c6eaa96e5cd502727da574d1e1cf25b4f71c84ea4e' AND total_steps=5)))<>3 OR
    (SELECT COUNT(*) FROM system_config WHERE config_key='booking.paymentMode' AND config_value='FREE' AND deleted=0)<>1 OR
    (SELECT COUNT(*) FROM sys_role WHERE code='ADMIN' AND status='ACTIVE' AND deleted=0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unexpected schema or ADMIN role';
  END IF;
  CREATE USER 'uni_pilates_runtime'@'%' IDENTIFIED WITH mysql_native_password BY MYSQL_LITERAL;
GRANTS
  START TRANSACTION;
  SELECT id INTO role_id_value FROM sys_role WHERE code='ADMIN' AND status='ACTIVE' AND deleted=0 FOR UPDATE;
  IF (SELECT COUNT(*) FROM sys_user)<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: account table changed'; END IF;
  INSERT INTO sys_user(username,name,password,status,auth_version) VALUES('yoyi_owner','Owner',ADMIN_LITERAL,'ACTIVE',0);
  SET new_owner_id=LAST_INSERT_ID();
  INSERT INTO sys_user_role(user_id,role_id) VALUES(new_owner_id,role_id_value);
  COMMIT;
  DO RELEASE_LOCK('pilates-test-account-bootstrap');
  SET acquired=0;
  SELECT 'CREATED_TEST_ACCOUNTS' AS result;
END;
"""
 replacements={'MYSQL_LITERAL':literal(mysql),'ADMIN_LITERAL':literal(bcrypt),'GRANTS':grants}
 sql=re.sub(r'\b(MYSQL_LITERAL|ADMIN_LITERAL|GRANTS)\b',lambda m:replacements[m.group(1)],sql)
 private(a.output/'01-install-private-routine.sql',sql)
 private(a.output/'02-call.sql','USE uni_pilates_test;\nCALL cloudrun_initialize_test_accounts();\n')
 private(a.output/'03-readonly.sql',"SELECT DATABASE(),@@server_uuid,VERSION();\nSELECT COUNT(*) AS runtime_accounts FROM mysql.user WHERE user='uni_pilates_runtime';\nSHOW GRANTS FOR 'uni_pilates_runtime'@'%';\nSELECT COUNT(*) AS owners FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id WHERE u.username='yoyi_owner' AND u.status='ACTIVE' AND r.code='ADMIN' AND u.deleted=0 AND ur.deleted=0 AND r.deleted=0;\n")
 private(a.output/'README.txt','仅限已核实的上海测试库。新口令的输入、确认和提交必须由你亲自完成。\n01 含新口令，请在 DMC 存储过程新建入口粘贴并提交；不要在普通 SQL 窗口安装过程定义。\n02 在 SQL 窗口调用。CREATE USER/GRANT 会隐式提交；若中途失败，保留已创建账号，停止并核查，不自动删用户或覆盖重试。\n03 仅只读检查，不能分享密码或散列。初始管理员用户名 yoyi_owner。\n运行账号仅业务表四项 DML，schema_history 只读；没有 DDL/GRANT/全局权限。\nMySQL 无 TLS，native_password 仅用于此测试内网例外；不得用于生产。\n该过程含凭据，初始化成功后应由操作者 DROP PROCEDURE cloudrun_initialize_test_accounts，保留账号和历史数据。\n')
 print(json.dumps({'status':'draft-only-not-executed','output':str(a.output.resolve()),'credentialsEmitted':False}))
if __name__=='__main__':
 try:main()
 except Exception as e:raise SystemExit('STOP: '+type(e).__name__+'; no credential values emitted')
