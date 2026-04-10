package com.boyang.search.controller;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.service.SearchService;
import com.boyang.search.service.SysAiTuningConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tuning")
@CrossOrigin(origins = "*") // 允许前端管理界面跨域
public class TuningConfigController {

    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    @Autowired
    private SearchService searchService;

    /**
     * 获取当前系统检索模型的全部热调优参数
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            SysAiTuningConfig config = tuningConfigService.getGlobalConfig();
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", config);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "获取 AI 调优配置失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 更新模型调优参数并主动推送 Python 服务热重载
     */
    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody SysAiTuningConfig config) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = tuningConfigService.updateConfigAndNotifyAi(config);
            if (success) {
                response.put("code", 200);
                response.put("msg", "参数已应用并且已下发至AI算力节点");
            } else {
                response.put("code", 400);
                response.put("msg", "更新配置失败");
            }
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "应用系统调优配置时发生错误: " + e.getMessage());
        }
        return response;
    }

    /**
     * 手动触发 ES 同义词热加载
     * 场景：修改 ES 同义词词典文件后，无需重启主服务，调用此接口即可令新同义词在检索中生效。
     */
    @PostMapping("/reload-synonyms")
    public Map<String, Object> reloadSynonyms(@RequestParam(defaultValue = "kb_document_v1") String indexName) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = searchService.reloadSearchAnalyzers(indexName);
            if (success) {
                response.put("code", 200);
                response.put("msg", "索引 [" + indexName + "] 同义词热加载成功");
            } else {
                response.put("code", 500);
                response.put("msg", "同义词热加载失败，请检查 ES 日志或词典文件路径");
            }
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "处理热加载请求时发生内部错误: " + e.getMessage());
        }
        return response;
    }
}
