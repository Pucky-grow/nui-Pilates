-- Apply with the database administrator after V004 completes on the isolated test database.
-- Runtime already has the scoped V001–V003 grants. Financial entries and audits are append-only.
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_wallet_profile` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_wallet` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT ON `uni_pilates_test`.`studio_wallet_entry` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_receipt` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_fee_rule` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_lesson_session` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_member_lesson` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_claim_invite` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_admin_command` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT ON `uni_pilates_test`.`studio_admin_audit` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_late_fee_grant` TO 'uni_pilates_runtime'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`studio_booking_terms` TO 'uni_pilates_runtime'@'%';
