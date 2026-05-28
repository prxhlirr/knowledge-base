package com.boyang.search.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.KbSensitivePolicyHitLog;
import com.boyang.search.mapper.KbSensitivePolicyHitLogMapper;
import com.boyang.search.security.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 敏感策略命中审计查询接口。
 *
 * <p>该接口面向安全管理员排查策略效果，支持按策略、阶段、用户和文档过滤。
 * 返回数据不包含命中文本原文，只包含命中次数、字段、动作、traceId 等审计上下文。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/sensitive-policy-hits")
@RequiredArgsConstructor
public class SensitivePolicyHitLogController {

    private final KbSensitivePolicyHitLogMapper hitLogMapper;
    private final PermissionGuard permissionGuard;

    /**
     * 查询敏感策略最近命中记录。
     */
    @OperationLog(module = "权限管理", operation = "查询敏感策略命中日志", recordResponse = false)
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long policyId,
                                    @RequestParam(required = false) String stage,
                                    @RequestParam(required = false) String userId,
                                    @RequestParam(required = false) String sourceName,
                                    @RequestParam(defaultValue = "100") Integer limit,
                                    HttpServletRequest request) {
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            return unauthorized();
        }

        QueryWrapper<KbSensitivePolicyHitLog> query = new QueryWrapper<>();
        if (policyId != null) {
            query.eq("policy_id", policyId);
        }
        if (!isBlank(stage)) {
            query.eq("stage", stage.trim().toUpperCase());
        }
        if (!isBlank(userId)) {
            query.eq("user_id", userId.trim());
        }
        if (!isBlank(sourceName)) {
            query.eq("source_name", sourceName.trim());
        }
        query.orderByDesc("created_at");
        query.last("LIMIT " + Math.max(1, Math.min(limit == null ? 100 : limit, 500)));

        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", hitLogMapper.selectList(query));
        return res;
    }

    private Map<String, Object> unauthorized() {
        Map<String, Object> res = new HashMap<>();
        res.put("code", 401);
        res.put("msg", "unauthorized");
        return res;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
