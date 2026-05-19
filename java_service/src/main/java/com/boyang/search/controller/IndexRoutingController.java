package com.boyang.search.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.SysIndexRouting;
import com.boyang.search.mapper.SysIndexRoutingMapper;
import com.boyang.search.service.DocIndexRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档类型路由管理 API
 *
 * 业务功能：提供文档类型（路由规则）的 CRUD 管理能力，同时暴露内部跨端通讯接口供 Python AI 服务同步使用。
 * 关键设计：所有写操作通过 DocIndexRoutingService 统一入口，保证 DB + 内存缓存 + ES 索引三态一致。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IndexRoutingController {

    private final DocIndexRoutingService docIndexRoutingService;
    private final SysIndexRoutingMapper  sysIndexRoutingMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // 【内部接口】供 Python AI 服务（es_migration / task_worker）同步路由配置
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：返回当前服务器在用的内存活跃路由 HashMap，用于 Python 端同步索引规则。
     * 路径：GET /api/v1/internal/routing/active
     */
    @GetMapping("/api/v1/internal/routing/active")
    public Map<String, Object> getActiveRoutings(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        Map<String, Object> res = new HashMap<>();
        String expectedToken = System.getenv("KB_INTERNAL_TOKEN");
        if (expectedToken == null) expectedToken = "kb-dev-token-change-me-in-prod";
        if (!expectedToken.equals(token)) {
            res.put("code", 403);
            res.put("msg", "Forbidden: invalid internal token");
            return res;
        }
        try {
            res.put("code", 200);
            res.put("data", docIndexRoutingService.exportActiveRoutings());
            res.put("msg", "success");
        } catch (Exception e) {
            log.error("[IndexRoutingController.getActiveRoutings] 导出路由缓存失败", e);
            res.put("code", 500);
            res.put("msg", "Internal Server Error");
        }
        return res;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 【管理接口】文档类型路由的增删查刷新，供前端管理后台调用
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：获取路由规则列表，用于前端文档类型下拉动态加载和管理后台展示。
     * 路径：GET /api/v1/admin/routing/list
     * 参数：activeOnly=true 时仅返回 is_active=1 的规则（前端下拉推荐使用）
     * 返回：[{ tagName, tagCode, targetIndex, description, isActive }]
     */
    @GetMapping("/api/v1/admin/routing/list")
    public Map<String, Object> listRoutings(
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {

        Map<String, Object> res = new HashMap<>();
        try {
            LambdaQueryWrapper<SysIndexRouting> wrapper = new LambdaQueryWrapper<>();
            if (activeOnly) {
                // 前端下拉场景：只返回启用规则（含兜底的 _FALLBACK_ 规则）
                wrapper.eq(SysIndexRouting::getIsActive, 1);
            }
            wrapper.orderByDesc(SysIndexRouting::getUpdateTime);
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysIndexRouting> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
            com.baomidou.mybatisplus.core.metadata.IPage<SysIndexRouting> pageResult = sysIndexRoutingMapper.selectPage(page, wrapper);

            // 只返回前端需要的最小字段集，过滤掉内部兜底标识 _FALLBACK_
            List<Map<String, Object>> resultList = pageResult.getRecords().stream()
                .filter(r -> !DocIndexRoutingService.FALLBACK_KEY.equals(r.getTagName()))
                .map(r -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("tagName",     r.getTagName());
                    item.put("tagCode",     r.getTagCode());
                    item.put("targetIndex", r.getTargetIndex());
                    item.put("description", r.getDescription() != null ? r.getDescription() : r.getTagName());
                    item.put("isActive",    r.getIsActive());
                    return item;
                }).collect(Collectors.toList());

            Map<String, Object> finalPage = new HashMap<>();
            finalPage.put("records", resultList);
            finalPage.put("total", pageResult.getTotal());
            finalPage.put("size", pageResult.getSize());
            finalPage.put("current", pageResult.getCurrent());

            res.put("code", 200);
            res.put("data", finalPage);
            res.put("msg", "success");
        } catch (Exception e) {
            log.error("[IndexRoutingController.listRoutings] 查询路由列表失败", e);
            res.put("code", 500);
            res.put("msg", "查询失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 业务功能：注册新文档类型路由规则（幂等，tagName 已存在则更新）。
     * 路径：POST /api/v1/admin/routing
     * 请求体：{ "tagName": "合同文书", "indexName": "kb_document_contract", "description": "合同类文档" }
     * 触发链：写 DB → 刷内存缓存 → 异步通知 Python 创建 ES 索引
     */
    @PostMapping("/api/v1/admin/routing")
    public Map<String, Object> registerRouting(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {

        Map<String, Object> res = new HashMap<>();
        try {
            String tagName     = body.get("tagName");
            String indexName   = body.get("indexName");
            String description = body.getOrDefault("description", "");

            // 索引名安全校验：只允许 kb_document_ 前缀，防止任意索引名注入
            if (indexName != null && !indexName.startsWith("kb_document_")) {
                res.put("code", 400);
                res.put("msg", "indexName 必须以 kb_document_ 开头（如 kb_document_contract）");
                return res;
            }

            docIndexRoutingService.register(tagName, indexName, description, userId);
            res.put("code", 200);
            res.put("msg", "文档类型注册成功，路由已热更新");
            Map<String, String> data = new HashMap<>();
            data.put("tagName",   tagName);
            data.put("indexName", indexName);
            res.put("data", data);
        } catch (IllegalArgumentException e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
        } catch (Exception e) {
            log.error("[IndexRoutingController.registerRouting] 注册路由失败", e);
            res.put("code", 500);
            res.put("msg", "注册失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 业务功能：停用指定文档类型路由规则（软删除，is_active=0，不影响历史 ES 数据）。
     * 路径：DELETE /api/v1/admin/routing/{tagName}
     */
    @DeleteMapping("/api/v1/admin/routing/{tagName}")
    public Map<String, Object> deactivateRouting(
            @PathVariable String tagName,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {

        Map<String, Object> res = new HashMap<>();
        try {
            docIndexRoutingService.deactivate(tagName, userId);
            res.put("code", 200);
            res.put("msg", "路由规则已停用: " + tagName);
        } catch (IllegalArgumentException e) {
            res.put("code", 400);
            res.put("msg", e.getMessage());
        } catch (Exception e) {
            log.error("[IndexRoutingController.deactivateRouting] 停用路由失败", e);
            res.put("code", 500);
            res.put("msg", "停用失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 业务功能：手动触发路由缓存全量刷新（从 DB 重新加载所有活跃规则）。
     * 适用场景：直接修改数据库后（如数据迁移）强制同步内存缓存，无需重启服务。
     * 路径：POST /api/v1/admin/routing/refresh
     */
    @PostMapping("/api/v1/admin/routing/refresh")
    public Map<String, Object> refreshRoutingCache(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        Map<String, Object> res = new HashMap<>();
        // 刷新接口需要内部 Token 鉴权，防止外部随意触发
        String expectedToken = System.getenv("KB_INTERNAL_TOKEN");
        if (expectedToken == null) expectedToken = "kb-dev-token-change-me-in-prod";
        if (!expectedToken.equals(token)) {
            res.put("code", 403);
            res.put("msg", "Forbidden: 需要 X-Internal-Token 鉴权");
            return res;
        }
        try {
            docIndexRoutingService.refreshCache();
            res.put("code", 200);
            res.put("msg", "路由缓存已全量刷新，当前规则数: " + docIndexRoutingService.exportActiveRoutings().size());
        } catch (Exception e) {
            log.error("[IndexRoutingController.refreshRoutingCache] 刷新路由缓存失败", e);
            res.put("code", 500);
            res.put("msg", "刷新失败: " + e.getMessage());
        }
        return res;
    }
}
