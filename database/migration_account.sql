-- =====================================================================
-- 优仪普拉提 · 账号权限体系迁移脚本(一次性执行)
-- 将旧 5 角色(超管/店长/运营/教练/财务) 收敛为 3 角色(管理员/教练/学员)
-- 适用: 已导入旧版 init_data.sql 的库
-- =====================================================================
USE yoyi_pilates;

-- 1. coach 表加 openid(教练微信绑定) —— 幂等: 列不存在才加
SET @has_openid = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='yoyi_pilates' AND TABLE_NAME='coach' AND COLUMN_NAME='openid');
SET @sql = IF(@has_openid = 0,
  'ALTER TABLE `coach` ADD COLUMN `openid` VARCHAR(64) DEFAULT NULL COMMENT ''教练绑定的微信 openid'' AFTER `sys_user_id`, ADD KEY `idx_openid` (`openid`)',
  'SELECT ''openid exists, skip''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 清空关联(将按新角色重建)
DELETE FROM `sys_role_permission`;
DELETE FROM `sys_user_role`;

-- 3. 删除多余角色(店长/运营/财务)
DELETE FROM `sys_role` WHERE `code` IN ('STORE_MANAGER','OPERATION','FINANCE');

-- 4. 超级管理员 -> 管理员
UPDATE `sys_role` SET `code`='ADMIN', `name`='管理员', `description`='拥有整个系统的管理权限' WHERE `code`='SUPER_ADMIN';

-- 5. 教练角色名称/描述
UPDATE `sys_role` SET `name`='教练', `description`='课程/排课/学员/训练记录管理' WHERE `code`='COACH';

-- 6. 学员角色
INSERT INTO `sys_role` (`code`,`name`,`description`)
SELECT 'MEMBER','学员','小程序用户端(无后台权限)'
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `code`='MEMBER');

-- 7. 新增教室管理权限点
INSERT INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`)
SELECT 0,'classroom:manage','教室管理','API',85
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code`='classroom:manage');

-- 8. 管理员角色: 全部权限
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p WHERE r.code='ADMIN';

-- 9. 教练角色: 课程/排课/教室/教练查看/看板
--    注意: 不给 member:view(会员列表), 教练通过教练端"我的学员"接口(数据权限)查看学员
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='COACH' AND p.code IN
 ('dashboard:view','course:view','course:create','course:update','course:delete',
  'schedule:view','schedule:create','schedule:update','schedule:cancel',
  'coach:view','classroom:manage');

-- 10. 重建用户-角色: admin->ADMIN, coach01->COACH
INSERT INTO `sys_user_role` (`user_id`,`role_id`)
SELECT u.id, r.id FROM `sys_user` u JOIN `sys_role` r
WHERE (u.username='admin' AND r.code='ADMIN') OR (u.username='coach01' AND r.code='COACH');
