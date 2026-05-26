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

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LocalIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalIngestStrategy.class);

    private final StorageService storageService;

    @Value("${doc.upload.max-file-size:104857600}")
    private long maxFileSizeBytes;

    @Value("${knowledge.base.local-import-mode:minio}")
    private String localImportMode;

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
            scanDir(new File(req.getDirPath()), files);
        } else if (req.getFilePath() != null) {
            files.add(new File(req.getFilePath()));
        }

        log.info("[DocIngest][LOCAL] scan completed, ready to dispatch count={} mode={}",
                files.size(), localImportMode);

        for (File f : files) {
            if (!f.exists() || !f.isFile()) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.warn("[DocIngest][LOCAL] file not found or not regular path={}", f.getAbsolutePath());
                continue;
            }
            if (f.length() > maxFileSizeBytes) {
                batch.setErrorCount(batch.getErrorCount() + 1);
                log.warn("[DocIngest][LOCAL] file exceeds size limit path={} size={}MB",
                        f.getAbsolutePath(), f.length() / 1024L / 1024L);
                continue;
            }

            try {
                String businessName = (req.getFileName() != null && !req.getFileName().trim().isEmpty())
                        ? req.getFileName()
                        : f.getName();

                if ("direct".equalsIgnoreCase(localImportMode)) {
                    Map<String, String> taskInfo = buildTaskInfo(f.getAbsolutePath(), businessName, req, "");
                    taskInfo.put("storageMode", "LOCAL_FS");
                    taskInfo.put("sourceLocalPath", f.getAbsolutePath());
                    result.add(taskInfo);
                    continue;
                }

                String contentHash = ContentHashUtils.compute(f.toPath());
                try (InputStream is = Files.newInputStream(f.toPath())) {
                    String savedPath = storageService.store(is, businessName);
                    Map<String, String> taskInfo = buildTaskInfo(savedPath, businessName, req, contentHash);
                    taskInfo.put("storageMode", "MINIO");
                    taskInfo.put("sourceLocalPath", f.getAbsolutePath());
                    result.add(taskInfo);
                    log.info("[DocIngest][LOCAL] uploaded local file sourcePath={} storagePath={}",
                            f.getAbsolutePath(), savedPath);
                }
            } catch (Exception e) {
                log.warn("[DocIngest][LOCAL] file processing failed path={} err={}",
                        f.getAbsolutePath(), e.getMessage());
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
                scanDir(f, files);
                continue;
            }
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".pdf")
                    && !name.endsWith(".doc")
                    && !name.endsWith(".docx")
                    && !name.endsWith(".txt")) {
                continue;
            }
            files.add(f);
        }
    }
}
