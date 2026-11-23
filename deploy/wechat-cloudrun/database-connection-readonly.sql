-- Read-only DMC checks. Execute ONE statement at a time and inspect its result.
-- Target: uni-test / Shanghai / cynosdbmysql-41yi5vim / uni_pilates_test.
-- This file does not initialize, migrate, provision accounts, or enable TLS.

-- STOP if any identity differs. Do not continue on a different instance/database.
-- UUID: d1f6adf9-d5d5-11f1-9afc-0c42a17335be
-- Full version: 8.0.30-cynos-3.1.19.002
SELECT DATABASE() AS current_database, VERSION() AS full_version,
       @@version_comment AS version_comment, @@server_uuid AS server_uuid,
       @@session.time_zone AS session_time_zone;

SELECT version, checksum, state, applied_steps, total_steps
FROM uni_pilates_test.schema_history ORDER BY version;

-- Count only: never select mysql.user authentication columns or member details.
-- sys_user_count counts all management accounts, not only ADMIN role members.
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='uni_pilates_test' AND table_type='BASE TABLE') AS table_count,
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='uni_pilates_test'
     AND (engine<>'InnoDB' OR table_collation NOT LIKE 'utf8mb4%')) AS invalid_table_format,
  (SELECT COUNT(*) FROM uni_pilates_test.schema_history WHERE state<>'DONE') AS incomplete_migrations,
  (SELECT config_value FROM uni_pilates_test.system_config
   WHERE config_key='booking.paymentMode' AND deleted=0) AS booking_mode,
  (SELECT COUNT(*) FROM uni_pilates_test.sys_user) AS sys_user_count,
  (SELECT COUNT(*) FROM mysql.user WHERE user='uni_pilates_runtime') AS runtime_account_count,
  @@session.sql_mode AS sql_mode, @@session.transaction_isolation AS isolation_level;

-- Do not infer enabled TLS from tls_version alone.
-- have_ssl=DISABLED blocks the current native endpoint VERIFY_IDENTITY gate.
-- No private key variables/files are read.
SHOW VARIABLES WHERE Variable_name IN
  ('have_ssl','require_secure_transport','ssl_ca','ssl_cert','tls_version');

-- This proves only the DMC server session, never application TLS validation.
SHOW SESSION STATUS LIKE 'Ssl_cipher';
