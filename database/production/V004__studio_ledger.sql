ALTER TABLE `user` MODIFY COLUMN openid VARCHAR(64) NULL COMMENT '微信 openid，线下会员待绑定时为空';

CREATE TABLE studio_wallet_profile (
  member_id BIGINT UNSIGNED PRIMARY KEY,
  mode VARCHAR(12) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  source_ref VARCHAR(128) UNIQUE,
  import_mode VARCHAR(12),
  cutoff_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_wallet (
  member_id BIGINT UNSIGNED NOT NULL,
  bucket VARCHAR(12) NOT NULL,
  balance_fen BIGINT NOT NULL DEFAULT 0,
  sequence_no BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (member_id,bucket),
  CHECK (balance_fen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_wallet_entry (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT UNSIGNED NOT NULL,
  bucket VARCHAR(12) NOT NULL,
  sequence_no BIGINT NOT NULL,
  kind VARCHAR(20) NOT NULL,
  delta_fen BIGINT NOT NULL,
  before_fen BIGINT NOT NULL,
  after_fen BIGINT NOT NULL,
  source_kind VARCHAR(16) NOT NULL,
  source_id BIGINT UNSIGNED NOT NULL,
  reversal_of BIGINT UNSIGNED UNIQUE,
  business_at DATETIME(6) NOT NULL,
  posted_at DATETIME(6) NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(255) NOT NULL,
  UNIQUE KEY uk_studio_wallet_sequence (member_id,bucket,sequence_no),
  KEY idx_studio_entry_source (source_kind,source_id),
  KEY idx_studio_member_entry (member_id,id),
  CHECK (delta_fen <> 0 AND before_fen >= 0 AND after_fen >= 0 AND before_fen + delta_fen = after_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_receipt (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT UNSIGNED NOT NULL,
  kind VARCHAR(12) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFY',
  version INT NOT NULL DEFAULT 1,
  amount_fen BIGINT NOT NULL,
  general_fen BIGINT NOT NULL,
  group_fen BIGINT NOT NULL,
  private_fen BIGINT NOT NULL,
  channel VARCHAR(32) NOT NULL,
  external_ref VARCHAR(128) UNIQUE,
  original_id BIGINT UNSIGNED,
  business_at DATETIME(6) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  verified_at DATETIME(6),
  KEY idx_studio_receipt_member (member_id,id),
  KEY idx_studio_receipt_review (state,id),
  KEY idx_studio_receipt_refund (original_id,state),
  CHECK (amount_fen >= 0 AND general_fen >= 0 AND group_fen >= 0 AND private_fen >= 0
         AND amount_fen = general_fen + group_fen + private_fen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_fee_rule (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  course_type VARCHAR(12) NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  free_minutes INT NOT NULL,
  late_kind VARCHAR(12) NOT NULL,
  late_value INT NOT NULL,
  absent_kind VARCHAR(12) NOT NULL,
  absent_value INT NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  UNIQUE KEY uk_studio_rule_start (course_type,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_lesson_session (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT UNSIGNED UNIQUE,
  source_ref VARCHAR(128) UNIQUE,
  course_name VARCHAR(64) NOT NULL,
  coach_name VARCHAR(64) NOT NULL,
  original_type VARCHAR(20),
  course_type VARCHAR(12) NOT NULL,
  start_at DATETIME(6) NOT NULL,
  end_at DATETIME(6) NOT NULL,
  price_fen BIGINT NOT NULL,
  rule_id BIGINT UNSIGNED,
  version INT NOT NULL DEFAULT 1,
  KEY idx_studio_session_start (start_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_member_lesson (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT UNSIGNED NOT NULL,
  session_id BIGINT UNSIGNED NOT NULL,
  booking_id BIGINT UNSIGNED UNIQUE,
  outcome VARCHAR(24) NOT NULL,
  cancelled_at DATETIME(6),
  decision VARCHAR(16) NOT NULL,
  manual_fen BIGINT,
  reason VARCHAR(255) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFY',
  settlement VARCHAR(24) NOT NULL DEFAULT 'UNPOSTED',
  settlement_basis VARCHAR(20),
  version INT NOT NULL DEFAULT 1,
  rule_id BIGINT UNSIGNED,
  price_fen BIGINT,
  amount_fen BIGINT,
  bucket VARCHAR(12),
  entry_id BIGINT UNSIGNED,
  recorded_at DATETIME(6) NOT NULL,
  verified_at DATETIME(6),
  UNIQUE KEY uk_studio_member_session (member_id,session_id),
  KEY idx_studio_lesson_review (state,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_claim_invite (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT UNSIGNED NOT NULL,
  code_hash CHAR(64) NOT NULL UNIQUE,
  state VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  claim_openid VARCHAR(64),
  claim_unionid VARCHAR(64),
  KEY idx_studio_claim_member (member_id,id),
  KEY idx_studio_claim_identity (claim_openid,state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_admin_command (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  operator_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  result_id BIGINT UNSIGNED,
  recorded_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_studio_command (operator_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_admin_audit (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  operator_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(32) NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  details TEXT NOT NULL,
  KEY idx_studio_audit_target (action,target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_late_fee_grant (
  operator_id BIGINT UNSIGNED PRIMARY KEY,
  enabled TINYINT NOT NULL,
  changed_by BIGINT UNSIGNED NOT NULL,
  changed_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE studio_booking_terms (
  booking_id BIGINT UNSIGNED PRIMARY KEY,
  member_id BIGINT UNSIGNED NOT NULL,
  schedule_id BIGINT UNSIGNED NOT NULL,
  session_id BIGINT UNSIGNED NOT NULL,
  course_type VARCHAR(12) NOT NULL,
  start_at DATETIME(6) NOT NULL,
  price_fen BIGINT NOT NULL,
  rule_id BIGINT UNSIGNED NOT NULL,
  cutoff_at DATETIME(6) NOT NULL,
  late_fen BIGINT NOT NULL,
  cancelled_at DATETIME(6),
  state VARCHAR(24) NOT NULL DEFAULT 'BOOKED',
  bucket VARCHAR(12),
  decision_by BIGINT UNSIGNED,
  decided_at DATETIME(6),
  decision_reason VARCHAR(255),
  entry_id BIGINT UNSIGNED,
  KEY idx_studio_terms_member (member_id,booking_id),
  KEY idx_studio_terms_state (state,booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
