CREATE TABLE IF NOT EXISTS public.kb_index_acl_subjects (
    id BIGSERIAL PRIMARY KEY,
    index_name VARCHAR(255) NOT NULL,
    read_alias VARCHAR(255),
    subject_type VARCHAR(32) NOT NULL,
    subject_value VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'READ',
    effect VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    is_active INT NOT NULL DEFAULT 1,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kias_index_scope
    ON public.kb_index_acl_subjects (index_name, scope, is_active);

CREATE INDEX IF NOT EXISTS idx_kias_subject
    ON public.kb_index_acl_subjects (subject_type, subject_value, is_active);
