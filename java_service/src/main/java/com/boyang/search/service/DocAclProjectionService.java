package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.json.JsonData;
import com.boyang.search.entity.KbAclProjectionTask;
import com.boyang.search.mapper.KbAclProjectionTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Keeps Elasticsearch ACL token projections in sync with MySQL ACL changes.
 *
 * 架构说明：
 *   ACL 变更时需同步更新三个 ES 索引：
 *   1. chunk 索引（kb_document_*）：通过 metadata.source 匹配，同时更新顶层和 metadata.acl_tokens
 *   2. doc_meta 索引（kb_doc_meta_write）：通过 source 匹配，仅更新顶层 acl_tokens
 *   3. doc_search 索引（kb_doc_search_write）：通过 source 匹配，仅更新顶层 acl_tokens
 *
 *   chunk 索引为主索引，失败时创建 retry task；
 *   doc_meta / doc_search 为辅助索引，失败仅打 warn 日志（避免创建大量 retry task）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAclProjectionService {

    private final ElasticsearchClient esClient;
    private final IndexAliasResolver indexAliasResolver;
    private final KbAclProjectionTaskMapper taskMapper;

    private static final int MAX_RETRY_COUNT = 8;

    @Value("${editor.similarity.meta-write-index:kb_doc_meta_write}")
    private String docMetaWriteIndex;

    @Value("${search.doc-search.write-index:${KB_DOC_SEARCH_WRITE_ALIAS:kb_doc_search_write}}")
    private String docSearchWriteIndex;

    public void grantToken(String targetIndex, String sourceName, String token) {
        updateToken(targetIndex, sourceName, token, true, true);
    }

    public void revokeToken(String targetIndex, String sourceName, String token) {
        updateToken(targetIndex, sourceName, token, false, true);
    }

    public boolean retryTask(KbAclProjectionTask task) {
        if (task == null) {
            return true;
        }
        boolean grant = "GRANT".equalsIgnoreCase(task.getOperation());
        boolean ok = updateToken(task.getTargetIndex(), task.getSourceName(), task.getAclToken(), grant, false);
        if (ok) {
            task.setStatus("SUCCESS");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        } else {
            markTaskFailed(task, task.getLastError());
        }
        return ok;
    }

    /**
     * 将 ACL token 变更同步到所有三个 ES 索引。
     *
     * @param targetIndex        chunk 索引（kb_document_*）
     * @param sourceName         文档 source 标识
     * @param token              ACL token
     * @param grant              true=授予，false=撤销
     * @param createTaskOnFailure chunk 索引失败时是否创建 retry task
     */
    private boolean updateToken(String targetIndex, String sourceName, String token,
                                boolean grant, boolean createTaskOnFailure) {
        if (isBlank(targetIndex) || isBlank(sourceName) || isBlank(token)) {
            return true;
        }

        // 1. Chunk 索引更新（主索引，使用 metadata.source 匹配，同时处理顶层和 metadata.acl_tokens）
        String chunkScript = grant ? grantChunkScript() : revokeChunkScript();
        boolean chunkOk = updateTokenSingleIndex(
                targetIndex, sourceName, token, grant, "metadata.source",
                chunkScript, createTaskOnFailure, true);

        // 2. doc_meta 索引更新（辅助索引，使用 source 匹配，仅更新顶层 acl_tokens）
        String topLevelScript = grant ? grantTopLevelScript() : revokeTopLevelScript();
        updateTokenSingleIndex(
                docMetaWriteIndex, sourceName, token, grant, "source",
                topLevelScript, false, false);

        // 3. doc_search 索引更新（辅助索引，使用 source 匹配，仅更新顶层 acl_tokens）
        updateTokenSingleIndex(
                docSearchWriteIndex, sourceName, token, grant, "source",
                topLevelScript, false, false);

        return chunkOk;
    }

    /**
     * 对单个 ES 索引执行 ACL token 更新。
     *
     * @param index              索引名称或别名
     * @param sourceName         文档 source 标识
     * @param token              ACL token
     * @param grant              true=授予，false=撤销
     * @param sourceField        匹配字段（chunk 索引用 "metadata.source"，doc_meta/doc_search 用 "source"）
     * @param script             Painless 脚本
     * @param createTaskOnFailure 失败时是否创建 retry task
     * @param resolveAlias       是否通过 IndexAliasResolver 解析写入目标
     */
    private boolean updateTokenSingleIndex(String index, String sourceName, String token,
                                           boolean grant, String sourceField,
                                           String script, boolean createTaskOnFailure,
                                           boolean resolveAlias) {
        try {
            String physicalIndex = resolveAlias
                    ? indexAliasResolver.normalizeWriteTarget(index)
                    : index;
            UpdateByQueryRequest request = new UpdateByQueryRequest.Builder()
                    .index(physicalIndex)
                    .query(q -> q.term(t -> t.field(sourceField).value(sourceName)))
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source(script)
                            .params("token", JsonData.of(token))))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                    .refresh(true)
                    .build();
            esClient.updateByQuery(request);
            log.info("[DocACLProjection] {} token sourceName={} index={} field={}",
                    grant ? "grant" : "revoke", sourceName, physicalIndex, sourceField);
            return true;
        } catch (Exception e) {
            log.warn("[DocACLProjection] token projection failed sourceName={} index={} token={} grant={} err={}",
                    sourceName, index, token, grant, e.getMessage());
            if (createTaskOnFailure) {
                createRetryTask(index, sourceName, token, grant, e.getMessage());
            }
            return false;
        }
    }

    private void createRetryTask(String targetIndex, String sourceName, String token, boolean grant, String error) {
        try {
            KbAclProjectionTask task = new KbAclProjectionTask();
            task.setSourceName(sourceName);
            task.setTargetIndex(targetIndex);
            task.setAclToken(token);
            task.setOperation(grant ? "GRANT" : "REVOKE");
            task.setStatus("PENDING");
            task.setRetryCount(0);
            task.setNextRetryAt(LocalDateTime.now().plusMinutes(1));
            task.setLastError(truncate(error));
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.insert(task);
        } catch (Exception taskError) {
            log.warn("[DocACLProjection] create retry task failed sourceName={} token={} err={}",
                    sourceName, token, taskError.getMessage());
        }
    }

    private void markTaskFailed(KbAclProjectionTask task, String error) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        retryCount++;
        task.setRetryCount(retryCount);
        task.setStatus(retryCount >= MAX_RETRY_COUNT ? "DEAD" : "FAILED");
        task.setNextRetryAt(LocalDateTime.now().plusMinutes(nextRetryDelayMinutes(retryCount)));
        task.setLastError(truncate(error));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private long nextRetryDelayMinutes(int retryCount) {
        if (retryCount <= 1) return 1L;
        if (retryCount == 2) return 5L;
        if (retryCount == 3) return 15L;
        return 30L;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    // ─── Painless 脚本 ─────────────────────────────────────────────────────

    /**
     * Chunk 索引授予脚本：同时更新顶层 acl_tokens 和 metadata.acl_tokens。
     * chunk 索引（kb_document_*）有两个 acl_tokens 字段路径。
     */
    private String grantChunkScript() {
        return "if (ctx._source.acl_tokens == null) { ctx._source.acl_tokens = new ArrayList(); } " +
               "if (!ctx._source.acl_tokens.contains(params.token)) { ctx._source.acl_tokens.add(params.token); } " +
               "if (ctx._source.metadata != null) { " +
               "  if (ctx._source.metadata.acl_tokens == null) { ctx._source.metadata.acl_tokens = new ArrayList(); } " +
               "  if (!ctx._source.metadata.acl_tokens.contains(params.token)) { ctx._source.metadata.acl_tokens.add(params.token); } " +
               "}";
    }

    /**
     * Chunk 索引撤销脚本：同时清理顶层 acl_tokens 和 metadata.acl_tokens。
     */
    private String revokeChunkScript() {
        return "if (ctx._source.acl_tokens != null) { while (ctx._source.acl_tokens.remove(params.token)) {} } " +
               "if (ctx._source.metadata != null && ctx._source.metadata.acl_tokens != null) { " +
               "  while (ctx._source.metadata.acl_tokens.remove(params.token)) {} " +
               "}";
    }

    /**
     * doc_meta / doc_search 授予脚本：仅更新顶层 acl_tokens（无 metadata 嵌套）。
     * kb_doc_meta 和 kb_doc_search 都是顶层字段结构，无 metadata 对象。
     */
    private String grantTopLevelScript() {
        return "if (ctx._source.acl_tokens == null) { ctx._source.acl_tokens = new ArrayList(); } " +
               "if (!ctx._source.acl_tokens.contains(params.token)) { ctx._source.acl_tokens.add(params.token); }";
    }

    /**
     * doc_meta / doc_search 撤销脚本：仅清理顶层 acl_tokens。
     */
    private String revokeTopLevelScript() {
        return "if (ctx._source.acl_tokens != null) { while (ctx._source.acl_tokens.remove(params.token)) {} }";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
