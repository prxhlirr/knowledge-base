package com.boyang.search.controller;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件预览控制器（FileController）。
 * 业务功能：根据原始文件名查询 MinIO 存储路径，生成有效期 15 分钟的预签名 URL，
 *           前端用此 URL 直接打开原始文档，无需后端代理流量。
 *
 * 关键流程：
 *   1. 接收 sourceName（文件名，对应 kb_doc_registry.source_name）
 *   2. 查询最新版注册记录，获取 storagePath（MinIO 对象路径）
 *   3. 调用 MinioStorageService.generatePresignedUrl() 生成预签名 GET URL
 *   4. 返回 URL 给前端，前端通过 window.open(url, '_blank') 打开原始文件
 *
 * 降级策略：
 *   - 文件未入库 → 404
 *   - MinIO 不可用（降级本地存储模式）→ 503 + 友好提示
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/file")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FileController {

    private final KbDocRegistryService docRegistryService;
    private final MinioStorageService  minioStorageService;

    /**
     * 生成原始文件的预签名预览 URL。
     *
     * 业务功能：前端点击搜索结果“查看原文”时调用，返回可直接访问的临时下载链接。
     * 查找策略（优先级降序）：
     *   1. docId 直接使用 ES chunk doc_id（格式：{hash}_v{N}_chunk_{i}）：
     *      截取 _chunk_/_fine_ 前缀 → 得到文档级 id（{hash}_v{N}）→ 查 kb_doc_registry.doc_id
     *   2. 如果 docId 未传或查不到，用 sourceName 按最新版本备用查找
     * 如果 storage_path 为空：说明文件注册时回调失败（通常因 JAVA_SERVICE_HOST 配置错误），
     *   操作人需重新上传文件以触发正确的路径注册。
     *
     * @param sourceName 文件展示名（搜索结果中的 file_name 字段）
     * @param docId      ES chunk doc_id（如 abc123_v3_chunk_2，可选）
     * @return {"code":200, "data":{"url":"..."}}
     */
    @GetMapping("/preview")
    public Map<String, Object> preview(
            @RequestParam(value = "sourceName", required = false) String sourceName,
            @RequestParam(value = "docId",      required = false) String docId) {

        Map<String, Object> resp = new HashMap<>();

        if ((sourceName == null || sourceName.trim().isEmpty())
                && (docId == null || docId.trim().isEmpty())) {
            resp.put("code", 400);
            resp.put("msg", "参数 sourceName 或 docId 至少提供一个");
            return resp;
        }

        KbDocRegistry doc = null;

        // 1. 优先通过 docId 精确定位（解决同名文件冲突）
        if (docId != null && !docId.trim().isEmpty()) {
            // ES chunk doc_id 格式：{hash}_v{N}_chunk_{i} 或 {hash}_v{N}_fine_{i}
            // 截取 _chunk_/_fine_ 前的部分，得到文档级 id：{hash}_v{N}
            String fileDocId = docId.trim().replaceAll("_(chunk|fine)_\\d+$", "");
            doc = docRegistryService.findByDocId(fileDocId);
            log.info("[FileController] docId 查找: chunk_id={} → file_doc_id={} → found={}",
                    docId.trim(), fileDocId, doc != null);
        }

        // 2. 备用：用 sourceName 查最新版本
        if (doc == null && sourceName != null && !sourceName.trim().isEmpty()) {
            doc = docRegistryService.findLatest(sourceName.trim());
            log.info("[FileController] sourceName 备用查找: sourceName={} → found={}",
                    sourceName.trim(), doc != null);
        }

        if (doc == null) {
            resp.put("code", 404);
            resp.put("msg", "文件不存在或尚未入库，请确认文件名是否正确");
            return resp;
        }

        // 3. 判断 storage_path 是否已填充
        if (doc.getStoragePath() == null || doc.getStoragePath().trim().isEmpty()) {
            log.warn("[FileController] storage_path 为空，注册回调可能失败 sourceName={}", doc.getSourceName());
            resp.put("code", 404);
            resp.put("msg", "文件路径未记录（可能因 Python 服务地址配置错误导致注册回调失败），请重新上传该文件");
            return resp;
        }

        // 4. 生成 MinIO 预签名 URL（15 分钟有效）
        String presignedUrl = minioStorageService.generatePresignedUrl(doc.getStoragePath());
        if (presignedUrl == null) {
            log.warn("[FileController] 预签名URL生成失败，MinIO 可能处于降级模式 sourceName={} storagePath={}",
                    doc.getSourceName(), doc.getStoragePath());
            resp.put("code", 503);
            resp.put("msg", "文件存储服务暂时不可用，请联系管理员");
            return resp;
        }

        // 5. 返回预签名 URL
        Map<String, Object> data = new HashMap<>();
        data.put("url",       presignedUrl);
        data.put("file_name", doc.getSourceName());
        resp.put("code", 200);
        resp.put("data", data);
        resp.put("msg", "success");
        log.info("[FileController] 预签名URL已生成 sourceName={}", doc.getSourceName());
        return resp;
    }
}
