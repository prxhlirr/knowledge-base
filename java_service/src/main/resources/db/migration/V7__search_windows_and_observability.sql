-- Flyway V7: decouple search windows and add lightweight observability columns.
-- This migration deliberately leaves rrf_window_size/getRrfK semantics unchanged.

ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS recall_top_k INT DEFAULT 300;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS fusion_top_k INT DEFAULT 200;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS rerank_top_k INT DEFAULT 50;
ALTER TABLE public.sys_ai_tuning_config ADD COLUMN IF NOT EXISTS rerank_global_max_chars INT DEFAULT 12000;

COMMENT ON COLUMN public.sys_ai_tuning_config.recall_top_k
    IS 'Internal ES recall window, independent from API page size.';
COMMENT ON COLUMN public.sys_ai_tuning_config.fusion_top_k
    IS 'RRF fused candidate window passed to downstream rerank/assembly.';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_top_k
    IS 'Cross-encoder rerank input document count.';
COMMENT ON COLUMN public.sys_ai_tuning_config.rerank_global_max_chars
    IS 'Global text character budget for one rerank request.';

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
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_degraded BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS rerank_semaphore_rejected BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS llm_semaphore_rejected BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS admin_bypass BOOLEAN DEFAULT false;
ALTER TABLE public.search_audit_log ADD COLUMN IF NOT EXISTS post_filter_denied_count INT DEFAULT 0;
