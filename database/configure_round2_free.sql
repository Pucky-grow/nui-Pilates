-- 可选操作脚本；需独立授权后由环境负责人执行，本轮未执行。
-- 先保存：SELECT config_key,config_value FROM system_config WHERE config_key='booking.paymentMode';
-- 回滚只恢复本次保存的原值；不要把历史 PACKAGE 环境误改为 FREE。
INSERT INTO system_config(config_key,config_value,description)
VALUES('booking.paymentMode','FREE','免费预约，无需课包')
ON DUPLICATE KEY UPDATE config_value='FREE';
-- 配置 Redis 缓存有效期 60 秒；等待到期后读取 GET /api/booking-rules 确认。
