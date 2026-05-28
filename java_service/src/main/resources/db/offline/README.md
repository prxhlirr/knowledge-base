# 离线数据库脚本说明

本项目生产环境不依赖 Flyway 自动迁移，数据库结构由 DBA 或运维按离线 SQL 审核后执行。

建议执行顺序：

1. `001_doc_acl_subjects.sql`
2. `002_sensitive_policy.sql`
3. `003_acl_projection_task.sql`
4. `004_index_acl_subjects.sql`
5. `005_sensitive_policy_hit_log.sql`

所有脚本均使用 `IF NOT EXISTS`，可在多环境重复校验执行。应用侧 `spring.flyway.enabled=false`，避免启动时自动迁移。
