/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.baomidou.mybatisplus.core.conditions.Wrapper
 *  com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
 *  com.boyang.search.entity.KbDocRegistry
 *  com.boyang.search.entity.SysDocBatch
 *  com.boyang.search.entity.SysDocImportTask
 *  com.boyang.search.entity.SysFileParseLog
 *  com.boyang.search.model.DocIngestRequest
 *  com.boyang.search.service.DocIngestService
 *  com.boyang.search.service.ISysFileParseLogService
 *  com.boyang.search.service.KbDocRegistryService
 *  com.boyang.search.service.StorageService
 *  com.boyang.search.service.SysDocBatchService
 *  com.boyang.search.service.SysDocImportTaskService
 *  com.boyang.search.utils.ContentHashUtils
 *  com.boyang.search.utils.FileTypeValidator
 *  com.fasterxml.jackson.databind.ObjectMapper
 *  javax.servlet.http.HttpServletRequest
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.redis.core.StringRedisTemplate
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 *  org.springframework.web.multipart.MultipartFile
 */
package com.boyang.search.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.entity.SysFileParseLog;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.service.DocIngestService;
import com.boyang.search.service.ISysFileParseLogService;
import com.boyang.search.service.KbDocRegistryService;
import com.boyang.search.service.StorageService;
import com.boyang.search.service.SysDocBatchService;
import com.boyang.search.service.SysDocImportTaskService;
import com.boyang.search.utils.ContentHashUtils;
import com.boyang.search.utils.FileTypeValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value={"/api/v1"})
public class DocImportController {
    @Autowired
    private SysDocBatchService sysDocBatchService;
    @Autowired
    private SysDocImportTaskService sysDocImportTaskService;
    @Autowired
    private ISysFileParseLogService sysFileParseLogService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private StorageService storageService;
    @Autowired
    private KbDocRegistryService kbDocRegistryService;
    @Autowired
    private DocIngestService docIngestService;
    @Value(value="${doc.upload.max-file-size:104857600}")
    private long maxFileSizeBytes;
    private static final String QUEUE_HIGH = "DOC_TASK_QUEUE_HIGH";
    private static final String QUEUE_LOW = "DOC_TASK_QUEUE";
    private static final long QUEUE_SPLIT_BYTES = 0x200000L;

    @PostMapping(value={"/admin/doc/batch_import"})
    public Map<String, Object> batchImport(@RequestBody Map<String, String> request) {
        HashMap<String, Object> response = new HashMap<String, Object>();
        String sourceDir = request.get("sourceDir");
        if (sourceDir == null || sourceDir.trim().isEmpty()) {
            response.put("code", 400);
            response.put("msg", "扫描路径(sourceDir)不能为空");
            return response;
        }

        try {
            DocIngestRequest req = new DocIngestRequest();
            req.setIngestType("LOCAL");
            req.setDirPath(sourceDir);
            req.setTargetIndex(request.getOrDefault("targetIndex", "kb_document_v1"));
            req.setVisibility(request.getOrDefault("visibility", "INTERNAL"));
            req.setDeptCode(request.getOrDefault("deptCode", ""));
            req.setTag(request.get("tag"));
            req.setUnit(request.get("unit"));
            req.setDocNumber(request.get("docNumber"));
            req.setOwner(request.get("owner"));
            req.setSearchQueries(request.get("searchQueries"));
            req.setPublishTime(request.get("publishTime"));
            req.setSourceSystem("LOCAL_DIR_ADMIN");

            String batchId = this.docIngestService.ingest(req);

            response.put("code", 200);
            response.put("msg", "任务流已成功提交下发到重计算引擎队伍中！");
            response.put("data", batchId);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "批量导入出错: " + e.getMessage());
        }
        return response;
    }

    @PostMapping(value={"/internal/task/callback"})
    public Map<String, Object> taskCallback(@RequestBody Map<String, Object> request) {
        String taskId = (String)request.get("taskId");
        String status = (String)request.get("status");
        String errorMsg = (String)request.get("errorMsg");
        SysDocImportTask task = this.sysDocImportTaskService.getByTaskId(taskId);
        if (task != null) {
            task.setStatus(status);
            task.setErrorMsg(errorMsg);
            this.sysDocImportTaskService.updateById(task);
            SysDocBatch batch = this.sysDocBatchService.getByBatchId(task.getBatchId());
            if (batch != null) {
                if ("INDEXED".equals(status)) {
                    batch.setSuccessCount(Integer.valueOf(batch.getSuccessCount() + 1));
                } else if ("ERROR".equals(status)) {
                    batch.setErrorCount(Integer.valueOf(batch.getErrorCount() + 1));
                }
                if (batch.getSuccessCount() + batch.getErrorCount() >= batch.getTotalCount()) {
                    batch.setStatus("DONE");
                }
                this.sysDocBatchService.updateById(batch);
            }
        }
        HashMap<String, Object> res = new HashMap<String, Object>();
        res.put("code", 200);
        res.put("msg", "callback handled");
        return res;
    }

    @GetMapping(value={"/admin/doc/batches"})
    public Map<String, Object> listBatches() {
        List list = this.sysDocBatchService.list((Wrapper)new LambdaQueryWrapper<SysDocBatch>().orderByDesc(SysDocBatch::getCreatedAt));
        HashMap<String, Object> res = new HashMap<String, Object>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", list);
        return res;
    }

    @GetMapping(value={"/admin/doc/batches/{batchId}/tasks"})
    public Map<String, Object> listTasks(@PathVariable String batchId, @RequestParam(required=false) String status) {
        LambdaQueryWrapper<SysDocImportTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDocImportTask::getBatchId, (Object)batchId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysDocImportTask::getStatus, (Object)status);
        }
        wrapper.orderByDesc(SysDocImportTask::getCreatedAt);
        List list = this.sysDocImportTaskService.list(wrapper);
        HashMap<String, Object> res = new HashMap<String, Object>();
        res.put("code", 200);
        res.put("msg", "success");
        res.put("data", list);
        return res;
    }

    @PostMapping(value={"/admin/doc/upload"})
    public Map<String, Object> uploadDocs(@RequestParam(value="files") MultipartFile[] files, @RequestParam(value="targetIndex", required=false, defaultValue="kb_document_v1") String targetIndex, @RequestParam(value="tag", required=false) String tag, @RequestParam(value="unit", required=false) String unit, @RequestParam(value="docNumber", required=false) String docNumber, @RequestParam(value="owner", required=false) String owner, @RequestParam(value="searchQueries", required=false) String searchQueries, @RequestParam(value="publishTime", required=false) String publishTime, @RequestParam(value="visibility", required=false, defaultValue="INTERNAL") String visibility, @RequestParam(value="deptCode", required=false, defaultValue="") String deptCode) {
        HashMap<String, Object> response = new HashMap<String, Object>();
        if (files == null || files.length == 0) {
            response.put("code", 400);
            response.put("msg", "请选择要上传的文件");
            return response;
        }
        if ("DEPT".equalsIgnoreCase(visibility) && (deptCode == null || deptCode.trim().isEmpty())) {
            response.put("code", 400);
            response.put("msg", "可见度为 DEPT 时，部门编码(deptCode)不能为空");
            return response;
        }

        try {
            DocIngestRequest req = new DocIngestRequest();
            req.setIngestType("UPLOAD");
            req.setTargetIndex(targetIndex);
            req.setVisibility(visibility);
            req.setDeptCode(deptCode);
            req.setTag(tag);
            req.setUnit(unit);
            req.setDocNumber(docNumber);
            req.setOwner(owner);
            req.setSearchQueries(searchQueries);
            req.setPublishTime(publishTime);
            req.setSourceSystem("UPLOAD_ADMIN");

            req.setUploadFiles(files);
            String batchId = this.docIngestService.ingest(req);

            response.put("code", 200);
            response.put("msg", "上传文件已进入排队队列");
            response.put("data", batchId);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "上传失败: " + e.getMessage());
        }
        return response;
    }

    @PostMapping(value={"/admin/doc/sftp_import"})
    public Map<String, Object> sftpImport(@RequestBody Map<String, String> request) {
        HashMap<String, Object> response = new HashMap<String, Object>();
        try {
            DocIngestRequest req = new DocIngestRequest();
            req.setIngestType("SFTP");
            req.setCredentialId(request.get("credentialId"));
            req.setFilePath(request.get("filePath"));
            req.setDirPath(request.getOrDefault("remotePath", request.get("dirPath")));
            req.setVisibility(request.getOrDefault("visibility", "INTERNAL"));
            req.setDeptCode(request.getOrDefault("deptCode", ""));
            req.setTag(request.get("tag"));
            req.setOwner(request.get("owner"));
            req.setUnit(request.get("unit"));
            req.setSourceSystem("SFTP_ADMIN");
            String batchId = this.docIngestService.ingest(req);
            response.put("code", 200);
            response.put("msg", "远程文件拉取任务已下发");
            response.put("data", batchId);
        }
        catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", "参数错误: " + e.getMessage());
        }
        catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "SFTP 接入失败: " + e.getMessage());
        }
        return response;
    }

    @PostMapping(value={"/admin/doc/url_import"})
    public Map<String, Object> urlImport(@RequestBody Map<String, String> request) {
        HashMap<String, Object> response = new HashMap<String, Object>();
        try {
            DocIngestRequest req = new DocIngestRequest();
            req.setIngestType("URL");
            req.setFilePath(request.get("url"));
            req.setFileName(request.get("name"));
            req.setVisibility(request.getOrDefault("visibility", "INTERNAL"));
            req.setDeptCode(request.getOrDefault("deptCode", ""));
            req.setTag(request.get("tag"));
            req.setOwner(request.get("owner"));
            req.setSourceSystem("URL_ADMIN");
            String batchId = this.docIngestService.ingest(req);
            response.put("code", 200);
            response.put("msg", "URL 文件下载任务已下发");
            response.put("data", batchId);
        }
        catch (SecurityException e) {
            response.put("code", 403);
            response.put("msg", "安全限制: " + e.getMessage());
        }
        catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "URL 下载失败: " + e.getMessage());
        }
        return response;
    }

}
