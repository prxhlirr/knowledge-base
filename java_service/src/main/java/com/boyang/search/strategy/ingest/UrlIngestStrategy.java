package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.StorageService;
import com.boyang.search.utils.ContentHashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class UrlIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(UrlIngestStrategy.class);
    private static final List<String> ALLOWED_SCHEMES = Arrays.asList("http", "https");

    private final StorageService storageService;

    @Autowired
    public UrlIngestStrategy(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getStrategyType() {
        return "URL";
    }

    @Override
    public List<Map<String, String>> process(DocIngestRequest req, SysDocBatch batch) throws Exception {
        String fileUrl = req.getFilePath();
        this.validateUrlSafety(fileUrl);
        String fileName = req.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
        }
        
        try (InputStream is = new URL(fileUrl).openStream()) {
            byte[] fileBytes = StreamUtils.copyToByteArray(is);
            String contentHash = ContentHashUtils.compute(fileBytes);
            String savedPath = this.storageService.store(new ByteArrayInputStream(fileBytes), fileName);
            return Collections.singletonList(this.buildTaskInfo(savedPath, fileName, req, contentHash));
        } catch (Exception e) {
            log.error("[DocIngest] URL拉取失败 URL={} {}", fileUrl, e.getMessage());
            batch.setErrorCount(batch.getErrorCount() + 1);
            throw e;
        }
    }

    private void validateUrlSafety(String rawUrl) throws Exception {
        URI uri = new URI(rawUrl);
        String scheme = uri.getScheme();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new SecurityException("不支持的 URL 协议: " + scheme + "，仅允许 http/https");
        }
        String host = uri.getHost();
        if (this.isPrivateHost(host)) {
            throw new SecurityException("禁止访问内网地址: " + host);
        }
    }

    private boolean isPrivateHost(String host) {
        if (host == null) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
