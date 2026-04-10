-- 创建系统租户搜索引擎权限策略表
CREATE TABLE public.sys_tenant_policy (
    id bigserial PRIMARY KEY,
    app_code varchar(64) NOT NULL UNIQUE,
    allowed_indices varchar(255) NOT NULL,
    force_file_type varchar(64),
    min_security_level int DEFAULT 0,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP,
    is_deleted smallint DEFAULT 0
);

-- 注释
COMMENT ON TABLE public.sys_tenant_policy IS '系统租户搜索引擎权限策略表';
COMMENT ON COLUMN public.sys_tenant_policy.app_code IS '调用方鉴权密钥 (原硬编码 key)';
COMMENT ON COLUMN public.sys_tenant_policy.allowed_indices IS '允许访问的 ES 索引匹配符';
COMMENT ON COLUMN public.sys_tenant_policy.force_file_type IS '强制带上的文件类型过滤';
COMMENT ON COLUMN public.sys_tenant_policy.min_security_level IS '强制带上的最低安全等级过滤，默认 0';

-- 迁移原有的硬编码数据作为系统的基础白名单
INSERT INTO public.sys_tenant_policy (app_code, allowed_indices, force_file_type, min_security_level) VALUES
('ADMIN_MASTER_KEY', 'kb_*', NULL, 0),
('VEND_A_7788', 'kb_document*', 'document', 0),
('VEND_B_9900', 'kb_document*', NULL, 1);
