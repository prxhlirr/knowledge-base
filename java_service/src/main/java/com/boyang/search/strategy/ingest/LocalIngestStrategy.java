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
        } else if (req.getFilePath() != null) {
            files.add(new File(req.getFilePath()));
        }

        log.info("[DocIngest][LOCAL] 扫描完成，准备派发任务 count={}", files.size());

        for (File f : files) {
            if (!f.exists()) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                continue;
            }
            // [P0 优化] 针对本地导入场景，不再调用 Files.readAllBytes() 读入内存。
            // 也不再上传到 MinIO (this.storageService.store)。
            // 而是将磁盘绝对路径直接传给下游 AI 服务进行"直读"。
            try {
                // businessName 优先取 req.getFileName()
                String businessName = (req.getFileName() != null && !req.getFileName().trim().isEmpty())
                        ? req.getFileName() : f.getName();
                
                // 构造任务信息，path 为本地绝对路径
                Map<String, String> taskInfo = this.buildTaskInfo(f.getAbsolutePath(), businessName, req, "");
                // 标记为本地直读模式，以便下游 Python 识别
                taskInfo.put("storageMode", "LOCAL_FS"); 
                
                result.add(taskInfo);
            } catch (Exception e) {
                log.warn("[DocIngest][LOCAL] 构造任务失败 path={} err={}", f.getAbsolutePath(), e.getMessage());
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
