-- DRAFT: no credentials; install via DMC New Stored Procedure after operator review.
-- SQL SECURITY INVOKER does not elevate the caller. Do not execute on a business instance.
CREATE PROCEDURE cloudrun_grant_test_runtime()
SQL SECURITY INVOKER
BEGIN
  IF DATABASE()<>'uni_pilates_test' OR @@server_uuid<>'d1f6adf9-d5d5-11f1-9afc-0c42a17335be' OR VERSION()<>'8.0.30-cynos-3.1.19.002' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: wrong isolated target';
  END IF;
  IF (SELECT COUNT(*) FROM mysql.user WHERE user='uni_pilates_runtime' AND host='%' AND plugin='mysql_native_password')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: operator-created scoped runtime account required';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='uni_pilates_test' AND table_type='BASE TABLE')<>58 OR (SELECT COUNT(*) FROM uni_pilates_test.schema_history WHERE state='DONE' AND applied_steps=total_steps)<>3 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: unexpected schema';
  END IF;
  IF (SELECT COUNT(*) FROM mysql.role_edges WHERE TO_USER='uni_pilates_runtime' AND TO_HOST='%')<>0 OR (SELECT COUNT(*) FROM information_schema.user_privileges WHERE GRANTEE=CONCAT(CHAR(39),'uni_pilates_runtime',CHAR(39),'@',CHAR(39),'%',CHAR(39)) AND PRIVILEGE_TYPE<>'USAGE')<>0 OR (SELECT COUNT(*) FROM information_schema.schema_privileges WHERE GRANTEE=CONCAT(CHAR(39),'uni_pilates_runtime',CHAR(39),'@',CHAR(39),'%',CHAR(39)))<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing runtime global/schema/role grants require review';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.column_privileges WHERE GRANTEE=CONCAT(CHAR(39),'uni_pilates_runtime',CHAR(39),'@',CHAR(39),'%',CHAR(39)))<>0 OR (SELECT COUNT(*) FROM information_schema.table_privileges WHERE GRANTEE=CONCAT(CHAR(39),'uni_pilates_runtime',CHAR(39),'@',CHAR(39),'%',CHAR(39)) AND (TABLE_SCHEMA<>'uni_pilates_test' OR IS_GRANTABLE<>'NO' OR PRIVILEGE_TYPE NOT IN ('SELECT','INSERT','UPDATE','DELETE') OR (TABLE_NAME='schema_history' AND PRIVILEGE_TYPE<>'SELECT')))<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STOP: existing runtime table/column grants require review';
  END IF;
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`article` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`article_category` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`article_favorite` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`article_like` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`attendance` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`banner` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`body_assessment` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`body_measurement` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`booking` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`classroom` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`coach` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`coach_availability` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`coach_certificate` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`coach_leave` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`coupon` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_category` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_coach` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_proposal` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_review` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_schedule` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`course_version` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`event_outbox` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`feedback` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`file_object` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`home_hot_course` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`home_team_coach` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`invitation` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`member` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`member_level` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`notification` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`notification_delivery` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`notification_template` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`operation_log` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`order` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`order_item` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`package` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`package_credit_log` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`package_item` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`payment` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`points_record` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`refund` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`schedule_change_request` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`sys_permission` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`sys_role` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`sys_role_permission` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`sys_user` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`sys_user_role` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`system_config` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`training_record` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`training_record_template` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`user` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`user_coupon` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`user_package` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`user_privacy` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`user_profile` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT,INSERT,UPDATE,DELETE ON `uni_pilates_test`.`waitlist` TO 'uni_pilates_runtime'@'%';
  GRANT SELECT ON `uni_pilates_test`.`schema_history` TO 'uni_pilates_runtime'@'%';
END;
