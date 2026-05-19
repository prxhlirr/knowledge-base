package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.SysFileParseLog;
import com.boyang.search.service.ISysFileParseLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FileParseLogController {

    @Autowired
    private ISysFileParseLogService sysFileParseLogService;

    @Autowired
    private com.boyang.search.service.StorageService storageService;

    /**
     * 【内部管理接口】分页查询入库日志
     */
    @GetMapping("/admin/logs/page")
    public Map<String, Object> pageLogs(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        
        LambdaQueryWrapper<SysFileParseLog> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(SysFileParseLog::getStatus, status);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(SysFileParseLog::getFilePath, keyword)
                             .or().like(SysFileParseLog::getUploader, keyword));
        }
        wrapper.orderByDesc(SysFileParseLog::getUploadTime);
        
        Page<SysFileParseLog> page = sysFileParseLogService.page(new Page<>(current, size), wrapper);
        
        Map<String, Object> res = new HashMap<>();
        res.put("code", 200);
        res.put("data", page);
        return res;
    }

    /**
     * 【后端回调接口】接收 Python Node 透传的各项指标
     */
    @PostMapping("/internal/logs/report")
    public Map<String, Object> reportLog(@RequestBody Map<String, Object> req) {
        try {
            sysFileParseLogService.handlePythonCallback(req);
            Map<String, Object> res = new HashMap<>();
            res.put("code", 200);
            res.put("msg", "报告接收成功");
            return res;
        } catch (Exception e) {
            Map<String, Object> res = new HashMap<>();
            res.put("code", 403);
            res.put("msg", e.getMessage());
            return res;
        }
    }

    /**
     * 【用户控制流】一键发起无损软重试
     */
    @OperationLog(module = "文档处理", operation = "软重试")
    @PostMapping("/admin/logs/retry")
    public Map<String, Object> retryTask(@RequestBody Map<String, String> req) {
        String fileCode = req.get("fileCode");
        Map<String, Object> res = new HashMap<>();
        try {
            sysFileParseLogService.softRetryTask(fileCode);
            res.put("code", 200);
            res.put("msg", "排队重入成功，系统准备唤起调度");
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", e.getMessage());
        }
        return res;
    }

    /**
     * 【用户控制流】硬拦截：直接上传源文件覆盖替换之前的坏块记录
     */
    @OperationLog(module = "文档处理", operation = "硬替换文件")
    @PostMapping("/admin/logs/hard_replace")
    public Map<String, Object> hardReplaceTask(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileCode") String fileCode,
            @RequestParam(value = "uploader", required = false) String uploader) {
        
        Map<String, Object> res = new HashMap<>();
        try {
            String savedPath = "";
            try (InputStream is = file.getInputStream()) {
                savedPath = storageService.store(is, file.getOriginalFilename());
            }
            
            SysFileParseLog log = sysFileParseLogService.getOne(new LambdaQueryWrapper<SysFileParseLog>().eq(SysFileParseLog::getFileCode, fileCode));
            if (log == null) {
                res.put("code", 404);
                res.put("msg", "未找到原始日志档案");
                return res;
            }
            log.setFilePath(savedPath);
            if(uploader != null) log.setUploader(uploader);
            sysFileParseLogService.updateById(log);
            
            // 调度原位重新解析
            sysFileParseLogService.softRetryTask(fileCode);
            
            res.put("code", 200);
            res.put("msg", "新文件顶更替代完成，即将进入二次抽丝解析");
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "更替源文件失败：" + e.getMessage());
        }
        return res;
    }
}
