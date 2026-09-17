-- 手工执行脚本：扩展 ACL 投影补偿任务，支持完整 token 覆盖和单位链同步。
--
-- 执行前提：
--   1. 已存在 public.kb_acl_projection_task。
--   2. 本脚本使用 PostgreSQL 语法，允许重复执行。
--   3. 不修改既有 acl_token / operation 非空约束，兼容旧 TOKEN_DELTA 任务。

BEGIN;

ALTER TABLE public.kb_acl_projection_task
    ADD COLUMN IF NOT EXISTS task_type VARCHAR(32) NOT NULL DEFAULT 'TOKEN_DELTA';

ALTER TABLE public.kb_acl_projection_task
    ADD COLUMN IF NOT EXISTS payload_json TEXT;

CREATE INDEX IF NOT EXISTS idx_kapt_task_type_status
    ON public.kb_acl_projection_task (task_type, status, next_retry_at);

COMMENT ON COLUMN public.kb_acl_projection_task.task_type IS
    '投影任务类型：TOKEN_DELTA=单 token 增删；ACL_TOKENS_SYNC=完整 acl_tokens 覆盖；UNIT_SYNC=单位权限字段同步。';

COMMENT ON COLUMN public.kb_acl_projection_task.payload_json IS
    '完整投影任务的 JSON 载荷。ACL_TOKENS_SYNC 保存 aclTokens；UNIT_SYNC 保存 ownerUnitCode、visibleUnitCodes、permissionVersion。';

COMMIT;

-- 验证：
-- SELECT column_name, data_type, is_nullable, column_default
-- FROM information_schema.columns
-- WHERE table_schema = 'public'
--   AND table_name = 'kb_acl_projection_task'
--   AND column_name IN ('task_type', 'payload_json')
-- ORDER BY column_name;
