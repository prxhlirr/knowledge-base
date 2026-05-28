package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.json.JsonData;
import com.boyang.search.entity.KbAclProjectionTask;
import com.boyang.search.mapper.KbAclProjectionTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Keeps Elasticsearch ACL token projections in sync with MySQL ACL changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAclProjectionService {

    private final ElasticsearchClient esClient;
    private final IndexAliasResolver indexAliasResolver;
    private final KbAclProjectionTaskMapper taskMapper;

    private static final int MAX_RETRY_COUNT = 8;

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

    private boolean updateToken(String targetIndex, String sourceName, String token, boolean grant, boolean createTaskOnFailure) {
        if (isBlank(targetIndex) || isBlank(sourceName) || isBlank(token)) {
            return true;
        }
        try {
            String physicalIndex = indexAliasResolver.normalizeWriteTarget(targetIndex);
            String script = grant ? grantScript() : revokeScript();
            UpdateByQueryRequest request = new UpdateByQueryRequest.Builder()
                    .index(physicalIndex)
                    .query(q -> q.term(t -> t.field("metadata.source").value(sourceName)))
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source(script)
                            .params("token", JsonData.of(token))))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                    .refresh(true)
                    .build();
            esClient.updateByQuery(request);
            log.info("[DocACLProjection] {} token sourceName={} index={} token={}",
                    grant ? "grant" : "revoke", sourceName, physicalIndex, token);
            return true;
        } catch (Exception e) {
            log.warn("[DocACLProjection] token projection failed sourceName={} token={} grant={} err={}",
                    sourceName, token, grant, e.getMessage());
            if (createTaskOnFailure) {
                createRetryTask(targetIndex, sourceName, token, grant, e.getMessage());
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

    private String grantScript() {
        return "if (ctx._source.acl_tokens == null) { ctx._source.acl_tokens = new ArrayList(); } " +
               "if (!ctx._source.acl_tokens.contains(params.token)) { ctx._source.acl_tokens.add(params.token); } " +
               "if (ctx._source.metadata != null) { " +
               "  if (ctx._source.metadata.acl_tokens == null) { ctx._source.metadata.acl_tokens = new ArrayList(); } " +
               "  if (!ctx._source.metadata.acl_tokens.contains(params.token)) { ctx._source.metadata.acl_tokens.add(params.token); } " +
               "}";
    }

    private String revokeScript() {
        return "if (ctx._source.acl_tokens != null) { while (ctx._source.acl_tokens.remove(params.token)) {} } " +
               "if (ctx._source.metadata != null && ctx._source.metadata.acl_tokens != null) { " +
               "  while (ctx._source.metadata.acl_tokens.remove(params.token)) {} " +
               "}";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
