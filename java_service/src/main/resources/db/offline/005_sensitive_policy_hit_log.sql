CREATE TABLE IF NOT EXISTS public.kb_sensitive_policy_hit_log (
    id BIGSERIAL PRIMARY KEY,
    policy_id BIGINT NOT NULL,
    app_code VARCHAR(64),
    user_id VARCHAR(128),
    stage VARCHAR(32) NOT NULL,
    source_name VARCHAR(512),
    doc_id VARCHAR(255),
    field_name VARCHAR(128),
    action VARCHAR(32),
    hit_count INT NOT NULL DEFAULT 1,
    trace_id VARCHAR(64),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ksph_policy_time
    ON public.kb_sensitive_policy_hit_log (policy_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ksph_user_time
    ON public.kb_sensitive_policy_hit_log (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ksph_stage_time
    ON public.kb_sensitive_policy_hit_log (stage, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ksph_source_time
    ON public.kb_sensitive_policy_hit_log (source_name, created_at DESC);
