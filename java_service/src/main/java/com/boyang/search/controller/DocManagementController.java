package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.KbDocRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理 REST Controller。
 * 业务功能：对外提供已入库文档的分页查询、内部注册回调、逻辑删除和元数据更新接口。
 * 关键接口：
 *   POST /api/v1/internal/doc/registry       - Python 入库后注册回调（内部，凭证校验）
 *   GET  /api/v1/admin/docs                  - 分页查询文档列表（需内部凭证）
 *   GET  /api/v1/admin/docs/{id}/versions    - 查询文档历史版本（权限校验）
 *   DELETE /api/v1/admin/docs/{id}           - 逻辑删除文档（需内部凭证+审计日志）
 *   PUT  /api/v1/admin/docs/{id}/meta        - 更新文档元数据（需内部凭证+审计日志）
 *   GET  /api/v1/admin/docs/verify/{source}  - 入库完整性校验
 *   GET  /api/v1/internal/doc/next-version   - 获取文档下一版本号（P1-7 原子自增）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocManagementController {

    private final KbDocRegistryService  registryService;
    private final PermissionGuard       permissionGuard;
    private final ElasticsearchClient   esClient;

    // ─────────────────────────────────────────────────────────────
    // 工具方法：统一 401 响应体
    // ─────────────────────────────────────────────────────────────

    /** 快速生成 401 未授权响应（管理接口统一使用） */
    private static Map<String, Object> unauthorized() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 401);
        r.put("msg", "未授权访问，管理端接口需携带有效内部服务凭证（X-Internal-Token）");
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // 重复文件检测
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/admin/docs/check")
    public Map<String, Object> checkExists(@RequestParam String name) {
        Map<String, Object> res = new HashMap<>();
        KbDocRegistry latest = registryService.findLatest(name);
        if (latest != null && !"DELETED".equals(latest.getStatus())) {
            res.put("code", 200);
            res.put("exists", true);
            res.put("version",   latest.getDocVersion());
            res.put("createdAt", latest.getCreatedAt());
            res.put("status",    latest.getStatus());
            res.put("msg", "文档已存在 v" + latest.getDocVersion() + "，继续上传将创建新版本");
        } else {
            res.put("code", 200);
            res.put("exists", false);
            res.put("msg", "文档不存在，可正常上传");
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────
    // 内部回调接口（凭证校验）
    // ─────────────────────────────────────────────────────────────

    /**
     * 文档注册回调（Python 入库成功后调用，旧版 rag_pipeline.py 使用）。
     * P0-1 修复：验证 X-Internal-Token（凭证由 yml + 环境变量注入，不再硬编码）。
     * 路由说明：原路径 /internal/doc/registry 已被 DocRegisterController（Outbox 模式）占用，
     *          此处改为 /internal/doc/register-callback，功能语义更清晰且无冲突。
     */
    @PostMapping("/internal/doc/register-callback")
    public Map<String, Object> registerDoc(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        try {
            KbDocRegistry entry = registryService.registerDoc(
                str(body, "sourceName"), num(body, "docVersion", 1),
                str(body, "docId"), str(body, "storagePath"), str(body, "targetIndex"),
                num(body, "chunkCount", 0), str(body, "contentHash"),
                str(body, "docNumber"), str(body, "unit"), str(body, "tags"),
                str(body, "publishTime"), str(body, "visibility"),
                str(body, "deptCode"), str(body, "uploaderId"), str(body, "uploaderName")
            );
            res.put("code", 200);
            res.put("msg", "注册成功");
            res.put("data", entry.getId());
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "注册失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 获取文档下一版本号（P1-7：原子自增，替代 rag_pipeline.py 的 ES 读取竞争方案）。
     * Python rag_pipeline 在入库前调用此接口获取版本号，保证单调递增且无并发竞争。
     * 底层由 MySQL 事务锁保证原子性。
     */
    @GetMapping("/internal/doc/next-version")
    public Map<String, Object> getNextVersion(@RequestParam String sourceName,
                                              HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        try {
            int nextVersion = registryService.getNextVersion(sourceName);
            res.put("code", 200);
            res.put("data", nextVersion);
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "获取版本号失败: " + e.getMessage());
        }
        return res;
    }

    // ─────────────────────────────────────────────────────────────
    // 管理前端接口（均需内部凭证校验）
    // ─────────────────────────────────────────────────────────────

    /**
     * 分页查询文档列表（P0-2 修复：补充身份校验）。
     * 修复前：无任何鉴权，任意访问即可枚举整个知识库文档目录。
     * 修复后：需携带有效内部服务凭证，未授权请求返回 401。
     */
    @GetMapping("/admin/docs")
    public Map<String, Object> listDocs(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String keyword,
            @RequestParam(required = false, defaultValue = "INDEXED") String status) {
        // P0-2: 管理端文档列表需鉴权，防止文档目录全量泄露
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        IPage<KbDocRegistry> result = registryService.listDocs(page, size, keyword, status);
        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("records", result.getRecords());
        dataBody.put("total",   result.getTotal());
        dataBody.put("current", result.getCurrent());
        dataBody.put("size",    result.getSize());
        res.put("data", dataBody);
        return res;
    }

    /**
     * 查询文档历史版本（权限校验）。
     * P1-2 修复：将原来的 listDocs(1,1).stream().filter() 替换为直接 getById 主键查询。
     * 原实现 Bug：id 不在第一页时必然返回 null → 404，功能完全失效。
     */
    @GetMapping("/admin/docs/{id}/versions")
    public Map<String, Object> getVersions(@PathVariable Long id, HttpServletRequest request) {
        Map<String, Object> res = new HashMap<>();
        // P1-2 修复：直接主键查询，O(1)，消除原来的分页 Bug
        KbDocRegistry entry = registryService.getById(id);
        if (entry == null) {
            res.put("code", 404);
            res.put("msg", "文档不存在");
            return res;
        }

        String userId   = request.getHeader("X-User-Id");
        String userDept = request.getHeader("X-User-Dept");
        PermissionGuard.AccessResult ar = permissionGuard.canAccess(entry, userId, userDept);
        if (!ar.isAllowed()) return ar.denyResponse();

        List<KbDocRegistry> versions = registryService.getVersionHistory(entry.getSourceName());
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", versions);
        return res;
    }

    /**
     * 逻辑删除文档（P0-3 修复：补充身份校验 + 操作审计日志）。
     * 修复前：无鉴权，任意人通过枚举 id 可删除任意文档。
     * 修复后：需携带内部凭证；删除前验证文档存在；写入操作审计日志。
     */
    @OperationLog(module = "文档管理", operation = "删除文档")
    @DeleteMapping("/admin/docs/{id}")
    public Map<String, Object> deleteDoc(@PathVariable Long id, HttpServletRequest request) {
        // P0-3: 删除操作需鉴权，防止任意文档被恶意删除
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        // 操作前校验文档存在（不依赖 deleteDoc 的返回值判断）
        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "文档不存在或已删除");
            return res;
        }
        // P0-3: 操作审计日志（operator / docId / sourceName 三要素）
        String operator = request.getHeader("X-User-Id");
        log.info("[DocAudit][DELETE] operator='{}' docId={} sourceName='{}'",
                operator != null ? operator : "unknown", id, doc.getSourceName());
        boolean ok = registryService.deleteDoc(id);
        res.put("code", ok ? 200 : 500);
        res.put("msg",  ok ? "删除成功" : "删除失败，请重试");
        return res;
    }

    /**
     * 更新文档元数据（P0-4 修复：补充身份校验 + 操作审计日志）。
     * 修复前：无鉴权，任意人可篡改文档 visibility、deptCode 等权限敏感字段。
     * 修复后：需携带内部凭证；记录操作人和变更字段。
     */
    @OperationLog(module = "文档管理", operation = "更新元数据")
    @PutMapping("/admin/docs/{id}/meta")
    public Map<String, Object> updateMeta(@PathVariable Long id,
                                          @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        // P0-4: 元数据更新（含 visibility/deptCode 权限字段）必须鉴权
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        String operator = request.getHeader("X-User-Id");
        // P0-4: 记录变更人和变更字段，便于安全审计
        log.info("[DocAudit][META_UPDATE] operator='{}' docId={} fields={}",
                operator != null ? operator : "unknown", id, body.keySet());
        boolean ok = registryService.updateMeta(id, body);
        res.put("code", ok ? 200 : 404);
        res.put("msg",  ok ? "更新成功" : "文档不存在");
        return res;
    }

    /**
     * 入库完整性校验（使用内部凭证保护）。
     * 比对 MySQL chunkCount 与 ES 中实际 chunk 数，不一致时返回 INCONSISTENT。
     */
    @GetMapping("/admin/docs/verify/{sourceName}")
    public Map<String, Object> verifyChunkIntegrity(@PathVariable String sourceName,
                                                    HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        try {
            KbDocRegistry latest = registryService.findLatest(sourceName);
            if (latest == null || "DELETED".equals(latest.getStatus())) {
                res.put("code", 404); res.put("msg", "文档不存在或已删除"); return res;
            }
            int expectedCount = latest.getChunkCount() != null ? latest.getChunkCount() : 0;
            CountRequest countReq = CountRequest.of(cr -> cr
                .index("kb_document_v*")
                .query(q -> q.bool(b -> b
                    .must(m -> m.term(t -> t.field("metadata.source").value(sourceName)))
                    .must(m -> m.term(t -> t.field("metadata.is_latest").value(true)))
                ))
            );
            long actualCount = esClient.count(countReq).count();
            Map<String, Object> data = new HashMap<>();
            data.put("sourceName",     sourceName);
            data.put("docVersion",     latest.getDocVersion());
            data.put("expectedChunks", expectedCount);
            data.put("actualChunks",   actualCount);
            data.put("consistent",     actualCount == expectedCount);
            data.put("status",         actualCount == expectedCount ? "OK" : "INCONSISTENT");
            res.put("code", 200);
            res.put("msg",  actualCount == expectedCount ? "入库完整" : "入库数据不一致，建议重新入库");
            res.put("data", data);
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "完整性校验失败: " + e.getMessage());
        }
        return res;
    }

    // ─── 工具方法 ────────────────────────────────────────────────
    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key); return v == null ? null : v.toString();
    }
    private static int num(Map<String, Object> m, String key, int def) {
        Object v = m.get(key); return v instanceof Number ? ((Number) v).intValue() : def;
    }
}
