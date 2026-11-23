-- Read-only. Use the SQL window of this isolated test instance only.
-- Select the newly created uni_pilates_test database before the second group.
-- Server UUID, full product/version and parameters are evidence; do not copy passwords.
SELECT VERSION() AS full_version, @@version_comment AS version_comment,
       @@server_uuid AS server_uuid,
       @@character_set_server AS character_set_server,
       @@collation_server AS collation_server,
       @@global.sql_mode AS global_sql_mode,
       @@global.transaction_isolation AS global_isolation,
       @@global.time_zone AS global_time_zone,
       @@system_time_zone AS system_time_zone;
SELECT DATABASE() AS selected_database,
       @@session.sql_mode AS session_sql_mode,
       @@session.transaction_isolation AS session_isolation,
       @@session.time_zone AS session_time_zone;
SHOW SESSION STATUS LIKE 'Ssl_cipher';
SELECT COUNT(*) AS existing_table_count
FROM information_schema.tables
WHERE table_schema = 'uni_pilates_test';
-- Expected before initialization: existing_table_count=0.
-- The tool will also reject a nonempty, unversioned target. No automatic history deletion.
