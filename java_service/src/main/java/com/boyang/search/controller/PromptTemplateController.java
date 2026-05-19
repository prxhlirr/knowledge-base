package com.boyang.search.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.boyang.search.entity.SysPromptTemplate;
import com.boyang.search.mapper.SysPromptTemplateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prompt 模板管理接口
 *
 * 业务功能：提供 Prompt 模板的完整生命周期管理，支持两类调用方：
 *   1. 管理端（运营/算法工程师）：通过 /api/v1/admin/prompts 系列接口查询和修改 Prompt 内容
 *   2. AI 服务（读：内部接口）：通过 /api/v1/internal/prompts/active 拉取全量激活 Prompt，
 *      60s TTL 内存缓存，不阻塞推理链路
 *
 * 关键设计：
 *   - 修改 Prompt 不重启服务，AI 服务 PromptRegistry 在下次 TTL 到期时自动刷新
 *   - is_active=0 时 AI 服务退回内置默认值，修改不影响在线服务可用性
 *   - 每次更新 content 时自动记录 updated_by 和 version（version+1）
 */
@RestController
@CrossOrigin(origins = "*")
public class PromptTemplateController {

    @Autowired
    private SysPromptTemplateMapper promptMapper;

    // ═══════════════════════════════════════════════════════════════
    // 管理端接口（/api/v1/admin/prompts）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询全部 Prompt 列表，支持按 scene 过滤
     *
     * @param scene 场景过滤（可选，如 HYDE_GENERAL / REWRITE / RERANK）
     * @return 按 scene + role 排序的 Prompt 列表
     */
    @GetMapping("/api/v1/admin/prompts")
    public Map<String, Object> listPrompts(
            @RequestParam(required = false) String scene,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {

        Map<String, Object> res = new HashMap<>();
        try {
            LambdaQueryWrapper<SysPromptTemplate> qw = new LambdaQueryWrapper<SysPromptTemplate>()
                    .eq(scene != null && !scene.isEmpty(), SysPromptTemplate::getScene, scene)
                    .orderByAsc(SysPromptTemplate::getScene)
                    .orderByAsc(SysPromptTemplate::getRole);  // system 先于 user 排列

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysPromptTemplate> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
            com.baomidou.mybatisplus.core.metadata.IPage<SysPromptTemplate> pageResult = promptMapper.selectPage(page, qw);
            
            res.put("code", 200);
            res.put("msg", "success");
            res.put("data", pageResult);
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "查询 Prompt 列表失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 按 prompt_key 精确查询单条 Prompt
     *
     * @param promptKey Prompt 唯一标识，如 HYDE_GENERAL_SYSTEM
     * @return 对应 Prompt 详情
     */
    @GetMapping("/api/v1/admin/prompts/{promptKey}")
    public Map<String, Object> getPrompt(@PathVariable String promptKey) {
        Map<String, Object> res = new HashMap<>();
        try {
            SysPromptTemplate pt = promptMapper.selectOne(
                    new LambdaQueryWrapper<SysPromptTemplate>()
                            .eq(SysPromptTemplate::getPromptKey, promptKey));

            if (pt == null) {
                res.put("code", 404);
                res.put("msg", "Prompt 不存在: " + promptKey);
            } else {
                res.put("code", 200);
                res.put("msg", "success");
                res.put("data", pt);
            }
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "查询 Prompt 失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 更新指定 prompt_key 的 Prompt 内容或启用状态
     *
     * 关键流程：
     *   1. 只允许修改 content / description / is_active / updated_by 字段
     *   2. 每次更新自动 version+1，记录修改时间
     *   3. AI 服务 PromptRegistry 在 60s TTL 到期后自动拉取新版本
     *
     * @param promptKey Prompt 唯一标识
     * @param body      包含 content / description / isActive / updatedBy 的 JSON 体
     */
    @PutMapping("/api/v1/admin/prompts/{promptKey}")
    public Map<String, Object> updatePrompt(
            @PathVariable String promptKey,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> res = new HashMap<>();
        try {
            // 参数提取（只允许修改业务内容，不允许修改 key/scene/role 等结构字段）
            String content     = (String) body.get("content");
            String description = (String) body.get("description");
            String updatedBy   = (String) body.getOrDefault("updatedBy", "admin");
            Object isActiveObj = body.get("isActive");

            // 检查目标记录是否存在
            SysPromptTemplate existing = promptMapper.selectOne(
                    new LambdaQueryWrapper<SysPromptTemplate>()
                            .eq(SysPromptTemplate::getPromptKey, promptKey));
            if (existing == null) {
                res.put("code", 404);
                res.put("msg", "Prompt 不存在: " + promptKey);
                return res;
            }

            // 构建更新条件（只更新有值的字段，防止误清空）
            LambdaUpdateWrapper<SysPromptTemplate> uw = new LambdaUpdateWrapper<SysPromptTemplate>()
                    .eq(SysPromptTemplate::getPromptKey, promptKey)
                    // 每次修改 version+1（通过数据库表达式实现原子加）
                    .setSql("version = version + 1")
                    .set(SysPromptTemplate::getUpdatedBy, updatedBy)
                    .set(SysPromptTemplate::getUpdatedAt, LocalDateTime.now());

            if (content != null)     uw.set(SysPromptTemplate::getContent,     content);
            if (description != null) uw.set(SysPromptTemplate::getDescription, description);
            if (isActiveObj != null) {
                // 兼容前端传 boolean 或 int 两种形式
                short isActiveVal = (isActiveObj instanceof Boolean)
                        ? ((Boolean) isActiveObj ? (short)1 : (short)0)
                        : Short.parseShort(isActiveObj.toString());
                uw.set(SysPromptTemplate::getIsActive, isActiveVal);
            }

            int affected = promptMapper.update(null, uw);
            if (affected > 0) {
                res.put("code", 200);
                res.put("msg", "Prompt 已更新，AI 服务将在 60 秒内自动生效");
                res.put("promptKey", promptKey);
            } else {
                res.put("code", 400);
                res.put("msg", "更新失败，未匹配到目标 Prompt");
            }
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "更新 Prompt 失败: " + e.getMessage());
        }
        return res;
    }

    // ═══════════════════════════════════════════════════════════════
    // AI 服务内部接口（/api/v1/internal/prompts）
    // 不对外公开，通过 X-Internal-Token Header 验证
    // ═══════════════════════════════════════════════════════════════

    /**
     * 返回全量激活 Prompt（is_active=1），供 AI 服务 PromptRegistry 拉取缓存
     *
     * 关键流程：
     *   - AI 服务以 60s TTL 内存缓存此结果，节省 DB 压力
     *   - 返回格式：{prompt_key: content} 的扁平字典，便于 AI 侧直接 dict.get(key)
     *   - 只返回 is_active=1 的记录，停用的 Prompt 不下发（AI 侧对缺失 key 用内置默认值兜底）
     *
     * @return {code, data: {HYDE_GENERAL_SYSTEM: "...", HYDE_GENERAL_USER: "...", ...}}
     */
    @GetMapping("/api/v1/internal/prompts/active")
    public Map<String, Object> getActivePrompts(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        Map<String, Object> res = new HashMap<>();
        // 内部 Token 校验（与 next-version 接口保持一致）
        String expectedToken = System.getenv("KB_INTERNAL_TOKEN");
        if (expectedToken == null) expectedToken = "kb-dev-token-change-me-in-prod";
        if (!expectedToken.equals(token)) {
            res.put("code", 403);
            res.put("msg", "Forbidden: invalid internal token");
            return res;
        }

        try {
            // 查询全量 is_active=1 的 Prompt，以扁平 Map 返回
            List<SysPromptTemplate> activeList = promptMapper.selectList(
                    new LambdaQueryWrapper<SysPromptTemplate>()
                            .eq(SysPromptTemplate::getIsActive, (short)1));

            // 转换为 {prompt_key: content} 扁平字典，AI 服务直接 dict[key] 使用
            Map<String, String> promptMap = activeList.stream()
                    .collect(Collectors.toMap(
                            SysPromptTemplate::getPromptKey,
                            SysPromptTemplate::getContent));

            res.put("code", 200);
            res.put("msg", "success");
            res.put("data", promptMap);
            res.put("count", promptMap.size());
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "获取激活 Prompt 失败: " + e.getMessage());
        }
        return res;
    }

    /**
     * 查询所有已注册的场景分组（供前端管理界面按 scene 分组展示时枚举选项）
     *
     * @return 不重复的 scene 列表，如 [HYDE_GENERAL, REWRITE, RERANK, ...]
     */
    @GetMapping("/api/v1/admin/prompts/scenes")
    public Map<String, Object> listScenes() {
        Map<String, Object> res = new HashMap<>();
        try {
            List<String> scenes = promptMapper.selectList(
                    new LambdaQueryWrapper<SysPromptTemplate>()
                            .select(SysPromptTemplate::getScene)
                            .groupBy(SysPromptTemplate::getScene)
                            .orderByAsc(SysPromptTemplate::getScene))
                    .stream()
                    .map(SysPromptTemplate::getScene)
                    .distinct()
                    .collect(Collectors.toList());

            res.put("code", 200);
            res.put("data", scenes);
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", e.getMessage());
        }
        return res;
    }
}
