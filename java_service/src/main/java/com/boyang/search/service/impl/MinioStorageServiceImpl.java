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
        try {
            // [重构核心] 彻底砍掉并根除了诸如 "C:\User\tmp" 这种毒瘤物理长径
            String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : "";
            // 采用时间与随机组合体保障唯一性并扁平铺装云存储
            String fileName = UUID.randomUUID().toString() + suffix;
            
            // 阶段 1：[零落盘] 直接以流的形式全投送云端
            // partSize 指定 10MB，应对 InputStream 长度不定的 Web 封装特性
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(inputStream, -1, 10485760)
                    .build()
            );
            
            // 阶段 2：以租约方式生成最长 7 天内有效、跨微服务通行的直链抓取地址！
            // 返回给下路的 Python AI 去调用 requests 直读，从此避开跨网段卷挂载难关！
            String preSignedUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(fileName)
                    .expiry(7, TimeUnit.DAYS)
                    .build()
            );
            
            return preSignedUrl;
        } catch (Exception e) {
            throw new RuntimeException("未能顺畅流式将文件吞储至 Minio 云堆: " + e.getMessage(), e);
        }
    }
}
