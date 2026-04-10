package com.boyang.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.service.SysDocBatchService;
import com.boyang.search.service.SysDocImportTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档任务自动恢复定时器（P0 #5/#6 + P0.2 修复）。
 * 业务功能：解决两类问题：
 *   1. Redis 队列消息丢失（Redis 重启后消息消失，但 MySQL 任务仍为 PENDING）
 *   2. 任务长时间 PENDING 无感知（task_worker 挂起或崩溃导致任务卡死）
 * P0.2 新增修复：
 *   3. 重推前先通过 ES UpdateByQuery 将该文档旧 chunk 标记 is_latest=false
 *      避免重试时产生"旧chunk(is_latest=true) + 新chunk(is_latest=true)"的双写问题
 *   4. Redis 入队使用 SETNX 幂等键（TTL=30min），同一任务在 30 分钟内不会重复入队
 * 执行策略：
 *   - 每隔 300 秒扫描一次 PENDING 任务
 *   - 超过 15 分钟的 PENDING 任务：清理旧 chunk → 幂等入队（最少一次语义）
 *   - 超过 60 分钟的 PENDING 任务：标记 ERROR
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocTaskRecoveryJob {

    private final SysDocImportTaskService taskService;
    private final SysDocBatchService      batchService;
    private final StringRedisTemplate     redisTemplate;
    private final ElasticsearchClient     esClient;
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
    /** 默认索引模式 */
    private static final String INDEX_PATTERN    = "kb_document_v*";

    @Value("${doc.default.target-index:kb_document_v1}")
    private String defaultTargetIndex;

    /**
     * 每 5 分钟扫描一次超时 PENDING 任务。
     * 使用 fixedDelay 保证串行执行，避免上次扫描未完成即触发下次。
     *
     * 核心流程：
     *   1. 查询所有 PENDING 任务
     *   2. 按超时时长分档处理（ERROR vs 重推）
     *   3. 重推前：① ES 清旧 chunk ② Redis SETNX 幂等入队
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
                // 超过 15 分钟：先清旧 chunk → 再幂等入队（P0.2 修复）
                String lockKey = ENQUEUE_LOCK_KEY + task.getTaskId();
                // SETNX 幂等键：若已持有则跳过（防止上次重推的 Python 还在跑）
                Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofMinutes(LOCK_TTL_MIN));
                if (!Boolean.TRUE.equals(acquired)) {
                    log.info("[Recovery] 幂等锁已持有，跳过重推 taskId={}", task.getTaskId());
                    continue;
                }

                // 步骤 1：清理该文档在 ES 中可能存在的旧 chunk（is_latest=false，使其对搜索不可见）
                // 避免重试产生双写（旧 chunk is_latest=true + 新 chunk is_latest=true）
                String sourceName = task.getOriginalName();
                if (sourceName != null && !sourceName.trim().isEmpty()) {
                    cleanupOldChunks(sourceName);
                }

                // 步骤 2：重新推入 Redis 队列
                try {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("taskId",       task.getTaskId());
                    payload.put("fileCode",     task.getTaskId());
                    payload.put("filePath",     task.getFilePath());
                    payload.put("originalName", task.getOriginalName());
                    payload.put("targetIndex",  defaultTargetIndex);
                    // [B-3 修复] visibility 和 deptCode 从数据库读取，不再硬编码 INTERNAL
                    // 防止恢复任务将 DEPT/PRIVATE/GRANT 文档权限静默扩大为 INTERNAL
                    String origVisibility = task.getVisibility();
                    payload.put("visibility",
                        (origVisibility != null && !origVisibility.trim().isEmpty())
                        ? origVisibility : "INTERNAL");
                    payload.put("deptCode",
                        task.getDeptCode() != null ? task.getDeptCode() : "");
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

    /**
     * 清理 ES 中该文档的旧 chunk（将 is_latest 置为 false）。
     * 在重试任务前调用，防止新旧 chunk 并存。
     * 容错策略：ES 操作失败时仅记录 warn，不中断重推流程。
     *
     * @param sourceName 文档名称（对应 ES metadata.source 字段）
     */
    private void cleanupOldChunks(String sourceName) {
        try {
            UpdateByQueryRequest req = UpdateByQueryRequest.of(r -> r
                .index(INDEX_PATTERN)
                .query(q -> q.term(t -> t.field("metadata.source").value(sourceName)))
                .script(s -> s.inline(i -> i
                    .lang("painless")
                    .source("ctx._source.metadata.is_latest = false")
                ))
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            );
            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse resp =
                esClient.updateByQuery(req);
            log.info("[Recovery] ES 旧 chunk 清理完成 source='{}' updated={}", sourceName, resp.updated());
        } catch (Exception e) {
            log.warn("[Recovery] ES 旧 chunk 清理失败（不影响重推） source='{}' err={}", sourceName, e.getMessage());
        }
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
