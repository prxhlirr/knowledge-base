-- ==========================================================
-- T6.5 文档动态索引路由配置表 (PostgreSQL)
-- ==========================================================

CREATE TABLE IF NOT EXISTS sys_index_routing (
    id BIGSERIAL PRIMARY KEY,
    tag_code VARCHAR(64) NOT NULL,
    tag_name VARCHAR(128) NOT NULL,
    target_index VARCHAR(128) NOT NULL,
    is_active SMALLINT DEFAULT 1,
    description VARCHAR(255),
    version INT DEFAULT 0,
    create_by VARCHAR(64) DEFAULT 'system',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT 'system',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建唯一索引保证单名字不重复路由
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_index_routing_tag_name ON sys_index_routing (tag_name);

COMMENT ON TABLE sys_index_routing IS '文档索引动态路由表';
COMMENT ON COLUMN sys_index_routing.id IS '主键';
COMMENT ON COLUMN sys_index_routing.tag_code IS '系统标准路由编码, 如 LAW_DOC';
COMMENT ON COLUMN sys_index_routing.tag_name IS '中文匹配健，如 "法律法规"，用于兼容上游传输格式';
COMMENT ON COLUMN sys_index_routing.target_index IS 'ES物理分片索引名称, 比如 kb_document_law';
COMMENT ON COLUMN sys_index_routing.is_active IS '1-启用 0-禁用';
COMMENT ON COLUMN sys_index_routing.description IS '状态和描述说明';
COMMENT ON COLUMN sys_index_routing.version IS '乐观锁版本控制';
COMMENT ON COLUMN sys_index_routing.create_by IS '创建人';
COMMENT ON COLUMN sys_index_routing.create_time IS '创建时间';
COMMENT ON COLUMN sys_index_routing.update_by IS '更新人';
COMMENT ON COLUMN sys_index_routing.update_time IS '更新时间';

-- ==========================================================
-- 插入基准映射数据
-- ==========================================================
-- 【法律法规分区】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('LAW', '法律法规', 'kb_document_law', '法律和政策法规库'),
('LAW', 'GA文库', 'kb_document_law', '公安业务文库'),
('LAW', 'BM知识', 'kb_document_law', '保密知识库')
ON CONFLICT (tag_name) DO NOTHING;

-- 【新闻资讯分区】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('NEWS', '简报', 'kb_document_news', '业务简报'),
('NEWS', '动态', 'kb_document_news', '日常动态新闻'),
('NEWS', '各地警务', 'kb_document_news', '各地警务新闻'),
('NEWS', '综合要闻', 'kb_document_news', '综合类新闻要闻'),
('NEWS', '时政要闻', 'kb_document_news', '时事政治新闻'),
('NEWS', '前沿科技', 'kb_document_news', '科技前参')
ON CONFLICT (tag_name) DO NOTHING;

-- 【公务发文分区 (含兜底)】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('OFFICIAL', '公文', 'kb_document_official', '正式公文审批单'),
('OFFICIAL', '文件', 'kb_document_official', '一般公务文件'),
('OFFICIAL', '批示/督办', 'kb_document_official', '批示或督办事件'),
('OFFICIAL', '批示督办', 'kb_document_official', '批示或督办事件(兼容名)'),
('OFFICIAL', '领导讲话', 'kb_document_official', '领导会议讲话记录'),
('OFFICIAL', '通知', 'kb_document_official', '日常通知'),
('OFFICIAL', '传真', 'kb_document_official', '外部传真归档')
ON CONFLICT (tag_name) DO NOTHING;

-- 【公示宣传分区】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('PUBLIC', '公示', 'kb_document_public', '信息公示公示公告'),
('PUBLIC', '公告', 'kb_document_public', '官方公告文书'),
('PUBLIC', '宣传', 'kb_document_public', '宣发材料'),
('PUBLIC', '调查研究', 'kb_document_public', '调研和论文报告')
ON CONFLICT (tag_name) DO NOTHING;

-- 【重要活动通报分区】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('NOTICE', '重要活动', 'kb_document_notice', '大型重要活动备忘录'),
('NOTICE', '报警内容', 'kb_document_notice', '警情报警日志')
ON CONFLICT (tag_name) DO NOTHING;

-- 【系统保留兜底配置 _FALLBACK_】
INSERT INTO sys_index_routing (tag_code, tag_name, target_index, description) VALUES
('FALLBACK_INDEX', '_FALLBACK_', 'kb_document_official', '所有未匹配 tag_name 时的默认兜底索引')
ON CONFLICT (tag_name) DO NOTHING;
