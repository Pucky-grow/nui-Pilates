-- Read-only checks after the UUID-pinned DMC initializer on the isolated test DB.
-- These are SQL/metadata checks, not HTTP, Redis or WeChat acceptance.
SELECT VERSION() AS full_version, @@version_comment AS version_comment,
       @@server_uuid AS server_uuid, @@global.transaction_isolation AS global_isolation,
       @@session.transaction_isolation AS session_isolation,
       @@session.time_zone AS session_time_zone, @@session.autocommit AS autocommit;
SELECT @@default_storage_engine AS default_engine, COUNT(*) AS non_innodb_tables
FROM information_schema.tables WHERE table_schema='uni_pilates_test' AND engine<>'InnoDB';
SELECT version,checksum,state,applied_steps,total_steps,
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='uni_pilates_test') AS table_count,
       (SELECT config_value FROM uni_pilates_test.system_config WHERE config_key='booking.paymentMode') AS payment_mode
FROM uni_pilates_test.schema_history ORDER BY version;
SELECT 'sys_user' AS check_name,COUNT(*) AS actual_count FROM uni_pilates_test.sys_user
UNION ALL SELECT 'user',COUNT(*) FROM uni_pilates_test.`user`
UNION ALL SELECT 'member',COUNT(*) FROM uni_pilates_test.member
UNION ALL SELECT 'coach',COUNT(*) FROM uni_pilates_test.coach
UNION ALL SELECT 'course',COUNT(*) FROM uni_pilates_test.course
UNION ALL SELECT 'course_schedule',COUNT(*) FROM uni_pilates_test.course_schedule
UNION ALL SELECT 'booking',COUNT(*) FROM uni_pilates_test.booking
UNION ALL SELECT 'order',COUNT(*) FROM uni_pilates_test.`order`
UNION ALL SELECT 'non_utf8mb4_tables',COUNT(*) FROM information_schema.tables
 WHERE table_schema='uni_pilates_test' AND table_collation NOT LIKE 'utf8mb4%'
UNION ALL SELECT 'staff_coach_finance_grants',COUNT(*) FROM uni_pilates_test.sys_role_permission rp
 JOIN uni_pilates_test.sys_role r ON r.id=rp.role_id JOIN uni_pilates_test.sys_permission p ON p.id=rp.permission_id
 WHERE r.code IN ('STAFF','COACH') AND p.code LIKE 'finance:%';
SHOW CREATE TABLE uni_pilates_test.booking;
SELECT id,topic,payload FROM uni_pilates_test.event_outbox
WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED;
SHOW SESSION STATUS LIKE 'Ssl_cipher';
