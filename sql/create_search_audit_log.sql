-- 混合检索模块搜索审计日志表 (PostgreSQL)
CREATE TABLE IF NOT EXISTS search_audit_log (
    id BIGSERIAL PRIMARY KEY,
    app_code VARCHAR(64) NOT NULL, -- 应用标识
    query_text TEXT NOT NULL, -- 用户原始输入
    normalized_query TEXT, -- 标准化后的查询词
    top_hits_count INT DEFAULT 0, -- 召回条数
    embedding_cost_ms INT DEFAULT 0, -- 向量化耗时
    es_cost_ms INT DEFAULT 0, -- ES检索耗时
    rerank_cost_ms INT DEFAULT 0, -- 重排耗时
    total_cost_ms INT DEFAULT 0, -- 总耗时
    user_id VARCHAR(64), -- 用户ID
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP -- 记录时间
);

-- 添加注释 (PostgreSQL 习惯用法)
COMMENT ON TABLE search_audit_log IS '搜索审计日志表';
COMMENT ON COLUMN search_audit_log.app_code IS '应用标识';
COMMENT ON COLUMN search_audit_log.query_text IS '用户原始输入';
COMMENT ON COLUMN search_audit_log.normalized_query IS '标准化后的查询词';
COMMENT ON COLUMN search_audit_log.top_hits_count IS '召回条数';
COMMENT ON COLUMN search_audit_log.embedding_cost_ms IS '向量化耗时';
COMMENT ON COLUMN search_audit_log.es_cost_ms IS 'ES检索耗时';
COMMENT ON COLUMN search_audit_log.rerank_cost_ms IS '重排耗时';
COMMENT ON COLUMN search_audit_log.total_cost_ms IS '总耗时';
COMMENT ON COLUMN search_audit_log.user_id IS '用户ID';
COMMENT ON COLUMN search_audit_log.create_time IS '记录时间';

-- 创建索引以优化查询
CREATE INDEX IF NOT EXISTS idx_search_audit_app_time ON search_audit_log (app_code, create_time);
