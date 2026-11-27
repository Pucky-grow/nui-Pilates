-- ============================================================
-- Phase 8 迁移: 训练记录 booking_id 唯一约束(防并发重复)
-- 业务规则: 一次授课(booking)最多一条有效训练记录
-- 回滚: ALTER TABLE training_record DROP INDEX uk_booking_training_record;
-- ============================================================

-- 1. 存量重复检测(booking_id 非空且未删除), 若返回行需人工处理后才能执行第 2 步
SELECT booking_id, COUNT(*) AS cnt
FROM training_record
WHERE booking_id IS NOT NULL AND deleted = 0
GROUP BY booking_id
HAVING COUNT(*) > 1;

-- 2. 上面无返回时, 执行添加唯一索引
--    说明: MySQL 唯一索引允许多个 NULL, 因此 booking_id 为 NULL 的训练记录不受影响;
--          当前无"删除后重建"流程, deleted 恒为 0, 故直接用 booking_id 做唯一键.
ALTER TABLE training_record
  ADD UNIQUE KEY `uk_booking_training_record` (`booking_id`);
