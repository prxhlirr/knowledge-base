CREATE TABLE IF NOT EXISTS public.kb_acl_projection_task (
    id BIGSERIAL PRIMARY KEY,
    source_name VARCHAR(512) NOT NULL,
    target_index VARCHAR(255) NOT NULL,
    subject_type VARCHAR(32),
    subject_value VARCHAR(255),
    acl_token VARCHAR(255) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kapt_retry
    ON public.kb_acl_projection_task (status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_kapt_source
    ON public.kb_acl_projection_task (source_name);
