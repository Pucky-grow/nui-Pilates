# 历史演进脚本

这里保留旧阶段的 SQL，供核对历史结构和权限来源。新库使用 `database/production/V001` 至 `V004`，本机演示使用 `database/schema.sql` 和 `database/init_data.sql`。Round2 的预约和权限脚本由现有隔离升级验收使用。

旧的 `database/migration_account.sql` 曾批量清空角色映射，现已退役。需要修复现有账号权限时，应按当前角色和实际账号情况审查权限变更。
