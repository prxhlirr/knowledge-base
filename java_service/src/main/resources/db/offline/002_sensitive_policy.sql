CREATE TABLE IF NOT EXISTS public.kb_sensitive_policy (
    id BIGSERIAL PRIMARY KEY,
    pattern_type VARCHAR(32) NOT NULL DEFAULT 'WORD',
    pattern_value TEXT NOT NULL,
    action VARCHAR(32) NOT NULL DEFAULT 'MASK',
    replacement VARCHAR(255) DEFAULT '[REDACTED]',
    applies_to VARCHAR(32) NOT NULL DEFAULT 'SEARCH',
    subject_type VARCHAR(32) NOT NULL DEFAULT 'ALL',
    subject_value VARCHAR(255) NOT NULL DEFAULT '*',
    priority INT NOT NULL DEFAULT 100,
    is_active INT NOT NULL DEFAULT 1,
    created_by VARCHAR(128),
    approved_by VARCHAR(128),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ksp_stage_active
    ON public.kb_sensitive_policy (applies_to, is_active, priority);

CREATE INDEX IF NOT EXISTS idx_ksp_subject
    ON public.kb_sensitive_policy (subject_type, subject_value, is_active);
