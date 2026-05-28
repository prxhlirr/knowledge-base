package com.boyang.search.controller;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.MinioStorageService;
import com.boyang.search.service.SensitivePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FileController {

    private final KbDocRegistryService docRegistryService;
    private final MinioStorageService minioStorageService;
    private final PermissionGuard permissionGuard;
    private final SensitivePolicyService sensitivePolicyService;

    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam(value = "sourceName", required = false) String sourceName,
            @RequestParam(value = "docId", required = false) String docId) {

        Map<String, Object> resp = new HashMap<>();
        if (isBlank(sourceName) && isBlank(docId)) {
            resp.put("code", 400);
            resp.put("msg", "sourceName or docId is required");
            return resp;
        }

        KbDocRegistry doc = null;
        if (!isBlank(docId)) {
            String fileDocId = docId.trim().replaceAll("_(chunk|fine)_\\d+$", "");
            doc = docRegistryService.findByDocId(fileDocId);
            log.info("[FilePreview] docId lookup chunkId={} fileDocId={} found={}",
                    docId.trim(), fileDocId, doc != null);
        }

        if (doc == null && !isBlank(sourceName)) {
            doc = docRegistryService.findLatestPreviewable(sourceName.trim());
            log.info("[FilePreview] sourceName lookup sourceName={} found={}", sourceName.trim(), doc != null);
        }

        if (doc == null) {
            resp.put("code", 404);
            resp.put("msg", "file not found or not ingested");
            return resp;
        }

        com.boyang.search.security.JwtVerifier.UserIdentity identity =
                com.boyang.search.security.UserContextHolder.getIdentity();
        PermissionGuard.AccessResult access = permissionGuard.canAccess(doc.getSourceName(), identity);
        if (!access.isAllowed()) {
            resp.put("code", 403);
            resp.put("msg", access.getDenyReason());
            return resp;
        }

        SensitivePolicyService.FilterResult previewPolicy =
                sensitivePolicyService.filterText(buildPolicyText(doc), "PREVIEW", identity);
        if (previewPolicy.isBlocked()) {
            resp.put("code", 403);
            resp.put("msg", "File preview blocked by sensitive policy");
            return resp;
        }
        SensitivePolicyService.FilterResult downloadPolicy =
                sensitivePolicyService.filterText(buildPolicyText(doc), "DOWNLOAD", identity);
        if (downloadPolicy.isBlocked()) {
            resp.put("code", 403);
            resp.put("msg", "File access blocked by sensitive policy");
            return resp;
        }

        if (isBlank(doc.getStoragePath())) {
            log.warn("[FilePreview] storagePath missing sourceName={}", doc.getSourceName());
            resp.put("code", 404);
            resp.put("msg", "file storage path is not registered");
            return resp;
        }

        String presignedUrl = minioStorageService.generatePresignedUrl(doc.getStoragePath());
        if (presignedUrl == null) {
            log.warn("[FilePreview] presigned URL generation failed sourceName={} storagePath={}",
                    doc.getSourceName(), doc.getStoragePath());
            resp.put("code", 503);
            resp.put("msg", "file storage service is temporarily unavailable");
            return resp;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("url", presignedUrl);
        data.put("file_name", doc.getSourceName());
        resp.put("code", 200);
        resp.put("data", data);
        resp.put("msg", "success");
        return resp;
    }

    private String buildPolicyText(KbDocRegistry doc) {
        StringBuilder text = new StringBuilder();
        appendPolicyText(text, doc.getSourceName());
        appendPolicyText(text, doc.getDocNumber());
        appendPolicyText(text, doc.getUnit());
        appendPolicyText(text, doc.getTags());
        appendPolicyText(text, doc.getDeptCode());
        return text.toString();
    }

    private void appendPolicyText(StringBuilder text, String value) {
        if (isBlank(value)) {
            return;
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(value.trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
