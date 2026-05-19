-- ============================================================
-- C5 权限事件溯源 - PostgreSQL 初始化脚本
-- ============================================================

-- 1. 权限变更事件溯源表
CREATE TABLE IF NOT EXISTS doc_permission_events (
    id           BIGSERIAL PRIMARY KEY,
    doc_id       VARCHAR(512) NOT NULL,
    action       VARCHAR(32)  NOT NULL CHECK (action IN ('GRANT','REVOKE','HANDLER','VISIBILITY_CHANGE')),
    target_type  VARCHAR(16)  NOT NULL CHECK (target_type IN ('USER','DEPT','GROUP')),
    target_value VARCHAR(128) NOT NULL,
    operator_id  VARCHAR(64)  NOT NULL,
    remark       VARCHAR(256),
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dpe_doc    ON doc_permission_events (doc_id);
CREATE INDEX IF NOT EXISTS idx_dpe_time   ON doc_permission_events (created_at);
CREATE INDEX IF NOT EXISTS idx_dpe_target ON doc_permission_events (target_type, target_value);
COMMENT ON TABLE doc_permission_events IS '文档权限变更事件溯源（只追加不修改）';

-- 2. RBAC 权限群组定义表
CREATE TABLE IF NOT EXISTS permission_groups (
    id          BIGSERIAL PRIMARY KEY,
    group_code  VARCHAR(64)  UNIQUE NOT NULL,
    group_name  VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    created_by  VARCHAR(64),
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE permission_groups IS 'RBAC 权限群组定义';

-- 3. 群组成员表
CREATE TABLE IF NOT EXISTS permission_group_members (
    id          BIGSERIAL PRIMARY KEY,
    group_code  VARCHAR(64)  NOT NULL,
    dept_code   VARCHAR(32),
    user_id     VARCHAR(64),
    granted_by  VARCHAR(64),
    granted_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pgm_group ON permission_group_members (group_code);
CREATE INDEX IF NOT EXISTS idx_pgm_dept  ON permission_group_members (dept_code);
CREATE INDEX IF NOT EXISTS idx_pgm_user  ON permission_group_members (user_id);

-- 4. 文档版本历史记录表（配合 ES content_hash 使用）
CREATE TABLE IF NOT EXISTS doc_version_history (
    id           BIGSERIAL PRIMARY KEY,
    source_name  VARCHAR(512) NOT NULL,
    doc_version  INT          NOT NULL,
    content_hash VARCHAR(64),
    chunk_count  INT,
    operator_id  VARCHAR(64),
    visibility   VARCHAR(32)  DEFAULT 'INTERNAL',
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_name, doc_version)
);
CREATE INDEX IF NOT EXISTS idx_dvh_source ON doc_version_history (source_name);
CREATE INDEX IF NOT EXISTS idx_dvh_hash   ON doc_version_history (content_hash);
COMMENT ON TABLE doc_version_history IS '文档版本历史记录（与 ES doc_version 字段对应）';

-- 5. 部门组织树表
CREATE TABLE IF NOT EXISTS dept_tree (
    dept_code    VARCHAR(32)  PRIMARY KEY,
    dept_name    VARCHAR(128) NOT NULL,
    dept_l2      VARCHAR(4),
    dept_l4      VARCHAR(8),
    dept_l6      VARCHAR(12),
    dept_l9      VARCHAR(18),
    dept_level   SMALLINT,
    parent_code  VARCHAR(32),
    is_active    SMALLINT     DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_dt_l2     ON dept_tree (dept_l2);
CREATE INDEX IF NOT EXISTS idx_dt_l6     ON dept_tree (dept_l6);
CREATE INDEX IF NOT EXISTS idx_dt_parent ON dept_tree (parent_code);
COMMENT ON TABLE dept_tree IS '行政区划组织树（GB/T 2260 标准）';
