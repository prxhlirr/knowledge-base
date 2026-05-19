-- 知识库基础表结构初始化 (适用版本: PostgreSQL 14.8)

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    dept_id BIGINT,
    role VARCHAR(20) DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS gov_document (
    doc_id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    doc_number VARCHAR(100), -- 比如 "国发〔2023〕1号"
    file_type VARCHAR(20),   -- pdf, docx, txt
    file_path VARCHAR(500),  -- 原始文件存储路径
    uploader_id BIGINT,
    dept_id BIGINT,          -- 属主部门
    status VARCHAR(20) DEFAULT 'PROCESSING', -- PROCESSING, ACTIVE, DELETED
    version INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doc_audit_log (
    id BIGSERIAL PRIMARY KEY,
    doc_id VARCHAR(64) NOT NULL,
    action VARCHAR(50) NOT NULL, -- IMPORT, UPDATE, DELETE
    operator_id BIGINT,
    details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 初始化一条超级管理员记录
INSERT INTO sys_user (username, password_hash, dept_id, role) 
VALUES ('admin', 'hashed_pwd_placeholder', 0, 'ADMIN')
ON CONFLICT (username) DO NOTHING;
