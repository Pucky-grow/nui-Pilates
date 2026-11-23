-- 最小增量授权；不授予全局首页、经营数据或 STAFF 排课写权限。
-- 现有库须先备份权限映射、审核后人工执行；本轮未执行。
START TRANSACTION;
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES
(0,'manager:course:coach:manage','管理课程授课资格','API',243),
(0,'coach:schedule:create','教练创建本人排课','API',267),
(0,'coach:schedule:update','教练编辑本人排课','API',268),
(0,'coach:schedule:cancel','教练取消本人排课','API',269);
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p
WHERE r.deleted=0 AND r.status='ACTIVE' AND p.deleted=0 AND (
      (r.code IN ('ADMIN','STORE_MANAGER') AND p.code IN ('schedule:view','schedule:create','schedule:update','schedule:cancel','manager:course:coach:manage'))
   OR (r.code='COACH' AND p.code IN ('coach:schedule:view','coach:schedule:create','coach:schedule:update','coach:schedule:cancel')));
-- 不覆盖已存在的收费模式，默认 FREE 仅用于尚未配置的环境。
INSERT IGNORE INTO system_config(config_key,config_value,description)
VALUES('booking.paymentMode','FREE','FREE无需课包；PACKAGE保留原扣课时模式');
COMMIT;
