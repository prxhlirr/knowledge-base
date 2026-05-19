package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.SysGovSynonym;
import com.boyang.search.service.SysGovSynonymService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 政务同义词词典管理接口。
 *
 * 业务功能：
 *   为后台管理界面提供同义词词条的 CRUD 操作，以及手动触发缓存热重载的接口。
 *   所有写操作均在 Service 层自动刷新内存缓存，无需额外调用 reload 接口。
 *   reload 接口保留用于手动强制刷新（如直接修改数据库后）。
 *
 * 安全说明：
 *   当前使用 @CrossOrigin 允许跨域，与后台其他管理接口一致。
 *   生产环境应收拢至 Spring Security 白名单，与 /api/v1/admin/** 统一控制。
 */
@RestController
@RequestMapping("/api/v1/admin/synonyms")
@CrossOrigin(origins = "*")
public class SynonymController {

    @Autowired
    private SysGovSynonymService synonymService;

    /**
     * 获取全部同义词词条（含停用），供管理界面列表展示。
     */
    @GetMapping
    public Map<String, Object> listAll(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword) {
        Map<String, Object> resp = new HashMap<>();
        try {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysGovSynonym> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size);
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysGovSynonym> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            if (keyword != null && !keyword.trim().isEmpty()) {
                wrapper.like(SysGovSynonym::getAbbr, keyword).or().like(SysGovSynonym::getFullTerms, keyword);
            }
            wrapper.orderByAsc(SysGovSynonym::getSynonymType).orderByAsc(SysGovSynonym::getAbbr);
            
            com.baomidou.mybatisplus.core.metadata.IPage<SysGovSynonym> data = synonymService.page(page, wrapper);
            resp.put("code", 200);
            resp.put("data", data);
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "获取同义词列表失败: " + e.getMessage());
        }
        return resp;
    }

    /**
     * 新增同义词词条。
     * Service 层会在写入数据库后自动刷新内存缓存，确保下次检索即生效。
     */
    @OperationLog(module = "同义词管理", operation = "新增同义词")
    @PostMapping
    public Map<String, Object> add(@RequestBody SysGovSynonym synonym) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (synonym.getAbbr() == null || synonym.getAbbr().trim().isEmpty()) {
                resp.put("code", 400);
                resp.put("msg", "缩略词（abbr）不能为空");
                return resp;
            }
            if (synonym.getFullTerms() == null || synonym.getFullTerms().trim().isEmpty()) {
                resp.put("code", 400);
                resp.put("msg", "完整词列表（fullTerms）不能为空");
                return resp;
            }
            boolean ok = synonymService.addSynonym(synonym);
            resp.put("code", ok ? 200 : 500);
            resp.put("msg", ok ? "添加成功，缓存已热更新" : "添加失败");
        } catch (Exception e) {
            // 唯一键冲突（abbr 重复）返回友好信息
            String msg = e.getMessage() != null && e.getMessage().contains("uq_gov_synonyms_abbr")
                ? "缩略词 [" + synonym.getAbbr() + "] 已存在，请修改后重试"
                : "添加失败: " + e.getMessage();
            resp.put("code", 400);
            resp.put("msg", msg);
        }
        return resp;
    }

    /**
     * 更新同义词词条（按 ID 全量更新）。
     */
    @OperationLog(module = "同义词管理", operation = "更新同义词")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody SysGovSynonym synonym) {
        Map<String, Object> resp = new HashMap<>();
        try {
            synonym.setId(id);
            boolean ok = synonymService.updateSynonym(synonym);
            resp.put("code", ok ? 200 : 404);
            resp.put("msg", ok ? "更新成功，缓存已热更新" : "词条不存在");
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "更新失败: " + e.getMessage());
        }
        return resp;
    }

    /**
     * 删除同义词词条（物理删除）。
     */
    @OperationLog(module = "同义词管理", operation = "删除同义词")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> resp = new HashMap<>();
        try {
            boolean ok = synonymService.deleteSynonym(id);
            resp.put("code", ok ? 200 : 404);
            resp.put("msg", ok ? "删除成功，缓存已热更新" : "词条不存在");
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "删除失败: " + e.getMessage());
        }
        return resp;
    }

    /**
     * 手动触发缓存热重载（用于直接修改数据库后的手动刷新）。
     */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        Map<String, Object> resp = new HashMap<>();
        try {
            synonymService.reloadCache();
            resp.put("code", 200);
            resp.put("msg", "缓存已热重载，当前共 " + synonymService.getSynonymMap().size() + " 个触发键");
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "热重载失败: " + e.getMessage());
        }
        return resp;
    }

    /**
     * 测试接口：验证某个查询词会被展开为哪些词条。
     * 供管理员在 UI 中实时预览展开效果，不实际执行检索。
     */
    @GetMapping("/expand")
    public Map<String, Object> expand(@RequestParam String query) {
        Map<String, Object> resp = new HashMap<>();
        try {
            Map<String, List<String>> synonymMap = synonymService.getSynonymMap();
            // 简单分词：按空格，每个词独立查找
            String[] tokens = query.split("\\s+");
            Map<String, List<String>> expansions = new HashMap<>();
            for (String token : tokens) {
                if (synonymMap.containsKey(token)) {
                    expansions.put(token, synonymMap.get(token));
                }
            }
            resp.put("code", 200);
            resp.put("originalQuery", query);
            resp.put("expansions", expansions);
            resp.put("hasExpansion", !expansions.isEmpty());
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg", "展开测试失败: " + e.getMessage());
        }
        return resp;
    }
}
