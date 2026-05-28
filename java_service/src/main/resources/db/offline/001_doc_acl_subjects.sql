CREATE TABLE IF NOT EXISTS public.kb_doc_acl_subjects (
    id BIGSERIAL PRIMARY KEY,
    registry_id BIGINT,
    source_name VARCHAR(512) NOT NULL,
    doc_version INT DEFAULT 0,
    subject_type VARCHAR(32) NOT NULL,
    subject_value VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'VIEW',
    effect VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
    source_type VARCHAR(32) NOT NULL DEFAULT 'RUNTIME_GRANT',
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    is_active INT NOT NULL DEFAULT 1,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdas_source_scope
    ON public.kb_doc_acl_subjects (source_name, scope, is_active);

CREATE INDEX IF NOT EXISTS idx_kdas_subject
    ON public.kb_doc_acl_subjects (subject_type, subject_value, is_active);

CREATE INDEX IF NOT EXISTS idx_kdas_registry
    ON public.kb_doc_acl_subjects (registry_id);
