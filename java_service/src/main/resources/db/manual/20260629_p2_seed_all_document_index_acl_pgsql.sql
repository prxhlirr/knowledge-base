-- P2 manual PostgreSQL script: seed explicit ACL rows for all current kb_document_* physical indices.
-- Execute manually by DBA/ops after role-code review. This file is intentionally not a Flyway migration.
--
-- Business purpose:
--   Make KB_INDEX_ACL_DEFAULT_DENY=true safe by ensuring every current kb_document_* index has explicit READ ACL rows.
--
-- Role-code convention used by this script:
--   kb_document_official -> official_reader
--   kb_document_public   -> public_reader
--   kb_document_law      -> law_reader
--   kb_document_notice   -> notice_reader
--   kb_document_v1       -> legacy_reader
--   kb_document_news     -> news_reader
--
-- If your IAM roleCode differs, edit subject_value before execution.

BEGIN;

WITH seed(index_name, subject_value) AS (
    VALUES
        ('kb_document_official', 'official_reader'),
        ('kb_document_public',   'public_reader'),
        ('kb_document_law',      'law_reader'),
        ('kb_document_notice',   'notice_reader'),
        ('kb_document_v1',       'legacy_reader'),
        ('kb_document_news',     'news_reader')
)
INSERT INTO public.kb_index_acl_subjects (
    index_name,
    read_alias,
    subject_type,
    subject_value,
    scope,
    effect,
    expires_at,
    is_active,
    created_by,
    created_at,
    updated_at
)
SELECT
    seed.index_name,
    'kb_document',
    'ROLE',
    seed.subject_value,
    'READ',
    'ALLOW',
    NULL,
    1,
    'manual_p2_seed_all_document_index_acl',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM seed
WHERE NOT EXISTS (
    SELECT 1
    FROM public.kb_index_acl_subjects existing
    WHERE existing.index_name = seed.index_name
      AND existing.read_alias = 'kb_document'
      AND existing.subject_type = 'ROLE'
      AND existing.subject_value = seed.subject_value
      AND existing.scope = 'READ'
      AND existing.effect = 'ALLOW'
      AND existing.is_active = 1
);

COMMIT;

-- Verification:
-- SELECT index_name, subject_type, subject_value, scope, effect, is_active
-- FROM public.kb_index_acl_subjects
-- WHERE index_name IN (
--     'kb_document_official',
--     'kb_document_public',
--     'kb_document_law',
--     'kb_document_notice',
--     'kb_document_v1',
--     'kb_document_news'
-- )
--   AND scope = 'READ'
--   AND is_active = 1
-- ORDER BY index_name, subject_type, subject_value;

-- Optional rollback for rows inserted by this script:
-- UPDATE public.kb_index_acl_subjects
-- SET is_active = 0,
--     updated_at = CURRENT_TIMESTAMP
-- WHERE created_by = 'manual_p2_seed_all_document_index_acl'
--   AND scope = 'READ'
--   AND effect = 'ALLOW'
--   AND is_active = 1;
