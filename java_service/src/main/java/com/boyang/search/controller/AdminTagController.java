package com.boyang.search.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.SysSearchTag;
import com.boyang.search.service.SysSearchTagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tags")
@CrossOrigin(origins = "*")
public class AdminTagController {

    @Autowired
    private SysSearchTagService sysSearchTagService;

    /**
     * 业务功能：后台分页获取打标列表
     */
    @GetMapping("/list")
    public Map<String, Object> listTags(@RequestParam(defaultValue = "1") Integer current,
                                        @RequestParam(defaultValue = "10") Integer size) {
        Map<String, Object> response = new HashMap<>();
        try {
            Page<SysSearchTag> page = new Page<>(current, size);
            QueryWrapper<SysSearchTag> wrapper = new QueryWrapper<>();
            wrapper.orderByDesc("update_time");
            Page<SysSearchTag> result = sysSearchTagService.page(page, wrapper);

            response.put("code", 200);
            response.put("data", result);
            response.put("msg", "success");
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "查询异常: " + e.getMessage());
        }
        return response;
    }

    /**
     * 业务功能：手动触发关键词和向量重同步
     */
    @PostMapping("/{id}/sync")
    public Map<String, Object> syncTags(@PathVariable("id") Long tagId) {
        Map<String, Object> response = new HashMap<>();
        try {
            sysSearchTagService.syncTagToEsAndAi(tagId);
            response.put("code", 200);
            response.put("msg", "数据重同步完毕");
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "同步失败: " + e.getMessage());
        }
        return response;
    }
}
