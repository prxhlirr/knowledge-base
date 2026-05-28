package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.KbIndexAclSubject;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.IndexAclSubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 索引级 ACL 管理接口。
 *
 * <p>该接口用于管理“用户/角色/部门是否可以读取某个物理索引”的精细化权限。
 * 租户策略仍是第一道边界，索引 ACL 只在租户允许的索引范围内继续收敛访问范围。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/index-acl-subjects")
@RequiredArgsConstructor
public class IndexAclSubjectsController {

    private final IndexAclSubjectService indexAclSubjectService;
    private final PermissionGuard permissionGuard;

    /**
     * 新增索引 ACL 规则。
     *
     * <p>请求体字段：indexName、subjectType、subjectValue、scope、effect、expiresAt。
     * scope 默认 READ，effect 默认 ALLOW；DENY 优先级高于 ALLOW。</p>
     */
    @OperationLog(module = "权限管理", operation = "新增索引ACL规则", recordResponse = false)
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        try {
            KbIndexAclSubject row = indexAclSubjectService.grant(
                    str(body, "indexName"),
                    str(body, "subjectType"),
                    str(body, "subjectValue"),
                    defaultText(str(body, "scope"), "READ"),
                    defaultText(str(body, "effect"), "ALLOW"),
                    request.getHeader("X-User-Id"),
                    parseExpiresAt(body.get("expiresAt")));
            res.put("code", 200);
            res.put("msg", "success");
            res.put("data", row.getId());
        } catch (Exception e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
        }
        return res;
    }

    /**
     * 撤销索引 ACL 规则。
     */
    @OperationLog(module = "权限管理", operation = "撤销索引ACL规则", recordResponse = false)
    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable Long id, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        indexAclSubjectService.revoke(id);
        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        return res;
    }

    /**
     * 查询指定索引当前生效的 ACL 规则。
     */
    @OperationLog(module = "权限管理", operation = "查询索引ACL规则", recordResponse = false)
    @GetMapping
    public Map<String, Object> list(@RequestParam String indexName, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", indexAclSubjectService.listActive(indexName));
        return res;
    }

    private Map<String, Object> unauthorized() {
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

    private String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : value.toString().trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
