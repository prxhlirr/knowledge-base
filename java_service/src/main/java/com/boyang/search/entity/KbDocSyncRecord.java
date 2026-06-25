package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 第三方文档增量同步映射记录实体。
 * <p>
 * 业务背景：第三方源表完全只读（不能加字段、不能改 status），本表承载增量同步的
 * 状态机与 full_hash 对账锚点，是「源记录 ↔ 知识库文档」的唯一映射权威。
 * <p>
 * 状态机：PENDING(待处理) → DISPATCHED(已派发，等 Python 异步入库) → CONFIRMED(对账命中 registry)
 *         失败/超时 → 回 PENDING 重试；重试耗尽 → FAILED(人工介入)。
 * <p>
 * 增量发现：源表只读，按 source_update_time 时间戳游标拉新，CONFIRMED 的记录被回 PENDING 触发变更重检。
 * 去重/对账锚点：full_hash（全文件 SHA-256），由 dbDocExtractJob 对账逻辑写入 registry.full_hash。
 */
@Data
@TableName("kb_doc_sync_record")
public class KbDocSyncRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源系统标识：DB_DOC_SYNC / DB_HTML_SYNC */
    private String sourceSystem;

    /** 第三方源表名（如 doc 源表名） */
    private String sourceTable;

    /** 源表主键值 */
    private String sourceId;

    /** 源表记录的 update_time（增量游标水位线依据） */
    private OffsetDateTime sourceUpdateTime;

    /** 全文件 SHA-256（对账/去重锚点，下载后计算） */
    private String fullHash;

    /** 唯一化后的 source_name（{原文件名}_{fullHash前8位}.doc） */
    private String fileName;

    /** 同步状态：PENDING / DISPATCHED / CONFIRMED / FAILED */
    private String syncStatus;

    /** DocIngestService.ingest() 返回的 batchId */
    private String batchId;

    /** 失败重试计数 */
    private Integer retryCount;

    /** 最近一次失败原因 */
    private String errorMsg;

    /** 进入 DISPATCHED 的时间（超时回退依据） */
    private OffsetDateTime dispatchedAt;

    /** 记录创建时间 */
    private OffsetDateTime createdAt;

    /** 记录最后更新时间 */
    private OffsetDateTime updatedAt;
}
