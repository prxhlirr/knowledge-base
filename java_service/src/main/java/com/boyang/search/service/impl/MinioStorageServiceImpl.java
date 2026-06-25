package com.boyang.search.service.impl;

import com.boyang.search.service.StorageService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件直写云端实现（无磁盘落地版）
 */
@Service
@ConditionalOnProperty(name = "knowledge.base.storage-type", havingValue = "minio")
public class MinioStorageServiceImpl implements StorageService {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @PostConstruct
    public void init() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception e) {
            System.err.println("MinIO bucket 层级骨架拉取与自检失败: " + e.getMessage());
        }
    }

    @Override
    public String store(InputStream inputStream, String originalName) {
        return store(inputStream, originalName, null);
    }

    /**
     * [MinIO 路径优化] 带业务分类的存储。
     * <p>
     * object key 格式：{businessCategory}/yyyy/MM/dd/{文件名主体}_{uuid}.{ext}
     *   - 业务首级（doc/html/upload）便于按来源管理与清理；
     *   - 日期精确到日，便于按时间归档；
     *   - 文件名主体保留可读性，UUID 后缀杜绝同名覆盖。
     * <p>
     * 重复内容在 dbDocExtractJob 预查阶段已被拦截（不 ingest → 不上传），
     * 故 UUID 后缀足够防覆盖，无需 hash 幂等路径。
     */
    @Override
    public String store(InputStream inputStream, String originalName, String businessCategory) {
        try {
            String suffix = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf(".")) : "";
            String baseName = sanitizeBaseName(originalName);
            String biz = (businessCategory != null && !businessCategory.trim().isEmpty())
                    ? businessCategory.trim() : "upload";
            String datePath = new java.text.SimpleDateFormat("yyyy/MM/dd").format(new java.util.Date());
            String objectKey = biz + "/" + datePath + "/" + baseName + "_" + UUID.randomUUID().toString() + suffix;

            // 阶段 1：[零落盘] 直接以流的形式全投送云端
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(inputStream, -1, 10485760)
                    .build()
            );

            // 阶段 2：生成 7 天有效的预签名直链，供 Python Worker 跨网段 requests 直读
            return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectKey)
                    .expiry(7, TimeUnit.DAYS)
                    .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("未能顺畅流式将文件吞储至 Minio 云堆: " + e.getMessage(), e);
        }
    }

    /** 提取文件名主体并去除文件系统非法字符，用作 object key 的可读部分 */
    private String sanitizeBaseName(String originalName) {
        if (originalName == null || originalName.isEmpty()) return "file";
        String name = originalName.contains(".")
                ? originalName.substring(0, originalName.lastIndexOf("."))
                : originalName;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "file" : name;
    }
}
