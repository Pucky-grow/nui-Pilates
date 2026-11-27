-- =====================================================================
-- 二期 Phase 6 权限迁移(幂等): 教练门店运营 + 排课 + 课程运营展示
-- =====================================================================
USE yoyi_pilates;

INSERT IGNORE INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`) VALUES
(0,'coach:home:view','教练-首页运营查看','API',260),
(0,'coach:home:banner','教练-Banner管理','API',261),
(0,'coach:home:hot-course','教练-热门课程管理','API',262),
(0,'coach:home:coach-team','教练-首页教练团队管理','API',263),
(0,'coach:home:studio','教练-门店信息管理','API',264),
(0,'coach:home:article','教练-首页文章管理','API',265),
(0,'coach:course:update','教练-课程运营展示编辑','API',266),
(0,'coach:schedule:create','教练-创建排课','API',267),
(0,'coach:schedule:update','教练-编辑排课','API',268),
(0,'coach:schedule:cancel','教练-取消排课','API',269),
(0,'coach:booking:view','教练-查看预约','API',270);

-- COACH 增加门店运营/排课/课程运营权限
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='COACH' AND p.code IN
 ('coach:home:view','coach:home:banner','coach:home:hot-course','coach:home:coach-team',
  'coach:home:studio','coach:home:article','coach:course:update',
  'coach:schedule:create','coach:schedule:update','coach:schedule:cancel','coach:booking:view');

-- ADMIN 补齐(hasPerm 短路, 此处仅为数据一致性)
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='ADMIN' AND p.code LIKE 'coach:home:%' OR (r.code='ADMIN' AND p.code IN ('coach:course:update','coach:schedule:create','coach:schedule:update','coach:schedule:cancel','coach:booking:view'))
AND NOT EXISTS (SELECT 1 FROM `sys_role_permission` x WHERE x.role_id=r.id AND x.permission_id=p.id);

-- FREE 预约模式配置(默认 FREE: 无需课包/不扣课时)
INSERT INTO `system_config` (`config_key`,`config_value`,`description`) VALUES
('booking.paymentMode','FREE','预约模式: FREE无需课包/PACKAGE需课包/MIXED混合')
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value);
