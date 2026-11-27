-- 历史账号迁移已退役。当前角色权限见 database/production/V002__base.sql。
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retired account migration. Review current role assignments before making changes.';
