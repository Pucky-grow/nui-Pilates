-- =====================================================================
-- 二期 Phase 5 权限迁移(幂等): 课程申请/授课资格权限 + 课程教练关联回填
-- =====================================================================
USE yoyi_pilates;

-- 1. 新增权限点
INSERT IGNORE INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`) VALUES
(0,'coach:course:view','教练-查看课程','API',230),
(0,'coach:course:apply','教练-申请授课资格','API',231),
(0,'coach:course:proposal:create','教练-创建课程方案','API',232),
(0,'coach:course:proposal:update','教练-修改课程方案','API',233),
(0,'coach:course:proposal:submit','教练-提交课程方案','API',234),
(0,'manager:course:proposal:view','店长-查看课程申请','API',240),
(0,'manager:course:proposal:approve','店长-审核课程申请','API',241),
(0,'manager:course:proposal:reject','店长-驳回课程申请','API',242),
(0,'manager:course:coach:manage','店长-管理授课教练','API',243),
(0,'admin:course:proposal:manage','管理员-课程申请管理','API',250),
(0,'admin:course:coach:manage','管理员-授课教练管理','API',251);

-- 2. 角色权限(ADMIN 已拥有全部, 无需额外)
-- COACH 增加课程申请/授课资格相关
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='COACH' AND p.code IN
 ('coach:course:view','coach:course:apply','coach:course:proposal:create',
  'coach:course:proposal:update','coach:course:proposal:submit');
-- STORE_MANAGER 增加课程申请审核/授课教练管理
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='STORE_MANAGER' AND p.code IN
 ('manager:course:proposal:view','manager:course:proposal:approve',
  'manager:course:proposal:reject','manager:course:coach:manage');
-- STAFF 增加查看课程
INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM `sys_role` r JOIN `sys_permission` p
WHERE r.code='STAFF' AND p.code='coach:course:view';

-- 3. 回填课程-教练关联(既有排课中 distinct 的 course+coach 视为已获授课资格)
INSERT INTO `course_coach` (`course_id`,`coach_id`,`status`,`qualification_source`,`approved_at`)
SELECT DISTINCT s.course_id, s.coach_id, 'APPROVED', 'ADMIN', NOW()
FROM `course_schedule` s
WHERE NOT EXISTS (SELECT 1 FROM `course_coach` cc WHERE cc.course_id=s.course_id AND cc.coach_id=s.coach_id);
