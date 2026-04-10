package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.StorageService;
import com.boyang.search.utils.ContentHashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalIngestStrategy.class);

    private final StorageService storageService;

    @Value("${doc.upload.max-file-size:104857600}")
    private long maxFileSizeBytes;

    @Autowired
    public LocalIngestStrategy(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getStrategyType() {
        return "LOCAL";
    }

    @Override
    public List<Map<String, String>> process(DocIngestRequest req, SysDocBatch batch) throws Exception {
        ArrayList<Map<String, String>> result = new ArrayList<>();
        ArrayList<File> files = new ArrayList<>();

        if (req.getDirPath() != null && !req.getDirPath().isEmpty()) {
            this.scanDir(new File(req.getDirPath()), files);
        } else {
            files.add(new File(req.getFilePath()));
        }

        for (File f : files) {
            if (!f.exists() || f.length() > this.maxFileSizeBytes) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                continue;
            }
            try {
                byte[] fileBytes = Files.readAllBytes(f.toPath());
                String contentHash = ContentHashUtils.compute(fileBytes);
                String savedPath = this.storageService.store(new ByteArrayInputStream(fileBytes), f.getName());
                // [Fix] 单文件模式下，优先使用 req.getFileName() 作为入库文档名（source_name）。
                // 根因：f.getName() 是物理临时文件名（如 db_1_xxx.html），会作为 ES source 字段永久存储，
                //       导致 source_name 没有业务含义，且不同 job 触发会产生重复内容的不同 source_name 绕过去重。
                // 修复：businessName 优先取 req.getFileName()，只有批量目录扫描（无 fileName 时）才用 f.getName()。
                String businessName = (req.getFileName() != null && !req.getFileName().trim().isEmpty())
                        ? req.getFileName() : f.getName();
                result.add(this.buildTaskInfo(savedPath, businessName, req, contentHash));
            } catch (Exception e) {
                log.warn("[DocIngest][LOCAL] 读取文件失败 path={} err={}", f.getAbsolutePath(), e.getMessage());
                batch.setErrorCount(batch.getErrorCount() + 1);
            }
        }
        return result;
    }

    private void scanDir(File dir, List<File> files) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                this.scanDir(f, files);
                continue;
            }
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".pdf") && !name.endsWith(".doc") && !name.endsWith(".docx") && !name.endsWith(".txt")) continue;
            files.add(f);
        }
    }

}
