package com.boyang.search.model;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 文档入库请求 DTO（API 接口 + Kafka 消息共用此结构）。
 * 业务功能：外部系统通过 REST API 或 Kafka Topic 向知识库推送新文档信息，
 *           触发系统从 SFTP 或已知路径拉取文件并入库。
 * 流程：
 *   1. 外部系统推送此 DTO（JSON）
 *   2. DocIngestService 解析 ingestType，按类型执行拉取逻辑
 *   3. 拉取成功后调用 createTasks 入 Redis 队列，由 Python Worker 处理
 */
@Data
public class DocIngestRequest {

    /**
     * 接入类型（必填）：
     *   SFTP     — 从指定 SFTP 服务器拉取文件（filePath 为远程完整路径）
     *   LOCAL    — 本地/NFS 路径（filePath 为本机可见的文件系统路径）
     *   URL      — 从公网/内网 HTTP URL 下载（filePath 为 URL）
     */
    private String ingestType = "SFTP";

    /**
     * SFTP 模式下使用：凭据 ID，对应 sftp.credentials.xxx 中的 key。
     * 不传密码，运维通过配置文件/环境变量管理（A-4 修复）。
     */
    private String credentialId;

    /**
     * 文件路径。
     *   SFTP 模式：远程路径，如 /data/docs/2024/report.pdf
     *   LOCAL 模式：本地路径，如 /mnt/fileserver/report.pdf
     *   URL 模式：完整 URL，如 https://docs.example.com/report.pdf
     */
    private String filePath;

    /** 显示文件名（可选，缺省用 filePath 的最后一段） */
    private String fileName;

    /** 拉取整个目录下的文件（SFTP/LOCAL 模式），与 filePath 互斥 */
    private String dirPath;

    // ─── 元数据字段 ──────────────────────
    /** 可见性：PUBLIC / INTERNAL / DEPT / PRIVATE */
    private String visibility = "INTERNAL";

    /** DEPT 可见性时必填，部门编码 */
    private String deptCode;

    /** ES 目标索引（默认 kb_document_v1） */
    private String targetIndex = "kb_document_v1";

    /** 标签（逗号分隔） */
    private String tag;

    /** 上传单位/部门名称 */
    private String unit;

    /** 文号 */
    private String docNumber;

    /** 发文人/负责人 */
    private String owner;

    /** 推荐搜索词（逗号分隔） */
    private String searchQueries;

    /** 发布时间（yyyy-MM-dd 格式） */
    private String publishTime;

    /** 来源系统标识（用于审计溯源，如 OA / DMS / ARCHIVE） */
    private String sourceSystem;

    /** 上传者用户 ID（由 Java 服务端从登录 Session 中获取，禁止客户端传入） */
    private String uploaderId;

    /**
     * GRANT 可见性时：显式授权的用户 ID 列表。
     * 服务端会为列表中每个用户生成 user::{uid} Token 写入 ES。
     * 这些用户查询时，因其 AclTokenBuilder 始终注入 user::userId，可自动命中。
     */
    private java.util.List<String> grantedUserIds;

    /**
     * GRANT 可见性时：显式授权的角色 Code 列表（来自 IAM 系统的 roleCode）。
     * 服务端会生成 role::{roleCode} Token 写入 ES。
     * 用户查询时，AclTokenBuilder 从 IAM 查询用户角色列表并注入 role:: Token，实现交集匹配。
     * 示例：grantedRoles=["mock_admin"] → ES 文档 acl_tokens 包含 "role::mock_admin"
     */
    private java.util.List<String> grantedRoles;

    /** 内存级文件数组，供 UPLOAD 模式处理。不被序列化与传输。 */
    @JsonIgnore
    private transient MultipartFile[] uploadFiles;
}
