-- MySQL 8; 仅供维护窗口人工审批执行。本轮未执行。
-- 前置：先运行 check_round2_booking.sql，确认正确库、旧索引、无有效重复、容量一致。
-- 停止所有旧版本写入并备份。DDL 隐式提交，不与应用混合滚动发布。
-- 若索引/列已存在或旧键不同，应停止核对，不要盲目重跑。
ALTER TABLE booking
  ADD COLUMN active_user_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW') THEN user_id ELSE NULL END) STORED,
  DROP INDEX uk_user_schedule,
  ADD UNIQUE KEY uk_active_user_schedule (active_user_id,schedule_id);
-- CANCELLED/REFUNDED 历史行生成 NULL；MySQL 允许唯一键中的多个 NULL。
-- 所有历史 booking.id、booking_no、attendance 和课时流水关联保持原样。
