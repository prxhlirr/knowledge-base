-- P0 manual PostgreSQL script for index-level ACL.
-- Execute manually by DBA/ops after review. This project keeps production DB
-- changes offline/manual, so this file is intentionally not a Flyway migration.

BEGIN;

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

CREATE INDEX IF NOT EXISTS idx_kias_effect_expire
    ON public.kb_index_acl_subjects (effect, expires_at, is_active);

COMMENT ON TABLE public.kb_index_acl_subjects IS 'Index-level ACL for kb_document_* physical indices.';
COMMENT ON COLUMN public.kb_index_acl_subjects.index_name IS 'Physical index name, e.g. kb_document_public.';
COMMENT ON COLUMN public.kb_index_acl_subjects.subject_type IS 'Principal type: USER, ROLE, DEPT, ALL, AUTHENTICATED.';
COMMENT ON COLUMN public.kb_index_acl_subjects.subject_value IS 'Principal value; use * for ALL/AUTHENTICATED.';
COMMENT ON COLUMN public.kb_index_acl_subjects.scope IS 'Permission scope. Current code uses READ.';
COMMENT ON COLUMN public.kb_index_acl_subjects.effect IS 'ALLOW or DENY. DENY has priority in service logic.';

COMMIT;

-- Verification:
-- SELECT to_regclass('public.kb_index_acl_subjects') AS index_acl_table;
-- SELECT indexname, indexdef FROM pg_indexes
--  WHERE schemaname = 'public' AND tablename = 'kb_index_acl_subjects'
--  ORDER BY indexname;

-- Example grants, adjust role codes before running:
-- INSERT INTO public.kb_index_acl_subjects
--     (index_name, read_alias, subject_type, subject_value, scope, effect, created_by)
-- VALUES
--     ('kb_document_official', 'kb_document', 'ROLE', 'official_reader', 'READ', 'ALLOW', 'manual_admin'),
--     ('kb_document_public', 'kb_document', 'ROLE', 'public_reader', 'READ', 'ALLOW', 'manual_admin');
