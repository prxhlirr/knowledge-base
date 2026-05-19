package com.boyang.search.service.impl;

import com.boyang.search.service.StorageService;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;

/**
 * 本地文件存储实现
 */
@Service
@ConditionalOnProperty(name = "knowledge.base.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalStorageServiceImpl implements StorageService {

    @Value("${knowledge.base.upload-path:e:/project/AI/knowledge-base/uploads}")
    private String uploadPath;

    @Override
    public String store(InputStream inputStream, String originalName) {
        try {
            String suffix = originalName.substring(originalName.lastIndexOf("."));
            String fileName = UUID.randomUUID().toString() + suffix;
            File targetFile = new File(uploadPath, fileName);
            
            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }
            
            FileUtils.copyInputStreamToFile(inputStream, targetFile);
            return targetFile.getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("本地存储文件失败: " + e.getMessage(), e);
        }
    }
}
