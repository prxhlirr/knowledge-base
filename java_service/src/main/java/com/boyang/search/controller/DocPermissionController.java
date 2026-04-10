package com.boyang.search.controller;

import com.boyang.search.entity.DocPermissionEvent;
import com.boyang.search.entity.DocVersionHistory;
import com.boyang.search.service.DocPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 文档权限事件 REST Controller。
 * 业务功能：对外提供入库事件写入（Python 侧调用）、权限历史查询、版本列表查询接口。
 * 关键接口：
 *   POST /api/doc/perm/record   - Python 入库后回调，写入版本记录和初始权限事件
 *   POST /api/doc/perm/handler  - 添加经手人事件
 *   GET  /api/doc/perm/events/{docId}    - 查询文档权限历史
 *   GET  /api/doc/perm/versions/{source} - 查询文档版本列表
 */
@RestController
@RequestMapping("/api/doc/perm")
@RequiredArgsConstructor
public class DocPermissionController {

    private final DocPermissionService permissionService;

    /**
     * 文档入库回调接口（由 Python rag_pipeline 在 bulk 写入成功后调用）。
     * 请求体格式：
     * {
     *   "sourceName":   "政法规程.docx",
     *   "docVersion":   1,
     *   "contentHash":  "abc123...",
     *   "chunkCount":   42,
     *   "uploaderId":   "user_007",
     *   "visibility":   "INTERNAL",
     *   "deptCode":     "620102900000"
     * }
     */
    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> recordIngestion(@RequestBody Map<String, Object> body) {
        try {
            String sourceName   = (String)  body.get("sourceName");
            int    docVersion   = body.get("docVersion") instanceof Number
                                  ? ((Number) body.get("docVersion")).intValue() : 1;
            String contentHash  = (String)  body.getOrDefault("contentHash", "");
            int    chunkCount   = body.get("chunkCount") instanceof Number
                                  ? ((Number) body.get("chunkCount")).intValue() : 0;
            String uploaderId   = (String)  body.getOrDefault("uploaderId", "system");
            String visibility   = (String)  body.getOrDefault("visibility",  "INTERNAL");
            String deptCode     = (String)  body.get("deptCode");

            permissionService.recordIngestion(sourceName, docVersion, contentHash,
                                              chunkCount, uploaderId, visibility, deptCode);
            Map<String, Object> okBody = new java.util.HashMap<>();
            okBody.put("status", "ok");
            okBody.put("sourceName", sourceName);
            okBody.put("version", docVersion);
            return ResponseEntity.ok(okBody);
        } catch (Exception e) {
            Map<String, Object> errBody = new java.util.HashMap<>();
            errBody.put("status", "error");
            errBody.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errBody);
        }
    }

    /**
     * 添加经手人事件接口（文档审核/流转时调用）。
     * 请求体格式：
     * {
     *   "docId":       "政法规程.docx",
     *   "handlerId":   "user_012",
     *   "handlerDept": "620102000000",
     *   "operatorId":  "user_007"
     * }
     */
    @PostMapping("/handler")
    public ResponseEntity<Map<String, Object>> addHandler(@RequestBody Map<String, Object> body) {
        try {
            String docId       = (String) body.get("docId");
            String handlerId   = (String) body.get("handlerId");
            String handlerDept = (String) body.get("handlerDept");
            String operatorId  = (String) body.getOrDefault("operatorId", "system");
            permissionService.addHandlerEvent(docId, handlerId, handlerDept, operatorId);
            Map<String, Object> okBody2 = new java.util.HashMap<>();
            okBody2.put("status", "ok");
            return ResponseEntity.ok(okBody2);
        } catch (Exception e) {
            Map<String, Object> errBody2 = new java.util.HashMap<>();
            errBody2.put("status", "error");
            errBody2.put("message", e.getMessage());
            return ResponseEntity.status(500).body(errBody2);
        }
    }

    /**
     * 变更文档可见度接口（管理员操作）。
     */
    @PostMapping("/visibility")
    public ResponseEntity<Map<String, Object>> changeVisibility(@RequestBody Map<String, Object> body) {
        String docId         = (String) body.get("docId");
        String newVisibility = (String) body.get("visibility");
        String operatorId    = (String) body.getOrDefault("operatorId", "system");
        permissionService.addVisibilityChange(docId, newVisibility, operatorId);
        Map<String, Object> okBody3 = new java.util.HashMap<>();
        okBody3.put("status", "ok");
        return ResponseEntity.ok(okBody3);
    }

    /**
     * 查询文档权限事件历史。
     *
     * @param docId 文档标识
     * @return 按时间升序的权限事件列表
     */
    @GetMapping("/events/{docId}")
    public ResponseEntity<List<DocPermissionEvent>> getEvents(@PathVariable String docId) {
        return ResponseEntity.ok(permissionService.getEventHistory(docId));
    }

    /**
     * 查询文档版本历史列表。
     *
     * @param source 文档名称（需 URL encode）
     * @return 按版本号倒序的版本列表
     */
    @GetMapping("/versions/{source}")
    public ResponseEntity<List<DocVersionHistory>> getVersions(@PathVariable String source) {
        return ResponseEntity.ok(permissionService.getVersionHistory(source));
    }
}
