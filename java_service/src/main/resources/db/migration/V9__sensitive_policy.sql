CREATE TABLE IF NOT EXISTS public.kb_sensitive_policy (
    id             BIGSERIAL      PRIMARY KEY,
    pattern_type   VARCHAR(32)    NOT NULL DEFAULT 'WORD',
    pattern_value  VARCHAR(512)   NOT NULL,
    action         VARCHAR(32)    NOT NULL DEFAULT 'MASK',
    replacement    VARCHAR(128)   DEFAULT '[REDACTED]',
    applies_to     VARCHAR(32)    NOT NULL DEFAULT 'SEARCH',
    subject_type   VARCHAR(32)    NOT NULL DEFAULT 'ALL',
    subject_value  VARCHAR(256)   NOT NULL DEFAULT '*',
    priority       INT            NOT NULL DEFAULT 100,
    is_active      SMALLINT       NOT NULL DEFAULT 1,
    created_by     VARCHAR(64),
    approved_by    VARCHAR(64),
    created_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ksp_stage_active
    ON public.kb_sensitive_policy (applies_to, is_active, priority);

CREATE INDEX IF NOT EXISTS idx_ksp_subject
    ON public.kb_sensitive_policy (subject_type, subject_value, is_active);
