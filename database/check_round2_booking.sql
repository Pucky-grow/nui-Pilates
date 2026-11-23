-- READ ONLY. 在计划升级的库执行并保存输出；本轮未连接真实数据库。
SELECT DATABASE() AS database_name, VERSION() AS mysql_version;
SHOW CREATE TABLE booking;
SHOW INDEX FROM booking;
-- 必须为零行：生成有效预约唯一键前检查重复。
SELECT user_id, schedule_id, COUNT(*) AS active_count
FROM booking WHERE deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')
GROUP BY user_id,schedule_id HAVING COUNT(*)>1;
-- 必须人工核对：人数与全部未取消历史占位不一致。
SELECT s.id,s.status,s.max_capacity,s.booked_count,COUNT(b.id) AS actual_count
FROM course_schedule s LEFT JOIN booking b ON b.schedule_id=s.id AND b.deleted=0
 AND b.status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')
WHERE s.deleted=0 GROUP BY s.id,s.status,s.max_capacity,s.booked_count
HAVING s.booked_count<>COUNT(b.id) OR COUNT(b.id)>s.max_capacity;
-- 尚未建立资格的未来课，不能据此自动给教练授权。
SELECT s.id,s.course_id,s.coach_id,s.course_date
FROM course_schedule s LEFT JOIN course_coach cc ON cc.course_id=s.course_id AND cc.coach_id=s.coach_id AND cc.deleted=0 AND cc.status='APPROVED'
WHERE s.deleted=0 AND s.status='SCHEDULED' AND s.course_date>=CURRENT_DATE AND cc.id IS NULL;
SELECT config_key,config_value FROM system_config WHERE deleted=0 AND config_key LIKE 'booking.%';

-- 必须为零行或先人工处置：升级前遗留的异步退课不能带入新版。
SELECT b.id,b.user_id,b.schedule_id,b.user_package_id,b.credits_used
FROM booking b JOIN course_schedule s ON s.id=b.schedule_id
WHERE b.deleted=0 AND s.deleted=0 AND s.status='CANCELLED' AND b.status IN ('BOOKED','SIGNED_IN');
SELECT r.code AS role_code,p.code AS permission_code FROM sys_role r
JOIN sys_role_permission rp ON rp.role_id=r.id AND rp.deleted=0
JOIN sys_permission p ON p.id=rp.permission_id AND p.deleted=0
WHERE r.deleted=0 AND (r.code IN ('ADMIN','STORE_MANAGER','COACH') OR (r.code='STAFF' AND p.code IN ('schedule:create','schedule:update','schedule:cancel')))
ORDER BY r.code,p.code;
