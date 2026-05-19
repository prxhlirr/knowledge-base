-- 创建由于人为对搜索结果打标关联的扩展表 (标签、关键词保存)
CREATE TABLE IF NOT EXISTS sys_search_tag (
    id BIGSERIAL PRIMARY KEY,
    doc_id VARCHAR(128) NOT NULL,
    index_name VARCHAR(128),
    tags TEXT,
    keywords TEXT,
    sync_status SMALLINT DEFAULT 0, -- 0:未同步 1:已同步 2:同步失败
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_doc_id UNIQUE (doc_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_search_tag_doc_id ON sys_search_tag(doc_id);

-- [OOM Fix] Ensure boyang-kb matches the actual ES index name.
-- DbInitializer.java had a bug: allowed_indices='knowledge_base' (wrong).
-- ES actual index: kb_document_v1 (26 docs). Also removing PDF-only filter.
INSERT INTO sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level, is_deleted)
VALUES ('boyang-kb', 'kb_document_v1', null, 0, 0)
ON CONFLICT (app_code) DO UPDATE
    SET allowed_indices    = 'kb_document_v1',
        force_file_type    = null,
        min_security_level = 0;

