-- Runtime account/session revocation, stable file records and reliable event delivery.
ALTER TABLE sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE file_object (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id CHAR(36) NOT NULL, object_key VARCHAR(512) NOT NULL,
 owner_type VARCHAR(20) NOT NULL, owner_id BIGINT UNSIGNED NOT NULL,
 uploader_type VARCHAR(20) NOT NULL, uploader_id BIGINT UNSIGNED NOT NULL,
 purpose VARCHAR(32) NOT NULL, visibility VARCHAR(12) NOT NULL,
 content_type VARCHAR(64) NOT NULL, byte_size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_file_public_id(public_id), UNIQUE KEY uk_file_object_key(object_key), KEY idx_file_owner(owner_type,owner_id)
) ENGINE=InnoDB;
CREATE TABLE event_outbox (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key CHAR(64) NOT NULL, topic VARCHAR(64) NOT NULL, tag VARCHAR(64) NOT NULL,
 business_key VARCHAR(128) NOT NULL, payload MEDIUMTEXT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(255) DEFAULT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at DATETIME DEFAULT NULL,
 UNIQUE KEY uk_outbox_event(event_key), KEY idx_outbox_pending(state,next_attempt_at,id)
) ENGINE=InnoDB;
ALTER TABLE notification ADD UNIQUE KEY uk_notification_business(user_id,biz_type,biz_id,deleted);
CREATE TABLE notification_delivery (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key CHAR(64) NOT NULL, user_id BIGINT UNSIGNED NOT NULL, channel VARCHAR(20) NOT NULL,
 template_code VARCHAR(64) NOT NULL, template_id VARCHAR(128) NOT NULL,
 payload TEXT NOT NULL, page VARCHAR(255) DEFAULT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) DEFAULT NULL, last_error VARCHAR(255) DEFAULT NULL,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at DATETIME DEFAULT NULL,
 UNIQUE KEY uk_delivery_event_recipient_channel(event_key,user_id,channel), KEY idx_delivery_due(state,next_attempt_at,id)
) ENGINE=InnoDB;
