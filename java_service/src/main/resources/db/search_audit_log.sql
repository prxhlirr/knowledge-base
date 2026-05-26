-- ====================================================================
-- search_audit_log standalone schema script
-- Target database: PostgreSQL
--
-- Purpose:
--   1. Create search_audit_log for fresh offline deployments.
--   2. Backfill missing columns for existing deployments.
--   3. Add lightweight indexes for latency, degradation and permission audits.
--
-- Safe to run repeatedly.
-- ====================================================================

CREATE TABLE IF NOT EXISTS public.search_audit_log (
    id                         BIGSERIAL PRIMARY KEY,
    app_code                   VARCHAR(64) NOT NULL,
    query_text                 TEXT NOT NULL,
    normalized_query           TEXT,
    top_hits_count             INT DEFAULT 0,
    embedding_cost_ms          INT DEFAULT 0,
    es_cost_ms                 INT DEFAULT 0,
    rerank_cost_ms             INT DEFAULT 0,
    total_cost_ms              INT DEFAULT 0,
    user_id                    VARCHAR(64),
    resolved_index             VARCHAR(255),
    search_mode                VARCHAR(32),
    return_top_k               INT DEFAULT 0,
    recall_top_k               INT DEFAULT 0,
    fusion_top_k               INT DEFAULT 0,
    rerank_top_k               INT DEFAULT 0,
    literal_hit_count          INT DEFAULT 0,
    bm25_hits                  INT DEFAULT 0,
    knn_hits                   INT DEFAULT 0,
    sparse_hits                INT DEFAULT 0,
    qa_hits                    INT DEFAULT 0,
    rrf_candidates             INT DEFAULT 0,
    rerank_input_count         INT DEFAULT 0,
    rerank_degraded            BOOLEAN DEFAULT false,
    rerank_semaphore_rejected  BOOLEAN DEFAULT false,
    llm_semaphore_rejected     BOOLEAN DEFAULT false,
    admin_bypass               BOOLEAN DEFAULT false,
    post_filter_denied_count   INT DEFAULT 0,
    create_time                TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Base columns. Keep ALTER columns nullable so upgrades do not fail on old data.
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS app_code VARCHAR(64);
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS query_text TEXT;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS normalized_query TEXT;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS top_hits_count INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS embedding_cost_ms INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS es_cost_ms INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_cost_ms INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS total_cost_ms INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- Search window and recall observability columns.
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS resolved_index VARCHAR(255);
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS search_mode VARCHAR(32);
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS return_top_k INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS recall_top_k INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS fusion_top_k INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_top_k INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS literal_hit_count INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS bm25_hits INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS knn_hits INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS sparse_hits INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS qa_hits INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rrf_candidates INT DEFAULT 0;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_input_count INT DEFAULT 0;

-- Degradation and security observability columns.
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_degraded BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_semaphore_rejected BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS llm_semaphore_rejected BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS admin_bypass BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS post_filter_denied_count INT DEFAULT 0;

COMMENT ON TABLE public.search_audit_log
    IS 'Search audit log for latency, recall windows, degradation and permission observability.';
COMMENT ON COLUMN public.search_audit_log.resolved_index
    IS 'Resolved ES index or index pattern used by the request.';
COMMENT ON COLUMN public.search_audit_log.search_mode
    IS 'Search mode: keyword, semantic or hybrid.';
COMMENT ON COLUMN public.search_audit_log.return_top_k
    IS 'External return window requested by API pagination.';
COMMENT ON COLUMN public.search_audit_log.recall_top_k
    IS 'Internal ES recall window.';
COMMENT ON COLUMN public.search_audit_log.fusion_top_k
    IS 'RRF fused candidate window.';
COMMENT ON COLUMN public.search_audit_log.rerank_top_k
    IS 'Configured rerank input window.';
COMMENT ON COLUMN public.search_audit_log.literal_hit_count
    IS 'High-confidence literal metadata recall hit count.';
COMMENT ON COLUMN public.search_audit_log.rrf_candidates
    IS 'Candidate count after RRF fusion.';
COMMENT ON COLUMN public.search_audit_log.rerank_input_count
    IS 'Actual document count sent to reranker.';
COMMENT ON COLUMN public.search_audit_log.rerank_degraded
    IS 'Whether rerank was skipped, timed out or degraded.';
COMMENT ON COLUMN public.search_audit_log.rerank_semaphore_rejected
    IS 'Whether ColBERT/rerank semaphore rejected the request.';
COMMENT ON COLUMN public.search_audit_log.llm_semaphore_rejected
    IS 'Whether LLM semaphore rejected the request.';
COMMENT ON COLUMN public.search_audit_log.admin_bypass
    IS 'Whether super-admin permission bypass was used.';
COMMENT ON COLUMN public.search_audit_log.post_filter_denied_count
    IS 'Documents removed by post PermissionGuard filtering.';

CREATE INDEX IF NOT EXISTS idx_search_audit_log_create_time
    ON public.search_audit_log (create_time DESC);

CREATE INDEX IF NOT EXISTS idx_search_audit_log_app_time
    ON public.search_audit_log (app_code, create_time DESC);

CREATE INDEX IF NOT EXISTS idx_search_audit_log_user_time
    ON public.search_audit_log (user_id, create_time DESC);

CREATE INDEX IF NOT EXISTS idx_search_audit_log_mode_time
    ON public.search_audit_log (search_mode, create_time DESC);

CREATE INDEX IF NOT EXISTS idx_search_audit_log_degraded_time
    ON public.search_audit_log (create_time DESC)
    WHERE rerank_degraded = true
       OR rerank_semaphore_rejected = true
       OR llm_semaphore_rejected = true;

CREATE INDEX IF NOT EXISTS idx_search_audit_log_permission_denied_time
    ON public.search_audit_log (create_time DESC)
    WHERE post_filter_denied_count > 0;
