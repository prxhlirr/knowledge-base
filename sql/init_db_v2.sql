-- 1. 创建审计日志表 (F9)
CREATE TABLE IF NOT EXISTS search_audit_log (
    id BIGSERIAL PRIMARY KEY,
    app_code VARCHAR(64) NOT NULL,
    query_text TEXT NOT NULL,
    normalized_query TEXT,
    top_hits_count INT DEFAULT 0,
    embedding_cost_ms INT DEFAULT 0,
    es_cost_ms INT DEFAULT 0,
    rerank_cost_ms INT DEFAULT 0,
    total_cost_ms INT DEFAULT 0,
    user_id VARCHAR(64),
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_search_audit_app_time ON search_audit_log (app_code, create_time);

-- 2. 初始化厂家策略数据 (解决 AppCode 无效问题)
-- 假设 index_pattern 为 'knowledge_base_*'，可根据实际情况调整
INSERT INTO sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level, is_deleted)
VALUES ('boyang-kb', 'kb_document*', 'PDF', 1, 0)
ON CONFLICT (id) DO NOTHING;

-- 3. 初始化全局调优配置 (F3/F6)
INSERT INTO sys_ai_tuning_config (id, model_path, reranker_path, device, cpu_threads, use_fp16, batch_size, bm25_weight, vector_weight, rrf_window_size, circuit_breaker_enabled, title_boost, es_norm_base, rerank_fusion_ratio, rerank_limit, rerank_max_chars)
VALUES (1, 'E:\project\AI\knowledge-base\models\bge-m3', 'E:\project\AI\knowledge-base\models\bge-reranker-v2-m3', 'cpu', 4, true, 16, 0.3, 0.7, 60, false, 5.0, 20.0, 0.7, 10, 500)
ON CONFLICT (id) DO NOTHING;
