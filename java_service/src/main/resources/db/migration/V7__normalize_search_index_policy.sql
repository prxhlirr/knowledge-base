UPDATE public.sys_tenant_policy
SET allowed_indices = 'kb_document',
    updated_at = CURRENT_TIMESTAMP
WHERE allowed_indices IN ('kb_document*', 'kb_*')
  AND app_code IN ('ADMIN_MASTER_KEY', 'VEND_A_7788', 'VEND_B_9900')
  AND is_deleted = 0;
