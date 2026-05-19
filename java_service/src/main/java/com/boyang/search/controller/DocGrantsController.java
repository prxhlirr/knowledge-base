package com.boyang.search.controller;

import com.boyang.search.entity.KbDocGrants;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.KbDocGrantsService;
import com.boyang.search.service.KbDocRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GRANT 文档授权管理 Controller（P0-5 完整实现）。
 * 业务功能：提供 GRANT 类文档的授权/撤销/查询 RESTful 接口。
 * 所有接口均需内部服务凭证（X-Internal-Token）。
 *
 * 接口列表：
 *   POST   /api/v1/admin/docs/{id}/grants           — 向用户授权
 *   DELETE /api/v1/admin/docs/{id}/grants/{userId}  — 撤销用户授权
 *   GET    /api/v1/admin/docs/{id}/grants           — 查询文档授权列表
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocGrantsController {

    private final KbDocGrantsService grantsService;
    private final KbDocRegistryService registryService;
    private final PermissionGuard permissionGuard;

    /** 统一 401 响应 */
    private static Map<String, Object> unauthorized() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 401);
        r.put("msg", "未授权访问，需携带有效内部服务凭证（X-Internal-Token）");
        return r;
    }

    /**
     * 向指定用户授予文档 GRANT 访问权（P0-5 核心接口）。
     * 请求体字段：
     *   granteeId   — 被授权用户 ID（必填）
     *   granteeName — 被授权用户姓名（选填，展示用）
     *   expiresAt   — 到期时间 ISO-8601（选填，null=永久）
     *   remark      — 授权原因（选填）
     *
     * @param id   文档注册表主键 ID（kb_doc_registry.id）
     */
    @PostMapping("/admin/docs/{id}/grants")
    public Map<String, Object> grantAccess(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();

        // 验证文档存在且未删除
        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "文档不存在或已删除");
            return res;
        }

        // 仅 GRANT 类文档支持此操作
        if (!"GRANT".equalsIgnoreCase(doc.getVisibility())) {
            res.put("code", 400);
            res.put("msg", "只有 visibility=GRANT 的文档才支持授权操作，当前文档 visibility=" + doc.getVisibility());
            return res;
        }

        String granteeId   = str(body, "granteeId");
        String granteeName = str(body, "granteeName");
        String remark      = str(body, "remark");
        String operatorId  = request.getHeader("X-User-Id");

        if (granteeId == null || granteeId.trim().isEmpty()) {
            res.put("code", 400);
            res.put("msg", "granteeId 不能为空");
            return res;
        }

        // 解析到期时间（可选，ISO-8601 格式）
        LocalDateTime expiresAt = null;
        if (body.get("expiresAt") != null) {
            try {
                expiresAt = LocalDateTime.parse(body.get("expiresAt").toString());
            } catch (Exception e) {
                res.put("code", 400);
                res.put("msg", "expiresAt 格式错误，请使用 ISO-8601 格式（如 2026-12-31T23:59:59）");
                return res;
            }
        }

        // 操作审计日志
        log.info("[DocGrant][API] 授权请求 docId={} sourceName='{}' granteeId='{}' by='{}'",
                 id, doc.getSourceName(), granteeId, operatorId);

        KbDocGrants grant = grantsService.grantAccess(
            doc.getSourceName(), doc.getId(), granteeId, granteeName,
            operatorId, expiresAt, remark
        );

        if (grant == null) {
            res.put("code", 409);
            res.put("msg", "用户 [" + granteeId + "] 已拥有该文档的有效授权，无需重复操作");
        } else {
            res.put("code", 200);
            res.put("msg", "授权成功");
            res.put("data", grant.getId());
        }
        return res;
    }

    /**
     * 撤销指定用户对文档的 GRANT 授权（软删除）。
     *
     * @param id     文档注册表主键 ID
     * @param userId 被撤销用户 ID
     */
    @DeleteMapping("/admin/docs/{id}/grants/{userId}")
    public Map<String, Object> revokeAccess(@PathVariable Long id,
                                            @PathVariable String userId,
                                            HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();

        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "文档不存在或已删除");
            return res;
        }

        String operatorId = request.getHeader("X-User-Id");
        log.info("[DocGrant][API] 撤销授权请求 docId={} sourceName='{}' granteeId='{}' by='{}'",
                 id, doc.getSourceName(), userId, operatorId);

        boolean ok = grantsService.revokeAccess(doc.getSourceName(), userId, operatorId);
        res.put("code", ok ? 200 : 404);
        res.put("msg",  ok ? "撤销成功" : "授权记录不存在或已撤销");
        return res;
    }

    /**
     * 查询文档的所有有效 GRANT 授权列表（管理端展示）。
     *
     * @param id 文档注册表主键 ID
     */
    @GetMapping("/admin/docs/{id}/grants")
    public Map<String, Object> listGrants(@PathVariable Long id, HttpServletRequest request,
                                          @RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();

        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "文档不存在或已删除");
            return res;
        }

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<KbDocGrants> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
        com.baomidou.mybatisplus.core.metadata.IPage<KbDocGrants> pageResult = grantsService.pageGrants(page, doc.getSourceName());
        res.put("code", 200);
        res.put("msg",  "success");
        res.put("data", pageResult);
        return res;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString().trim();
    }
}
