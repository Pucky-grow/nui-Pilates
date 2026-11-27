#!/usr/bin/env python3
"""Validate migrations in an owned, disposable MySQL datadir/socket, never an existing server."""
import argparse, json, pathlib, shutil, subprocess, tempfile, time
root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--report', type=pathlib.Path, default=root/'server/target-round2/mysql-migration.json')
args = parser.parse_args()
server, client = shutil.which('mysqld'), shutil.which('mysql')
if not server or not client: raise SystemExit('mysqld/mysql are required; no existing server will be used')
version = subprocess.check_output([server, '--no-defaults', '--version'], text=True).strip()
cases = []
with tempfile.TemporaryDirectory(prefix='pilates-mysql-') as directory:
    temp = pathlib.Path(directory); data = temp / 'data'; sock = temp / 'mysql.sock'
    initialize = subprocess.run([server, '--no-defaults', '--initialize-insecure', '--datadir='+str(data)], capture_output=True, text=True, timeout=120)
    if initialize.returncode: raise SystemExit(initialize.stderr)
    process = subprocess.Popen([server, '--no-defaults', '--datadir='+str(data), '--socket='+str(sock), '--skip-networking', '--loose-mysqlx=OFF', '--innodb-buffer-pool-size=32M', '--log-error='+str(temp/'error.log')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    command = [client, '--no-defaults', '--protocol=SOCKET', '--socket='+str(sock), '-uroot', '--batch', '--skip-column-names']
    try:
        for _ in range(200):
            if process.poll() is not None: raise RuntimeError((temp/'error.log').read_text())
            ping = subprocess.run(command+['-e','SELECT 1'], capture_output=True, text=True)
            if ping.returncode == 0: break
            time.sleep(.1)
        else: raise RuntimeError('temporary MySQL startup timeout')
        def sql(statement, success=True):
            result = subprocess.run(command, input=statement, capture_output=True, text=True)
            if success and result.returncode: raise AssertionError(result.stderr)
            if not success and (result.returncode == 0 or '1062' not in result.stderr): raise AssertionError('Expected duplicate-key rejection: '+result.stderr)
            return result.stdout.strip()
        assert pathlib.Path(sql('SELECT @@datadir;')).resolve() == data.resolve()
        demo = (root/'database/init_data.sql').read_text()
        sql((root/'database/schema.sql').read_text());sql(demo);cases.append('fresh full schema and initialization')
        assert sql('USE yoyi_pilates; SELECT COUNT(*) FROM sys_user;') == '0'
        sql('CREATE DATABASE fixture_baseline CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        sql('USE fixture_baseline;\n'+(root/'database/production/V001__structure.sql').read_text())
        sql('USE fixture_baseline;\n'+(root/'database/production/V002__base.sql').read_text())
        grants = """SELECT r.code,p.code FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id
                    JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.deleted=0 ORDER BY r.code,p.code;"""
        assert sql('USE yoyi_pilates;'+grants) == sql('USE fixture_baseline;'+grants)
        cases.append('demo accounts absent and role grants match V002')
        snapshot = 'USE yoyi_pilates;\n' + '\n'.join('SELECT COUNT(*) FROM `'+table+'`;' for table in (
            'course_category', 'classroom', 'coach', 'coach_certificate', 'course', 'course_schedule', 'package',
            'package_item', 'coupon', 'banner', 'article_category', 'article', 'system_config', 'sys_role_permission'))
        before = sql(snapshot)
        sql("USE yoyi_pilates; UPDATE course_schedule SET course_date='2020-01-01' WHERE id=1;")
        sql(demo)
        assert sql(snapshot) == before
        assert sql('USE yoyi_pilates; SELECT course_date FROM course_schedule WHERE id=1;') == '2020-01-01'
        cases.append('repeat demo seed preserves row counts and existing schedule dates')
        assert sql("USE yoyi_pilates; SELECT config_value FROM system_config WHERE config_key='booking.paymentMode';") == 'FREE'
        sql('USE yoyi_pilates;\n'+(root/'database/migration_round2_permissions.sql').read_text());cases.append('minimal permission migration is repeatable')
        assert sql("USE yoyi_pilates; SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code='STAFF' AND p.code IN ('schedule:create','schedule:update','schedule:cancel');") == '0';cases.append('STAFF receives no scheduling writes')
        sql('USE yoyi_pilates; ALTER TABLE booking DROP INDEX uk_active_user_schedule, DROP COLUMN active_user_id, ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted);')
        sql("USE yoyi_pilates; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('old-A',1,1,0,'BOOKED'); UPDATE booking SET status='CANCELLED' WHERE booking_no='old-A';")
        sql("USE yoyi_pilates; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('old-B',1,1,0,'BOOKED');",False);cases.append('legacy unique key reproduces cancellation/rebooking blocker')
        sql('USE yoyi_pilates;\n'+(root/'database/migration_round2_booking.sql').read_text());cases.append('upgrade DDL executes in disposable MySQL')
        sql("USE yoyi_pilates; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('new-B',1,1,0,'BOOKED');")
        sql("USE yoyi_pilates; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('duplicate',1,1,0,'BOOKED');",False);cases.append('generated unique key blocks a second occupied booking')
        sql("USE yoyi_pilates; UPDATE booking SET status='CANCELLED' WHERE booking_no='new-B'; INSERT INTO booking(booking_no,user_id,schedule_id,credits_used,status) VALUES('new-C',1,1,0,'BOOKED');")
        assert sql("USE yoyi_pilates; SELECT COUNT(*) FROM booking;") == '3'
        assert sql("USE yoyi_pilates; SELECT COUNT(*) FROM booking WHERE active_user_id IS NOT NULL;") == '1';cases.append('multiple cancellations preserve history and one active booking')
        report = {'passed':True,'version':version,'isolated':True,'cases':cases,'productionDatabaseTouched':False}
        output=args.report;output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False))
    finally:
        process.terminate()
        try: process.wait(timeout=30)
        except subprocess.TimeoutExpired: process.kill();process.wait()
