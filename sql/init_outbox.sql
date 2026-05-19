-- =====================================================================
-- Transactional Outbox Pattern — kb_doc_outbox 表
-- 数据库：PostgreSQL
-- 作用：作为 ES 文档激活事件的事务性中间状态表，解决数据一致性问题。
--
-- 核心设计原理：
--   1. Python bulk_write 写入 ES (is_latest=false) 后，同步回调 Java
--   2. Java 在单一事务内将 outbox.status 置为 READY
--   3. OutboxPoller 发现 READY 记录后执行 ES update_by_query 激活文档
--   4. 任意步骤失败都不会产生"幽灵可见"文档
--
-- 状态流转：
--   WAITING → READY → DONE   (正常路径)
--   WAITING → FAILED          (Python 回调超时且重试耗尽)
--   READY   → FAILED          (Poller 激活 ES 失败且重试耗尽)
--
-- PG 与 MySQL 主要差异：
--   AUTO_INCREMENT   → BIGSERIAL（或 GENERATED ALWAYS AS IDENTITY）
--   DATETIME         → TIMESTAMPTZ（带时区，推荐）
--   ON UPDATE        → 触发器实现（PG 不支持列级 ON UPDATE）
--   COMMENT          → COMMENT ON COLUMN（DDL 后单独执行）
--   内联 INDEX       → CREATE INDEX（PG 不支持内联索引定义）
--   ENGINE/CHARSET   → 无（PG 不需要）
-- =====================================================================

CREATE TABLE IF NOT EXISTS kb_doc_outbox (
    id             BIGSERIAL       PRIMARY KEY,
    task_id        VARCHAR(64)     NOT NULL,
    source_name    VARCHAR(512)    NOT NULL,
    doc_version    INT             NOT NULL DEFAULT 0,
    file_base_hash VARCHAR(64)     NOT NULL DEFAULT '',
    target_index   VARCHAR(128)    NOT NULL DEFAULT 'kb_document_v1',
    -- 状态枚举: WAITING=等待Python确认 | READY=可激活 | DONE=已激活 | FAILED=失败
    status         VARCHAR(20)     NOT NULL DEFAULT 'WAITING',
    retry_count    INT             NOT NULL DEFAULT 0,
    error_msg      VARCHAR(512)    DEFAULT NULL,
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ── 字段注释 ──────────────────────────────────────────────────────────
COMMENT ON TABLE  kb_doc_outbox                   IS '事务性 Outbox 表：控制 ES 文档激活时序，防止权限时间窗口数据泄露。与 sys_doc_import_task 关联。';
COMMENT ON COLUMN kb_doc_outbox.id                IS '自增主键';
COMMENT ON COLUMN kb_doc_outbox.task_id           IS 'Redis 任务 ID（与 sys_doc_import_task.task_id 关联）';
COMMENT ON COLUMN kb_doc_outbox.source_name       IS '文档原始文件名（与 ES metadata.source 一致）';
COMMENT ON COLUMN kb_doc_outbox.doc_version       IS '待激活的文档版本号';
COMMENT ON COLUMN kb_doc_outbox.file_base_hash    IS 'ES 文档 ID 前缀（用于 update_by_query）';
COMMENT ON COLUMN kb_doc_outbox.target_index      IS 'ES 目标索引名';
COMMENT ON COLUMN kb_doc_outbox.status            IS '状态枚举: WAITING | READY | DONE | FAILED';
COMMENT ON COLUMN kb_doc_outbox.retry_count       IS '已重试次数（超过3次置FAILED）';
COMMENT ON COLUMN kb_doc_outbox.error_msg         IS '失败原因（便于运维排查）';
COMMENT ON COLUMN kb_doc_outbox.created_at        IS '记录创建时间';
COMMENT ON COLUMN kb_doc_outbox.updated_at        IS '状态最后变更时间';

-- ── 自动维护 updated_at（等价于 MySQL 的 ON UPDATE CURRENT_TIMESTAMP）──
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER trg_kb_doc_outbox_updated_at
BEFORE UPDATE ON kb_doc_outbox
FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

-- ── 索引 ──────────────────────────────────────────────────────────────
-- 按状态+创建时间：供 OutboxPoller 高效扫描 READY 记录
CREATE INDEX IF NOT EXISTS idx_outbox_status_created
    ON kb_doc_outbox (status, created_at);

-- 按 task_id：供 Python 回调时精确定位
CREATE INDEX IF NOT EXISTS idx_outbox_task_id
    ON kb_doc_outbox (task_id);

-- 按 source_name+version：供幂等性检查
CREATE INDEX IF NOT EXISTS idx_outbox_source_version
    ON kb_doc_outbox (source_name, doc_version);
