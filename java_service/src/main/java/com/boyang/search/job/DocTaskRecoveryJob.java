package com.boyang.search.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.service.SysDocBatchService;
import com.boyang.search.service.SysDocImportTaskService;
import com.boyang.search.utils.DocumentTextNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档任务自动恢复定时器（P0 #5/#6 + P0.2 修复）。
 * 业务功能：解决两类问题：
 *   1. Redis 队列消息丢失（Redis 重启后消息消失，但 MySQL 任务仍为 PENDING）
 *   2. 任务长时间 PENDING 无感知（task_worker 挂起或崩溃导致任务卡死）
 * P0.2 新增修复：
 *   3. Redis 入队使用 SETNX 幂等键（TTL=30min），同一任务在 30 分钟内不会重复入队
 *   4. 重试任务不提前隐藏旧版本，版本切换统一交给 OutboxPoller 在新 chunk 写入成功后完成
 * 执行策略：
 *   - 每隔 300 秒扫描一次 PENDING 任务
 *   - 超过 15 分钟的 PENDING 任务：幂等入队（最少一次语义）
 *   - 超过 60 分钟的 PENDING 任务：标记 ERROR
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocTaskRecoveryJob {

    private final SysDocImportTaskService taskService;
    private final SysDocBatchService      batchService;
    private final StringRedisTemplate     redisTemplate;
    /** [C-2 修复] 注入单例 ObjectMapper，不再每次 new（ObjectMapper 是重量级对象） */
    private final ObjectMapper            objectMapper;

    private static final String QUEUE_KEY        = "DOC_TASK_QUEUE";
    /** 幂等入队防重键前缀（SETNX + TTL） */
    private static final String ENQUEUE_LOCK_KEY = "doc:requeue:lock:";
    /** 幂等锁 TTL（分钟），防止同一任务在此时间窗口内重复入队 */
    private static final int    LOCK_TTL_MIN     = 30;
    /** 超过此时长仍为 PENDING → 重推队列（分钟） */
    private static final int    REQUEUE_MIN      = 15;
    /** 超过此时长仍为 PENDING → 标记 ERROR（分钟） */
    private static final int    TIMEOUT_MIN      = 60;
    @Value("${doc.default.target-index:kb_document_v1}")
    private String defaultTargetIndex;

    /**
     * 每 5 分钟扫描一次超时 PENDING 任务。
     * 使用 fixedDelay 保证串行执行，避免上次扫描未完成即触发下次。
     *
     * 核心流程：
     *   1. 查询所有 PENDING 任务
     *   2. 按超时时长分档处理（ERROR vs 重推）
     *   3. 重推前：Redis SETNX 幂等入队；不提前隐藏旧版本
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void recoverStuckTasks() {
        long now = System.currentTimeMillis();

        List<SysDocImportTask> pendingTasks = taskService.list(
            new LambdaQueryWrapper<SysDocImportTask>()
                .eq(SysDocImportTask::getStatus, "PENDING")
        );

        if (pendingTasks.isEmpty()) return;
        log.info("[Recovery] 扫描到 {} 个 PENDING 任务", pendingTasks.size());

        int requeuedCount = 0;
        int timedOutCount = 0;

        for (SysDocImportTask task : pendingTasks) {
            if (task.getCreatedAt() == null) continue;

            long elapsedMin = (now - task.getCreatedAt().getTime()) / 60_000L;

            if (elapsedMin >= TIMEOUT_MIN) {
                // 超过 60 分钟：标记 ERROR
                task.setStatus("ERROR");
                task.setErrorMsg("任务超时 " + elapsedMin + " 分钟，已自动标记失败（可手动重试）");
                task.setUpdatedAt(new Date());
                taskService.updateById(task);
                syncBatchErrorCount(task.getBatchId());
                log.warn("[Recovery] 任务超时标记 ERROR taskId={} elapsedMin={}", task.getTaskId(), elapsedMin);
                timedOutCount++;

            } else if (elapsedMin >= REQUEUE_MIN) {
                // 超过 15 分钟：幂等入队，旧版本保持可见直到 OutboxPoller 完成新版本激活
                String lockKey = ENQUEUE_LOCK_KEY + task.getTaskId();
                // SETNX 幂等键：若已持有则跳过（防止上次重推的 Python 还在跑）
                Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofMinutes(LOCK_TTL_MIN));
                if (!Boolean.TRUE.equals(acquired)) {
                    log.info("[Recovery] 幂等锁已持有，跳过重推 taskId={}", task.getTaskId());
                    continue;
                }

                // 步骤 1：重新推入 Redis 队列
                try {
                    Map<String, Object> payload = buildRecoveryPayload(task, defaultTargetIndex);
                    // [B-3 修复] visibility 和 deptCode 从数据库读取，不再硬编码 INTERNAL
                    // 防止恢复任务将 DEPT/PRIVATE/GRANT 文档权限静默扩大为 INTERNAL
                    payload.put("_recovery",    true); // 标记为恢复任务，Python 侧可以据此跳过重复 check

                    // [C-2 修复] 不再 new ObjectMapper()，复用注入的全局单例
                    redisTemplate.opsForList().rightPush(QUEUE_KEY, objectMapper.writeValueAsString(payload));
                    log.info("[Recovery] 幂等重推队列 taskId={} elapsedMin={}", task.getTaskId(), elapsedMin);
                    requeuedCount++;
                } catch (Exception e) {
                    log.error("[Recovery] 重推失败 taskId={} err={}", task.getTaskId(), e.getMessage());
                    // 重推失败时释放幂等锁，允许下次重试
                    redisTemplate.delete(lockKey);
                }
            }
        }

        if (requeuedCount > 0 || timedOutCount > 0) {
            log.info("[Recovery] 本次处理完成 requeued={} timedOut={}", requeuedCount, timedOutCount);
        }
    }

    static Map<String, Object> buildRecoveryPayload(SysDocImportTask task, String targetIndex) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId",       task.getTaskId());
        payload.put("fileCode",     task.getTaskId());
        payload.put("filePath",     task.getFilePath());
        payload.put("originalName", DocumentTextNormalizer.normalizeFilename(task.getOriginalName()));
        payload.put("targetIndex",  targetIndex);
        String origVisibility = task.getVisibility();
        payload.put("visibility",
            (origVisibility != null && !origVisibility.trim().isEmpty())
            ? origVisibility : "INTERNAL");
        payload.put("deptCode",
            task.getDeptCode() != null ? task.getDeptCode() : "");
        payload.putAll(buildRecoveryUnitProjection((String) payload.get("deptCode")));
        payload.put("acl_tokens_json", buildRecoveryAclTokensJson(
            (String) payload.get("visibility"),
            (String) payload.get("deptCode")
        ));
        return payload;
    }

    /**
     * 业务功能：为恢复重推任务补齐 ES 入库所需的 acl_tokens_json。
     * 关键流程：基于 sys_doc_import_task 中已持久化的 visibility/deptCode 重建可证明安全的 ACL Token。
     * 设计原因：恢复任务无法读取原 Redis payload；若缺失 acl_tokens_json，Python 会降级为 _INTERNAL，
     *          导致 DEPT/PRIVATE/GRANT 文档在 ES 前置过滤阶段被错误放宽。
     *
     * @param visibility 文档可见度
     * @param deptCode 文档归属单位编码
     * @return JSON 数组字符串，供 Python Worker 直接写入 ES acl_tokens
     */
    static String buildRecoveryAclTokensJson(String visibility, String deptCode) {
        List<String> tokens = new ArrayList<>();
        String vis = visibility != null && !visibility.trim().isEmpty()
            ? visibility.trim().toUpperCase()
            : "INTERNAL";

        switch (vis) {
            case "PUBLIC":
                tokens.add("_PUBLIC");
                break;
            case "INTERNAL":
                tokens.add("_INTERNAL");
                break;
            case "DEPT":
                if (deptCode != null && !deptCode.trim().isEmpty()) {
                    for (String code : buildAdministrativeAncestorChain(deptCode)) {
                        tokens.add("dept::" + code);
                    }
                }
                if (tokens.isEmpty()) {
                    tokens.add("_NO_ACCESS");
                }
                break;
            case "PRIVATE":
            case "GRANT":
                // sys_doc_import_task 未持久化 uploader/grantedUsers/grantedRoles。
                // 恢复链路不能猜测授权主体，必须保守拒绝 ES 前置命中，避免权限被扩大为 INTERNAL。
                tokens.add("_NO_ACCESS");
                break;
            default:
                tokens.add("_NO_ACCESS");
                break;
        }
        return toJsonArray(tokens);
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 业务功能：为恢复重推任务补齐单位权限投影字段，避免故障恢复链路生成缺权限字段的新 payload。
     * 关键流程：恢复任务只有历史 task.deptCode，没有完整部门树服务上下文，因此采用行政区划编码的
     *          两位层级兜底生成“本级 + 上级”链；非行政编码则至少保留自身。
     * 设计原因：恢复任务不能比正常入库链路少字段，否则 Redis 丢失后的重推会制造权限投影不完整数据。
     *
     * @param deptCode 文档归属单位编码
     * @return ownerUnitCode、visibleUnitCodes、permissionVersion 三个 Redis payload 字段
     */
    static Map<String, Object> buildRecoveryUnitProjection(String deptCode) {
        Map<String, Object> projection = new HashMap<>();
        String ownerUnitCode = deptCode != null ? deptCode.trim() : "";
        List<String> visibleUnitCodes = new ArrayList<>();

        if (ownerUnitCode.isEmpty()) {
            ownerUnitCode = "global";
            visibleUnitCodes.add("global");
        } else {
            visibleUnitCodes.addAll(buildAdministrativeAncestorChain(ownerUnitCode));
        }

        projection.put("ownerUnitCode", ownerUnitCode);
        projection.put("visibleUnitCodes", visibleUnitCodes);
        projection.put("permissionVersion", System.currentTimeMillis());
        return projection;
    }

    private static List<String> buildAdministrativeAncestorChain(String deptCode) {
        Set<String> chain = new LinkedHashSet<>();
        String normalized = normalizeDeptCode(deptCode);
        chain.add(normalized);
        if (normalized.matches("\\d+")) {
            for (int len = normalized.length() - 2; len >= 2; len -= 2) {
                chain.add(normalized.substring(0, len));
            }
        }
        return new ArrayList<>(chain);
    }

    private static String normalizeDeptCode(String code) {
        String c = code == null ? "" : code.trim();
        while (c.length() > 2 && c.endsWith("00")) {
            c = c.substring(0, c.length() - 2);
        }
        return c;
    }

    /**
     * 超时任务标记 ERROR 后，同步更新批次级别的 errorCount 和 status。
     */
    private void syncBatchErrorCount(String batchId) {
        try {
            SysDocBatch batch = batchService.getByBatchId(batchId);
            if (batch == null) return;
            batch.setErrorCount(batch.getErrorCount() + 1);
            if (batch.getSuccessCount() + batch.getErrorCount() >= batch.getTotalCount()) {
                batch.setStatus("DONE");
            }
            batchService.updateById(batch);
        } catch (Exception e) {
            log.warn("[Recovery] 批次状态同步失败 batchId={} err={}", batchId, e.getMessage());
        }
    }
}
