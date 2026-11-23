#!/usr/bin/env python3
"""Generate a UUID-pinned DMC initializer for the empty, isolated test database only.

No network calls. Uses the reviewed JAR's actual SQL splitter and immutable migrations.
Install using DMC's New Stored Procedure object editor, never the ordinary SQL window
(it splits semicolons). CALL is a separate SQL-window operation. No credentials/GRANTs.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATABASE = 'uni_pilates_test'
VERSIONS = ['V001__structure.sql', 'V002__base.sql', 'V003__delivery.sql']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def split_with_reviewed_jar(bundle, files):
    # Reuse Java's implementation, including comment/quote semantics and step counts.
    source = '''import java.nio.file.*; import java.util.*;
import com.yoyi.pilates.infra.tools.DatabaseTool;
public class DmcSplit {
 public static void main(String[] args) throws Exception {
  for(String arg:args) {
   System.out.println("FILE");
   for(String s:DatabaseTool.split(Files.readString(Path.of(arg))))
    System.out.println(Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
 }
}'''
    with tempfile.TemporaryDirectory(prefix='pilates-dmc-split-') as directory:
        tmp = pathlib.Path(directory)
        with zipfile.ZipFile(bundle / 'java-upload/pilates-server.jar') as jar:
            entry = 'com/yoyi/pilates/infra/tools/DatabaseTool.class'
            target = tmp / entry
            target.parent.mkdir(parents=True)
            target.write_bytes(jar.read('BOOT-INF/classes/' + entry))
        helper = tmp / 'DmcSplit.java'
        helper.write_text(source)
        subprocess.run(['javac', '--release', '17', '-cp', directory, str(helper)], check=True)
        lines = subprocess.check_output(['java', '-cp', directory, 'DmcSplit', *map(str, files)], text=True).splitlines()
    groups = []
    for line in lines:
        if line == 'FILE':
            groups.append([])
        else:
            groups[-1].append(base64.b64decode(line).decode('utf-8'))
    if len(groups) != len(files) or not all(groups):
        raise ValueError('invalid SQL splitter output')
    return groups


def generate(bundle, uuid, version, output):
    if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', uuid):
        raise ValueError('a confirmed lowercase server UUID is required')
    if not re.fullmatch(r'8\.0\.\d+(?:[a-zA-Z0-9.-]*)', version):
        raise ValueError('an explicit MySQL 8.0 full version is required')
    bundle = bundle.resolve()
    subprocess.run(['python3', str(ROOT / 'scripts/cloudrun/verify.py'), '--bundle', str(bundle)], check=True)
    manifest = json.loads((bundle / 'manifest.json').read_text())
    files = [bundle / 'database/production' / name for name in VERSIONS]
    for file in files:
        if sha(file) != manifest['files']['database/production/' + file.name]:
            raise ValueError('migration checksum mismatch')
    statements = split_with_reviewed_jar(bundle, files)
    migrations = [{'version': p.name, 'checksum': sha(p), 'totalSteps': len(parts)} for p, parts in zip(files, statements)]
    identity = hashlib.sha256(json.dumps([uuid, version, migrations], sort_keys=True).encode()).hexdigest()[:12]
    routine = 'cloudrun_init_' + identity
    qualified = '`' + DATABASE + '`.`' + routine + '`'
    matching = ' OR '.join(
        "(version='{version}' AND checksum='{checksum}' AND state='DONE' AND applied_steps={totalSteps} AND total_steps={totalSteps})".format(**m)
        for m in migrations
    )
    lines = [f'''CREATE PROCEDURE {qualified}(IN confirmed_uuid CHAR(36))
SQL SECURITY INVOKER
main: BEGIN
 DECLARE migration_locked INT DEFAULT 0;
 DECLARE table_count INT DEFAULT 0;
 DECLARE tracked_count INT DEFAULT 0;
 DECLARE matched_count INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  ROLLBACK;
  IF migration_locked=1 THEN DO RELEASE_LOCK('pilates-schema-{DATABASE}'); END IF;
  RESIGNAL;
 END;
 IF @@server_uuid <> '{uuid}' OR confirmed_uuid IS NULL OR confirmed_uuid <> '{uuid}'
    OR VERSION() <> '{version}' OR DATABASE() <> '{DATABASE}' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wrong isolated target or full version';
 END IF;
 IF @@session.autocommit<>1 OR
    (FIND_IN_SET('STRICT_TRANS_TABLES',@@session.sql_mode)=0 AND FIND_IN_SET('STRICT_ALL_TABLES',@@session.sql_mode)=0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: autocommit and strict mode required';
 END IF;
 IF (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{DATABASE}'
     AND default_character_set_name='utf8mb4' AND default_collation_name='utf8mb4_0900_ai_ci')<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wrong database charset/collation';
 END IF;
 SET migration_locked=GET_LOCK('pilates-schema-{DATABASE}',0);
 IF migration_locked IS NULL OR migration_locked<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: migration lock unavailable';
 END IF;
 SET SESSION time_zone='+08:00';
 SELECT COUNT(*) INTO table_count FROM information_schema.tables WHERE table_schema='{DATABASE}';
 SELECT COUNT(*) INTO tracked_count FROM information_schema.tables WHERE table_schema='{DATABASE}' AND table_name='schema_history';
 IF tracked_count=1 THEN
  SELECT COUNT(*) INTO tracked_count FROM schema_history;
  SELECT COUNT(*) INTO matched_count FROM schema_history WHERE {matching};
  IF tracked_count=3 AND matched_count=3 THEN
   DO RELEASE_LOCK('pilates-schema-{DATABASE}');
   SET migration_locked=0;
   SELECT 'SKIP: three identical DONE migrations' AS result;
   LEAVE main;
  END IF;
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: partial, dirty, changed or unknown schema history; review manually';
 END IF;
 IF table_count<>0 OR (SELECT COUNT(*) FROM information_schema.events WHERE event_schema='{DATABASE}')<>0
    OR (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='{DATABASE}')<>0
    OR (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='{DATABASE}' AND routine_name<>'{routine}')<>0 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: nonempty unversioned database';
 END IF;
 CREATE TABLE schema_history(version VARCHAR(100) PRIMARY KEY,checksum CHAR(64) NOT NULL,state VARCHAR(20) NOT NULL,applied_steps INT NOT NULL DEFAULT 0,total_steps INT NOT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP);
''']
    for migration, parts in zip(migrations, statements):
        name, checksum, total = migration['version'], migration['checksum'], migration['totalSteps']
        if name.startswith('V003'):
            lines.append("IF EXISTS(SELECT 1 FROM notification WHERE biz_id IS NOT NULL AND biz_type IS NOT NULL GROUP BY user_id,biz_type,biz_id,deleted HAVING COUNT(*)>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: duplicate notification business keys'; END IF;")
        lines.append(f"INSERT INTO schema_history(version,checksum,state,applied_steps,total_steps) VALUES('{name}','{checksum}','DIRTY',0,{total});\nCOMMIT;")
        if name.startswith('V002'):
            lines.append('START TRANSACTION;')
        for index, statement in enumerate(parts, 1):
            lines.append(statement + ';')
            lines.append(f"UPDATE schema_history SET applied_steps={index} WHERE version='{name}';")
        lines.append(f"UPDATE schema_history SET state='DONE' WHERE version='{name}';\nCOMMIT;")
    lines.append(f"DO RELEASE_LOCK('pilates-schema-{DATABASE}');\nSET migration_locked=0;\nSELECT 'APPLIED: three reviewed migrations' AS result;\nEND;")
    install = '\n'.join(lines) + '\n'
    # Do not emit destructive cleanup, credentials, public access or GRANTs.
    output.mkdir(parents=True, exist_ok=False)
    (output / '01-install-routine.sql').write_text(install)
    (output / '02-call.sql').write_text(f"CALL {qualified}('{uuid}');\n")
    checks = f'''SELECT VERSION() AS full_version, @@server_uuid AS server_uuid;
SELECT version,checksum,state,applied_steps,total_steps FROM `{DATABASE}`.schema_history ORDER BY version;
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='{DATABASE}';
SELECT config_value AS payment_mode FROM `{DATABASE}`.system_config WHERE config_key='booking.paymentMode';
SELECT 'sys_user' AS entity,COUNT(*) AS actual_count FROM `{DATABASE}`.sys_user
UNION ALL SELECT 'user',COUNT(*) FROM `{DATABASE}`.`user`
UNION ALL SELECT 'member',COUNT(*) FROM `{DATABASE}`.member
UNION ALL SELECT 'course',COUNT(*) FROM `{DATABASE}`.course
UNION ALL SELECT 'course_schedule',COUNT(*) FROM `{DATABASE}`.course_schedule
UNION ALL SELECT 'booking',COUNT(*) FROM `{DATABASE}`.booking;
SHOW CREATE TABLE `{DATABASE}`.booking;
'''
    (output / '03-readonly.sql').write_text(checks)
    meta = {'type': 'DMC-test-only-initializer', 'database': DATABASE, 'serverUuid': uuid,
            'fullVersion': version, 'routine': routine, 'migrations': migrations,
            'sourceBundle': str(bundle), 'jarSha256': manifest['jarSha256'],
            'installationEntry': 'DMC New Stored Procedure object editor; not ordinary SQL window',
            'freshOrIdenticalDoneOnly': True, 'credentialsIncluded': False,
            'files': {p.name: sha(p) for p in sorted(output.iterdir())}}
    (output / 'manifest.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
    print('GENERATED', output)
    return meta


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=pathlib.Path)
    p.add_argument('--expect-server-uuid', required=True)
    p.add_argument('--full-version', required=True)
    p.add_argument('--confirm-isolated-database', required=True, choices=[DATABASE])
    p.add_argument('--output', required=True, type=pathlib.Path)
    a = p.parse_args()
    generate(a.bundle, a.expect_server_uuid, a.full_version, a.output)
