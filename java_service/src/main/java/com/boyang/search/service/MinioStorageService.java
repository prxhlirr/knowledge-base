package com.boyang.search.service;

import io.minio.*;
import io.minio.errors.MinioException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * MinIO 对象存储服务（MinioStorageService）。
 * 业务功能：文档入库时将原始文件持久化到 MinIO 对象存储，
 *           返回对象路径（bucket/objectName 格式）供 Python Worker 读取和处理。
 * 关键流程：
 *   1. 文件通过 PutObjectArgs 上传到 MinIO Bucket
 *   2. 返回 objectName（如 kb/2024/xxx.pdf），Python Worker 通过 MinIO SDK 下载
 *   3. MinIO 不可用时降级写本地目录，并记录告警日志
 * 降级策略：
 *   MinIO 连接失败时自动 fallback 到 storage.local.path 下的本地目录，
 *   确保文档入库不中断，待 MinIO 恢复后可重新上传。
 */
@Slf4j
@Service
public class MinioStorageService {

    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${minio.accessKey:minioadmin}")
    private String accessKey;

    @Value("${minio.secretKey:minioadmin}")
    private String secretKey;

    @Value("${minio.bucketName:knowledge-base}")
    private String bucketName;

    /**
     * 本地降级路径（MinIO 不可用时使用）。
     * 生产环境配置 storage.local.path，确保与 Python Worker 共享可见。
     */
    @Value("${storage.local.path:${java.io.tmpdir}/kb-uploads}")
    private String localFallbackPath;

    private MinioClient minioClient;
    private boolean     minioAvailable = false;

    /**
     * 初始化 MinIO 客户端，并确保目标 Bucket 存在。
     * 若 MinIO 服务不可达，标记 minioAvailable=false，后续请求走本地降级。
     */
    @PostConstruct
    public void init() {
        try {
            minioClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            // 检查并创建 Bucket（幂等操作）
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("[MinioStorage] Bucket '{}' 创建成功", bucketName);
            }
            minioAvailable = true;
            log.info("[MinioStorage] MinIO 连接就绪 endpoint={} bucket={}", endpoint, bucketName);
        } catch (Exception e) {
            minioAvailable = false;
            log.warn("[MinioStorage] MinIO 不可用，降级为本地存储 endpoint={} err={}", endpoint, e.getMessage());
            // 初始化本地降级目录
            try {
                Files.createDirectories(Paths.get(localFallbackPath));
            } catch (Exception ex) {
                log.error("[MinioStorage] 本地降级目录创建失败 path={}", localFallbackPath);
            }
        }
    }

    /**
     * 存储文件：优先写入 MinIO，失败时降级写本地目录。
     *
     * @param stream        文件输入流
     * @param originalName  原始文件名（含后缀）
     * @return MinIO 模式：objectName（如 kb/uuid.pdf）；
     *         降级模式：本地绝对路径（如 /tmp/kb-uploads/uuid.pdf）
     */
    public String store(InputStream stream, String originalName) throws Exception {
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        // 按日期分目录，便于管理和清理（如 kb/2024/03/uuid.pdf）
        String datePath  = new java.text.SimpleDateFormat("yyyy/MM").format(new java.util.Date());
        String objectKey = datePath + "/" + UUID.randomUUID().toString() + ext;

        if (minioAvailable) {
            return storeToMinio(stream, objectKey, originalName);
        } else {
            log.warn("[MinioStorage] MinIO 不可用，降级本地存储 file={}", originalName);
            return storeToLocal(stream, objectKey);
        }
    }

    /**
     * 上传到 MinIO 对象存储。
     * 返回格式：objectName（Python Worker 通过 MinIO SDK + objectName 下载）
     */
    private String storeToMinio(InputStream stream, String objectKey, String originalName) throws Exception {
        try {
            // 根据后缀推断 Content-Type（MinIO metadata 检索用）
            String contentType = guessContentType(originalName);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(stream, -1, 10 * 1024 * 1024) // 分片大小 10MB
                            .contentType(contentType)
                            .build());

            log.info("[MinioStorage] 文件上传 MinIO 成功 bucket={} object={}", bucketName, objectKey);
            // 返回 bucket/objectKey 格式，Python Worker 解析后获取文件
            return bucketName + "/" + objectKey;
        } catch (MinioException e) {
            // [Bug 修复] 不再永久设 minioAvailable=false：
            // 根因：单次上传失败（网络抖动/MinIO 临时不可用）不等于 MinIO 全局不可用。
            // 原逻辑将 minioAvailable 永久翻转为 false，导致 MinIO 恢复后
            // generatePresignedUrl 仍然返回 null，所有「查看原文」永久失效。
            // 修复：仅对本次上传降级到本地，不影响全局标志。
            log.error("[MinioStorage] MinIO 上传失败，本次降级本地 object={} err={}", objectKey, e.getMessage());
            return storeToLocal(stream, objectKey);
        }
    }

    /**
     * 降级：写到本地共享目录（如 NFS 挂载点）。
     * 注意：多节点部署时 localFallbackPath 必须为共享存储（NFS/SAN），
     *       否则 Python Worker 可能无法访问该路径。
     */
    private String storeToLocal(InputStream stream, String objectKey) throws Exception {
        java.nio.file.Path target = Paths.get(localFallbackPath,
                objectKey.replace("/", java.io.File.separator));
        Files.createDirectories(target.getParent());
        Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target.toAbsolutePath().toString();
    }

    /** 根据文件后缀推断 MIME 类型 */
    private String guessContentType(String name) {
        if (name == null) return "application/octet-stream";
        name = name.toLowerCase();
        if (name.endsWith(".pdf"))  return "application/pdf";
        if (name.endsWith(".doc"))  return "application/msword";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".txt"))  return "text/plain";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    /**
     * 生成文件预览的 MinIO 预签名 URL（15 分钟有效期）。
     * 业务功能：供前端文件跳转使用，无需在 Java 服务代理大文件流量。
     * MinIO 不可用（降级本地模式）或 storagePath 格式不匹配时返回 null，
     * 调用方应对 null 降级为提示"文件暂时不可预览"。
     *
     * @param storagePath MinioStorageService.store() 返回的路径，
     *                    格式为 "knowledge-base/2024/03/uuid.pdf"（即 bucket/objectKey）
     * @return 预签名 GET URL 字符串；不可用时返回 null
     */
    public String generatePresignedUrl(String storagePath) {
        // [Bug 修复] 不再依赖缓存的 minioAvailable 标志：
        // 原因： storeToMinio() 失败时会永久将 minioAvailable 置为 false，
        //   导致 MinIO 恢复后这里仍然返回 null。
        // 修复：直接尝试 SDK 调用，MinioClient 为 null 时才返回 null。
        if (minioClient == null) {
            log.warn("[MinioStorage] MinIO 客户端未初始化，跳过预签名 storePath={}", storagePath);
            return null;
        }
        if (storagePath == null || storagePath.isEmpty()) {
            log.warn("[MinioStorage] storagePath 为空，跳过预签名");
            return null;
        }

        // [兼容修复] 已有数据中 storagePath 可能是完整预签名 URL（http://host/bucket/key?X-Amz-...）。
        // 根因：Python bg_notify_java() 将 Redis 任务的 filePath（预签名下载 URL）直接存入 DB，
        //       导致 startsWith("knowledge-base/") 检查失败 → return null → 503。
        // 修复：识别 http/https 前缀，提取 URI 路径部分，去掉前导 '/'，恢复为 bucket/objectKey 格式。
        String normalizedPath = storagePath;
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
            try {
                java.net.URI uri = new java.net.URI(storagePath);
                // uri.getPath() = "/knowledge-base/2026/04/uuid.docx"
                String path = uri.getPath();
                if (path.startsWith("/")) path = path.substring(1); // "knowledge-base/2026/04/uuid.docx"
                normalizedPath = path;
                log.info("[MinioStorage] storagePath 为完整 URL，已提取路径部分 path={}", normalizedPath);
            } catch (Exception e) {
                log.warn("[MinioStorage] 无法解析 URL 格式的 storagePath: {} err={}", storagePath, e.getMessage());
                return null;
            }
        }

        // storagePath 格式：bucketName + "/" + objectKey，先分离出 objectKey
        String prefix = bucketName + "/";
        if (!normalizedPath.startsWith(prefix)) {
            log.warn("[MinioStorage] storagePath 格式不匹配（可能是本地降级路径） bucket={} path={}", bucketName, storagePath);
            return null;
        }
        String objectKey = normalizedPath.substring(prefix.length());
        try {
            String url = minioClient.getPresignedObjectUrl(
                io.minio.GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET)
                    .bucket(bucketName)
                    .object(objectKey)
                    .expiry(15, java.util.concurrent.TimeUnit.MINUTES)
                    .build()
            );
            log.info("[MinioStorage] 预签名URL生成成功 object={}", objectKey);
            return url;
        } catch (Exception e) {
            log.error("[MinioStorage] 预签名URL生成失败 object={} err={}", objectKey, e.getMessage());
            return null;
        }
    }

    /** 返回当前 MinIO 是否可用（供健康检查使用） */
    public boolean isMinioAvailable() {
        return minioAvailable;
    }
}
