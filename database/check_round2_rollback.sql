-- READ ONLY. 只有以下查询为零行，才可能恢复旧唯一键。
SELECT user_id,schedule_id,deleted,COUNT(*) AS history_count
FROM booking GROUP BY user_id,schedule_id,deleted HAVING COUNT(*)>1;
-- 新版允许多次取消重约后通常会有结果，不能无损恢复旧键。
-- 不允许为回滚而删除历史、签到或流水；优先向前修复。
-- 若确需回退，停写并保留新约束，使用兼容该约束的代码版本；不得直接运行旧版。

-- 仅当上方无重复、已停写、已备份并另行获批时，才可手工考虑以下 DDL：
-- ALTER TABLE booking DROP INDEX uk_active_user_schedule, DROP COLUMN active_user_id,
--   ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted);
