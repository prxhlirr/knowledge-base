package com.boyang.search.strategy.ingest;

import com.boyang.search.config.SftpCredentialConfig;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.StorageService;
import com.boyang.search.utils.ContentHashUtils;
import com.boyang.search.utils.SftpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class SftpIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(SftpIngestStrategy.class);
    
    private final StorageService storageService;
    private final SftpCredentialConfig sftpCredentialConfig;

    @Autowired
    public SftpIngestStrategy(StorageService storageService, SftpCredentialConfig sftpCredentialConfig) {
        this.storageService = storageService;
        this.sftpCredentialConfig = sftpCredentialConfig;
    }

    @Override
    public String getStrategyType() {
        return "SFTP";
    }

    @Override
    public List<Map<String, String>> process(DocIngestRequest req, SysDocBatch batch) throws Exception {
        SftpCredentialConfig.SftpCredential cred = this.sftpCredentialConfig.getCredential(req.getCredentialId());
        String knownHosts = expandHome(this.sftpCredentialConfig.getKnownHostsPath());
        ArrayList<Map<String, String>> result = new ArrayList<>();
        
        try (SftpUtil sftp = new SftpUtil(cred.getHost(), cred.getPort(), cred.getUser(), cred.getPassword(), knownHosts)) {
            List<SftpUtil.SftpFile> remoteFiles;
            if (req.getDirPath() != null && !req.getDirPath().isEmpty()) {
                remoteFiles = sftp.listFiles(req.getDirPath());
            } else {
                SftpUtil.SftpFile singleFile = new SftpUtil.SftpFile(
                        req.getFilePath(),
                        req.getFileName() != null ? req.getFileName() : req.getFilePath().substring(req.getFilePath().lastIndexOf("/") + 1)
                );
                remoteFiles = Collections.singletonList(singleFile);
            }
            
            for (SftpUtil.SftpFile rf : remoteFiles) {
                try (InputStream is = sftp.downloadStream(rf.fullPath)) {
                    byte[] fileBytes = StreamUtils.copyToByteArray(is);
                    String contentHash = ContentHashUtils.compute(fileBytes);
                    String savedPath = this.storageService.store(new ByteArrayInputStream(fileBytes), rf.name);
                    result.add(this.buildTaskInfo(savedPath, rf.name, req, contentHash));
                } catch (Exception e) {
                    log.warn("[DocIngest][SFTP] 文件下载失败 path={} err={}", rf.fullPath, e.getMessage());
                    batch.setErrorCount(batch.getErrorCount() + 1);
                }
            }
        }
        return result;
    }

    private static String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
