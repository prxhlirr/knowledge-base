package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * ES 文档激活事件 Outbox 实体（kb_doc_outbox 表）。
 *
 * 业务功能：实现 Transactional Outbox Pattern，保证 ES 文档可见性激活的时序安全。
 *
 * 设计原理（"先写不可见，后激活可见"）：
 *   1. Python 写入 ES 时 is_latest=false（文档不可见）
 *   2. Python bulk_write 成功后同步回调 Java，Java 将此 Outbox 记录置为 READY
 *   3. OutboxPoller 发现 READY 记录，执行 ES update_by_query 激活文档（is_latest=true）
 *   4. 任意步骤失败都不会产生对用户可见的"幽灵文档"
 *
 * 状态机：
 *   WAITING → READY  （Python 回调成功，Java 确认 ES 写入完成）
 *   READY   → DONE   （OutboxPoller 成功执行 ES 激活）
 *   WAITING → FAILED （Python 回调超时，重试耗尽）
 *   READY   → FAILED （ES 激活失败，重试耗尽）
 */
@Data
@TableName("kb_doc_outbox")
public class KbDocOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Redis 任务 ID（与 sys_doc_import_task.task_id 关联，供 Python 回调时精确定位） */
    private String taskId;

    /** 文档原始文件名（与 ES metadata.source 一致，用于 update_by_query 定位文档） */
    private String sourceName;

    /** 待激活的文档版本号 */
    private Integer docVersion;

    /** ES 文档 ID 前缀（file_base_hash，用于构造 ES update_by_query filter） */
    private String fileBaseHash;

    /** ES 目标索引名（默认 kb_document_v1） */
    private String targetIndex;

    /**
     * 状态枚举：
     *   WAITING — 已推入 Redis 队列，等待 Python 确认 ES 写入完成
     *   READY   — Python 已确认 ES 写入，等待 Poller 执行激活
     *   DONE    — Poller 已成功执行 ES 激活（is_latest=true）
     *   FAILED  — 重试耗尽，需人工干预
     */
    private String status;

    /** 已重试次数（超过 MAX_RETRY 次置 FAILED，防止无限循环） */
    private Integer retryCount;

    /** 失败原因摘要（便于运维排查） */
    private String errorMsg;

    /** 记录创建时间 */
    private OffsetDateTime createdAt;

    /** 状态最后变更时间 */
    private OffsetDateTime updatedAt;
}
