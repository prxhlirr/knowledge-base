CREATE TABLE IF NOT EXISTS public.kb_doc_acl_subjects (
    id             BIGSERIAL      PRIMARY KEY,
    registry_id    BIGINT,
    source_name    VARCHAR(512)   NOT NULL,
    doc_version    INT            NOT NULL DEFAULT 0,
    subject_type   VARCHAR(32)    NOT NULL,
    subject_value  VARCHAR(256)   NOT NULL,
    scope          VARCHAR(32)    NOT NULL DEFAULT 'VIEW',
    effect         VARCHAR(16)    NOT NULL DEFAULT 'ALLOW',
    source_type    VARCHAR(32)    NOT NULL DEFAULT 'INGEST_INIT',
    expires_at     TIMESTAMP,
    is_active      SMALLINT       NOT NULL DEFAULT 1,
    created_by     VARCHAR(64),
    created_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_kdas_source_scope
    ON public.kb_doc_acl_subjects (source_name, scope, is_active);

CREATE INDEX IF NOT EXISTS idx_kdas_subject
    ON public.kb_doc_acl_subjects (subject_type, subject_value, is_active);

CREATE INDEX IF NOT EXISTS idx_kdas_registry
    ON public.kb_doc_acl_subjects (registry_id);
