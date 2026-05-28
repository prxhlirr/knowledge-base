package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.KbSensitivePolicy;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.SensitivePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/sensitive-policies")
@RequiredArgsConstructor
public class SensitivePolicyController {

    private final SensitivePolicyService sensitivePolicyService;
    private final PermissionGuard permissionGuard;

    /**
     * 创建敏感内容策略。
     *
     * <p>策略创建后会立即递增权限策略版本，使搜索缓存自动失效；后续 SEARCH、PREVIEW、QA、DOWNLOAD
     * 等出口都会按 appliesTo 进行实时过滤。</p>
     */
    @OperationLog(module = "权限管理", operation = "创建敏感内容策略", recordResponse = false)
    @PostMapping
    public Map<String, Object> create(@RequestBody KbSensitivePolicy policy, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        try {
            KbSensitivePolicy created = sensitivePolicyService.createPolicy(policy, request.getHeader("X-User-Id"));
            res.put("code", 200);
            res.put("msg", "success");
            res.put("data", created.getId());
        } catch (IllegalArgumentException e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
        }
        return res;
    }

    /**
     * 禁用敏感内容策略。
     *
     * <p>采用软禁用而不是物理删除，便于审计追溯；禁用后同步递增策略版本。</p>
     */
    @OperationLog(module = "权限管理", operation = "禁用敏感内容策略", recordResponse = false)
    @DeleteMapping("/{id}")
    public Map<String, Object> disable(@PathVariable Long id, HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        boolean ok = sensitivePolicyService.disablePolicy(id);
        res.put("code", ok ? 200 : 404);
        res.put("msg", ok ? "success" : "not found");
        return res;
    }

    /**
     * 更新敏感内容策略。
     *
     * <p>用于生产环境调整策略值、动作、适用阶段、适用主体和优先级。
     * 更新成功后递增策略版本，保证旧搜索缓存不会继续被新请求命中。</p>
     */
    @OperationLog(module = "权限管理", operation = "更新敏感内容策略", recordResponse = false)
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody KbSensitivePolicy policy,
                                      HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        KbSensitivePolicy updated = sensitivePolicyService.updatePolicy(id, policy, request.getHeader("X-User-Id"));
        if (updated == null) {
            res.put("code", 404);
            res.put("msg", "not found");
            return res;
        }
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", updated.getId());
        return res;
    }

    /**
     * 查询当前生效的敏感内容策略。
     */
    @OperationLog(module = "权限管理", operation = "查询敏感内容策略", recordResponse = false)
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "SEARCH") String appliesTo,
                                    HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }
        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", sensitivePolicyService.listActive(appliesTo));
        return res;
    }

    private Map<String, Object> unauthorized() {
        Map<String, Object> res = new HashMap<>();
        res.put("code", 401);
        res.put("msg", "unauthorized");
        return res;
    }
}
