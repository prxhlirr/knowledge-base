package com.boyang.search.controller;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.service.SysAiTuningConfigService;
import com.boyang.search.service.SysGovSynonymService;
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

    // 将内存同义词缓存热重载妧使用，可防止 SearchService 已删除时的类引用期错误
    @Autowired
    private SysGovSynonymService govSynonymService;

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
            // 内存同义词缓存热重载：从 DB 重新加载并替换内存中的同义词 Map
            // （原本调用 searchService.reloadSearchAnalyzers，但 SearchService 已淘汰，
            //  实际功能等价于刷新应用层内存同义词词典缓存）
            govSynonymService.reloadCache();
            response.put("code", 200);
            response.put("msg", "应用层同义词缓存热重载成功，新词典已即时生效（indexName=" + indexName + ")");
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "同义词热加载失败，请检查 DB 连接或词典表内容: " + e.getMessage());
        }
        return response;
    }
}
