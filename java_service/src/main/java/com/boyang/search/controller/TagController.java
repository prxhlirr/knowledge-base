package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.boyang.search.entity.SysSearchTag;
import com.boyang.search.service.SysSearchTagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tags")
@CrossOrigin(origins = "*")
public class TagController {

    @Autowired
    private SysSearchTagService sysSearchTagService;

    /**
     * 业务功能：前台保存/更新文档打标信息
     * 关键方法：sysSearchTagService.saveOrUpdate()
     * 
     * 流程：
     * 1. 接收前台传来的 doc_id，tags 和 keywords
     * 2. 查询该 doc_id 是否已存在记录，存在则更新，不存在则插入
     * 3. 同步状态自动重置为 0 (未同步)
     */
    @OperationLog(module = "标签管理", operation = "保存文档标签")
    @PostMapping("/save")
    public Map<String, Object> saveTag(@RequestBody SysSearchTag tagData) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (tagData.getDocId() == null || tagData.getDocId().trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "参数不合法，doc_id 不能为空");
                return response;
            }

            QueryWrapper<SysSearchTag> wrapper = new QueryWrapper<>();
            wrapper.eq("doc_id", tagData.getDocId());
            SysSearchTag existTag = sysSearchTagService.getOne(wrapper);

            if (existTag != null) {
                existTag.setTags(tagData.getTags());
                existTag.setKeywords(tagData.getKeywords());
                existTag.setSyncStatus(0); // 内容有变，需重新同步
                existTag.setUpdateTime(LocalDateTime.now());
                sysSearchTagService.updateById(existTag);
            } else {
                tagData.setSyncStatus(0);
                tagData.setCreateTime(LocalDateTime.now());
                tagData.setUpdateTime(LocalDateTime.now());
                sysSearchTagService.save(tagData);
            }

            response.put("code", 200);
            response.put("msg", "保存打标成功");
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务内部异常: " + e.getMessage());
        }
        return response;
    }

    /**
     * 业务功能：根据 docId 获取现有的打标信息
     */
    @GetMapping("/detail")
    public Map<String, Object> getTagByDocId(@RequestParam String docId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (docId == null || docId.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "参数不合法，doc_id 不能为空");
                return response;
            }
            QueryWrapper<SysSearchTag> wrapper = new QueryWrapper<>();
            wrapper.eq("doc_id", docId);
            SysSearchTag tag = sysSearchTagService.getOne(wrapper);
            
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", tag);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务内部异常: " + e.getMessage());
        }
        return response;
    }
}
