USE yoyi_pilates;

-- 二期新增表: 请假/调课/训练模板/候补/隐私/教练可用时间
-- =====================================================================

-- 教练请假
CREATE TABLE IF NOT EXISTS `coach_leave` (
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
  KEY `idx_coach_date` (`coach_id`,`leave_date`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='教练请假';

-- 调课申请
CREATE TABLE IF NOT EXISTS `schedule_change_request` (
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
  KEY `idx_schedule` (`schedule_id`)
) ENGINE=InnoDB COMMENT='调课申请';

-- 训练记录模板
CREATE TABLE IF NOT EXISTS `training_record_template` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `coach_id`   BIGINT UNSIGNED NOT NULL,
  `name`       VARCHAR(64) NOT NULL,
  `content`    TEXT COMMENT '模板内容(训练目标/动作/强度等)',
  `sort`       INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`    TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_coach` (`coach_id`)
) ENGINE=InnoDB COMMENT='教练训练记录模板';

-- 学员隐私设置
CREATE TABLE IF NOT EXISTS `user_privacy` (
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
CREATE TABLE IF NOT EXISTS `waitlist` (
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
  KEY `idx_schedule_status` (`schedule_id`,`status`)
) ENGINE=InnoDB COMMENT='候补队列';

-- 教练可预约时间
CREATE TABLE IF NOT EXISTS `coach_availability` (
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
