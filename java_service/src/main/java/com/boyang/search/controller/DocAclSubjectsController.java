package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.KbDocAclSubject;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.KbDocAclSubjectService;
import com.boyang.search.service.KbDocRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档 ACL 主体管理接口。
 *
 * <p>该接口用于在文档入库后动态追加或撤销用户、角色、部门级权限。
 * 权威数据写入 MySQL，ES 中 acl_tokens 仅作为召回阶段投影，并由重试任务保证最终同步。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocAclSubjectsController {

    private final KbDocAclSubjectService aclSubjectService;
    private final KbDocRegistryService registryService;
    private final PermissionGuard permissionGuard;

    /**
     * 新增文档 ACL 规则。
     *
     * <p>支持 USER、ROLE、DEPT、ALL、AUTHENTICATED 等主体类型；支持 ALLOW/DENY。
     * ALLOW 会尝试投影到 ES acl_tokens，DENY 仅作为后置权限强阻断规则。</p>
     */
    @OperationLog(module = "权限管理", operation = "新增文档ACL规则", recordResponse = false)
    @PostMapping("/admin/docs/{id}/acl-subjects")
    public Map<String, Object> grantOrDeny(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }

        Map<String, Object> res = new HashMap<>();
        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "document not found or deleted");
            return res;
        }

        String subjectType = str(body, "subjectType");
        String subjectValue = str(body, "subjectValue");
        String scope = defaultText(str(body, "scope"), "VIEW");
        String effect = defaultText(str(body, "effect"), "ALLOW");
        String operatorId = request.getHeader("X-User-Id");
        if (isBlank(subjectType) || isBlank(subjectValue)) {
            res.put("code", 400);
            res.put("msg", "subjectType and subjectValue are required");
            return res;
        }

        try {
            LocalDateTime expiresAt = parseExpiresAt(body.get("expiresAt"));
            KbDocAclSubject created = aclSubjectService.grantRuntimeSubject(
                    doc, subjectType, subjectValue, scope, effect, operatorId, expiresAt, true);
            log.info("[DocACL][API] runtime ACL created docId={} sourceName={} type={} value={} scope={} effect={} by={}",
                    id, doc.getSourceName(), subjectType, subjectValue, scope, effect, operatorId);
            res.put("code", 200);
            res.put("msg", "success");
            res.put("data", created.getId());
            return res;
        } catch (IllegalArgumentException e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
            return res;
        }
    }

    /**
     * 撤销文档 ACL 规则。
     *
     * <p>撤销 ALLOW 规则时会同步移除 ES acl_tokens 投影；若 ES 更新失败，会进入投影重试任务。</p>
     */
    @OperationLog(module = "权限管理", operation = "撤销文档ACL规则", recordResponse = false)
    @DeleteMapping("/admin/docs/{id}/acl-subjects")
    public Map<String, Object> revoke(@PathVariable Long id,
                                      @RequestParam String subjectType,
                                      @RequestParam String subjectValue,
                                      @RequestParam(defaultValue = "VIEW") String scope,
                                      @RequestParam(defaultValue = "ALLOW") String effect,
                                      HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }

        Map<String, Object> res = new HashMap<>();
        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "document not found or deleted");
            return res;
        }

        try {
            aclSubjectService.revokeRuntimeSubject(
                    doc.getSourceName(), doc.getTargetIndex(), subjectType, subjectValue, scope, effect, true);
            log.info("[DocACL][API] runtime ACL revoked docId={} sourceName={} type={} value={} scope={} effect={} by={}",
                    id, doc.getSourceName(), subjectType, subjectValue, scope, effect, request.getHeader("X-User-Id"));
            res.put("code", 200);
            res.put("msg", "success");
            return res;
        } catch (IllegalArgumentException e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
            return res;
        }
    }

    /**
     * 查询指定文档当前生效的 ACL 规则。
     */
    @OperationLog(module = "权限管理", operation = "查询文档ACL规则", recordResponse = false)
    @GetMapping("/admin/docs/{id}/acl-subjects")
    public Map<String, Object> list(@PathVariable Long id, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }

        Map<String, Object> res = new HashMap<>();
        KbDocRegistry doc = registryService.getById(id);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            res.put("code", 404);
            res.put("msg", "document not found or deleted");
            return res;
        }
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", aclSubjectService.listActive(doc.getSourceName()));
        return res;
    }

    private static Map<String, Object> unauthorized() {
        Map<String, Object> res = new HashMap<>();
        res.put("code", 401);
        res.put("msg", "unauthorized");
        return res;
    }

    private LocalDateTime parseExpiresAt(Object raw) {
        if (raw == null || raw.toString().trim().isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(raw.toString().trim());
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString().trim();
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
