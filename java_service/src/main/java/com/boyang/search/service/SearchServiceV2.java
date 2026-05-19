package com.boyang.search.service;

import com.boyang.search.entity.SearchAuditLog;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.pipeline.steps.*;
import com.boyang.search.pipeline.steps.QaInjectionStep;
import com.boyang.search.qa.QaAnswerProperties;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.security.UserContextHolder;
import com.boyang.search.security.JwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 重构后的 V2 版本搜索服务：采用 Pipeline 架构彻底解耦 "God Object"
 *
 * [Phase 2 新增] Pipeline 末端接入后置 PermissionGuard：
 *   - 作为 ES 权限过滤的兜底防线，解决 ES Refresh Interval 延迟导致的"幽灵文档"泄露
 *   - 超级管理员身份直接跳过，普通用户逐条过 MySQL 权威数据源校验
 *   - 被过滤的文档数记录到日志，供后续接入审计链路
 */
@Service
public class SearchServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceV2.class);

    @Autowired
    private QueryNormalizeStep queryNormalizeStep;

    @Autowired
    private VectorFetchStep vectorFetchStep;

    @Autowired
    private EsRecallStep esRecallStep;

    @Autowired
    private KeywordDocumentMatchStep keywordDocumentMatchStep;

    @Autowired
    private KeywordCoarseEvidenceStep keywordCoarseEvidenceStep;

    @Autowired
    private KeywordRankStep keywordRankStep;

    @Autowired
    private KeywordResultAssembleStep keywordResultAssembleStep;

    @Autowired
    private RrfFusionStep rrfFusionStep;

    /**
     * [P0-1 修复] Q&A 注入节点：对齐 V1 fetchQaResults 逻辑，修复 "_qa_hit" 消费代码是幽灵代码的问题。
     * 在 RrfFusionStep 之后、DocExpansionStep 之前注入，确保 QA 命中得到 Veto Gate 豆免和 LTR 满分。
     */
    @Autowired
    private QaInjectionStep qaInjectionStep;

    @Autowired
    private DocExpansionStep docExpansionStep;

    @Autowired
    private RerankStep rerankStep;

    @Autowired
    private SysTenantPolicyService sysTenantPolicyService;

    @Autowired
    private SearchIndexResolver searchIndexResolver;

    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    /**
     * [Phase 2 注入] 后置权限守卫：校验 finalResult 中每条文档的权限
     * （解决 ES 延迟刷新导致的幽灵文档二次泄露）
     */
    @Autowired
    private PermissionGuard permissionGuard;

    // [P1-1 补充] 审计日志服务：对齐 V1 recordAuditLog，记录每次搜索的耐时、hits 数等性能指标
    @Autowired
    private SearchAuditLogService auditLogService;

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    /**
     * V2 统一搜索主入口（支持三种检索模式）
     *
     * 流程：
     *   1. 初始化 SearchContext（含策略与调参配置、searchMode）
     *   2. 动态组装 Pipeline：
     *      - keyword  : 跳过 VectorFetchStep（节省 200~500ms），EsRecallStep 内部走 KeywordRecallStrategy
     *      - semantic : 保留 VectorFetchStep，EsRecallStep 内部走 SemanticRecallStrategy
     *      - hybrid   : 保留 VectorFetchStep，EsRecallStep 内部走 HybridRecallStrategy（原逻辑不变）
     *   3. [Phase 2] 后置 PermissionGuard 过滤（防幽灵文档）
     *   4. 返回最终结果
     *
     * @param appCode     租户 AppCode（用于查策略表）
     * @param queryText   原始查询词
     * @param topK        后端召回上限
     * @param filters     权限过滤参数（userId / deptCode 等）
     * @param searchMode  检索模式：hybrid | keyword | semantic（null 默认为 hybrid）
     */
    public List<Map<String, Object>> hybridSearchV2(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode) throws Exception {
        return hybridSearchContext(appCode, queryText, topK, filters, searchMode, false).getFinalResult();
    }

    public SearchContext hybridSearchContext(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode) throws Exception {
        return hybridSearchContext(appCode, queryText, topK, filters, searchMode, true);
    }

    private SearchContext hybridSearchContext(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode,
            boolean enableAnswerQaRecall) throws Exception {
        long startMs = System.currentTimeMillis();
        System.out.println("====== [SearchServiceV2 Pipeline Start] ======");

        SysTenantPolicy policy = sysTenantPolicyService.getByAppCode(appCode);
        if (policy == null) {
            throw new IllegalArgumentException("无效的 AppCode: " + appCode);
        }
        
        SysAiTuningConfig config = tuningConfigService.getGlobalConfig();
        if (config == null) {
            config = new SysAiTuningConfig();
        }

        // 1. 初始化贯穿全局的执行上下文
        SearchContext context = new SearchContext();
        context.setAppCode(appCode);
        context.setQueryText(queryText);
        context.setTopK(topK);
        context.setFilters(filters);
        context.setTenantPolicy(policy);
        context.setResolvedIndexPattern(searchIndexResolver.resolve(policy, filters));
        context.setTuningConfig(config);
        context.setStartTime(startMs);
        context.setEvidencePrefetchEnabled(enableAnswerQaRecall);
        // 写入 searchMode（null 安全：保底为 hybrid，确保向后兼容）
        context.setSearchMode(searchMode != null && !searchMode.isEmpty() ? searchMode : "hybrid");

        // 2. 动态组装 Pipeline
        // keyword 模式不需要向量，直接跳过 VectorFetchStep，节省 200~500ms 的 Python HTTP 调用延迟。
        // semantic / hybrid 模式需要稠密&稀疏向量，保留 VectorFetchStep。
        // EsRecallStep 内部通过 Strategy Pattern 根据 context.searchMode 选择召回路径。
        final boolean needVector = !"keyword".equals(context.getSearchMode());
        SearchPipelineStep[] pipeline = needVector
            ? new SearchPipelineStep[]{
                queryNormalizeStep,
                vectorFetchStep,   // semantic / hybrid 需要向量
                esRecallStep,
                rrfFusionStep,
                // [TEMP] 普通检索暂时禁用 QA 问答结果进入评分链路
                // qaInjectionStep,
                docExpansionStep,
                rerankStep
            }
            : new SearchPipelineStep[]{
                queryNormalizeStep,
                                   // keyword 模式：跳过 VectorFetchStep
                esRecallStep,
                keywordDocumentMatchStep,
                keywordCoarseEvidenceStep,
                keywordRankStep,
                keywordResultAssembleStep
            };

        log.info("[SearchServiceV2] mode={} needVector={} topK={} index={}",
                context.getSearchMode(), needVector, topK, context.getResolvedIndexPattern());

        for (SearchPipelineStep step : pipeline) {
            long stepStart = System.currentTimeMillis();
            step.execute(context);
            long stepMs = System.currentTimeMillis() - stepStart;
            context.getTimings().put(timingKey(step), stepMs);
            System.out.println("  [V2-Trace] Step " + step.getClass().getSimpleName()
                + " finish in " + stepMs + " ms.");
            if (enableAnswerQaRecall && step == vectorFetchStep) {
                startAnswerQaRecall(context);
            }
        }
        if (enableAnswerQaRecall) {
            collectAnswerQaRecall(context);
        }

        System.out.println("====== [SearchServiceV2 Pipeline End] Total Time: " + (System.currentTimeMillis() - startMs) + " ms ======");

        // 3. [Phase 2] 后置 PermissionGuard 兜底过滤
        //    目的：ES filter 基于索引数据（有延迟刷新），PermissionGuard 基于 MySQL（强一致）
        //    超管：直接跳过，不强制逐条查 MySQL（避免额外数据库压力）
        //    普通用户：逐条验证，过滤掉因 ES 延迟而混入的"幽灵文档"
        long permissionStart = System.currentTimeMillis();
        List<Map<String, Object>> finalResult = applyPostPermissionFilter(context.getFinalResult());
        context.getTimings().put("permission_filter_ms", System.currentTimeMillis() - permissionStart);
        context.setFinalResult(finalResult);

        // [P1-1 修复] 审计日志记录：对齐 V1 recordAuditLog 调用时机和字段
        // 使用异步写入（saveAsync）不将 IO 贺加在请求链路上
        try {
            SearchAuditLog auditLog = new SearchAuditLog();
            auditLog.setAppCode(appCode);
            auditLog.setQueryText(queryText);
            auditLog.setNormalizedQuery(context.getNormalizedQuery());
            auditLog.setTopHitsCount(finalResult != null ? finalResult.size() : 0);
            auditLog.setTotalCostMs((int)(System.currentTimeMillis() - startMs));
            auditLog.setCreateTime(LocalDateTime.now());
            auditLogService.saveAsync(auditLog);
        } catch (Exception e) {
            log.warn("[SearchServiceV2] 审计日志写入失败（不影响主链路）: {}", e.getMessage());
        }

        return context;
    }

    private void startAnswerQaRecall(SearchContext context) {
        if (context == null || "keyword".equals(context.getSearchMode())) {
            return;
        }
        final List<Double> denseVector = context.getQueryVector();
        final String queryText = context.getQueryText();
        final JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        CompletableFuture<List<Map<String, Object>>> future = CompletableFuture.supplyAsync(() -> {
            UserContextHolder.setIdentity(identity);
            long startMs = System.currentTimeMillis();
            try {
                List<Map<String, Object>> hits;
                if (denseVector != null && !denseVector.isEmpty()) {
                    hits = aiEngineGateway.fetchQaResults(denseVector, queryText, null,
                            Math.max(1, Math.min(10, context.getTopK())));
                } else {
                    hits = aiEngineGateway.fetchQaResultsByBm25(queryText, null,
                            Math.max(1, Math.min(10, context.getTopK())));
                }
                context.setAnswerQaRecallMs(System.currentTimeMillis() - startMs);
                context.getTimings().put("answer_qa_recall_ms", context.getAnswerQaRecallMs());
                return hits == null ? Collections.emptyList() : hits;
            } catch (Exception e) {
                context.setAnswerQaRecallMs(System.currentTimeMillis() - startMs);
                context.getTimings().put("answer_qa_recall_ms", context.getAnswerQaRecallMs());
                context.setAnswerQaRecallSkippedReason("error");
                log.warn("[SearchServiceV2] answer QA recall failed: {}", e.getMessage());
                return Collections.emptyList();
            } finally {
                UserContextHolder.clear();
            }
        });
        context.setAnswerQaHitsFuture(future);
    }

    private void collectAnswerQaRecall(SearchContext context) {
        if (context == null || context.getAnswerQaHitsFuture() == null) {
            return;
        }
        long waitStart = System.currentTimeMillis();
        try {
            int waitBudgetMs = qaAnswerProperties.isQaRecallRequired()
                    ? Math.max(1, qaAnswerProperties.getQaRecallTimeoutMs())
                    : Math.max(1, qaAnswerProperties.getQaRecallWaitBudgetMs());
            List<Map<String, Object>> hits = context.getAnswerQaHitsFuture().get(waitBudgetMs, TimeUnit.MILLISECONDS);
            context.setAnswerQaRecallWaitMs(System.currentTimeMillis() - waitStart);
            context.getTimings().put("answer_qa_recall_wait_ms", context.getAnswerQaRecallWaitMs());
            context.setAnswerQaHits(hits == null ? Collections.emptyList() : hits);
            context.setAnswerQaRecallCompleted(true);
        } catch (Exception e) {
            context.getAnswerQaHitsFuture().cancel(true);
            context.setAnswerQaHits(Collections.emptyList());
            context.setAnswerQaRecallWaitMs(System.currentTimeMillis() - waitStart);
            context.getTimings().put("answer_qa_recall_wait_ms", context.getAnswerQaRecallWaitMs());
            context.setAnswerQaRecallCompleted(false);
            context.setAnswerQaRecallSkippedReason(e instanceof java.util.concurrent.TimeoutException ? "wait_budget_timeout" : "collect_failed");
            log.warn("[SearchServiceV2] answer QA recall timed out/skipped: {}", e.getMessage());
        }
    }

    private String timingKey(SearchPipelineStep step) {
        if (step == vectorFetchStep) return "vector_ms";
        if (step == esRecallStep) return "es_recall_ms";
        if (step == rrfFusionStep) return "rrf_fusion_ms";
        if (step == docExpansionStep) return "doc_expansion_ms";
        if (step == rerankStep) return "rerank_ms";
        if (step == keywordDocumentMatchStep) return "keyword_doc_match_ms";
        if (step == keywordCoarseEvidenceStep) return "keyword_coarse_evidence_ms";
        if (step == keywordRankStep) return "keyword_rank_ms";
        if (step == keywordResultAssembleStep) return "keyword_result_assemble_ms";
        return step.getClass().getSimpleName() + "_ms";
    }

    /**
     * 后置权限过滤器（Pipeline 末端安全兜底）。
     *
     * 核心逻辑：
     *   1. 取当前线程的 UserIdentity（由 JwtAuthInterceptor 拦截器注入）
     *   2. 超级管理员（isSuperAdmin=true）→ 直接返回全量结果，仅打印日志
     *   3. 普通用户 → 逐条查 PermissionGuard（MySQL 强一致）过滤无权文档
     *
     * [P0-2 修复] 校验维度从 organization（机构名）改为 doc_id（文档唯一标识）。
     * 根因：organization = metadata.source（发布机构名称），同一机构可能发布多个权限不同的文档；
     *       用机构名做 PermissionGuard 的 sourceName 参数，实际粒度是「机构级」而非「文档级」，
     *       导致同一机构的高权限文档可能使低权限文档被误放行。
     * 修复：取 doc_id（写入 RerankStep 的 extracted_doc_id，是 kb_doc_registry.source_name
     *       的原始值），作为 PermissionGuard.canAccess() 的查询键。
     *
     * @param rawResult Pipeline 输出的原始结果集
     * @return 经权限过滤后的安全结果集
     */
    private List<Map<String, Object>> applyPostPermissionFilter(List<Map<String, Object>> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return rawResult;
        }

        // 从当前线程取用户身份（由 JwtAuthInterceptor 拦截器注入）
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();

        // 超管身份直接放行，不做逐条 MySQL 校验（降低数据库压力）
        if (identity != null && identity.isSuperAdmin()) {
            log.debug("[PostPermFilter] 超管身份，跳过后置过滤 resultSize={}", rawResult.size());
            return rawResult;
        }

        List<Map<String, Object>> safeResult = new ArrayList<>();
        int deniedCount = 0;

        for (Map<String, Object> doc : rawResult) {
            // [P0-2 修复] PermissionGuard.canAccess 要求传入的是文档的 sourceName (即文件名)
            // 在 RerankStep 中，把 metadata.source 映射到了 "organization" 或 "file_name"
            String organization = (String) doc.getOrDefault("file_name", doc.getOrDefault("organization", ""));
            String docIdHash = (String) doc.get("doc_id");

            // 用 organization(sourceName) 作为权限查询主键
            String guardKey = (organization != null && !organization.isEmpty()) ? organization : null;

            if (guardKey == null) {
                // 权限校验缺少文档主键时必须 fail-closed，避免无标识结果越权泄露。
                deniedCount++;
                log.warn("[PostPermFilter] organization/file_name 为空，无法校验权限，拒绝返回 doc_id='{}'" , docIdHash);
                continue;
            }

            PermissionGuard.AccessResult ar = permissionGuard.canAccess(guardKey, identity);
            if (ar.isAllowed()) {
                safeResult.add(doc);
            } else {
                deniedCount++;
                // deniedCount > 0 说明 ES 权限过滤出现遗漏（幽灵文档），需关注触发修复
                log.warn("[PostPermFilter] 幽灵文档已被后置过滤 docId='{}' org='{}' reason='{}' userId='{}'",
                         guardKey, organization, ar.getDenyReason(),
                         identity != null ? identity.getUserId() : "anonymous");
            }
        }

        if (deniedCount > 0) {
            log.warn("[PostPermFilter] ⚠️ 后置过滤拦截了 {} 条文档（ES延迟一致性导致），请排查 acl_tokens 数据质量",
                     deniedCount);
        }

        return safeResult;
    }
}
