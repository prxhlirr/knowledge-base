package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/**
 * 知识库文档注册中心实体。
 * 业务功能：作为 MySQL 侧"已入库文档"的唯一权威目录，记录每个文档每个版本的完整元数据。
 * 与 ES 的关系：
 * - ES 是向量检索引擎，is_latest / visibility 以本表为准（ES 值在入库时写入，后续变更需同步）。
 * - doc_version_history 仅做不可变的版本溯源，本表用于业务操作（列表、删除、编辑元数据）。
 * 设计约束：(source_name, doc_version) 联合唯一；旧版本 is_latest=0，当前版本 is_latest=1。
 */
@Data
@TableName("kb_doc_registry")
public class KbDocRegistry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ES _id（由 source_name + ":" + doc_version 生成，供精确定位 ES 文档） */
    private String docId;

    /** 文档原始文件名（与 ES metadata.source 字段一致） */
    private String sourceName;

    /** 版本号，从 1 开始自增，每次覆盖式上传递增 */
    private Integer docVersion;

    /** 是否最新版本（1=是，0=历史版本），每次入库时将旧版本置 0 */
    private Integer isLatest;

    /** 文件物理存储路径（本地磁盘绝对路径 或 MinIO Object Key） */
    private String storagePath;

    /** ES 目标索引名（默认 kb_document_v1） */
    private String targetIndex;

    /** 该版本成功写入 ES 的 chunk 总数 */
    private Integer chunkCount;

    /** 正文前 2000 字 MD5，与 doc_version_history 保持一致，供跨表校验去重 */
    private String contentHash;

    /** 文件文号（如 国发〔2026〕1号） */
    private String docNumber;

    /** 来源单位 */
    private String unit;

    /** 文档标签（逗号分隔，如 "公安,年报"），前端筛选用 */
    private String tags;

    /** 发文时间 */
    private java.time.LocalDate publishTime;

    /** 可见度枚举：PUBLIC / INTERNAL / DEPT / PRIVATE / GRANT */
    private String visibility;

    /** 12位行政区划编码（visibility=DEPT 时必填） */
    private String deptCode;

    /** 上传人 ID（对接鉴权体系的用户标识） */
    private String uploaderId;

    /** 上传人姓名（冗余存储，避免关联查询） */
    private String uploaderName;

    /**
     * 文档状态：
     * INDEXED — 已成功写入 ES，正常可用
     * FAILED — 写入 ES 失败
     * DELETED — 已逻辑删除（ES 侧 is_latest=false）
     */
    private String status;

    /** 记录创建时间（入库时机） */
    private OffsetDateTime createdAt;

    /** 记录最后更新时间（元数据编辑时更新） */
    private OffsetDateTime updatedAt;
}
