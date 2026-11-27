USE yoyi_pilates;

-- 二期 Phase 5: 课程体系(课程↔教练多对多/课程方案/课程版本)
-- =====================================================================

-- 课程-教练关联(授课资格, 多对多)
CREATE TABLE IF NOT EXISTS `course_coach` (
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
CREATE TABLE IF NOT EXISTS `course_proposal` (
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
CREATE TABLE IF NOT EXISTS `course_version` (
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
