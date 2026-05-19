package com.boyang.search.controller;

import com.boyang.search.mapper.KbDocOutboxMapper;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.DocIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部系统文档注册接口（DocRegisterController）。
 * 业务功能：向其他业务系统（OA、DMS、档案系统等）提供标准 HTTP API，
 *           允许外部系统在产生新文档时主动推送给知识库，触发文件拉取和向量化入库。
 * 关键流程：
 *   1. 外部系统携带 X-Internal-Token + JSON Body 调用 POST /api/v1/internal/doc/register
 *   2. Token 校验通过后，解析请求并委托 DocIngestService 完成入库
 *   3. 返回 batchId 供外部系统追踪处理进度
 * 鉴权：X-Internal-Token（与其他内部接口统一，修复 A-2 同套机制）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class DocRegisterController {

    private final DocIngestService  docIngestService;
    private final PermissionGuard   permissionGuard;
    /** [T1-6 Outbox] Python 回调时更新 outbox 状态 */
    private final KbDocOutboxMapper outboxMapper;
    /** [Fix] Python 回调同步充填 kb_doc_register 字段，将占位草稿从 PROCESSING 推进到 INDEXED */
    private final com.boyang.search.service.KbDocRegistryService kbDocRegistryService;

    /**
     * 单文件/目录注册接口（供外部系统主动推送）。
     * 支持 SFTP 单文件、SFTP 目录、HTTP URL 三种模式。
     *
     * 请求示例（SFTP 单文件）：
     * <pre>
     * POST /api/v1/internal/doc/register
     * X-Internal-Token: kb-dev-token-...
     * Content-Type: application/json
     * {
     *   "ingestType": "SFTP",
     *   "credentialId": "fileserver-prod",
     *   "filePath": "/data/docs/2024/report.pdf",
     *   "visibility": "INTERNAL",
     *   "sourceSystem": "OA"
     * }
     * </pre>
     *
     * @return 成功返回 batchId，失败返回错误信息
     */
    @PostMapping("/doc/register")
    public Map<String, Object> register(@RequestBody DocIngestRequest req,
                                        HttpServletRequest httpReq) {
        Map<String, Object> resp = new HashMap<>();

        // 统一内部 Token 鉴权（A-2 修复同套机制）
        if (!permissionGuard.isValidInternalToken(httpReq.getHeader("X-Internal-Token"))) {
            resp.put("code", 401);
            resp.put("msg",  "未授权访问，需携带有效内部服务凭证（X-Internal-Token）");
            return resp;
        }

        try {
            String batchId = docIngestService.ingest(req);
            resp.put("code",    200);
            resp.put("msg",     "文档入库任务已提交");
            resp.put("batchId", batchId);
            log.info("[DocRegister] API 注册成功 batchId={} ingestType={} sourceSystem={}",
                batchId, req.getIngestType(), req.getSourceSystem());
        } catch (IllegalArgumentException e) {
            resp.put("code", 400);
            resp.put("msg",  "请求参数错误: " + e.getMessage());
            log.warn("[DocRegister] 参数校验失败 err={}", e.getMessage());
        } catch (SecurityException e) {
            resp.put("code", 403);
            resp.put("msg",  "安全限制: " + e.getMessage());
            log.warn("[DocRegister] 安全拦截 err={}", e.getMessage());
        } catch (Exception e) {
            resp.put("code", 500);
            resp.put("msg",  "入库失败: " + e.getMessage());
            log.error("[DocRegister] 入库异常", e);
        }
        return resp;
    }


    /**
     * 批量注册接口（一次提交多个文档）。
     * 场景：外部系统批量同步历史文件（如按天导出）。
     *
     * @param requests 文档入库请求列表（最多 200 条，防止单次过大）
     */
    @PostMapping("/doc/register/batch")
    public Map<String, Object> registerBatch(@RequestBody java.util.List<DocIngestRequest> requests,
                                             HttpServletRequest httpReq) {
        Map<String, Object> resp = new HashMap<>();

        if (!permissionGuard.isValidInternalToken(httpReq.getHeader("X-Internal-Token"))) {
            resp.put("code", 401);
            resp.put("msg",  "未授权访问");
            return resp;
        }

        if (requests == null || requests.isEmpty()) {
            resp.put("code", 400);
            resp.put("msg",  "请求列表不能为空");
            return resp;
        }

        if (requests.size() > 200) {
            resp.put("code", 400);
            resp.put("msg",  "单次批量请求最多 200 条，当前: " + requests.size());
            return resp;
        }

        java.util.List<String> batchIds   = new java.util.ArrayList<>();
        java.util.List<String> failedItems = new java.util.ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            try {
                String batchId = docIngestService.ingest(requests.get(i));
                batchIds.add(batchId);
            } catch (Exception e) {
                failedItems.add("[" + i + "] " + e.getMessage());
                log.warn("[DocRegister][Batch] 第 {} 条入库失败 err={}", i, e.getMessage());
            }
        }

        resp.put("code",        200);
        resp.put("msg",         "批量提交完成");
        resp.put("batchIds",    batchIds);
        resp.put("failedCount", failedItems.size());
        resp.put("failedItems", failedItems);
        return resp;
    }

    /**
     * [T1-6 Outbox] Python 文档处理完成回调接口。
     * 业务功能：Python Worker 在 ES bulk_write 成功后，同步调用此接口确认 ES 已写入，
     *           Java 将 kb_doc_outbox 状态从 WAITING 推进到 READY，
     *           OutboxPoller 发现 READY 记录后执行 ES update_by_query 激活文档（is_latest=true）。
     * 关键流程：
     *   1. Python: bulk_write (is_latest=false) → POST /api/v1/internal/doc/registry
     *   2. Java: outbox WAITING → READY，回填 docVersion + fileBaseHash
     *   3. OutboxPoller: READY → ES update_by_query → outbox DONE
     *
     * 请求体字段：
     *   taskId       - 任务唯一标识（对应 sys_doc_import_task.task_id）
     *   sourceName   - 文档原始文件名
     *   docVersion   - Python 计算的实际版本号（供 Poller update_by_query）
     *   fileBaseHash - ES doc_id 前缀（file_base_hash，供 Poller 构造查询）
     *   chunkCount   - 已写入 ES 的 chunk 总数（用于审计）
     *   targetIndex  - ES 目标索引名
     */
    @PostMapping("/doc/registry")
    public Map<String, Object> registry(@RequestBody Map<String, Object> body,
                                         HttpServletRequest httpReq) {
        Map<String, Object> resp = new HashMap<>();

        if (!permissionGuard.isValidInternalToken(httpReq.getHeader("X-Internal-Token"))) {
            resp.put("code", 401);
            resp.put("msg",  "未授权访问，需携带有效内部服务凭证（X-Internal-Token）");
            return resp;
        }

        String taskId       = (String) body.get("taskId");
        String sourceName   = (String) body.getOrDefault("sourceName", "");
        Object versionObj   = body.get("docVersion");
        String fileBaseHash = (String) body.getOrDefault("fileBaseHash", "");

        if (taskId == null || taskId.trim().isEmpty()) {
            resp.put("code", 400);
            resp.put("msg",  "taskId 不能为空");
            return resp;
        }

        int docVersion = (versionObj instanceof Number) ? ((Number) versionObj).intValue() : 0;

        // [Diag] 明确记录 Python 回调到达的参数，便于追踪 taskId 与 outbox 的匹配情况
        log.info("[DocRegistry] 收到 Python 回调: taskId={} sourceName={} docVersion={} fileBaseHash={}",
            taskId, sourceName, docVersion, fileBaseHash);

        try {
            // [Fix] 先按 taskId 查（不过滤 status），再根据 status 分支处理：
            // 这样可以区分「taskId 根本不存在」和「taskId 存在但 status 已推进」。
            com.boyang.search.entity.KbDocOutbox record = outboxMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.boyang.search.entity.KbDocOutbox>()
                    .eq(com.boyang.search.entity.KbDocOutbox::getTaskId, taskId)
            );

            if (record == null) {
                // taskId 根本不在 outbox 表 → 说明 Python 传的 taskId 与 Java 创建时不一致
                log.error("[DocRegistry] outbox 记录完全不存在！taskId={} 请核查 Redis payload 中的 taskId", taskId);
                resp.put("code", 200);
                resp.put("msg",  "OK (outbox not found)");
                return resp;
            }
            log.info("[DocRegistry] outbox 查到记录: taskId={} id={} status={}", taskId, record.getId(), record.getStatus());

            if (!"WAITING".equals(record.getStatus())) {
                // 已被 Poller 推进，幂等返回
                log.warn("[DocRegistry] outbox 已推进 taskId={} status={}", taskId, record.getStatus());
                resp.put("code", 200);
                resp.put("msg",  "OK (already " + record.getStatus() + ")");
                return resp;
            }

            // [Fix] 检查是否为去重跳过/解析失败的通知
            // 根因：Python 因 ContentDedup 跳过入库时，bg_notify_java() 不会被调用，
            //       但为了关闭 outbox WAITING 记录，Python 会带 skipped=True 调用此接口。
            //       此时不需要 OutboxPoller 激活 ES（ES 中已存在 is_latest=true 的文档），
            //       直接将 outbox 置 DONE 即可。
            boolean skipped = Boolean.TRUE.equals(body.get("skipped"));
            if (skipped) {
                com.boyang.search.entity.KbDocOutbox doneUpdate = new com.boyang.search.entity.KbDocOutbox();
                doneUpdate.setId(record.getId());
                doneUpdate.setStatus("DONE");
                outboxMapper.updateById(doneUpdate);
                log.info("[DocRegistry] outbox WAITING→DONE (skipped/dedup) taskId={} sourceName={}", taskId, sourceName);
                resp.put("code", 200);
                resp.put("msg",  "OK (skipped)");
                return resp;
            }

            // [T1-6] 推进状态：WAITING → READY，并回填 docVersion + fileBaseHash
            // [is_latest Fix] 同步更新 targetIndex：
            //   根因：Java 写 outbox 时 targetIndex 由 DocIndexRoutingService 按 tag 预置，
            //         Python 消费时会再次按 doc_type 独立路由，实际写入索引可能与预置值不同。
            //         OutboxPoller 读 outbox.targetIndex 执行 update_by_query —— 若索引不匹配
            //         则 0 条命中，is_latest 永远停留在 false。
            //   修复：Python 回调已携带实际写入的 targetIndex，此处用它覆盖 Java 预置值。
            String actualTargetIndex = (String) body.get("targetIndex");

            // [Fix] 回调同时充填 kb_doc_register：将占位草稿（PROCESSING + 字段为空）推进到 INDEXED
            // 根因：getNextVersion() 创建了占位记录但字段全为 NULL，必须在此处回填完整免数据。
            // Outbox 模式下此处只登记 MySQL，ES 激活统一交给 OutboxPoller。
            try {
                String storagePath   = (String) body.getOrDefault("storagePath", "");
                String targetIndex   = (String) body.getOrDefault("targetIndex", "kb_document_v1");
                int    chunkCount    = body.get("chunkCount") instanceof Number
                                      ? ((Number) body.get("chunkCount")).intValue() : 0;
                String contentHash   = (String) body.getOrDefault("contentHash", "");
                String docNumber     = (String) body.get("docNumber");
                String unit          = (String) body.get("unit");
                String tags          = (String) body.get("tags");
                String publishTime   = (String) body.get("publishTime");
                String visibility    = (String) body.getOrDefault("visibility", "INTERNAL");
                String deptCode      = (String) body.get("deptCode");
                String uploaderId    = (String) body.getOrDefault("uploaderId", "");
                String uploaderName  = (String) body.getOrDefault("uploaderName", "");
                String docId         = (String) body.getOrDefault("docId", sourceName + ":" + docVersion);
                String parseStatus   = (String) body.getOrDefault("parseStatus", "INDEXED");

                kbDocRegistryService.registerDoc(
                    sourceName, docVersion, docId,
                    storagePath, targetIndex, chunkCount,
                    contentHash, docNumber, unit,
                    tags, publishTime, visibility,
                    deptCode, uploaderId, uploaderName,
                    parseStatus,
                    false
                );
                log.info("[DocRegistry] kb_doc_register 已充填 taskId={} sourceName={} v{} status={}",
                    taskId, sourceName, docVersion, parseStatus);
            } catch (Exception regErr) {
                log.error("[DocRegistry] kb_doc_register 充填失败，outbox 保持 WAITING taskId={} err={}",
                    taskId, regErr.getMessage());
                throw regErr;
            }

            com.boyang.search.entity.KbDocOutbox update = new com.boyang.search.entity.KbDocOutbox();
            update.setId(record.getId());
            update.setDocVersion(docVersion);
            update.setFileBaseHash(fileBaseHash);
            update.setStatus("READY");
            // 用 Python 实际写入索引覆盖预置值（null 时保留原值，兼容旧版 Python 不传此字段的情况）
            if (actualTargetIndex != null && !actualTargetIndex.trim().isEmpty()) {
                update.setTargetIndex(actualTargetIndex);
            }
            outboxMapper.updateById(update);

            log.info("[DocRegistry] outbox WAITING→READY taskId={} sourceName={} v{} hash={} targetIndex={}",
                taskId, sourceName, docVersion, fileBaseHash,
                actualTargetIndex != null ? actualTargetIndex : record.getTargetIndex());

            resp.put("code", 200);
            resp.put("msg",  "OK");
        } catch (Exception e) {
            log.error("[DocRegistry] 更新 outbox 失败 taskId={} err={}", taskId, e.getMessage());
            resp.put("code", 500);
            resp.put("msg",  "服务器内部错误: " + e.getMessage());
        }
        return resp;
    }
}

