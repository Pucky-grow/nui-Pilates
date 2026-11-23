-- Read-only production preflight; do not execute migrations when any anomaly rule is nonzero.
-- 仅 SELECT / SHOW；不启动事务、不改会话配置、不输出会员资料；异常只输出规则和数量。
SELECT DATABASE() database_name, VERSION() version, @@version_comment product_comment,
 @@character_set_server charset_name, @@collation_server collation_name,
 @@sql_mode sql_mode, @@transaction_isolation transaction_isolation,
 @@global.time_zone global_time_zone, @@session.time_zone session_time_zone, @@system_time_zone system_time_zone;
SHOW CREATE TABLE booking;
SHOW INDEX FROM booking;
SELECT 'duplicate_occupied_user_schedule' rule,COUNT(*) anomaly_count FROM (
 SELECT user_id,schedule_id FROM booking WHERE deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')
 GROUP BY user_id,schedule_id HAVING COUNT(*)>1) x;
SELECT 'capacity_or_count_mismatch' rule,COUNT(*) anomaly_count FROM (
 SELECT s.id FROM course_schedule s LEFT JOIN booking b ON b.schedule_id=s.id AND b.deleted=0
 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW') WHERE s.deleted=0
 GROUP BY s.id,s.booked_count,s.max_capacity
 HAVING COUNT(b.id)<>s.booked_count OR COUNT(b.id)>s.max_capacity OR s.max_capacity<1 OR s.booked_count<0) x;
SELECT 'cancelled_schedule_has_live_bookings' rule,COUNT(*) anomaly_count FROM booking b JOIN course_schedule s ON s.id=b.schedule_id
 WHERE b.deleted=0 AND s.deleted=0 AND s.status='CANCELLED' AND b.status IN ('BOOKED','SIGNED_IN');
SELECT 'booking_orphan_or_deleted_parent' rule,COUNT(*) anomaly_count FROM booking b
 LEFT JOIN course_schedule s ON s.id=b.schedule_id LEFT JOIN `user` u ON u.id=b.user_id
 WHERE b.deleted=0 AND (s.id IS NULL OR u.id IS NULL OR s.deleted<>0 OR u.deleted<>0);
SELECT 'invalid_booking_status' rule,COUNT(*) anomaly_count FROM booking WHERE CAST(status AS BINARY) NOT IN
 ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW','CANCELLED','REFUNDED') OR status IS NULL;
SELECT 'invalid_schedule_status' rule,COUNT(*) anomaly_count FROM course_schedule WHERE deleted=0 AND CAST(status AS BINARY) NOT IN ('SCHEDULED','CANCELLED','FINISHED');
SELECT 'attendance_missing_or_duplicate' rule,COUNT(*) anomaly_count FROM (
 SELECT b.id,b.status FROM booking b LEFT JOIN attendance a ON a.booking_id=b.id AND a.deleted=0
 WHERE b.deleted=0 GROUP BY b.id,b.status
 HAVING (b.status IN ('SIGNED_IN','COMPLETED') AND COUNT(a.id)<>1)
 OR (b.status IN ('BOOKED','CANCELLED','NO_SHOW') AND COUNT(a.id)>0)) x;
SELECT 'attendance_orphan_or_identity_mismatch' rule,COUNT(*) anomaly_count FROM attendance a LEFT JOIN booking b ON b.id=a.booking_id
 WHERE a.deleted=0 AND (b.id IS NULL OR b.deleted<>0 OR a.user_id<>b.user_id OR a.schedule_id<>b.schedule_id);
SELECT 'future_schedule_missing_qualification' rule,COUNT(*) anomaly_count FROM course_schedule s LEFT JOIN course_coach q
 ON q.course_id=s.course_id AND q.coach_id=s.coach_id AND q.deleted=0 AND q.status='APPROVED'
 WHERE s.deleted=0 AND s.status='SCHEDULED' AND s.course_date>=CURRENT_DATE AND q.id IS NULL;
SELECT 'schedule_orphan_or_invalid_time' rule,COUNT(*) anomaly_count FROM course_schedule s
 LEFT JOIN course c ON c.id=s.course_id LEFT JOIN coach co ON co.id=s.coach_id LEFT JOIN classroom r ON r.id=s.classroom_id
 WHERE s.deleted=0 AND (c.id IS NULL OR co.id IS NULL OR r.id IS NULL OR s.start_time>=s.end_time OR s.max_capacity>r.capacity);
SELECT 'permission_mapping_orphans' rule,COUNT(*) anomaly_count FROM sys_role_permission rp
 LEFT JOIN sys_role r ON r.id=rp.role_id LEFT JOIN sys_permission p ON p.id=rp.permission_id
 WHERE rp.deleted=0 AND (r.id IS NULL OR p.id IS NULL OR r.deleted<>0 OR p.deleted<>0);
SELECT 'role_code_duplicates' rule,COUNT(*) anomaly_count FROM (SELECT code FROM sys_role WHERE deleted=0 GROUP BY code HAVING COUNT(*)>1) x;
SELECT 'soft_deleted_target_mappings' rule,COUNT(*) anomaly_count FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id
 JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.deleted<>0 AND r.code IN ('ADMIN','STORE_MANAGER','COACH')
 AND p.code IN ('schedule:view','schedule:create','schedule:update','schedule:cancel','manager:course:coach:manage','coach:schedule:view','coach:schedule:create','coach:schedule:update','coach:schedule:cancel');
SELECT 'soft_deleted_target_permissions' rule,COUNT(*) anomaly_count FROM sys_permission WHERE deleted<>0 AND code IN
 ('manager:course:coach:manage','coach:schedule:create','coach:schedule:update','coach:schedule:cancel');
SELECT r.code role_code,r.status,COUNT(DISTINCT r.id) role_count FROM sys_role r WHERE r.deleted=0 GROUP BY r.code,r.status;
SELECT r.code role_code,p.code permission_code FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id AND rp.deleted=0
 JOIN sys_permission p ON p.id=rp.permission_id AND p.deleted=0 WHERE r.deleted=0 ORDER BY r.code,p.code;
SELECT config_key,config_value FROM system_config WHERE deleted=0 AND config_key IN
 ('booking.paymentMode','booking.max_per_day','booking.advance_days','booking.deadline_minutes');
SELECT 'old_unique_rollback_conflicts' rule,COUNT(*) anomaly_count FROM
 (SELECT user_id,schedule_id,deleted FROM booking GROUP BY user_id,schedule_id,deleted HAVING COUNT(*)>1) x;

SELECT 'noncanonical_logical_delete_flags' rule,(SELECT COUNT(*) FROM booking WHERE deleted NOT IN (0,1))+(SELECT COUNT(*) FROM course_schedule WHERE deleted NOT IN (0,1)) anomaly_count;
SELECT 'missing_normal_member_level' rule,IF(COUNT(*)=1,0,1) anomaly_count FROM member_level WHERE deleted=0 AND code='NORMAL' AND status='ACTIVE';
SELECT 'staff_or_coach_financial_grants' rule,COUNT(*) anomaly_count FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id AND rp.deleted=0 JOIN sys_permission p ON p.id=rp.permission_id AND p.deleted=0
 WHERE r.deleted=0 AND r.code IN ('STAFF','COACH') AND p.code IN ('statistics:view','finance:view','order:refund');

SELECT VERSION(),@@version_comment,@@sql_mode,@@transaction_isolation,@@session.time_zone;
SELECT 'duplicate_notification_business',COUNT(*) FROM (
 SELECT user_id,biz_type,biz_id,deleted FROM notification WHERE biz_type IS NOT NULL AND biz_id IS NOT NULL
 GROUP BY user_id,biz_type,biz_id,deleted HAVING COUNT(*)>1) duplicates;
SELECT 'existing_demo_account_names',COUNT(*) FROM sys_user WHERE username IN ('admin','coach01','manager','staff') AND deleted=0;
SELECT 'unknown_booking_status',COUNT(*) FROM booking WHERE deleted=0 AND status NOT IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW','CANCELLED','REFUNDED');
SELECT index_name,column_name,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='booking';
SELECT 'production_extension_tables',COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('event_outbox','file_object','notification_delivery');
