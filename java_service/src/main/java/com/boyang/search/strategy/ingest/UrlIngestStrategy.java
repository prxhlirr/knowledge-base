package com.boyang.search.strategy.ingest;

import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.utils.DocumentTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * URL 入库策略（从公网/内网 HTTP URL 下载）。
 * <p>
 * [统一化] 子类职责收敛为「文件来源」：URL 安全校验 + 下载到 byte[]，
 * 其余复用 {@link AbstractIngestStrategy#processFile} 公共管线。
 */
@Component
public class UrlIngestStrategy extends AbstractIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(UrlIngestStrategy.class);
    private static final List<String> ALLOWED_SCHEMES = Arrays.asList("http", "https");

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
            fileName = deriveFilenameFromUrl(fileUrl);
        } else {
            fileName = DocumentTextNormalizer.normalizeFilename(fileName);
        }

        try (InputStream is = new URL(fileUrl).openStream()) {
            final byte[] fileBytes = StreamUtils.copyToByteArray(is);
            // 公共管线：sanitize + full_hash + 前置去重 + 分级 store + full_hash 透传
            Map<String, String> info = processFile(
                    () -> new java.io.ByteArrayInputStream(fileBytes),
                    fileName, req, batch);
            return info != null ? Collections.singletonList(info) : Collections.emptyList();
        } catch (Exception e) {
            log.error("[DocIngest] URL拉取失败 URL={} {}", fileUrl, e.getMessage());
            batch.setErrorCount(batch.getErrorCount() + 1);
            throw e;
        }
    }

    static String deriveFilenameFromUrl(String rawUrl) throws Exception {
        URI uri = new URI(rawUrl);
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty() || rawPath.endsWith("/")) {
            return "downloaded-file";
        }
        String segment = rawPath.substring(rawPath.lastIndexOf("/") + 1);
        return DocumentTextNormalizer.normalizeFilename(segment);
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
