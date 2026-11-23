USE yoyi_pilates;

-- 二期 Phase 6-7: 意见反馈 / 邀请好友
-- =====================================================================

-- 意见反馈
CREATE TABLE IF NOT EXISTS `feedback` (
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
CREATE TABLE IF NOT EXISTS `invitation` (
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
CREATE TABLE IF NOT EXISTS `home_hot_course` (
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
CREATE TABLE IF NOT EXISTS `home_team_coach` (
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
