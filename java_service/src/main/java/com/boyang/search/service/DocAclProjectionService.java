package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.json.JsonData;
import com.boyang.search.entity.KbAclProjectionTask;
import com.boyang.search.mapper.KbAclProjectionTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps Elasticsearch ACL token projections in sync with MySQL ACL changes.
 *
 * 架构说明：
 *   ACL 变更时需同步更新三个 ES 索引：
 *   1. chunk 索引（kb_document_*）：通过 metadata.source 匹配，同时更新顶层和 metadata.acl_tokens
 *   2. doc_meta 索引（kb_doc_meta_write）：通过 source 匹配，仅更新顶层 acl_tokens
 *   3. doc_search 索引（kb_doc_search_write）：通过 source 匹配，仅更新顶层 acl_tokens
 *   4. QA 索引（kb_qa_write）：通过 source 匹配，仅更新顶层 acl_tokens
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
    private static final String TASK_TYPE_TOKEN_DELTA = "TOKEN_DELTA";
    private static final String TASK_TYPE_ACL_TOKENS_SYNC = "ACL_TOKENS_SYNC";
    private static final String TASK_TYPE_UNIT_SYNC = "UNIT_SYNC";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${editor.similarity.meta-write-index:kb_doc_meta_write}")
    private String docMetaWriteIndex;

    @Value("${search.doc-search.write-index:${KB_DOC_SEARCH_WRITE_ALIAS:kb_doc_search_write}}")
    private String docSearchWriteIndex;

    @Value("${search.qa.write-index:${KB_QA_WRITE_ALIAS:kb_qa_write}}")
    private String qaWriteIndex;

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
        String taskType = task.getTaskType() == null || task.getTaskType().trim().isEmpty()
                ? TASK_TYPE_TOKEN_DELTA
                : task.getTaskType().trim().toUpperCase();
        if (TASK_TYPE_ACL_TOKENS_SYNC.equals(taskType)) {
            return retryAclTokensSyncTask(task);
        }
        if (TASK_TYPE_UNIT_SYNC.equals(taskType)) {
            return retryUnitSyncTask(task);
        }
        boolean grant = "GRANT".equalsIgnoreCase(task.getOperation());
        boolean ok = updateToken(task.getTargetIndex(), task.getSourceName(), task.getAclToken(), grant, false);
        finishRetryTask(task, ok, task.getLastError());
        return ok;
    }

    /**
     * 将 ACL token 变更同步到所有检索相关 ES 索引。
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

        // 4. QA 索引更新（辅助索引，使用 source 匹配，仅更新顶层 acl_tokens）
        updateTokenSingleIndex(
                qaWriteIndex, sourceName, token, grant, "source",
                topLevelScript, false, false);

        return chunkOk;
    }

    /**
     * 业务功能：同步文档单位权限投影到所有检索相关 ES 索引。
     * 关键流程：按 sourceName 定位文档，在 chunk/doc_meta/doc_search/QA 中统一写入 owner_unit_code、
     *          visible_unit_codes、permission_version。
     * 设计原因：MySQL 是权限权威来源，ES 只是召回加速投影；单位归属变化后若只改 MySQL，
     *          ES 前置过滤、QA 召回和相似文档会出现漏召回或脏召回。
     *
     * @param targetIndex chunk 物理索引或写别名
     * @param sourceName 文档 source 标识
     * @param ownerUnitCode 文档归属单位
     * @param visibleUnitCodes 可见单位链
     * @param permissionVersion 权限投影版本
     * @return chunk 主索引是否同步成功；当前任务表无法表达完整单位链，失败时先记录日志
     */
    public boolean syncUnitProjection(String targetIndex, String sourceName,
                                      String ownerUnitCode, List<String> visibleUnitCodes,
                                      long permissionVersion) {
        return syncUnitProjectionInternal(targetIndex, sourceName, ownerUnitCode, visibleUnitCodes,
                permissionVersion, true);
    }

    private boolean syncUnitProjectionInternal(String targetIndex, String sourceName,
                                               String ownerUnitCode, List<String> visibleUnitCodes,
                                               long permissionVersion, boolean createTaskOnFailure) {
        if (isBlank(targetIndex) || isBlank(sourceName)) {
            return true;
        }
        String chunkScript = unitChunkScript();
        boolean chunkOk = updateUnitSingleIndex(
                targetIndex, sourceName, ownerUnitCode, visibleUnitCodes, permissionVersion,
                "metadata.source", chunkScript, false, true);

        String topLevelScript = unitTopLevelScript();
        boolean metaOk = updateUnitSingleIndex(
                docMetaWriteIndex, sourceName, ownerUnitCode, visibleUnitCodes, permissionVersion,
                "source", topLevelScript, false, false);
        boolean searchOk = updateUnitSingleIndex(
                docSearchWriteIndex, sourceName, ownerUnitCode, visibleUnitCodes, permissionVersion,
                "source", topLevelScript, false, false);
        boolean qaOk = updateUnitSingleIndex(
                qaWriteIndex, sourceName, ownerUnitCode, visibleUnitCodes, permissionVersion,
                "source", topLevelScript, false, false);
        boolean allOk = chunkOk && metaOk && searchOk && qaOk;
        if (!allOk && createTaskOnFailure) {
            createProjectionRetryTask(targetIndex, sourceName, TASK_TYPE_UNIT_SYNC,
                    unitPayload(ownerUnitCode, visibleUnitCodes, permissionVersion),
                    "unit projection failed in one or more indexes");
        }
        return allOk;
    }

    /**
     * 业务功能：把文档当前完整 ACL token 集合覆盖同步到所有检索相关 ES 索引。
     * 关键流程：按 sourceName 定位文档，在 chunk/doc_meta/doc_search/QA 中统一替换 acl_tokens。
     * 设计原因：visibility/deptCode 变更不是单个 token 的增删，而是权限全集重建；
     *          使用覆盖写入可以避免 PUBLIC → DEPT 或 DEPT → PRIVATE 后残留旧 token。
     *
     * @param targetIndex chunk 物理索引或写别名
     * @param sourceName 文档 source 标识
     * @param aclTokens 当前完整可访问 token 集合
     * @return chunk 主索引是否同步成功；当前重试表不能表达 token 列表，失败时记录日志
     */
    public boolean syncAclTokensProjection(String targetIndex, String sourceName, List<String> aclTokens) {
        return syncAclTokensProjectionInternal(targetIndex, sourceName, aclTokens, true);
    }

    private boolean syncAclTokensProjectionInternal(String targetIndex, String sourceName,
                                                   List<String> aclTokens, boolean createTaskOnFailure) {
        if (isBlank(targetIndex) || isBlank(sourceName)) {
            return true;
        }
        List<String> safeTokens = aclTokens != null ? aclTokens : Collections.emptyList();
        // chunk 投影直接打读别名 kb_document（覆盖所有 kb_document_*_v2 chunk 索引，靠 metadata.source 过滤），
        // 不再走 normalizeWriteTarget(targetIndex)——后者对逻辑名（如 kb_document_law）会返回同名 0 条空壳
        // 遗留具体索引，导致 chunk 的 acl_tokens 投影 0 命中。doc_meta/doc_search/qa 三路用各自写别名不受影响。
        boolean chunkOk = updateAclTokensSingleIndex(
                IndexAliasResolver.DOCUMENT_READ_ALIAS, sourceName, safeTokens, "metadata.source", aclTokensChunkScript(), false);

        String topLevelScript = aclTokensTopLevelScript();
        boolean metaOk = updateAclTokensSingleIndex(docMetaWriteIndex, sourceName, safeTokens, "source", topLevelScript, false);
        boolean searchOk = updateAclTokensSingleIndex(docSearchWriteIndex, sourceName, safeTokens, "source", topLevelScript, false);
        boolean qaOk = updateAclTokensSingleIndex(qaWriteIndex, sourceName, safeTokens, "source", topLevelScript, false);
        boolean allOk = chunkOk && metaOk && searchOk && qaOk;
        if (!allOk && createTaskOnFailure) {
            createProjectionRetryTask(targetIndex, sourceName, TASK_TYPE_ACL_TOKENS_SYNC,
                    aclTokensPayload(safeTokens), "acl tokens projection failed in one or more indexes");
        }
        return allOk;
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

    private boolean updateUnitSingleIndex(String index, String sourceName,
                                          String ownerUnitCode, List<String> visibleUnitCodes,
                                          long permissionVersion, String sourceField,
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
                            .params("ownerUnitCode", JsonData.of(ownerUnitCode))
                            .params("visibleUnitCodes", JsonData.of(visibleUnitCodes))
                            .params("permissionVersion", JsonData.of(permissionVersion))))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                    .refresh(true)
                    .build();
            esClient.updateByQuery(request);
            log.info("[DocACLProjection] sync unit sourceName={} index={} field={}",
                    sourceName, physicalIndex, sourceField);
            return true;
        } catch (Exception e) {
            log.warn("[DocACLProjection] unit projection failed sourceName={} index={} err={}",
                    sourceName, index, e.getMessage());
            if (createTaskOnFailure) {
                createRetryTask(index, sourceName, "__UNIT_PROJECTION__", true, e.getMessage());
            }
            return false;
        }
    }

    private boolean updateAclTokensSingleIndex(String index, String sourceName, List<String> aclTokens,
                                               String sourceField, String script, boolean resolveAlias) {
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
                            .params("aclTokens", JsonData.of(aclTokens))))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                    .refresh(true)
                    .build();
            esClient.updateByQuery(request);
            log.info("[DocACLProjection] sync acl tokens sourceName={} index={} field={} tokenCount={}",
                    sourceName, physicalIndex, sourceField, aclTokens.size());
            return true;
        } catch (Exception e) {
            log.warn("[DocACLProjection] acl token projection failed sourceName={} index={} err={}",
                    sourceName, index, e.getMessage());
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
            task.setTaskType(TASK_TYPE_TOKEN_DELTA);
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

    /**
     * 业务功能：创建可重放的完整权限投影补偿任务。
     * 关键流程：保留旧表的 acl_token/operation 非空约束，用 task_type/payload_json 表达新任务语义。
     * 设计原因：完整 token 覆盖和单位链同步都不是单 token 增删，必须保存完整 payload 才能安全重试。
     */
    private void createProjectionRetryTask(String targetIndex, String sourceName,
                                           String taskType, Map<String, Object> payload,
                                           String error) {
        try {
            KbAclProjectionTask task = new KbAclProjectionTask();
            task.setSourceName(sourceName);
            task.setTargetIndex(targetIndex);
            task.setAclToken("__" + taskType + "__");
            task.setOperation("SYNC");
            task.setTaskType(taskType);
            task.setPayloadJson(OBJECT_MAPPER.writeValueAsString(payload));
            task.setStatus("PENDING");
            task.setRetryCount(0);
            task.setNextRetryAt(LocalDateTime.now().plusMinutes(1));
            task.setLastError(truncate(error));
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.insert(task);
        } catch (Exception taskError) {
            log.warn("[DocACLProjection] create projection retry task failed sourceName={} type={} err={}",
                    sourceName, taskType, taskError.getMessage());
        }
    }

    private boolean retryAclTokensSyncTask(KbAclProjectionTask task) {
        try {
            Map<String, Object> payload = parsePayload(task);
            List<String> aclTokens = readStringList(payload.get("aclTokens"));
            boolean ok = syncAclTokensProjectionInternal(
                    task.getTargetIndex(), task.getSourceName(), aclTokens, false);
            finishRetryTask(task, ok, ok ? null : "ACL tokens projection retry failed");
            return ok;
        } catch (Exception e) {
            finishRetryTask(task, false, e.getMessage());
            return false;
        }
    }

    private boolean retryUnitSyncTask(KbAclProjectionTask task) {
        try {
            Map<String, Object> payload = parsePayload(task);
            String ownerUnitCode = stringValue(payload.get("ownerUnitCode"));
            List<String> visibleUnitCodes = readStringList(payload.get("visibleUnitCodes"));
            long permissionVersion = longValue(payload.get("permissionVersion"));
            boolean ok = syncUnitProjectionInternal(
                    task.getTargetIndex(), task.getSourceName(), ownerUnitCode,
                    visibleUnitCodes, permissionVersion, false);
            finishRetryTask(task, ok, ok ? null : "Unit projection retry failed");
            return ok;
        } catch (Exception e) {
            finishRetryTask(task, false, e.getMessage());
            return false;
        }
    }

    private void finishRetryTask(KbAclProjectionTask task, boolean ok, String error) {
        if (ok) {
            task.setStatus("SUCCESS");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        } else {
            markTaskFailed(task, error);
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

    private Map<String, Object> aclTokensPayload(List<String> aclTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aclTokens", aclTokens != null ? aclTokens : Collections.emptyList());
        return payload;
    }

    private Map<String, Object> unitPayload(String ownerUnitCode, List<String> visibleUnitCodes,
                                            long permissionVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerUnitCode", ownerUnitCode);
        payload.put("visibleUnitCodes", visibleUnitCodes != null ? visibleUnitCodes : Collections.emptyList());
        payload.put("permissionVersion", permissionVersion);
        return payload;
    }

    private Map<String, Object> parsePayload(KbAclProjectionTask task) throws Exception {
        if (task.getPayloadJson() == null || task.getPayloadJson().trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return OBJECT_MAPPER.readValue(task.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rawList = (List<?>) value;
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object item : rawList) {
            if (item != null && !item.toString().trim().isEmpty()) {
                result.add(item.toString().trim());
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception ignored) {
            return 0L;
        }
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

    /**
     * Chunk 单位投影脚本：顶层和 metadata 同步更新。
     */
    private String unitChunkScript() {
        return "ctx._source.owner_unit_code = params.ownerUnitCode; " +
               "ctx._source.visible_unit_codes = params.visibleUnitCodes; " +
               "ctx._source.permission_version = params.permissionVersion; " +
               "if (ctx._source.metadata != null) { " +
               "  ctx._source.metadata.owner_unit_code = params.ownerUnitCode; " +
               "  ctx._source.metadata.visible_unit_codes = params.visibleUnitCodes; " +
               "  ctx._source.metadata.permission_version = params.permissionVersion; " +
               "}";
    }

    /**
     * 辅助索引单位投影脚本：doc_meta/doc_search/QA 均为顶层字段。
     */
    private String unitTopLevelScript() {
        return "ctx._source.owner_unit_code = params.ownerUnitCode; " +
               "ctx._source.visible_unit_codes = params.visibleUnitCodes; " +
               "ctx._source.permission_version = params.permissionVersion;";
    }

    /**
     * Chunk ACL 覆盖脚本：顶层和 metadata 保持一致，避免召回与展示读取不同路径时产生歧义。
     */
    private String aclTokensChunkScript() {
        return "ctx._source.acl_tokens = params.aclTokens; " +
               "if (ctx._source.metadata != null) { ctx._source.metadata.acl_tokens = params.aclTokens; }";
    }

    /**
     * 辅助索引 ACL 覆盖脚本：doc_meta/doc_search/QA 均使用顶层 acl_tokens。
     */
    private String aclTokensTopLevelScript() {
        return "ctx._source.acl_tokens = params.aclTokens;";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
