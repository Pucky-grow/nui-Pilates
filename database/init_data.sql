-- 优仪普拉提 · 本机演示数据
-- 先导入 schema.sql。本文件供本机演示库初始化使用。
-- 重复导入保留已有记录和排课日期。后台账号由本机启动器生成独立口令。
USE yoyi_pilates;

START TRANSACTION;

-- 基线角色和权限来自 database/production/V002__base.sql。仅增加重复导入处理。
-- Roles, minimum permissions, FREE rules and templates only. No accounts or business data.
INSERT IGNORE INTO `member_level` (`code`,`name`,`discount`,`sort`) VALUES
('NORMAL','普通会员',1.00,0),
('SILVER','银卡会员',0.98,1),
('GOLD', '金卡会员',0.95,2),
('VIP',  'VIP会员', 0.90,3);

INSERT IGNORE INTO `notification_template` (`code`,`name`,`title_tpl`,`content_tpl`) VALUES
('BOOKING_SUCCESS','预约成功','预约成功','您已成功预约{courseDate} {startTime} 的{courseName}, 请合理安排到店时间。'),
('COURSE_REMIND','开课提醒','课程提醒','您预约的{courseName}将在2小时后开始({startTime}), 请提前到店。'),
('COURSE_CANCEL','课程取消','课程取消通知','很抱歉, 您预约的{courseDate} {courseName}因故取消，请在课表查看最新安排。'),
('MEMBER_EXPIRE','会员到期提醒','会员到期提醒','您的会员将于{expireDate}到期, 续费可继续享受会员权益。'),
('CREDITS_LOW','课时不足提醒','课时提醒','您的课包「{packageName}」剩余{remaining}节课, 可及时续购。'),
('ORDER_PAID','支付成功','支付成功','您购买的「{packageName}」已支付成功, 课时已到账。');

INSERT IGNORE INTO `sys_role` (`code`,`name`,`description`) VALUES
('ADMIN','管理员','拥有整个系统的管理权限'),
('STORE_MANAGER','店长','课程/排课/教练/会员/课包/统计管理(无系统配置/RBAC/财务)'),
('STAFF','前台','会员/预约/签到/订单查询/代预约'),
('COACH','教练','查看自己排课/学员 + 训练记录 + 请假/调课申请'),
('MEMBER','学员','小程序用户端(无后台权限)');

INSERT IGNORE INTO `sys_permission` (`parent_id`,`code`,`name`,`type`,`sort`) VALUES
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

INSERT IGNORE INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='ADMIN';

INSERT IGNORE INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='STORE_MANAGER' AND p.code IN
 ('dashboard:view','course:view','course:create','course:update','course:delete',
  'schedule:view','schedule:create','schedule:update','schedule:cancel',
  'coach:view','coach:manage','member:view','member:update','member:adjust',
  'package:view','package:manage','order:view','coupon:manage',
  'content:manage','classroom:manage','statistics:view');

INSERT IGNORE INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='STAFF' AND p.code IN
 ('dashboard:view','course:view','schedule:view','member:view','order:view',
  'staff:member:view','staff:booking:view','staff:booking:create','staff:attendance:create');

INSERT IGNORE INTO `sys_role_permission` (`role_id`,`permission_id`)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p WHERE r.code='COACH' AND p.code IN
 ('dashboard:view','coach:view',
  'coach:schedule:view','coach:student:view','coach:training:create','coach:training:view',
  'coach:leave:create','coach:leave:view','coach:adjustment:create');

INSERT IGNORE INTO `system_config` (`config_key`,`config_value`,`description`) VALUES
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

INSERT IGNORE INTO system_config(config_key,config_value,description) VALUES('studio.configured','false','门店经营资料尚待经营者确认');

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

-- ---------------------------------------------------------------------
-- 课程分类
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `course_category` (`id`,`code`,`name`,`sort`) VALUES
(1,'REFORMER','器械普拉提',1),
(2,'MAT','垫上普拉提',2),
(3,'SMALL_GROUP','小团课',3),
(4,'PRIVATE','私教',4),
(5,'EXPERIENCE','体验课',5),
(6,'CORE','核心训练',6),
(7,'POSTURE','体态改善',7),
(8,'SHAPING','塑形',8),
(9,'POSTPARTUM','产后恢复',9),
(10,'STRETCH','拉伸放松',10);

-- ---------------------------------------------------------------------
-- 教室
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `classroom` (`id`,`name`,`type`,`capacity`,`equipment`) VALUES
(1,'A教室','REFORMER',8,'Reformer核心床×8, 凯迪拉克架×1'),
(2,'B教室','REFORMER',8,'Reformer核心床×8, 稳踏椅×2'),
(3,'私教室','PRIVATE',2,'Reformer核心床×1, 凯迪拉克架×1, 稳踏椅×1'),
(4,'垫上区','MAT',12,'瑜伽垫×12, 普拉提圈/弹力带/小球'),
(5,'器械区','GROUP',6,'综合训练器械');

-- ---------------------------------------------------------------------
-- 教练
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `coach` (`id`,`name`,`avatar`,`title`,`years`,`specialties`,`bio`,`rating`,`sort`) VALUES
(1,'王老师','/assets/avatar/coach1.png','资深普拉提教练',5,'体态改善,核心训练,器械普拉提','北极星普拉提认证教练, 专注体态管理与核心稳定训练, 擅长为久坐人群设计循序渐进的训练方案。',4.9,1),
(2,'李老师','/assets/avatar/coach2.png','普拉提教练',4,'垫上普拉提,塑形,拉伸放松','BASI 认证教练, 课程节奏舒缓, 注重呼吸与动作的配合, 深受入门学员喜爱。',4.8,2),
(3,'张老师','/assets/avatar/coach3.png','孕产普拉提教练',6,'产后运动恢复,核心训练,私教','孕产普拉提专项认证, 长期服务产后妈妈群体, 帮助会员安全地恢复身体状态。',5.0,3);

INSERT IGNORE INTO `coach_certificate` (`id`,`coach_id`,`name`,`issuer`,`issued_at`) VALUES
(1,1,'Polestar Pilates 全场馆认证','Polestar Pilates','2021-06-01'),
(2,2,'BASI Pilates 垫上认证','BASI Pilates','2022-03-15'),
(3,3,'孕产普拉提专项认证','PMA','2020-11-20');

-- ---------------------------------------------------------------------
-- 课程
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `course` (`id`,`category_id`,`name`,`duration_minutes`,`type`,`difficulty`,`credits_cost`,`price`,`target_users`,`training_focus`,`notices`,`sort`) VALUES
(1,1,'器械普拉提 · 核心床',60,'GROUP','JUNIOR',1,168.00,'有一定运动基础, 想提升核心力量与体态的会员','核心稳定 / 髋部控制 / 脊柱灵活性','请穿防滑袜; 课前1小时避免大量进食',1),
(2,2,'垫上普拉提 · 入门',60,'GROUP','BEGINNER',1,128.00,'零基础入门, 久坐办公人群','呼吸模式 / 基础核心激活 / 身体排列','零基础友好, 请穿舒适运动服',2),
(3,6,'核心强化小班',45,'GROUP','INTERMEDIATE',1,148.00,'希望强化核心力量的会员','深层核心 / 抗旋转稳定 / 骨盆控制','建议有一定普拉提基础',3),
(4,7,'体态改善 · 圆肩驼背',60,'GROUP','BEGINNER',1,148.00,'圆肩驼背, 长期伏案人群','胸椎伸展 / 肩胛稳定 / 颈肩放松','训练前后建议做评估对比',4),
(5,9,'产后运动恢复(小班)',60,'GROUP','BEGINNER',2,198.00,'产后42天以上, 希望逐步恢复身体状态的妈妈','盆底觉知 / 腹直肌分离友好的核心训练 / 呼吸重建','请先在教练指导下完成身体评估后再约课',5),
(6,10,'拉伸放松',45,'GROUP','BEGINNER',1,98.00,'所有会员, 适合训练后或工作间隙','全身拉伸 / 筋膜放松 / 呼吸放松','节奏舒缓, 适合任何基础',6),
(7,4,'私教一对一',60,'PRIVATE','BEGINNER',1,480.00,'希望获得个性化训练方案的会员','按个人评估结果定制训练内容','私教课请提前24小时预约',7),
(8,5,'新人体验课',45,'EXPERIENCE','BEGINNER',1,99.00,'首次到店的普拉提新人','普拉提基础体验 / 身体状态初评','每人限购一次',8);

-- ---------------------------------------------------------------------
-- 演示排课: 今天起未来7天, 每天 3-4 节
-- (课程ID/教练ID/教室ID 对应上方插入顺序)
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `course_schedule` (`id`,`course_id`,`coach_id`,`classroom_id`,`course_date`,`start_time`,`end_time`,`max_capacity`)
SELECT slot, c, ch, r, DATE_ADD(CURDATE(), INTERVAL d DAY), st, et, cap FROM (
  SELECT 1 AS slot, 1 AS c, 1 AS ch, 1 AS r, 0 AS d, '18:30' AS st, '19:30' AS et, 8 AS cap
  UNION ALL SELECT 2, 2, 2, 4, 0, '12:15', '13:15', 12
  UNION ALL SELECT 3, 4, 1, 3, 0, '10:00', '11:00', 2
  UNION ALL SELECT 4, 1, 1, 1, 1, '09:00', '10:00', 8
  UNION ALL SELECT 5, 1, 1, 2, 1, '18:30', '19:30', 8
  UNION ALL SELECT 6, 3, 2, 1, 1, '20:00', '20:45', 8
  UNION ALL SELECT 7, 2, 2, 4, 2, '12:15', '13:15', 12
  UNION ALL SELECT 8, 7, 1, 2, 2, '18:30', '19:30', 8
  UNION ALL SELECT 9, 5, 3, 1, 2, '19:45', '20:45', 6
  UNION ALL SELECT 10, 1, 1, 1, 3, '09:00', '10:00', 8
  UNION ALL SELECT 11, 6, 2, 4, 3, '19:00', '19:45', 12
  UNION ALL SELECT 12, 1, 1, 1, 4, '18:30', '19:30', 8
  UNION ALL SELECT 13, 8, 2, 3, 4, '20:00', '21:00', 2
  UNION ALL SELECT 14, 4, 1, 1, 5, '10:30', '11:30', 8
  UNION ALL SELECT 15, 2, 2, 4, 5, '15:00', '16:00', 12
  UNION ALL SELECT 16, 1, 1, 2, 5, '18:30', '19:30', 8
  UNION ALL SELECT 17, 1, 1, 1, 6, '09:00', '10:00', 8
  UNION ALL SELECT 18, 3, 1, 1, 6, '10:30', '11:15', 8
  UNION ALL SELECT 19, 2, 2, 4, 6, '14:00', '15:00', 12
  UNION ALL SELECT 20, 7, 1, 2, 6, '18:30', '19:30', 8
) t;

-- ---------------------------------------------------------------------
-- 课包
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `package` (`id`,`name`,`type`,`original_price`,`sale_price`,`total_credits`,`valid_days`,`is_new_user_only`,`allow_transfer`,`allow_refund`,`purchase_limit`,`description`,`sort`) VALUES
(1,'新人体验课','TIMES',168.00,99.00,1,30,1,0,1,1,'新会员专享, 每人限购一次, 含一节体验课与基础身体状态初评。',1),
(2,'器械普拉提10次卡','TIMES',1680.00,1380.00,10,180,0,0,1,0,'适用于器械普拉提系列团课, 180天有效。',2),
(3,'垫上普拉提月卡','PERIOD',1280.00,980.00,0,30,0,0,1,0,'30天内垫上课程不限次预约。',3),
(4,'综合季卡','TIMES',4680.00,3980.00,36,90,0,1,1,0,'全店团课通用36课时, 90天有效, 可转赠。',4),
(5,'私教10节课包','PRIVATE',4800.00,4200.00,10,365,0,0,1,0,'一对一私教10节, 可根据评估结果定制训练计划。',5);

INSERT IGNORE INTO `package_item` (`id`,`package_id`,`item_type`,`item_id`) VALUES
(1,1,'COURSE',8),
(2,2,'CATEGORY',1),
(3,3,'CATEGORY',2),
(4,4,'CATEGORY',NULL),
(5,5,'COURSE',7);

-- ---------------------------------------------------------------------
-- 优惠券
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `coupon` (`id`,`name`,`type`,`discount_amount`,`min_amount`,`apply_type`,`total_count`,`per_limit`,`valid_from`,`valid_until`) VALUES
(1,'新人礼包·50元券','NEW_USER',50.00,300.00,'ALL',0,1,DATE_SUB(NOW(), INTERVAL 1 DAY),DATE_ADD(NOW(), INTERVAL 365 DAY)),
(2,'满1000减100','FULL_REDUCE',100.00,1000.00,'ALL',500,2,DATE_SUB(NOW(), INTERVAL 1 DAY),DATE_ADD(NOW(), INTERVAL 90 DAY)),
(3,'私教课9折券','PRIVATE',NULL,0.00,'COURSE',200,1,DATE_SUB(NOW(), INTERVAL 1 DAY),DATE_ADD(NOW(), INTERVAL 90 DAY));

-- ---------------------------------------------------------------------
-- Banner
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `banner` (`id`,`image_url`,`title`,`subtitle`,`link_type`,`link_value`,`sort`) VALUES
(1,'/assets/banner/main.png','优仪普拉提','让身体回到更舒展的状态','NONE',NULL,1),
(2,'/assets/banner/newbie.png','新人体验课 ¥99','首次到店专享, 限购一次','PACKAGE','1',2),
(3,'/assets/banner/posture.png','体态改善专题','圆肩驼背改善小班课','COURSE','4',3);

-- ---------------------------------------------------------------------
-- 文章(优仪课堂)
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `article_category` (`id`,`code`,`name`,`sort`) VALUES
(1,'KNOWLEDGE','普拉提知识',1),
(2,'POSTURE_K','体态知识',2),
(3,'SKILL','训练技巧',3),
(4,'EQUIPMENT','器械介绍',4),
(5,'STUDIO','工作室动态',5);

INSERT IGNORE INTO `article` (`id`,`category_id`,`title`,`author`,`tags`,`content`,`views`,`status`,`published_at`) VALUES
(1,1,'什么是普拉提? 一篇写给新手的入门指南','优仪教研组','入门,知识','<p>普拉提(Pilates)是一套强调核心控制、呼吸与身体排列的训练体系……</p><p>它适合久坐人群、希望改善体态与提升身体控制力的练习者。</p>',326,'PUBLISHED',NOW()),
(2,2,'久坐人群的 5 个日常体态自查','王老师','体态,圆肩','<p>长期伏案容易带来头部前伸、圆肩等体态问题……</p><p>本文提供 5 个简单自查方法, 帮助你了解自己的身体状态。</p>',198,'PUBLISHED',NOW()),
(3,4,'认识核心床: Reformer 基础介绍','李老师','器械,核心床','<p>Reformer(核心床)是普拉提最经典的器械之一……</p>',156,'PUBLISHED',NOW());

-- 本机演示资料
INSERT IGNORE INTO system_config(config_key,config_value,description) VALUES
('studio.name','优仪普拉提','工作室名称'),
('studio.slogan','让身体回到更舒展的状态','品牌标语'),
('studio.phone','021-88888888','联系电话'),
('studio.address','上海市静安区示例路 88 号 3F','工作室地址'),
('studio.hours','09:00-21:30','营业时间'),
('studio.location','31.2304,121.4737','经纬度(导航用)');

COMMIT;
