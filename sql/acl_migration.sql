-- ============================================================
-- ACL Token 权限体系重构 — 数据库迁移脚本
-- 对应架构：Phase 1 + Phase 2
-- 执行顺序：本 SQL 先执行，再执行 ES Mapping 变更（注释中附带）
-- 环境：PostgreSQL（search_audit_log 所在库）
-- ============================================================


-- ──────────────────────────────────────────────────────────────
-- [Part 1] ES Mapping 变更（通过 curl/Kibana 执行，非 SQL）
-- ──────────────────────────────────────────────────────────────
--
-- 以下为 Elasticsearch REST API 命令，请在 Kibana Dev Tools 或 curl 中执行：
--
-- 1. 新增 acl_tokens 字段（keyword 数组，支持 terms 求交集权限过滤）
--
--   PUT /kb_document_v1/_mapping
--   {
--     "properties": {
--       "acl_tokens": { "type": "keyword" }
--     }
--   }
--
-- 2. 给 metadata.source 和 metadata.document_number 增加 text 子字段
--    （让 BM25 multiMatch 可以对文件名/文号进行分词检索，修复 boost 20x 无效的 Bug）
--
--   PUT /kb_document_v1/_mapping
--   {
--     "properties": {
--       "metadata": {
--         "properties": {
--           "source": {
--             "type": "keyword",
--             "fields": {
--               "text": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
--             }
--           },
--           "document_number": {
--             "type": "keyword",
--             "fields": {
--               "text": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
--             }
--           }
--         }
--       }
--     }
--   }
--
-- 3. 执行 _update_by_query 触发已有文档重建倒排索引（使 .text 子字段生效）
--
--   POST /kb_document_v1/_update_by_query?conflicts=proceed
--   { "query": { "match_all": {} } }
--
-- 注意：操作 3 会消耗大量 CPU/IO，建议在业务低峰期执行，可加 wait_for_completion=false 异步化。
-- ──────────────────────────────────────────────────────────────


-- ──────────────────────────────────────────────────────────────
-- [Part 2] search_audit_log 表 DDL 变更
-- Phase 2 新增 admin_bypass 和 post_filter_denied_count 审计字段
-- 支持合规审查（超管特权访问识别）和质量监控（ACL数据质量告警）
-- ──────────────────────────────────────────────────────────────

-- 2-1. 新增超管旁路标志列
ALTER TABLE search_audit_log
    ADD COLUMN IF NOT EXISTS admin_bypass BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN search_audit_log.admin_bypass
    IS '超管旁路标志：true=超级管理员特权访问，后置权限校验被跳过（合规审计用）';

-- 2-2. 新增后置过滤拦截计数列
ALTER TABLE search_audit_log
    ADD COLUMN IF NOT EXISTS post_filter_denied_count INT DEFAULT 0;

COMMENT ON COLUMN search_audit_log.post_filter_denied_count
    IS '后置权限过滤拦截的文档数量；>0 表示 ES 权限数据存在质量问题（幽灵文档漏出），触发告警';

-- 2-3. 新增超管访问专项索引（支撑安全审计查询）
CREATE INDEX IF NOT EXISTS idx_audit_admin_bypass
    ON search_audit_log (admin_bypass, create_time)
    WHERE admin_bypass = TRUE;

-- 2-4. 新增后置过滤告警索引（快速查询曾发生幽灵文档漏出的记录）
CREATE INDEX IF NOT EXISTS idx_audit_post_filter_denied
    ON search_audit_log (post_filter_denied_count, create_time)
    WHERE post_filter_denied_count > 0;


-- ──────────────────────────────────────────────────────────────
-- [Part 3] kb_doc_grants 表结构验证
-- 确保 check_access 查询所需字段和索引已存在
-- ──────────────────────────────────────────────────────────────

-- 3-1. 确认 is_active + expires_at 组合索引（KbDocGrantsMapper.checkAccess 的核心查询路径）
CREATE INDEX IF NOT EXISTS idx_grants_source_user_active
    ON kb_doc_grants (source_name, user_id, is_active, expires_at);

COMMENT ON INDEX idx_grants_source_user_active
    IS '支持 PermissionGuard.canAccess 中 GRANT 类文档的授权列表查询（source_name + user_id 精确匹配）';


-- ──────────────────────────────────────────────────────────────
-- [Part 4] 存量数据 acl_tokens 回填（可选，建议在 Migration Job 中执行）
-- 由于 ES 数据通过 rag_pipeline.py 写入，此处仅提供手工验证 SQL
-- 实际回填应通过 Java 侧的 AclTokenMigrationJob 通过 ES _update_by_query 执行
-- ──────────────────────────────────────────────────────────────

-- 4-1. 查询存量文档中尚未完成 acl_tokens 回填的记录（在 MySQL 侧统计）
-- 注：实际 acl_tokens 字段在 ES 中，此处仅统计 kb_doc_registry 中待迁移的文档量
SELECT
    visibility,
    COUNT(*) AS doc_count
FROM kb_doc_registry
WHERE status != 'DELETED'
GROUP BY visibility
ORDER BY doc_count DESC;

-- 4-2. 确认 KB_INTERNAL_TOKEN 配置（非生产环境自查用）
-- 生产环境此 Token 应通过 KB_INTERNAL_TOKEN 环境变量注入，不要直接写在配置文件中
-- SELECT current_setting('app.kb_internal_token', true);  -- 仅在支持 pg 参数读取的场景使用
