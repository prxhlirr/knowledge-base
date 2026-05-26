-- ============================================================
-- Flyway V1: 知识库核心 Schema 初始化
-- 整合来源：
--   - DbInitRunner.java（原 CommandLineRunner 内嵌 DDL）
--   - DatabaseInitializer.java（外部 SQL 文件加载）
--   - db/sys_ai_tuning_config.sql
-- ============================================================

-- ─── 1. 租户鉴权策略表 ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_tenant_policy (
    id          BIGSERIAL     PRIMARY KEY,
    app_code    VARCHAR(64)   NOT NULL UNIQUE,
    allowed_indices VARCHAR(255) NOT NULL,
    force_file_type VARCHAR(64),
    min_security_level INT     DEFAULT 0,
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    is_deleted  SMALLINT      DEFAULT 0
);

-- 初始化测试策略数据
INSERT INTO public.sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level)
VALUES
    ('ADMIN_MASTER_KEY', 'kb_*',           NULL,       0),
    ('VEND_A_7788',      'kb_document*',   'document', 0),
    ('VEND_B_9900',      'kb_document*',   NULL,       1),
    ('boyang-kb',        'kb_document_v1', NULL,       0)
ON CONFLICT (app_code) DO NOTHING;

-- ─── 2. AI 调优配置表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_ai_tuning_config (
    id                    SERIAL        PRIMARY KEY,
    model_path            VARCHAR(255),
    reranker_path         VARCHAR(255),
    device                VARCHAR(50)   DEFAULT 'cpu',
    cpu_threads           INT           DEFAULT 4,
    use_fp16              BOOLEAN       DEFAULT true,
    batch_size            INT           DEFAULT 16,
    max_length_cpu        INT           DEFAULT 128,
    max_length_gpu        INT           DEFAULT 512,
    bm25_weight           NUMERIC(5,2)  DEFAULT 0.30,
    vector_weight         NUMERIC(5,2)  DEFAULT 0.70,
    rrf_window_size       INT           DEFAULT 60,
    recall_top_k          INT           DEFAULT 300,
    fusion_top_k          INT           DEFAULT 200,
    rerank_top_k          INT           DEFAULT 50,
    rerank_global_max_chars INT         DEFAULT 12000,
    circuit_breaker_enabled BOOLEAN     DEFAULT false,
    title_boost           NUMERIC(5,2)  DEFAULT 5.0,
    es_norm_base          NUMERIC(5,2)  DEFAULT 20.0,
    rerank_fusion_ratio   NUMERIC(5,2)  DEFAULT 0.7,
    rerank_limit          INT           DEFAULT 10,
    rerank_max_chars      INT           DEFAULT 500,
    knn_num_candidates    INT           DEFAULT 200,
    -- 调优扩展字段（原 migration_tuning_params.sql + SearchService.@PostConstruct）
    es_query_timeout          INT           DEFAULT 1500,
    embedding_timeout         INT           DEFAULT 1500,
    rerank_timeout            INT           DEFAULT 1500,
    lexical_fast_path_max_length INT        DEFAULT 4,
    adaptive_breaker_max_length  INT        DEFAULT 4,
    breaker_rrf_threshold     NUMERIC(10,4) DEFAULT 0.0200,
    breaker_raw_score_threshold NUMERIC(10,4) DEFAULT 1.0000,
    truthful_ui_max_score_limit NUMERIC(10,4) DEFAULT 0.1500,
    truthful_ui_ceiling       NUMERIC(10,4) DEFAULT 0.7500,
    quality_breaker_floor     NUMERIC(10,4) DEFAULT 0.0600,
    updated_time              TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE public.sys_ai_tuning_config IS 'AI 系统大模型核心热调优参数表';

-- 初始化默认配置行（id=1）
INSERT INTO public.sys_ai_tuning_config (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM public.sys_ai_tuning_config WHERE id = 1);

-- ─── 3. 文档批次管理表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_doc_batch (
    id            BIGSERIAL     PRIMARY KEY,
    batch_id      VARCHAR(64)   NOT NULL UNIQUE,
    source_dir    VARCHAR(512),
    source_info   TEXT,
    ingest_mode   VARCHAR(32)   DEFAULT 'LOCAL_DIR',
    total_count   INT           DEFAULT 0,
    success_count INT           DEFAULT 0,
    error_count   INT           DEFAULT 0,
    status        VARCHAR(32)   DEFAULT 'PENDING',
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    is_deleted    SMALLINT      DEFAULT 0
);

-- ─── 4. 文档导入原子任务表 ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_doc_import_task (
    id            BIGSERIAL      PRIMARY KEY,
    task_id       VARCHAR(64)    NOT NULL UNIQUE,
    batch_id      VARCHAR(64)    NOT NULL,
    file_path     VARCHAR(1024)  NOT NULL,
    original_name VARCHAR(255),
    visibility    VARCHAR(32)    DEFAULT 'INTERNAL',
    dept_code     VARCHAR(32),
    status        VARCHAR(32)    DEFAULT 'IDLE',
    error_msg     TEXT,
    created_at    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    is_deleted    SMALLINT       DEFAULT 0
);

-- ─── 5. 文档解析日志表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_file_parse_log (
    id             BIGSERIAL     PRIMARY KEY,
    file_code      VARCHAR(64)   NOT NULL UNIQUE,
    file_path      VARCHAR(1024),
    uploader       VARCHAR(128),
    uploader_dept  VARCHAR(128),
    upload_time    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    status         SMALLINT      DEFAULT 0,
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- ─── 6. 搜索审计日志表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.search_audit_log (
    id                BIGSERIAL   PRIMARY KEY,
    app_code          VARCHAR(64) NOT NULL,
    query_text        TEXT        NOT NULL,
    normalized_query  TEXT,
    top_hits_count    INT         DEFAULT 0,
    embedding_cost_ms INT         DEFAULT 0,
    es_cost_ms        INT         DEFAULT 0,
    rerank_cost_ms    INT         DEFAULT 0,
    total_cost_ms     INT         DEFAULT 0,
    user_id           VARCHAR(64),
    resolved_index    VARCHAR(255),
    search_mode       VARCHAR(32),
    return_top_k      INT         DEFAULT 0,
    recall_top_k      INT         DEFAULT 0,
    fusion_top_k      INT         DEFAULT 0,
    rerank_top_k      INT         DEFAULT 0,
    literal_hit_count INT         DEFAULT 0,
    bm25_hits         INT         DEFAULT 0,
    knn_hits          INT         DEFAULT 0,
    sparse_hits       INT         DEFAULT 0,
    qa_hits           INT         DEFAULT 0,
    rrf_candidates    INT         DEFAULT 0,
    rerank_input_count INT        DEFAULT 0,
    rerank_degraded   BOOLEAN     DEFAULT false,
    rerank_semaphore_rejected BOOLEAN DEFAULT false,
    llm_semaphore_rejected BOOLEAN DEFAULT false,
    admin_bypass      BOOLEAN     DEFAULT false,
    post_filter_denied_count INT  DEFAULT 0,
    create_time       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

-- ─── 7. 权限事件溯源表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.doc_permission_events (
    id           BIGSERIAL     PRIMARY KEY,
    doc_id       VARCHAR(512)  NOT NULL,
    action       VARCHAR(32)   NOT NULL CHECK (action IN ('GRANT','REVOKE','HANDLER','VISIBILITY_CHANGE')),
    target_type  VARCHAR(16)   NOT NULL CHECK (target_type IN ('USER','DEPT','GROUP')),
    target_value VARCHAR(128)  NOT NULL,
    operator_id  VARCHAR(64)   NOT NULL,
    remark       VARCHAR(256),
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dpe_doc    ON public.doc_permission_events (doc_id);
CREATE INDEX IF NOT EXISTS idx_dpe_time   ON public.doc_permission_events (created_at);
CREATE INDEX IF NOT EXISTS idx_dpe_target ON public.doc_permission_events (target_type, target_value);

-- ─── 8. 权限组相关表 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.permission_groups (
    id          BIGSERIAL    PRIMARY KEY,
    group_code  VARCHAR(64)  UNIQUE NOT NULL,
    group_name  VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    created_by  VARCHAR(64),
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.permission_group_members (
    id          BIGSERIAL   PRIMARY KEY,
    group_code  VARCHAR(64) NOT NULL,
    dept_code   VARCHAR(32),
    user_id     VARCHAR(64),
    granted_by  VARCHAR(64),
    granted_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pgm_group ON public.permission_group_members (group_code);
CREATE INDEX IF NOT EXISTS idx_pgm_dept  ON public.permission_group_members (dept_code);

-- ─── 9. 文档版本历史表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.doc_version_history (
    id           BIGSERIAL    PRIMARY KEY,
    source_name  VARCHAR(512) NOT NULL,
    doc_version  INT          NOT NULL,
    content_hash VARCHAR(64),
    chunk_count  INT,
    operator_id  VARCHAR(64),
    visibility   VARCHAR(32)  DEFAULT 'INTERNAL',
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_name, doc_version)
);
CREATE INDEX IF NOT EXISTS idx_dvh_source ON public.doc_version_history (source_name);
CREATE INDEX IF NOT EXISTS idx_dvh_hash   ON public.doc_version_history (content_hash);

-- ─── 10. 部门树表 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.dept_tree (
    dept_code   VARCHAR(32)  PRIMARY KEY,
    dept_name   VARCHAR(128) NOT NULL,
    dept_l2     VARCHAR(4),
    dept_l4     VARCHAR(8),
    dept_l6     VARCHAR(12),
    dept_l9     VARCHAR(18),
    dept_level  SMALLINT,
    parent_code VARCHAR(32),
    is_active   SMALLINT     DEFAULT 1
);

-- ─── 11. 文档注册中心表 kb_doc_registry ──────────────────────────
CREATE TABLE IF NOT EXISTS public.kb_doc_registry (
    id            BIGSERIAL     PRIMARY KEY,
    doc_id        VARCHAR(256)  NOT NULL,
    source_name   VARCHAR(512)  NOT NULL,
    doc_version   INT           NOT NULL DEFAULT 1,
    is_latest     SMALLINT      NOT NULL DEFAULT 1,
    storage_path  VARCHAR(1024),
    target_index  VARCHAR(128)  NOT NULL DEFAULT 'kb_document_v1',
    chunk_count   INT           DEFAULT 0,
    content_hash  VARCHAR(64),
    doc_number    VARCHAR(128),
    unit          VARCHAR(256),
    tags          VARCHAR(512),
    publish_time  DATE,
    visibility    VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL',
    dept_code     VARCHAR(32),
    uploader_id   VARCHAR(64),
    uploader_name VARCHAR(128),
    status        VARCHAR(32)   NOT NULL DEFAULT 'INDEXED',
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_name, doc_version)
);
CREATE INDEX IF NOT EXISTS idx_kdr_source   ON public.kb_doc_registry (source_name);
CREATE INDEX IF NOT EXISTS idx_kdr_latest   ON public.kb_doc_registry (is_latest, status);
CREATE INDEX IF NOT EXISTS idx_kdr_uploader ON public.kb_doc_registry (uploader_id);
CREATE INDEX IF NOT EXISTS idx_kdr_dept     ON public.kb_doc_registry (dept_code);

-- ─── 12. 文档授权明细表 kb_doc_grants ───────────────────────────
CREATE TABLE IF NOT EXISTS public.kb_doc_grants (
    id            BIGSERIAL     PRIMARY KEY,
    registry_id   BIGINT        NOT NULL REFERENCES kb_doc_registry(id),
    source_name   VARCHAR(512)  NOT NULL,
    grantee_id    VARCHAR(64)   NOT NULL,
    grantee_name  VARCHAR(128),
    expires_at    TIMESTAMP,
    granted_by    VARCHAR(64)   NOT NULL,
    remark        VARCHAR(256),
    is_active     SMALLINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kdg_unique_grant ON public.kb_doc_grants (source_name, grantee_id) WHERE is_active = 1;
CREATE INDEX IF NOT EXISTS idx_kdg_source  ON public.kb_doc_grants (source_name, is_active);
CREATE INDEX IF NOT EXISTS idx_kdg_grantee ON public.kb_doc_grants (grantee_id, is_active);

-- ─── 13. 政务同义词表 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_gov_synonym (
    id           BIGSERIAL     PRIMARY KEY,
    abbr         VARCHAR(64)   NOT NULL UNIQUE,
    full_forms   TEXT          NOT NULL,
    category     VARCHAR(64),
    is_enabled   SMALLINT      DEFAULT 1,
    created_at   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- ─── 14. 搜索标签配置表 ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.sys_search_tag (
    id         BIGSERIAL    PRIMARY KEY,
    tag_code   VARCHAR(64)  NOT NULL UNIQUE,
    tag_name   VARCHAR(128) NOT NULL,
    sort_order INT          DEFAULT 0,
    is_enabled SMALLINT     DEFAULT 1,
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
