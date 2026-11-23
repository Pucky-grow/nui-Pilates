-- Immutable pre-production structural baseline. No database creation or demo inserts.
-- =====================================================================
-- 优仪普拉提 Yoyi Pilates · 数据库结构
-- MySQL 8.0+ / InnoDB / utf8mb4
-- 设计约定:
--   1. 所有表: id BIGINT UNSIGNED 自增主键, created_at/updated_at, deleted 逻辑删除
--   2. 金额统一 DECIMAL(10,2), 单位元
--   3. 状态字段使用 VARCHAR 枚举, 可读性优先, 取值见字段注释
--   4. 高并发扣减字段(user_package.remaining_credits 等)配合 version 乐观锁
--   5. 唯一约束即幂等兜底: order_no / out_trade_no / booking(user_id,schedule_id)
-- =====================================================================



SET NAMES utf8mb4;

-- =====================================================================
-- 一、用户与会员
-- =====================================================================

-- 微信小程序用户
CREATE TABLE `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `openid`        VARCHAR(64)  NOT NULL COMMENT '微信 openid',
  `unionid`       VARCHAR(64)  DEFAULT NULL COMMENT '微信 unionid',
  `nickname`      VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `avatar_url`    VARCHAR(512) DEFAULT NULL COMMENT '头像',
  `gender`        TINYINT      DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `phone`         VARCHAR(20)  DEFAULT NULL COMMENT '手机号(密文存储,展示脱敏)',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正常/BANNED限制预约/DISABLED禁用',
  `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB COMMENT='微信用户';

-- 用户资料
CREATE TABLE `user_profile` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `real_name`  VARCHAR(32)  DEFAULT NULL COMMENT '真实姓名',
  `birthday`   DATE         DEFAULT NULL,
  `height`     DECIMAL(5,1) DEFAULT NULL COMMENT '身高cm',
  `weight`     DECIMAL(5,1) DEFAULT NULL COMMENT '体重kg',
  `remark`     VARCHAR(512) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB COMMENT='用户资料';

-- 会员等级
CREATE TABLE `member_level` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(32)  NOT NULL COMMENT '等级编码 NORMAL/SILVER/GOLD/VIP',
  `name`       VARCHAR(32)  NOT NULL COMMENT '等级名称',
  `discount`   DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '折扣 0.95=95折',
  `sort`       INT NOT NULL DEFAULT 0,
  `status`     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB COMMENT='会员等级';

-- 会员
CREATE TABLE `member` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `member_no`         VARCHAR(32) NOT NULL COMMENT '会员编号',
  `user_id`           BIGINT UNSIGNED NOT NULL,
  `level_id`          BIGINT UNSIGNED NOT NULL COMMENT '会员等级ID',
  `valid_until`       DATE DEFAULT NULL COMMENT '会员有效期(NULL=长期)',
  `total_sessions`    INT NOT NULL DEFAULT 0 COMMENT '累计上课次数',
  `total_consumption` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '累计消费',
  `last_training_at`  DATETIME DEFAULT NULL COMMENT '最后训练时间',
  `status`            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_no` (`member_no`),
  UNIQUE KEY `uk_user_member` (`user_id`),
  KEY `idx_level` (`level_id`)
) ENGINE=InnoDB COMMENT='会员';

-- =====================================================================
-- 二、教练与课程
-- =====================================================================

-- 教练
CREATE TABLE `coach` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sys_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联后台账号(教练登录)',
  `openid`      VARCHAR(64)  DEFAULT NULL COMMENT '教练绑定的微信 openid(绑定后微信一键登录)',
  `name`        VARCHAR(32)  NOT NULL,
  `avatar`      VARCHAR(512) DEFAULT NULL,
  `title`       VARCHAR(64)  DEFAULT NULL COMMENT '职位',
  `years`       INT          DEFAULT 0 COMMENT '从业年限',
  `specialties` VARCHAR(512) DEFAULT NULL COMMENT '专业方向,逗号分隔',
  `bio`         TEXT         COMMENT '个人简介',
  `rating`      DECIMAL(2,1) NOT NULL DEFAULT 5.0 COMMENT '用户评分',
  `sort`        INT NOT NULL DEFAULT 0,
  `status`      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB COMMENT='教练';

-- 教练证书
CREATE TABLE `coach_certificate` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`   BIGINT UNSIGNED NOT NULL,
  `name`       VARCHAR(128) NOT NULL COMMENT '证书名称',
  `issuer`     VARCHAR(128) DEFAULT NULL COMMENT '颁发机构',
  `issued_at`  DATE DEFAULT NULL,
  `image_url`  VARCHAR(512) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach` (`coach_id`)
) ENGINE=InnoDB COMMENT='教练证书';

-- 课程分类
CREATE TABLE `course_category` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(32) NOT NULL COMMENT '分类编码',
  `name`       VARCHAR(32) NOT NULL COMMENT '分类名称',
  `sort`       INT NOT NULL DEFAULT 0,
  `status`     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_course_category` (`code`)
) ENGINE=InnoDB COMMENT='课程分类';

-- 课程
CREATE TABLE `course` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `category_id`     BIGINT UNSIGNED NOT NULL,
  `name`            VARCHAR(64)  NOT NULL,
  `cover`           VARCHAR(512) DEFAULT NULL,
  `description`     TEXT COMMENT '课程介绍',
  `duration_minutes` INT NOT NULL DEFAULT 60 COMMENT '时长(分钟)',
  `type`            VARCHAR(20) NOT NULL DEFAULT 'GROUP' COMMENT 'GROUP团课/PRIVATE私教/EXPERIENCE体验课',
  `difficulty`      VARCHAR(20) NOT NULL DEFAULT 'BEGINNER' COMMENT 'BEGINNER入门/JUNIOR初级/INTERMEDIATE中级/ADVANCED进阶',
  `credits_cost`    INT NOT NULL DEFAULT 1 COMMENT '消耗课时',
  `price`           DECIMAL(10,2) DEFAULT NULL COMMENT '单次价格(展示用)',
  `target_users`    VARCHAR(512) DEFAULT NULL COMMENT '适合人群',
  `training_focus`  VARCHAR(512) DEFAULT NULL COMMENT '训练重点',
  `notices`         VARCHAR(512) DEFAULT NULL COMMENT '注意事项',
  `sort`            INT NOT NULL DEFAULT 0,
  `status`          VARCHAR(20) NOT NULL DEFAULT 'ON_SHELF' COMMENT 'ON_SHELF上架/OFF_SHELF下架',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category_status` (`category_id`,`status`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB COMMENT='课程';

-- 教室
CREATE TABLE `classroom` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(32) NOT NULL COMMENT '教室名称',
  `type`       VARCHAR(20) NOT NULL DEFAULT 'GROUP' COMMENT 'GROUP团课/PRIVATE私教/REFORMER器械/MAT垫上',
  `capacity`   INT NOT NULL DEFAULT 8 COMMENT '容纳人数',
  `equipment`  VARCHAR(512) DEFAULT NULL COMMENT '设备说明',
  `status`     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `remark`     VARCHAR(512) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='教室';

-- 排课(课程实例) —— 并发预约的核心库存表
CREATE TABLE `course_schedule` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`       BIGINT UNSIGNED NOT NULL,
  `coach_id`        BIGINT UNSIGNED NOT NULL,
  `classroom_id`    BIGINT UNSIGNED NOT NULL,
  `course_date`     DATE NOT NULL COMMENT '上课日期',
  `start_time`      TIME NOT NULL,
  `end_time`        TIME NOT NULL,
  `max_capacity`    INT NOT NULL COMMENT '最大人数',
  `booked_count`    INT NOT NULL DEFAULT 0 COMMENT '已预约人数(含签到,不含已取消)',
  `status`          VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED待开课/CANCELLED已取消/FINISHED已结束',
  `cancel_reason`   VARCHAR(255) DEFAULT NULL,
  `version`         INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_date_time` (`course_date`,`start_time`),
  KEY `idx_course_date` (`course_id`,`course_date`),
  KEY `idx_coach_date` (`coach_id`,`course_date`,`start_time`),
  KEY `idx_classroom_date` (`classroom_id`,`course_date`,`start_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='排课';

-- =====================================================================
-- 三、预约与签到
-- =====================================================================

-- 预约 —— 唯一索引 (user_id, schedule_id) 是幂等与防重复预约的最终兜底
CREATE TABLE `booking` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `booking_no`       VARCHAR(32) NOT NULL COMMENT '预约编号',
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `schedule_id`      BIGINT UNSIGNED NOT NULL,
  `user_package_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '扣课时来源课包(FREE模式为空)',
  `credits_used`     INT NOT NULL DEFAULT 1 COMMENT '消耗课时',
  `status`           VARCHAR(20) NOT NULL DEFAULT 'BOOKED' COMMENT 'PENDING待确认/BOOKED已预约/SIGNED_IN已签到/COMPLETED已完成/CANCELLED已取消/NO_SHOW已爽约/REFUNDED已退款',
  `cancel_reason`    VARCHAR(255) DEFAULT NULL,
  `cancelled_at`     DATETIME DEFAULT NULL,
  `remark`           VARCHAR(512) DEFAULT NULL,
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_no` (`booking_no`),
  `active_user_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `deleted` = 0 AND `status` IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW') THEN `user_id` ELSE NULL END) STORED,
  UNIQUE KEY `uk_active_user_schedule` (`active_user_id`,`schedule_id`),
  KEY `idx_schedule_status` (`schedule_id`,`status`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB COMMENT='预约';

-- 签到/出勤
CREATE TABLE `attendance` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `booking_id`   BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `schedule_id`  BIGINT UNSIGNED NOT NULL,
  `checkin_type` VARCHAR(20) NOT NULL COMMENT 'USER_SCAN用户扫码/COACH教练签到/ADMIN后台签到',
  `status`       VARCHAR(20) NOT NULL DEFAULT 'PRESENT' COMMENT 'PRESENT出席/ABSENT缺席',
  `operator_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人(教练/管理员签到时)',
  `checkin_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签到时间',
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking` (`booking_id`),
  KEY `idx_schedule` (`schedule_id`,`status`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB COMMENT='签到出勤';

-- =====================================================================
-- 四、课包
-- =====================================================================

-- 课包定义
CREATE TABLE `package` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`             VARCHAR(64) NOT NULL,
  `cover`            VARCHAR(512) DEFAULT NULL,
  `type`             VARCHAR(20) NOT NULL COMMENT 'TIMES次卡/PERIOD期限卡/PRIVATE私教课包',
  `original_price`   DECIMAL(10,2) NOT NULL COMMENT '原价',
  `sale_price`       DECIMAL(10,2) NOT NULL COMMENT '销售价',
  `total_credits`    INT NOT NULL DEFAULT 0 COMMENT '总课时(期限卡为0表示不限次)',
  `valid_days`       INT NOT NULL COMMENT '有效天数',
  `is_new_user_only` TINYINT NOT NULL DEFAULT 0 COMMENT '是否新人专享',
  `allow_transfer`   TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许转赠',
  `allow_refund`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许退款',
  `purchase_limit`   INT NOT NULL DEFAULT 0 COMMENT '每人限购数量(0不限)',
  `description`      TEXT,
  `sort`             INT NOT NULL DEFAULT 0,
  `status`           VARCHAR(20) NOT NULL DEFAULT 'ON_SHELF',
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort_package` (`status`,`sort`)
) ENGINE=InnoDB COMMENT='课包定义';

-- 课包适用范围
CREATE TABLE `package_item` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `package_id` BIGINT UNSIGNED NOT NULL,
  `item_type`  VARCHAR(20) NOT NULL COMMENT 'ALL全部/CATEGORY分类/COURSE课程/COACH教练',
  `item_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT 'item_type=ALL时为NULL',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_package` (`package_id`)
) ENGINE=InnoDB COMMENT='课包适用范围';

-- 用户课包实例 —— remaining_credits 扣减走 version 乐观锁 + 流水对账
CREATE TABLE `user_package` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT UNSIGNED NOT NULL,
  `package_id`        BIGINT UNSIGNED NOT NULL,
  `order_id`          BIGINT UNSIGNED NOT NULL COMMENT '来源订单(发放幂等: uk order_id+package_id)',
  `name`              VARCHAR(64) NOT NULL COMMENT '课包名称快照',
  `type`              VARCHAR(20) NOT NULL COMMENT '类型快照',
  `total_credits`     INT NOT NULL DEFAULT 0,
  `remaining_credits` INT NOT NULL DEFAULT 0,
  `used_credits`      INT NOT NULL DEFAULT 0,
  `valid_from`        DATE NOT NULL,
  `valid_until`       DATE NOT NULL,
  `status`            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE有效/USED_UP已用完/EXPIRED已过期/REFUNDED已退款',
  `version`           INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_package` (`order_id`,`package_id`),
  KEY `idx_user_status_user_package` (`user_id`,`status`),
  KEY `idx_valid_until` (`valid_until`,`status`)
) ENGINE=InnoDB COMMENT='用户课包';

-- 课时流水(一切课时变动的审计依据, 余额=流水累计)
CREATE TABLE `package_credit_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_package_id` BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `change_type`     VARCHAR(20) NOT NULL COMMENT 'GRANT发放/DEDUCT预约扣减/REFUND取消返还/ADJUST后台调整/EXPIRE过期清零',
  `change_amount`   INT NOT NULL COMMENT '变动数量(正增负减)',
  `balance_after`   INT NOT NULL COMMENT '变动后余额',
  `biz_type`        VARCHAR(32) DEFAULT NULL COMMENT '业务类型 BOOKING/ORDER/ADMIN等',
  `biz_id`          VARCHAR(64) DEFAULT NULL COMMENT '关联业务单号',
  `operator`        VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  `remark`          VARCHAR(512) DEFAULT NULL,
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_package` (`user_package_id`),
  KEY `idx_user_package_credit_log` (`user_id`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB COMMENT='课时流水';

-- =====================================================================
-- 五、交易(订单/支付/退款)
-- =====================================================================

-- 订单
CREATE TABLE `order` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no`        VARCHAR(32) NOT NULL COMMENT '订单号',
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `order_type`      VARCHAR(20) NOT NULL DEFAULT 'PACKAGE' COMMENT 'PACKAGE课包/MEMBER会员/ACTIVITY活动',
  `total_amount`    DECIMAL(10,2) NOT NULL COMMENT '原价总额',
  `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
  `points_amount`   DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '积分抵扣金额',
  `pay_amount`      DECIMAL(10,2) NOT NULL COMMENT '实付金额',
  `user_coupon_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '使用的优惠券',
  `pay_type`        VARCHAR(20) DEFAULT NULL COMMENT 'WECHAT微信/MOCK模拟',
  `status`          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待支付/PAYING支付中/PAID支付成功/FAILED支付失败/CLOSED已关闭/REFUNDING退款中/REFUNDED已退款',
  `paid_at`         DATETIME DEFAULT NULL,
  `closed_at`       DATETIME DEFAULT NULL,
  `remark`          VARCHAR(512) DEFAULT NULL,
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status_order` (`user_id`,`status`),
  KEY `idx_status_created` (`status`,`created_at`)
) ENGINE=InnoDB COMMENT='订单';

-- 订单明细
CREATE TABLE `order_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`    BIGINT UNSIGNED NOT NULL,
  `item_type`   VARCHAR(20) NOT NULL COMMENT 'PACKAGE课包',
  `item_id`     BIGINT UNSIGNED NOT NULL,
  `item_name`   VARCHAR(128) NOT NULL COMMENT '商品名称快照',
  `quantity`    INT NOT NULL DEFAULT 1,
  `unit_price`  DECIMAL(10,2) NOT NULL,
  `total_price` DECIMAL(10,2) NOT NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB COMMENT='订单明细';

-- 支付单 —— out_trade_no 唯一, 回调幂等第一防线
CREATE TABLE `payment` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `payment_no`     VARCHAR(32) NOT NULL,
  `order_id`       BIGINT UNSIGNED NOT NULL,
  `out_trade_no`   VARCHAR(64) NOT NULL COMMENT '商户订单号(微信)',
  `transaction_id` VARCHAR(64) DEFAULT NULL COMMENT '微信支付流水号',
  `amount`         DECIMAL(10,2) NOT NULL,
  `channel`        VARCHAR(20) NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT/MOCK',
  `status`         VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
  `paid_at`        DATETIME DEFAULT NULL,
  `callback_raw`   TEXT COMMENT '回调原始报文',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  UNIQUE KEY `uk_out_trade_no` (`out_trade_no`),
  KEY `idx_order_payment` (`order_id`)
) ENGINE=InnoDB COMMENT='支付单';

-- 退款单
CREATE TABLE `refund` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no`   VARCHAR(32) NOT NULL,
  `order_id`    BIGINT UNSIGNED NOT NULL,
  `payment_id`  BIGINT UNSIGNED NOT NULL,
  `amount`      DECIMAL(10,2) NOT NULL,
  `reason`      VARCHAR(255) DEFAULT NULL,
  `status`      VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
  `refunded_at` DATETIME DEFAULT NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_order_refund` (`order_id`)
) ENGINE=InnoDB COMMENT='退款单';

-- =====================================================================
-- 六、营销(优惠券/积分)
-- =====================================================================

-- 优惠券
CREATE TABLE `coupon` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`            VARCHAR(64) NOT NULL,
  `type`            VARCHAR(20) NOT NULL COMMENT 'NEW_USER新人券/FULL_REDUCE满减券/DISCOUNT折扣券/COURSE课程券/PRIVATE私教券/ACTIVITY活动券',
  `discount_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '优惠金额(满减/固定)',
  `discount_rate`   DECIMAL(3,2)  DEFAULT NULL COMMENT '折扣率(折扣券)',
  `min_amount`      DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低消费门槛',
  `apply_type`      VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL全部/CATEGORY分类/COURSE课程/PACKAGE课包',
  `apply_ids`       VARCHAR(1024) DEFAULT NULL COMMENT '适用ID集合,逗号分隔',
  `total_count`     INT NOT NULL DEFAULT 0 COMMENT '发放总量(0不限)',
  `issued_count`    INT NOT NULL DEFAULT 0 COMMENT '已发放',
  `per_limit`       INT NOT NULL DEFAULT 1 COMMENT '每人限领',
  `valid_from`      DATETIME NOT NULL,
  `valid_until`     DATETIME NOT NULL,
  `status`          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_valid` (`status`,`valid_until`)
) ENGINE=InnoDB COMMENT='优惠券';

-- 用户优惠券
CREATE TABLE `user_coupon` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coupon_id`   BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `status`      VARCHAR(20) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED未使用/USED已使用/EXPIRED已过期',
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `used_at`     DATETIME DEFAULT NULL,
  `order_id`    BIGINT UNSIGNED DEFAULT NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_status_user_coupon` (`user_id`,`status`),
  KEY `idx_coupon` (`coupon_id`)
) ENGINE=InnoDB COMMENT='用户优惠券';

-- 积分流水
CREATE TABLE `points_record` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `change_type`   VARCHAR(10) NOT NULL COMMENT 'EARN获得/SPEND消耗',
  `points`        INT NOT NULL COMMENT '积分数量(正数)',
  `balance_after` INT NOT NULL COMMENT '变动后余额',
  `reason`        VARCHAR(128) NOT NULL COMMENT '原因: 注册/购买/签到/完成课程/评价/邀请/兑换等',
  `biz_type`      VARCHAR(32) DEFAULT NULL,
  `biz_id`        VARCHAR(64) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`,`created_at`),
  KEY `idx_biz_points_record` (`biz_type`,`biz_id`)
) ENGINE=InnoDB COMMENT='积分流水';

-- =====================================================================
-- 七、训练与评估
-- =====================================================================

-- 训练记录(教练填写)
CREATE TABLE `training_record` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `coach_id`      BIGINT UNSIGNED NOT NULL,
  `booking_id`    BIGINT UNSIGNED DEFAULT NULL,
  `schedule_id`   BIGINT UNSIGNED DEFAULT NULL,
  `training_date` DATE NOT NULL,
  `goal`          VARCHAR(255) DEFAULT NULL COMMENT '训练目标',
  `content`       TEXT COMMENT '训练内容',
  `body_parts`    VARCHAR(255) DEFAULT NULL COMMENT '训练部位',
  `movements`     VARCHAR(512) DEFAULT NULL COMMENT '训练动作',
  `intensity`     VARCHAR(20) DEFAULT NULL COMMENT 'LOW低/MEDIUM中/HIGH高',
  `completion`    VARCHAR(255) DEFAULT NULL COMMENT '完成情况',
  `feedback`      TEXT COMMENT '身体反馈',
  `suggestion`    TEXT COMMENT '下次训练建议',
  `remark`        VARCHAR(512) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_date` (`user_id`,`training_date`),
  KEY `idx_coach_date_training_record` (`coach_id`,`training_date`),
  UNIQUE KEY `uk_booking_training_record` (`booking_id`)
) ENGINE=InnoDB COMMENT='训练记录';

-- 身体评估(不做医学诊断)
CREATE TABLE `body_assessment` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `coach_id`         BIGINT UNSIGNED DEFAULT NULL,
  `assess_date`      DATE NOT NULL,
  `height`           DECIMAL(5,1) DEFAULT NULL COMMENT '身高cm',
  `weight`           DECIMAL(5,1) DEFAULT NULL COMMENT '体重kg',
  `body_fat_rate`    DECIMAL(4,1) DEFAULT NULL COMMENT '体脂率%',
  `waist`            DECIMAL(5,1) DEFAULT NULL COMMENT '腰围cm',
  `hip`              DECIMAL(5,1) DEFAULT NULL COMMENT '臀围cm',
  `thigh`            DECIMAL(5,1) DEFAULT NULL COMMENT '大腿围cm',
  `head_forward`     VARCHAR(64) DEFAULT NULL COMMENT '头部前伸',
  `rounded_shoulder` VARCHAR(64) DEFAULT NULL COMMENT '圆肩',
  `thoracic`         VARCHAR(64) DEFAULT NULL COMMENT '胸椎姿态',
  `pelvis`           VARCHAR(64) DEFAULT NULL COMMENT '骨盆姿态',
  `shoulder_level`   VARCHAR(64) DEFAULT NULL COMMENT '高低肩',
  `knee`             VARCHAR(64) DEFAULT NULL COMMENT '膝关节姿态',
  `foot`             VARCHAR(64) DEFAULT NULL COMMENT '足部姿态',
  `front_photo`      VARCHAR(512) DEFAULT NULL,
  `side_photo`       VARCHAR(512) DEFAULT NULL,
  `back_photo`       VARCHAR(512) DEFAULT NULL,
  `remark`           VARCHAR(512) DEFAULT NULL,
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_date_body_assessment` (`user_id`,`assess_date`)
) ENGINE=InnoDB COMMENT='身体评估';

-- 围度体重追踪(用于趋势图)
CREATE TABLE `body_measurement` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `measure_date`  DATE NOT NULL,
  `weight`        DECIMAL(5,1) DEFAULT NULL,
  `body_fat_rate` DECIMAL(4,1) DEFAULT NULL,
  `waist`         DECIMAL(5,1) DEFAULT NULL,
  `hip`           DECIMAL(5,1) DEFAULT NULL,
  `thigh`         DECIMAL(5,1) DEFAULT NULL,
  `remark`        VARCHAR(255) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_date_body_measurement` (`user_id`,`measure_date`)
) ENGINE=InnoDB COMMENT='身体数据追踪';

-- 课程评价
CREATE TABLE `course_review` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `booking_id`  BIGINT UNSIGNED NOT NULL COMMENT '一节课只能评价一次',
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `schedule_id` BIGINT UNSIGNED NOT NULL,
  `course_id`   BIGINT UNSIGNED NOT NULL,
  `coach_id`    BIGINT UNSIGNED NOT NULL,
  `course_score` TINYINT NOT NULL COMMENT '课程评分1-5',
  `coach_score`  TINYINT NOT NULL COMMENT '教练评分1-5',
  `env_score`    TINYINT NOT NULL COMMENT '环境评分1-5',
  `content`     VARCHAR(1024) DEFAULT NULL,
  `images`      VARCHAR(2048) DEFAULT NULL COMMENT '图片JSON数组',
  `tags`        VARCHAR(512) DEFAULT NULL COMMENT '标签,逗号分隔',
  `status`      VARCHAR(20) NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE可见/HIDDEN隐藏',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booking_course_review` (`booking_id`),
  KEY `idx_coach_course_review` (`coach_id`,`status`),
  KEY `idx_course` (`course_id`,`status`),
  KEY `idx_user_course_review` (`user_id`)
) ENGINE=InnoDB COMMENT='课程评价';

-- =====================================================================
-- 八、内容(优仪课堂/Banner)
-- =====================================================================

-- 文章分类
CREATE TABLE `article_category` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(32) NOT NULL,
  `name`       VARCHAR(32) NOT NULL,
  `sort`       INT NOT NULL DEFAULT 0,
  `status`     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_article_category` (`code`)
) ENGINE=InnoDB COMMENT='文章分类';

-- 文章
CREATE TABLE `article` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `category_id`  BIGINT UNSIGNED NOT NULL,
  `title`        VARCHAR(128) NOT NULL,
  `cover`        VARCHAR(512) DEFAULT NULL,
  `content`      LONGTEXT,
  `video_url`    VARCHAR(512) DEFAULT NULL,
  `author`       VARCHAR(64) DEFAULT NULL,
  `tags`         VARCHAR(255) DEFAULT NULL,
  `views`        INT NOT NULL DEFAULT 0,
  `likes`        INT NOT NULL DEFAULT 0,
  `status`       VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿/PUBLISHED已发布/OFFLINE已下线',
  `published_at` DATETIME DEFAULT NULL,
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category_status_article` (`category_id`,`status`),
  KEY `idx_published` (`status`,`published_at`)
) ENGINE=InnoDB COMMENT='文章';

-- 文章点赞
CREATE TABLE `article_like` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_user` (`article_id`,`user_id`)
) ENGINE=InnoDB COMMENT='文章点赞';

-- 文章收藏
CREATE TABLE `article_favorite` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_user_article_favorite` (`article_id`,`user_id`)
) ENGINE=InnoDB COMMENT='文章收藏';

-- Banner
CREATE TABLE `banner` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `image_url`  VARCHAR(512) NOT NULL,
  `title`      VARCHAR(64) DEFAULT NULL,
  `subtitle`   VARCHAR(128) DEFAULT NULL,
  `link_type`  VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/COURSE/PACKAGE/ACTIVITY/ARTICLE/URL',
  `link_value` VARCHAR(512) DEFAULT NULL COMMENT '跳转目标ID或URL',
  `sort`       INT NOT NULL DEFAULT 0,
  `start_at`   DATETIME DEFAULT NULL,
  `end_at`     DATETIME DEFAULT NULL,
  `status`     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort_banner` (`status`,`sort`)
) ENGINE=InnoDB COMMENT='首页Banner';

-- =====================================================================
-- 九、消息通知
-- =====================================================================

-- 消息模板
CREATE TABLE `notification_template` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`           VARCHAR(64) NOT NULL COMMENT '模板编码 BOOKING_SUCCESS/COURSE_REMIND等',
  `name`           VARCHAR(64) NOT NULL,
  `title_tpl`      VARCHAR(128) NOT NULL COMMENT '标题模板,{var}占位',
  `content_tpl`    VARCHAR(512) NOT NULL COMMENT '内容模板,{var}占位',
  `wx_template_id` VARCHAR(128) DEFAULT NULL COMMENT '微信订阅消息模板ID',
  `status`         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`        TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_notification_template` (`code`)
) ENGINE=InnoDB COMMENT='消息模板';

-- 站内消息
CREATE TABLE `notification` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `template_code` VARCHAR(64) DEFAULT NULL,
  `title`        VARCHAR(128) NOT NULL,
  `content`      VARCHAR(512) NOT NULL,
  `biz_type`     VARCHAR(32) DEFAULT NULL,
  `biz_id`       VARCHAR(64) DEFAULT NULL,
  `is_read`      TINYINT NOT NULL DEFAULT 0,
  `read_at`      DATETIME DEFAULT NULL,
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`,`created_at`)
) ENGINE=InnoDB COMMENT='站内消息';

-- =====================================================================
-- 十、系统(RBAC/日志/配置)
-- =====================================================================

-- 后台用户
CREATE TABLE `sys_user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(32) NOT NULL,
  `password`      VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  `name`          VARCHAR(32) NOT NULL,
  `avatar`        VARCHAR(512) DEFAULT NULL,
  `phone`         VARCHAR(20) DEFAULT NULL,
  `email`         VARCHAR(64) DEFAULT NULL,
  `status`        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `last_login_at` DATETIME DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB COMMENT='后台用户';

-- 角色
CREATE TABLE `sys_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN/STORE_MANAGER/OPERATION/COACH/FINANCE',
  `name`        VARCHAR(32) NOT NULL,
  `description` VARCHAR(255) DEFAULT NULL,
  `status`      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_sys_role` (`code`)
) ENGINE=InnoDB COMMENT='角色';

-- 权限
CREATE TABLE `sys_permission` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `code`       VARCHAR(64) NOT NULL COMMENT '如 course:view / booking:cancel:any',
  `name`       VARCHAR(64) NOT NULL,
  `type`       VARCHAR(10) NOT NULL DEFAULT 'API' COMMENT 'MENU菜单/BUTTON按钮/API接口',
  `path`       VARCHAR(255) DEFAULT NULL COMMENT '前端路由或API路径',
  `sort`       INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_sys_permission` (`code`)
) ENGINE=InnoDB COMMENT='权限';

-- 用户-角色
CREATE TABLE `sys_user_role` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `role_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`)
) ENGINE=InnoDB COMMENT='用户角色关联';

-- 角色-权限
CREATE TABLE `sys_role_permission` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id`       BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`,`permission_id`)
) ENGINE=InnoDB COMMENT='角色权限关联';

-- 操作日志(后台一切敏感操作必记)
CREATE TABLE `operation_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `operator_name` VARCHAR(64) DEFAULT NULL,
  `module`        VARCHAR(64) NOT NULL COMMENT '模块: member/course/schedule/order等',
  `action`        VARCHAR(64) NOT NULL COMMENT '动作: adjust_credits/refund/update等',
  `target_type`   VARCHAR(64) DEFAULT NULL,
  `target_id`     VARCHAR(64) DEFAULT NULL,
  `detail`        TEXT COMMENT '操作详情(变更前后)',
  `ip`            VARCHAR(64) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_operator` (`operator_id`,`created_at`),
  KEY `idx_module` (`module`,`created_at`),
  KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB COMMENT='操作日志';

-- 系统配置(预约/取消/积分等业务规则, 后台可改)
CREATE TABLE `system_config` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`   VARCHAR(64) NOT NULL,
  `config_value` VARCHAR(1024) NOT NULL,
  `description`  VARCHAR(255) DEFAULT NULL,
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key` (`config_key`)
) ENGINE=InnoDB COMMENT='系统配置';

-- =====================================================================
-- 二期新增表: 请假/调课/训练模板/候补/隐私/教练可用时间
-- =====================================================================

-- 教练请假
CREATE TABLE `coach_leave` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`      BIGINT UNSIGNED NOT NULL COMMENT '教练ID',
  `leave_date`    DATE NOT NULL,
  `start_time`    TIME NOT NULL,
  `end_time`      TIME NOT NULL,
  `reason`        VARCHAR(255) DEFAULT NULL,
  `status`        VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审批/APPROVED已通过/REJECTED已拒绝/CANCELLED已取消',
  `approver_id`   BIGINT UNSIGNED DEFAULT NULL,
  `approved_at`   DATETIME DEFAULT NULL,
  `reject_reason` VARCHAR(255) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach_date_coach_leave` (`coach_id`,`leave_date`),
  KEY `idx_status_coach_leave` (`status`)
) ENGINE=InnoDB COMMENT='教练请假';

-- 调课申请
CREATE TABLE `schedule_change_request` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `schedule_id`     BIGINT UNSIGNED NOT NULL,
  `coach_id`        BIGINT UNSIGNED NOT NULL COMMENT '申请教练',
  `type`            VARCHAR(20) NOT NULL COMMENT 'TIME改时间/COACH换教练/CLASSROOM换教室',
  `new_course_date` DATE DEFAULT NULL,
  `new_start_time`  TIME DEFAULT NULL,
  `new_end_time`    TIME DEFAULT NULL,
  `new_coach_id`    BIGINT UNSIGNED DEFAULT NULL,
  `new_classroom_id` BIGINT UNSIGNED DEFAULT NULL,
  `reason`          VARCHAR(255) DEFAULT NULL,
  `status`          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `approver_id`     BIGINT UNSIGNED DEFAULT NULL,
  `handled_at`      DATETIME DEFAULT NULL,
  `reject_reason`   VARCHAR(255) DEFAULT NULL,
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach_status` (`coach_id`,`status`),
  KEY `idx_schedule_schedule_change_request` (`schedule_id`)
) ENGINE=InnoDB COMMENT='调课申请';

-- 训练记录模板
CREATE TABLE `training_record_template` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`   BIGINT UNSIGNED NOT NULL,
  `name`       VARCHAR(64) NOT NULL,
  `content`    TEXT COMMENT '模板内容(训练目标/动作/强度等)',
  `sort`       INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach_training_record_template` (`coach_id`)
) ENGINE=InnoDB COMMENT='教练训练记录模板';

-- 学员隐私设置
CREATE TABLE `user_privacy` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`               BIGINT UNSIGNED NOT NULL,
  `allow_assessment_view` TINYINT NOT NULL DEFAULT 1 COMMENT '允许教练查看身体评估',
  `allow_training_view`   TINYINT NOT NULL DEFAULT 1 COMMENT '允许教练查看历史训练',
  `allow_photo`           TINYINT NOT NULL DEFAULT 1 COMMENT '允许使用训练照片',
  `allow_show_nickname`   TINYINT NOT NULL DEFAULT 1 COMMENT '允许展示评价昵称',
  `allow_marketing`       TINYINT NOT NULL DEFAULT 1 COMMENT '允许接收营销消息',
  `created_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`               TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_privacy` (`user_id`)
) ENGINE=InnoDB COMMENT='学员隐私设置';

-- 候补队列
CREATE TABLE `waitlist` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `schedule_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `rank`         INT NOT NULL DEFAULT 0 COMMENT '排队位次',
  `status`       VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING排队中/CONVERTED已转正/EXPIRED已失效/CANCELLED已取消',
  `converted_at` DATETIME DEFAULT NULL,
  `expire_at`    DATETIME DEFAULT NULL,
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`      TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_user_waitlist` (`schedule_id`,`user_id`),
  KEY `idx_schedule_status_waitlist` (`schedule_id`,`status`)
) ENGINE=InnoDB COMMENT='候补队列';

-- 教练可预约时间
CREATE TABLE `coach_availability` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`   BIGINT UNSIGNED NOT NULL,
  `weekday`    TINYINT NOT NULL COMMENT '星期 1=周一...7=周日',
  `start_time` TIME NOT NULL,
  `end_time`   TIME NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach_weekday` (`coach_id`,`weekday`)
) ENGINE=InnoDB COMMENT='教练可预约时间';

-- =====================================================================
-- 二期 Phase 5: 课程体系(课程↔教练多对多/课程方案/课程版本)
-- =====================================================================

-- 课程-教练关联(授课资格, 多对多)
CREATE TABLE `course_coach` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`            BIGINT UNSIGNED NOT NULL,
  `coach_id`             BIGINT UNSIGNED NOT NULL,
  `status`               VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审核/APPROVED已获资格/REJECTED已驳回/DISABLED已停用',
  `qualification_source` VARCHAR(20) DEFAULT NULL COMMENT 'PROPOSAL新建课程/APPLY申请授课/ADMIN管理员指定',
  `approved_by`          BIGINT UNSIGNED DEFAULT NULL,
  `approved_at`          DATETIME DEFAULT NULL,
  `remark`               VARCHAR(255) DEFAULT NULL,
  `created_at`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`              TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_coach` (`course_id`,`coach_id`),
  KEY `idx_coach_status_course_coach` (`coach_id`,`status`),
  KEY `idx_course_status` (`course_id`,`status`)
) ENGINE=InnoDB COMMENT='课程授课教练关联';

-- 教练课程方案(新课程申请)
CREATE TABLE `course_proposal` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `proposal_no`        VARCHAR(32) NOT NULL,
  `coach_id`           BIGINT UNSIGNED NOT NULL,
  `category_id`        BIGINT UNSIGNED DEFAULT NULL,
  `course_name`        VARCHAR(64) NOT NULL,
  `course_type`        VARCHAR(20) NOT NULL DEFAULT 'GROUP' COMMENT 'GROUP团课/PRIVATE私教',
  `description`        TEXT,
  `training_goal`      VARCHAR(255) DEFAULT NULL,
  `target_audience`    VARCHAR(255) DEFAULT NULL,
  `difficulty`         VARCHAR(20) DEFAULT 'BEGINNER',
  `duration`           INT DEFAULT 60 COMMENT '建议时长(分钟)',
  `suggested_capacity` INT DEFAULT 8 COMMENT '建议人数',
  `credit_cost`        INT DEFAULT 1 COMMENT '建议消耗课时',
  `content`            TEXT COMMENT '课程内容说明',
  `precautions`        VARCHAR(512) DEFAULT NULL,
  `status`             VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿/PENDING_REVIEW审核中/APPROVED已通过/REJECTED已驳回/CANCELLED已取消',
  `reject_reason`      VARCHAR(255) DEFAULT NULL,
  `reviewer_id`        BIGINT UNSIGNED DEFAULT NULL,
  `reviewed_at`        DATETIME DEFAULT NULL,
  `course_id`          BIGINT UNSIGNED DEFAULT NULL COMMENT '通过后生成的正式课程ID',
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`            TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proposal_no` (`proposal_no`),
  KEY `idx_coach_status_course_proposal` (`coach_id`,`status`)
) ENGINE=InnoDB COMMENT='教练课程方案';

-- 课程版本(修改历史)
CREATE TABLE `course_version` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`     BIGINT UNSIGNED NOT NULL,
  `version_no`    INT NOT NULL DEFAULT 1,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `operator_name` VARCHAR(64) DEFAULT NULL,
  `change_content` VARCHAR(1024) DEFAULT NULL COMMENT '修改内容摘要',
  `reason`        VARCHAR(255) DEFAULT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_course_course_version` (`course_id`,`version_no`)
) ENGINE=InnoDB COMMENT='课程版本历史';

-- =====================================================================
-- 二期 Phase 6-7: 意见反馈 / 邀请好友
-- =====================================================================

-- 意见反馈
CREATE TABLE `feedback` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `type`       VARCHAR(32) DEFAULT NULL COMMENT '问题类型: 课程/教练/支付/体验/建议/其他',
  `content`    VARCHAR(1024) NOT NULL,
  `images`     VARCHAR(2048) DEFAULT NULL COMMENT '图片JSON数组',
  `contact`    VARCHAR(128) DEFAULT NULL COMMENT '联系方式',
  `status`     VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待处理/PROCESSING处理中/DONE已完成',
  `handled_by` BIGINT UNSIGNED DEFAULT NULL,
  `handled_at` DATETIME DEFAULT NULL,
  `reply`      VARCHAR(512) DEFAULT NULL COMMENT '处理回复',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_feedback` (`user_id`),
  KEY `idx_status_feedback` (`status`)
) ENGINE=InnoDB COMMENT='意见反馈';

-- 邀请好友
CREATE TABLE `invitation` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inviter_id`  BIGINT UNSIGNED NOT NULL COMMENT '邀请人 user_id',
  `invite_code` VARCHAR(32) NOT NULL COMMENT '邀请码',
  `invitee_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '被邀请人 user_id(注册后回填)',
  `status`      VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待注册/REGISTERED已注册/REWARDED已发奖',
  `rewarded_at` DATETIME DEFAULT NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invite_code` (`invite_code`),
  KEY `idx_inviter` (`inviter_id`),
  KEY `idx_invitee` (`invitee_id`)
) ENGINE=InnoDB COMMENT='邀请好友';

-- =====================================================================
-- 二期 Phase 6: 首页运营配置(热门课程/首页教练团队)
-- =====================================================================

-- 首页热门课程
CREATE TABLE `home_hot_course` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`  BIGINT UNSIGNED NOT NULL,
  `sort`       INT NOT NULL DEFAULT 0,
  `enabled`    TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hot_course` (`course_id`)
) ENGINE=InnoDB COMMENT='首页热门课程';

-- 首页教练团队展示
CREATE TABLE `home_team_coach` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`   BIGINT UNSIGNED NOT NULL,
  `sort`       INT NOT NULL DEFAULT 0,
  `intro`      VARCHAR(255) DEFAULT NULL COMMENT '首页展示简介',
  `enabled`    TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_coach` (`coach_id`)
) ENGINE=InnoDB COMMENT='首页教练团队';
