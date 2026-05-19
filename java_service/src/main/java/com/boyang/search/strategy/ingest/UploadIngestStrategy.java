package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.StorageService;
import com.boyang.search.utils.ContentHashUtils;
import com.boyang.search.utils.FileTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class UploadIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(UploadIngestStrategy.class);

    private final StorageService storageService;
    private final KbDocRegistryService kbDocRegistryService;

    @Value("${doc.upload.max-file-size:104857600}")
    private long maxFileSizeBytes;

    @Autowired
    public UploadIngestStrategy(StorageService storageService, KbDocRegistryService kbDocRegistryService) {
        this.storageService = storageService;
        this.kbDocRegistryService = kbDocRegistryService;
    }

    @Override
    public String getStrategyType() {
        return "UPLOAD";
    }

    @Override
    public List<Map<String, String>> process(DocIngestRequest req, SysDocBatch batch) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        MultipartFile[] files = req.getUploadFiles();

        if (files == null || files.length == 0) {
            return result;
        }

        for (MultipartFile file : files) {
            byte[] header;
            if (file.getSize() > this.maxFileSizeBytes) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.warn("[DocIngest] 上传文件超过大小限制跳过: {} size={}MB", file.getOriginalFilename(), file.getSize() / 1024L / 1024L);
                continue;
            }
            try {
                header = file.getBytes();
            } catch (Exception e) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.error("[DocIngest] 读取文件字节失败: {}", file.getOriginalFilename());
                continue;
            }

            if (!FileTypeValidator.isAllowed(header)) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.warn("[DocIngest] 文件类型校验不通过: {}", file.getOriginalFilename());
                continue;
            }

            String contentHash = ContentHashUtils.compute(header);
            String safeName = sanitizeFilename(file.getOriginalFilename());
            boolean isDuplicate = false;
            try {
                KbDocRegistry existing = this.kbDocRegistryService.findLatest(safeName);
                if (existing == null) {
                    isDuplicate = this.kbDocRegistryService.existsByContentHash(contentHash);
                }
            } catch (Exception ex) {
                log.error("[DocIngest] 内容哈希查询异常: {}", ex.getMessage());
            }

            if (isDuplicate) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.info("[DocIngest] 跨文件名内容重复，跳过: {} hash={}", file.getOriginalFilename(), contentHash);
                continue;
            }

            try {
                String savedPath = this.storageService.store(new ByteArrayInputStream(header), safeName);
                result.add(this.buildTaskInfo(savedPath, safeName, req, contentHash));
            } catch (Exception e) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.error("[DocIngest] 上传保存失败: {} {}", file.getOriginalFilename(), e.getMessage());
            }
        }
        return result;
    }

    public static String sanitizeFilename(String original) {
        if (original == null || original.isEmpty()) {
            return "unknown";
        }
        String name = Paths.get(original).getFileName().toString();
        name = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}
