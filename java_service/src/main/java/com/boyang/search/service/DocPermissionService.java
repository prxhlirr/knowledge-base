package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.DocPermissionEvent;
import com.boyang.search.entity.DocVersionHistory;
import com.boyang.search.mapper.DocPermissionEventMapper;
import com.boyang.search.mapper.DocVersionHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档权限事件服务层。
 * 业务功能：管理文档权限变更事件的写入和版本历史记录，以及 ES 权限元数据的同步。
 * 关键流程：
 *   1. 入库完成后，Python 侧调用 recordIngestion() 写入版本记录和授权事件。
 *   2. 文档经手时，调用 addHandlerEvent() 追加经手人事件。
 *   3. 可见度变更时，调用 addVisibilityChange()：
 *      - 追加 MySQL 变更事件（审计日志，不可变）
 *      - 同步更新 ES 文档 chunk 中的 metadata.visibility（P0.1 修复）
 *      - 触发 SearchCacheService 清除相关缓存（P2.11 修复）
 * 设计原则：事件只追加不修改，所有历史均可回溯；ES 和 MySQL 保持最终一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocPermissionService extends ServiceImpl<DocPermissionEventMapper, DocPermissionEvent> {

    private final DocPermissionEventMapper eventMapper;
    private final DocVersionHistoryMapper  versionMapper;
    private final ElasticsearchClient      esClient;
    private final SearchCacheService       searchCacheService;

    /** 默认索引模式，与 SearchUtil.getPolicy() 保持一致 */
    private static final String DEFAULT_INDEX_PATTERN = "kb_document_v*";

    /**
     * 文档入库完成后写入版本历史和初始权限事件。
     * 流程：插入版本历史 → 生成 HANDLER 事件 → 生成 GRANT DEPT 事件（DEPT 类型时）
     *
     * @param sourceName  文档名称（对应 ES metadata.source）
     * @param docVersion  本次入库版本号
     * @param contentHash 正文前 2000 字 MD5
     * @param chunkCount  切片总数
     * @param uploaderId  上传者ID
     * @param visibility  可见度（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT）
     * @param deptCode    文档归属部门编码（12位，DEPT 类型时非空）
     */
    @Transactional
    public void recordIngestion(String sourceName, int docVersion, String contentHash,
                                int chunkCount, String uploaderId,
                                String visibility, String deptCode) {
        // 幂等保护：同名文档同版本号已存在时跳过插入，避免重复回调触发唯一约束异常
        Integer existingMax = versionMapper.findMaxVersion(sourceName);
        boolean alreadyRecorded = existingMax != null && existingMax >= docVersion;
        if (!alreadyRecorded) {
            DocVersionHistory history = new DocVersionHistory();
            history.setSourceName(sourceName);
            history.setDocVersion(docVersion);
            history.setContentHash(contentHash);
            history.setChunkCount(chunkCount);
            history.setOperatorId(uploaderId);
            history.setVisibility(visibility);
            history.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(history);
        } else {
            System.out.printf("[PermEvent] 幂等跳过：'%s' v%d 版本记录已存在%n", sourceName, docVersion);
        }

        if (uploaderId != null && !uploaderId.trim().isEmpty()) {
            addEvent(sourceName, "HANDLER", "USER", uploaderId, uploaderId, "文档入库操作人");
        }
        if ("DEPT".equals(visibility) && deptCode != null && !deptCode.trim().isEmpty()) {
            addEvent(sourceName, "GRANT", "DEPT", deptCode, uploaderId, "入库时按部门授权");
        }
        System.out.printf("[PermEvent] 文档 '%s' v%d 权限事件已记录%n", sourceName, docVersion);
    }

    /**
     * 追加经手人事件（文档被新的用户/部门操作时调用）。
     */
    @Transactional
    public void addHandlerEvent(String docId, String handlerId, String handlerDept, String operatorId) {
        if (handlerId != null) addEvent(docId, "HANDLER", "USER", handlerId, operatorId, "文档经手记录");
        if (handlerDept != null) addEvent(docId, "HANDLER", "DEPT", handlerDept, operatorId, "经手人所在部门");
    }

    /**
     * 变更文档可见度（B-2 修复：ES 同步失败时写入补偿队列，保证最终一致性）。
     * 核心安全约束：可见度变更必须在 ES 中同步生效，否则搜索层会用旧 visibility 过滤，
     *              导致用户仍能搜到权限已变更的文档（权限绕过漏洞）。
     * 修复方案：ES 更新失败时，将补偿任务写入 Redis 队列（es:sync:pending），
     *           由 EsSyncRetryJob 定时重试，直至 ES 与 MySQL 一致。
     */
    @Transactional
    public void addVisibilityChange(String docId, String newVisibility, String operatorId) {
        addEvent(docId, "VISIBILITY_CHANGE", "USER", operatorId, operatorId,
                 "可见度变更 → " + newVisibility);
        log.info("[Visibility] docId='{}' 变更 → {} by {}", docId, newVisibility, operatorId);

        boolean esOk = false;
        try {
            UpdateByQueryRequest esReq = UpdateByQueryRequest.of(r -> r
                .index(DEFAULT_INDEX_PATTERN)
                .query(q -> q.term(t -> t.field("metadata.source").value(docId)))
                .script(s -> s
                    .inline(i -> i
                        .lang("painless")
                        .source("ctx._source.metadata.visibility = params.vis")
                        .params("vis", co.elastic.clients.json.JsonData.of(newVisibility))
                    )
                )
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            );
            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse resp =
                esClient.updateByQuery(esReq);
            log.info("[Visibility] ES 同步完成 docId='{}' updated={}", docId, resp.updated());
            esOk = true;
        } catch (Exception e) {
            // [B-2 修复] ES 更新失败时写入 Redis 补偿队列，由定时任务重试（非静默忽略）
            log.error("[Visibility][B-2] ES 同步失败，写入补偿队列等待重试 docId='{}' err={}", docId, e.getMessage());
        }

        if (!esOk) {
            // 写入 Redis 补偿队列：key=es:sync:pending，value=JSON
            try {
                String pendingPayload = String.format(
                    "{\"docId\":\"%s\",\"type\":\"VISIBILITY\",\"newValue\":\"%s\",\"ts\":%d}",
                    docId.replace("\"", "\\\""), newVisibility, System.currentTimeMillis()
                );
                searchCacheService.pushEsSyncPending(pendingPayload);
                log.warn("[Visibility][B-2] 已将 ES 同步任务推入补偿队列 docId='{}'", docId);
            } catch (Exception queueErr) {
                // 补偿队列写入也失败时，降级为错误日志报警（人工介入）
                log.error("[Visibility][B-2][ALERT] 补偿队列写入失败，需人工同步 ES docId='{}' err={}",
                         docId, queueErr.getMessage());
            }
        }

        // 无论 ES 是否成功，均清除搜索缓存（缓存中旧数据比 ES 不一致问题影响更小）
        try {
            searchCacheService.invalidateByDocSource(docId);
        } catch (Exception e) {
            log.warn("[Visibility] 缓存清除失败 docId='{}' err={}", docId, e.getMessage());
        }
    }


    /**
     * 查询指定文档的完整权限历史事件链。
     */
    public List<DocPermissionEvent> getEventHistory(String docId) {
        return eventMapper.findByDocId(docId);
    }

    /**
     * 查询指定文档的版本列表。
     */
    public List<DocVersionHistory> getVersionHistory(String sourceName) {
        return versionMapper.findBySourceName(sourceName);
    }

    private void addEvent(String docId, String action, String targetType,
                          String targetValue, String operatorId, String remark) {
        DocPermissionEvent evt = new DocPermissionEvent();
        evt.setDocId(docId);
        evt.setAction(action);
        evt.setTargetType(targetType);
        evt.setTargetValue(targetValue);
        evt.setOperatorId(operatorId != null ? operatorId : "system");
        evt.setRemark(remark);
        evt.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(evt);
    }
}
