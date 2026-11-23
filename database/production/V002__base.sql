-- Roles, minimum permissions, FREE rules and templates only. No accounts or business data.
INSERT INTO `member_level` (`code`,`name`,`discount`,`sort`) VALUES
('NORMAL','普通会员',1.00,0),
('SILVER','银卡会员',0.98,1),
('GOLD', '金卡会员',0.95,2),
('VIP',  'VIP会员', 0.90,3);

INSERT INTO `notification_template` (`code`,`name`,`title_tpl`,`content_tpl`) VALUES
('BOOKING_SUCCESS','预约成功','预约成功','您已成功预约{courseDate} {startTime} 的{courseName}, 请合理安排到店时间。'),
('COURSE_REMIND','开课提醒','课程提醒','您预约的{courseName}将在2小时后开始({startTime}), 请提前到店。'),
('COURSE_CANCEL','课程取消','课程取消通知','很抱歉, 您预约的{courseDate} {courseName}因故取消，请在课表查看最新安排。'),
('MEMBER_EXPIRE','会员到期提醒','会员到期提醒','您的会员将于{expireDate}到期, 续费可继续享受会员权益。'),
('CREDITS_LOW','课时不足提醒','课时提醒','您的课包「{packageName}」剩余{remaining}节课, 可及时续购。'),
('ORDER_PAID','支付成功','支付成功','您购买的「{packageName}」已支付成功, 课时已到账。');

INSERT INTO `sys_role` (`code`,`name`,`description`) VALUES
('ADMIN','管理员','拥有整个系统的管理权限'),
('STORE_MANAGER','店长','课程/排课/教练/会员/课包/统计管理(无系统配置/RBAC/财务)'),
('STAFF','前台','会员/预约/签到/订单查询/代预约'),
('COACH','教练','查看自己排课/学员 + 训练记录 + 请假/调课申请'),
('MEMBER','学员','小程序用户端(无后台权限)');

INSERT INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`) VALUES
(0,'dashboard:view','数据看板','MENU',1),
(0,'course:view','课程查看','API',10),(0,'course:create','课程新增','API',11),
(0,'course:update','课程编辑','API',12),(0,'course:delete','课程删除','API',13),
(0,'schedule:view','排课查看','API',20),(0,'schedule:create','排课新增','API',21),
(0,'schedule:update','排课编辑','API',22),(0,'schedule:cancel','排课取消','API',23),
(0,'coach:view','教练查看','API',30),(0,'coach:manage','教练管理','API',31),
(0,'member:view','会员查看','API',40),(0,'member:update','会员编辑','API',41),
(0,'member:adjust','会员调课时','API',42),
(0,'package:view','课包查看','API',50),(0,'package:manage','课包管理','API',51),
(0,'order:view','订单查看','API',60),(0,'order:refund','订单退款','API',61),
(0,'coupon:manage','优惠券管理','API',70),
(0,'content:manage','内容管理','API',80),
(0,'classroom:manage','教室管理','API',85),
(0,'finance:view','财务查看','API',90),(0,'finance:export','财务导出','API',91),
(0,'statistics:view','统计查看','API',100),
(0,'system:manage','系统管理','API',110),
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

INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='ADMIN';

INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='STORE_MANAGER' AND p.code IN
 ('dashboard:view','course:view','course:create','course:update','course:delete',
  'schedule:view','schedule:create','schedule:update','schedule:cancel',
  'coach:view','coach:manage','member:view','member:update','member:adjust',
  'package:view','package:manage','order:view','coupon:manage',
  'content:manage','classroom:manage','statistics:view');

INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='STAFF' AND p.code IN
 ('dashboard:view','course:view','schedule:view','member:view','order:view',
  'staff:member:view','staff:booking:view','staff:booking:create','staff:attendance:create');

INSERT INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='COACH' AND p.code IN
 ('dashboard:view','coach:view',
  'coach:schedule:view','coach:student:view','coach:training:create','coach:training:view',
  'coach:leave:create','coach:leave:view','coach:adjustment:create');

INSERT INTO `system_config` (`config_key`,`config_value`,`description`) VALUES
('booking.cancel_free_hours','4','开课前N小时可免费取消'),
('booking.cancel_late_deduct','1','超过免费取消时限取消时扣除课时数(0=不允许取消)'),
('booking.deadline_minutes','30','开课前N分钟停止预约'),
('booking.max_per_day','3','每人每日最大预约节数'),
('booking.advance_days','7','最早提前N天预约'),
('booking.late_minutes','15','开课后超过N分钟签到记为迟到'),
('points.register','50','注册赠送积分'),
('points.per_yuan','1','每消费1元获得积分'),
('points.review','20','完成评价获得积分'),
('points.checkin','5','签到获得积分'),
('points.deduct_rate','100','积分抵扣比例: N积分=1元'),
('member.expire_remind_days','7,3','会员到期提醒天数'),
('credits.low_remind','3,1','课时不足提醒阈值');

INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES
(0,'manager:course:coach:manage','管理课程授课资格','API',243),
(0,'coach:schedule:create','教练创建本人排课','API',267),
(0,'coach:schedule:update','教练编辑本人排课','API',268),
(0,'coach:schedule:cancel','教练取消本人排课','API',269);

INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p
WHERE (r.code IN ('ADMIN','STORE_MANAGER') AND p.code IN ('schedule:view','schedule:create','schedule:update','schedule:cancel','manager:course:coach:manage'))
   OR (r.code='COACH' AND p.code IN ('coach:schedule:view','coach:schedule:create','coach:schedule:update','coach:schedule:cancel'));

INSERT IGNORE INTO system_config(config_key,config_value,description)
VALUES('booking.paymentMode','FREE','FREE无需课包；PACKAGE保留原扣课时模式');

INSERT INTO system_config(config_key,config_value,description) VALUES('studio.configured','false','门店经营资料尚待经营者确认');

-- Current endpoint permission vocabulary, no automatic grant to STAFF/COACH.
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES
(0,'coach:course:view','教练查看本人课程与方案','API',230),
(0,'coach:course:apply','教练申请授课资格','API',231),
(0,'coach:course:proposal:create','教练创建本人方案','API',232),
(0,'coach:course:proposal:update','教练修改本人方案','API',233),
(0,'coach:course:proposal:submit','教练提交本人方案','API',234),
(0,'coach:course:update','教练编辑可授课课程展示','API',266);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'booking:manage','booking:manage','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'coach:home:banner','coach:home:banner','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'coach:home:coach-team','coach:home:coach-team','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'coach:home:hot-course','coach:home:hot-course','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'coach:home:studio','coach:home:studio','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'coach:home:view','coach:home:view','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'manager:course:proposal:approve','manager:course:proposal:approve','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'manager:course:proposal:reject','manager:course:proposal:reject','API',300);
INSERT IGNORE INTO sys_permission(parent_id,code,name,type,sort) VALUES(0,'manager:course:proposal:view','manager:course:proposal:view','API',300);
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p WHERE
(r.code='ADMIN') OR
(r.code='STORE_MANAGER' AND p.code IN ('manager:course:proposal:view','manager:course:proposal:approve','manager:course:proposal:reject')) OR
(r.code='COACH' AND p.code IN ('coach:course:view','coach:course:apply','coach:course:proposal:create','coach:course:proposal:update','coach:course:proposal:submit','coach:course:update'));
