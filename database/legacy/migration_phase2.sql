-- =====================================================================
-- 优仪普拉提 · 二期 Phase 1 权限体系迁移(幂等, 可重复执行)
-- 角色扩展: ADMIN/STORE_MANAGER/STAFF/COACH/MEMBER
-- 教练排课权收窄: 普通教练不能建/改/取消排课, 只能请假/调课申请
-- =====================================================================
USE yoyi_pilates;

-- 1. 角色(5个)
INSERT INTO `sys_role` (`code`,`name`,`description`)
SELECT 'STORE_MANAGER','店长','课程/排课/教练/会员/课包/统计管理(无系统配置/RBAC/财务)'
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `code`='STORE_MANAGER');
INSERT INTO `sys_role` (`code`,`name`,`description`)
SELECT 'STAFF','前台','会员/预约/签到/订单查询/代预约'
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `code`='STAFF');
INSERT INTO `sys_role` (`code`,`name`,`description`)
SELECT 'MEMBER','学员','小程序用户端(无后台权限)'
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `code`='MEMBER');

-- 2. 细粒度权限点(教练/前台/店长) —— INSERT IGNORE 幂等
INSERT IGNORE INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`) VALUES
(0,'coach:schedule:view','教练-我的排课查看','API',200),
(0,'coach:student:view','教练-学员查看','API',201),
(0,'coach:training:create','教练-填写训练记录','API',202),
(0,'coach:training:view','教练-查看训练记录','API',203),
(0,'coach:leave:create','教练-发起请假','API',204),
(0,'coach:leave:view','教练-查看请假','API',205),
(0,'coach:adjustment:create','教练-提交调课申请','API',206),
(0,'staff:member:view','前台-会员查看','API',210),
(0,'staff:booking:view','前台-预约查看','API',211),
(0,'staff:booking:create','前台-代预约','API',212),
(0,'staff:attendance:create','前台-手工签到','API',213),
(0,'manager:course:view','店长-课程查看','API',220),
(0,'manager:schedule:manage','店长-排课管理','API',221),
(0,'manager:coach:view','店长-教练查看','API',222),
(0,'manager:member:view','店长-会员查看','API',223),
(0,'manager:statistics:view','店长-统计查看','API',224);

-- 3. 重建角色-权限映射
DELETE FROM `sys_role_permission`;

-- ADMIN: 全部权限
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p WHERE r.code='ADMIN';

-- STORE_MANAGER(店长): 课程/排课/教练/会员/课包/订单/券/内容/教室/统计(无 system/finance)
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='STORE_MANAGER' AND p.code IN
 ('dashboard:view','course:view','course:create','course:update','course:delete',
  'schedule:view','schedule:create','schedule:update','schedule:cancel',
  'coach:view','coach:manage','member:view','member:update','member:adjust',
  'package:view','package:manage','order:view','order:refund','coupon:manage',
  'content:manage','classroom:manage','statistics:view');

-- STAFF(前台): 会员/预约/签到/订单查询/课程查询
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='STAFF' AND p.code IN
 ('dashboard:view','course:view','schedule:view','member:view','order:view',
  'staff:member:view','staff:booking:view','staff:booking:create','staff:attendance:create');

-- COACH(教练): 收窄 —— 仅看自己排课/学员 + 训练记录 + 请假 + 调课申请
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='COACH' AND p.code IN
 ('dashboard:view','coach:view',
  'coach:schedule:view','coach:student:view','coach:training:create','coach:training:view',
  'coach:leave:create','coach:leave:view','coach:adjustment:create');

-- 4. 演示账号(店长/前台)
INSERT INTO `sys_user` (`username`,`password`,`name`,`phone`,`status`)
SELECT 'manager','$2y$10$cvMEm0.L5IOFWRe1hFvtjOtW1lvrjm5RhSB3tZea3z.Ps8T/pftxO','张店长','13800000002','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username`='manager');
INSERT INTO `sys_user` (`username`,`password`,`name`,`phone`,`status`)
SELECT 'staff','$2y$10$cvMEm0.L5IOFWRe1hFvtjOtW1lvrjm5RhSB3tZea3z.Ps8T/pftxO','李前台','13800000003','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username`='staff');

-- 5. 用户-角色: manager->STORE_MANAGER, staff->STAFF
INSERT INTO `sys_user_role` (`user_id`,`role_id`)
SELECT u.id, r.id FROM `sys_user` u JOIN `sys_role` r
WHERE ((u.username='manager' AND r.code='STORE_MANAGER')
    OR (u.username='staff' AND r.code='STAFF'))
  AND NOT EXISTS (SELECT 1 FROM `sys_user_role` x WHERE x.user_id=u.id AND x.role_id=r.id);
