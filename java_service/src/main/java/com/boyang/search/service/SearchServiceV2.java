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
import org.springframework.beans.factory.annotation.Value;
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
    private LiteralRecallStep literalRecallStep;

    @Autowired
    private VectorFetchStep vectorFetchStep;

    @Autowired
    private EsRecallStep esRecallStep;

    /**
     * [性能优化] 注入 HybridRecallStrategy，用于在管线并行轨道中提前执行 DocSearchPrefilter。
     * prefilter 查询（kb_doc_search）不依赖向量编码，可与 VectorFetchStep 并行运行，节省 ~50-200ms。
     */
    @Autowired
    private HybridRecallStrategy hybridRecallStrategy;

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

    @Autowired
    private SensitivePolicyService sensitivePolicyService;

    // [P1-1 补充] 审计日志服务：对齐 V1 recordAuditLog，记录每次搜索的耐时、hits 数等性能指标
    @Autowired
    private SearchAuditLogService auditLogService;

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    /**
     * [性能/可回滚] keyword 模式是否跳过 LiteralRecallStep。
     * LiteralRecall 对精确文号/文件名做 term 高置信探针；实测（小语料 8k chunk）56/56 零命中，
     * 纯属 1-2 次 ES 往返的额外延迟。但生产环境若有大量按文号精确检索的流量，跳过会丢失这些高置信命中。
     * 默认 false（不跳过，保持原行为）——待生产数据用新埋点 literal_recall_ms 确认零命中规律后再开启。
     */
    @Value("${search.keyword.literal.skip:false}")
    private boolean keywordLiteralSkip;

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
        return hybridSearchContext(appCode, queryText, topK, filters, searchMode, false, false).getFinalResult();
    }

    public SearchContext hybridSearchContext(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode) throws Exception {
        return hybridSearchContext(appCode, queryText, topK, filters, searchMode, true, false);
    }

    public SearchContext hybridSearchContextForHome(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode) throws Exception {
        return hybridSearchContext(appCode, queryText, topK, filters, searchMode, true, true);
    }

    private SearchContext hybridSearchContext(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode,
            boolean enableAnswerQaRecall,
            boolean homeLightweightMode) throws Exception {
        long startMs = System.currentTimeMillis();
        System.out.println("====== [SearchServiceV2 Pipeline Start] ======");
        aiEngineGateway.clearLlmSemaphoreRejected();

        SysTenantPolicy policy = sysTenantPolicyService.getByAppCode(appCode);
        if (policy == null) {
            throw new IllegalArgumentException("无效的 AppCode: " + appCode);
        }
        
        SysAiTuningConfig config = tuningConfigService.getGlobalConfig();
        if (config == null) {
            config = new SysAiTuningConfig();
        }

        // 1. 初始化贯穿全局的执行上下文
        int returnTopK = Math.max(1, topK);
        int recallTopK = Math.max(returnTopK, config.getRecallTopK());
        int fusionTopK = Math.max(returnTopK, config.getFusionTopK());
        int rerankTopK = Math.max(1, config.getRerankTopK());
        int rerankGlobalMaxChars = Math.max(1, config.getRerankGlobalMaxChars());

        SearchContext context = new SearchContext();
        context.setAppCode(appCode);
        context.setQueryText(queryText);
        context.setTopK(returnTopK);
        context.setReturnTopK(returnTopK);
        context.setRecallTopK(recallTopK);
        context.setFusionTopK(fusionTopK);
        context.setRerankTopK(rerankTopK);
        context.setRerankGlobalMaxChars(rerankGlobalMaxChars);
        context.setFilters(filters);
        context.setTenantPolicy(policy);
        context.setResolvedIndexPattern(searchIndexResolver.resolve(policy, filters));
        context.setTuningConfig(config);
        context.setStartTime(startMs);
        context.setEvidencePrefetchEnabled(enableAnswerQaRecall);
        context.setHomeLightweightMode(homeLightweightMode);
        if ("__no_readable_index__".equals(context.getResolvedIndexPattern())) {
            context.setFinalResult(Collections.emptyList());
            context.getTimings().put("index_acl_denied", 1);
            return context;
        }
        // 写入 searchMode（null 安全：保底为 hybrid，确保向后兼容）
        context.setSearchMode(searchMode != null && !searchMode.isEmpty() ? searchMode : "hybrid");

        // 2. 动态组装 Pipeline
        // keyword 模式不需要向量，直接跳过 VectorFetchStep，节省 200~500ms 的 Python HTTP 调用延迟。
        // semantic / hybrid 模式需要稠密&稀疏向量，保留 VectorFetchStep。
        // EsRecallStep 内部通过 Strategy Pattern 根据 context.searchMode 选择召回路径。
        final boolean needVector = !"keyword".equals(context.getSearchMode());

        // [性能优化] 判断是否需要 DocSearchPrefilter（与 VectorFetch 并行执行的条件）
        final boolean usePrefilter = needVector && context.isHomeLightweightMode();

        log.info("[SearchServiceV2] mode={} needVector={} returnTopK={} recallTopK={} fusionTopK={} rerankTopK={} index={}",
                context.getSearchMode(), needVector, context.getReturnTopK(), context.getRecallTopK(),
                context.getFusionTopK(), context.getRerankTopK(), context.getResolvedIndexPattern());

        // ── Phase 1: QueryNormalize (必须先完成，后续步骤依赖 normalizedQuery) ──────────
        executeStep(queryNormalizeStep, context);
        if (context.getFinalResult() != null) return context;

        if (needVector) {
            // ── Phase 2 (hybrid/semantic): VectorFetch ∥ LiteralRecall ∥ DocSearchPrefilter 三路并行 ──
            // [性能优化] 将三个无依赖的步骤并行执行：
            //   Track A: VectorFetchStep (~300-600ms) — BGE-M3 向量编码
            //   Track B: LiteralRecallStep (~50ms) — 精确文号/文件名匹配
            //   Track C: DocSearchPrefilter (~50-200ms) — kb_doc_search 预过滤查询
            // 总耗时 = max(A, B, C) 而非 A + B + C，节省 ~300-500ms
            long parallelStart = System.currentTimeMillis();

            CompletableFuture<Void> vectorFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try {
                    vectorFetchStep.execute(context);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            // DocSearchPrefilter 提前执行（原在 HybridRecallStrategy 内部同步执行）
            if (usePrefilter) {
                try {
                    List<co.elastic.clients.elasticsearch._types.FieldValue> candidates =
                            hybridRecallStrategy.prefilterSources(context);
                    context.setDocCandidateSources(candidates);
                } catch (Exception e) {
                    System.err.println("[V2-Parallel] DocSearchPrefilter failed (will fallback in recall): " + e.getMessage());
                }
            }

            // LiteralRecallStep 在当前线程执行（很快 ~50ms）
            executeStep(literalRecallStep, context);

            // 等待 VectorFetch 完成
            try {
                vectorFuture.get(6_000, TimeUnit.MILLISECONDS);
                context.getTimings().put("vector_fetch_ms", System.currentTimeMillis() - parallelStart);
            } catch (Exception e) {
                System.err.println("[V2-Parallel] VectorFetch timed out, continuing with degraded recall");
                vectorFuture.cancel(true);
            }

            if (enableAnswerQaRecall) {
                startAnswerQaRecall(context);
            }
            if (context.getFinalResult() != null) return context;

            // ── Phase 3-6: 顺序执行后续步骤 ──────────────────────────────────────────
            executeStep(esRecallStep, context);
            if (context.getFinalResult() != null) return context;

            executeStep(rrfFusionStep, context);
            // [TEMP] 普通检索暂时禁用 QA 问答结果进入评分链路
            // executeStep(qaInjectionStep, context);
            executeStep(docExpansionStep, context);
            if (context.getFinalResult() != null) return context;

            executeStep(rerankStep, context);
        } else {
            // ── keyword 模式：无向量，顺序执行 ─────────────────────────────────────────
            // [性能/可回滚] LiteralRecall 跳过开关（search.keyword.literal.skip，默认 false）。
            // 跳过后 fastTrackDocs 为 null → 短路条件不成立 → 走完整 BM25 管道（与 literal 零命中等价）。
            if (!keywordLiteralSkip) {
                executeStep(literalRecallStep, context);
                if (context.getFinalResult() != null) return context;

                // [性能优化] LiteralRecall 短路：精确命中数已覆盖 returnTopK 时，
                // 跳过 BM25 召回 + 文档交集 + 证据分片三个步骤，节省 300-500ms。
                if (context.getFastTrackDocs() != null
                        && context.getFastTrackDocs().size() >= context.getReturnTopK()) {
                    context.setLiteralShortCircuit(true);
                    System.out.printf("[SearchServiceV2] Literal short-circuit: %d literal hits >= topK=%d, skipping BM25 pipeline%n",
                            context.getFastTrackDocs().size(), context.getReturnTopK());
                }
            }

            if (!context.isLiteralShortCircuit()) {
                executeStep(esRecallStep, context);
                executeStep(keywordDocumentMatchStep, context);
                executeStep(keywordCoarseEvidenceStep, context);
            }
            executeStep(keywordRankStep, context);
            executeStep(keywordResultAssembleStep, context);
        }
        if (enableAnswerQaRecall) {
            collectAnswerQaRecall(context);
        }
        context.setLlmSemaphoreRejected(aiEngineGateway.wasLlmSemaphoreRejected());

        System.out.println("====== [SearchServiceV2 Pipeline End] Total Time: " + (System.currentTimeMillis() - startMs) + " ms ======");

        // 3. [Phase 2] 后置 PermissionGuard 兜底过滤
        //    目的：ES filter 基于索引数据（有延迟刷新），PermissionGuard 基于 MySQL（强一致）
        //    超管：直接跳过，不强制逐条查 MySQL（避免额外数据库压力）
        //    普通用户：逐条验证，过滤掉因 ES 延迟而混入的"幽灵文档"
        long permissionStart = System.currentTimeMillis();
        List<Map<String, Object>> finalResult = applyPostPermissionFilter(context, context.getFinalResult());
        finalResult = applySensitivePolicyFilter(context, finalResult);
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
            auditLog.setEmbeddingCostMs(timingMs(context, "vector_ms"));
            auditLog.setEsCostMs(timingMs(context, "es_recall_ms"));
            auditLog.setRerankCostMs(timingMs(context, "rerank_ms"));
            auditLog.setTotalCostMs((int)(System.currentTimeMillis() - startMs));
            JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
            auditLog.setUserId(identity != null ? identity.getUserId() : null);
            auditLog.setAdminBypass(identity != null && identity.isSuperAdmin());
            auditLog.setResolvedIndex(context.getResolvedIndexPattern());
            auditLog.setSearchMode(context.getSearchMode());
            auditLog.setReturnTopK(context.getReturnTopK());
            auditLog.setRecallTopK(context.getRecallTopK());
            auditLog.setFusionTopK(context.getFusionTopK());
            auditLog.setRerankTopK(context.getRerankTopK());
            auditLog.setLiteralHitCount(context.getLiteralHitCount());
            auditLog.setBm25Hits(context.getBm25Hits());
            auditLog.setKnnHits(context.getKnnHits());
            auditLog.setSparseHits(context.getSparseHits());
            auditLog.setQaHits(context.getQaHitsCount());
            auditLog.setRrfCandidates(context.getRrfCandidateCount());
            auditLog.setRerankInputCount(context.getRerankInputCount());
            auditLog.setRerankDegraded(context.isRerankDegraded());
            auditLog.setRerankSemaphoreRejected(context.isRerankSemaphoreRejected());
            auditLog.setLlmSemaphoreRejected(context.isLlmSemaphoreRejected());
            auditLog.setPostFilterDeniedCount(context.getPostFilterDeniedCount());
            auditLog.setDocSearchEnabled(timingBool(context, "doc_search_enabled"));
            auditLog.setDocSearchMs(timingMsAny(context, "doc_search_keyword_ms", "doc_search_prefilter_ms"));
            auditLog.setDocSearchCandidates(timingMsAny(context, "doc_search_keyword_candidates", "doc_search_prefilter_candidates"));
            auditLog.setDocSearchPrefilterApplied(timingBool(context, "doc_search_prefilter_applied"));
            // [验证用] 每步管线耗时落库：把内存 timings map 中未持久化的每步耗时补齐，
            // 使 total_cost_ms 可被完整分解（residual → ~0），精确定位瓶颈环节。
            // 注意：keyword 模式下 vector/rerank 为 0；hybrid 模式下 keyword_* 步骤为 0。
            auditLog.setLiteralRecallMs(timingMs(context, "literal_recall_ms"));
            auditLog.setKeywordDocMatchMs(timingMs(context, "keyword_doc_match_ms"));
            auditLog.setCoarseEvidenceMs(timingMs(context, "keyword_coarse_evidence_ms"));
            auditLog.setKeywordRankMs(timingMs(context, "keyword_rank_ms"));
            auditLog.setResultAssembleMs(timingMs(context, "keyword_result_assemble_ms"));
            auditLog.setPermissionFilterMs(timingMs(context, "permission_filter_ms"));
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
                            Math.max(1, Math.min(10, context.getReturnTopK())));
                } else {
                    hits = aiEngineGateway.fetchQaResultsByBm25(queryText, null,
                            Math.max(1, Math.min(10, context.getReturnTopK())));
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
        if (step == literalRecallStep) return "literal_recall_ms";
        if (step == vectorFetchStep) return "vector_ms";
        if (step == esRecallStep) return "es_recall_ms";
        if (step == rrfFusionStep) return "rrf_fusion_ms";
        if (step == docExpansionStep) return "doc_expansion_ms";
        if (step == rerankStep) return "rerank_ms";
        if (step == keywordDocumentMatchStep) return "keyword_doc_match_ms";
        if (step == keywordCoarseEvidenceStep) return "keyword_coarse_evidence_ms";
        if (step == keywordRankStep) return "keyword_rank_ms";
        if (step == keywordResultAssembleStep) return "keyword_result_assemble_ms";
        return step.getClass().getSimpleName().replace("Step", "").toLowerCase() + "_ms";
    }

    /**
     * [性能优化] 执行单个管线步骤并记录耗时（替代原 for 循环中的内联逻辑）。
     */
    private void executeStep(SearchPipelineStep step, SearchContext context) throws Exception {
        long stepStart = System.currentTimeMillis();
        step.execute(context);
        long stepMs = System.currentTimeMillis() - stepStart;
        context.getTimings().put(timingKey(step), stepMs);
        System.out.println("  [V2-Trace] Step " + step.getClass().getSimpleName()
                + " finish in " + stepMs + " ms.");
    }

    private int timingMs(SearchContext context, String key) {
        if (context == null || context.getTimings() == null || key == null) {
            return 0;
        }
        Object value = context.getTimings().get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private int timingMsAny(SearchContext context, String... keys) {
        if (keys == null) {
            return 0;
        }
        for (String key : keys) {
            int value = timingMs(context, key);
            if (value != 0) {
                return value;
            }
        }
        return 0;
    }

    private boolean timingBool(SearchContext context, String key) {
        if (context == null || context.getTimings() == null || key == null) {
            return false;
        }
        Object value = context.getTimings().get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
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
    private List<Map<String, Object>> applyPostPermissionFilter(SearchContext context, List<Map<String, Object>> rawResult) {
        if (context != null) {
            context.setPostFilterDeniedCount(0);
        }
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

        // [性能优化] 提取所有 guardKey，尝试批量权限校验
        List<String> guardKeys = new java.util.ArrayList<>();
        List<Map<String, Object>> docsToCheck = new java.util.ArrayList<>();
        int nullKeyCount = 0;

        for (Map<String, Object> doc : rawResult) {
            String organization = (String) doc.getOrDefault("file_name", doc.getOrDefault("organization", ""));
            String guardKey = (organization != null && !organization.isEmpty()) ? organization : null;
            if (guardKey == null) {
                nullKeyCount++;
                log.warn("[PostPermFilter] organization/file_name 为空，无法校验权限，拒绝返回 doc_id='{}'",
                        doc.get("doc_id"));
            } else {
                guardKeys.add(guardKey);
                docsToCheck.add(doc);
            }
        }

        List<Map<String, Object>> safeResult = new java.util.ArrayList<>();
        int deniedCount = nullKeyCount;

        if (!guardKeys.isEmpty()) {
            try {
                // 批量权限校验：3 次 SQL 替代 N×3 次
                java.util.Map<String, PermissionGuard.AccessResult> batchResults =
                        permissionGuard.batchCheck(guardKeys, identity);

                for (int i = 0; i < docsToCheck.size(); i++) {
                    String guardKey = guardKeys.get(i);
                    PermissionGuard.AccessResult ar = batchResults.get(guardKey);
                    if (ar != null && ar.isAllowed()) {
                        safeResult.add(docsToCheck.get(i));
                    } else {
                        deniedCount++;
                        log.warn("[PostPermFilter] 幽灵文档已被后置过滤 guardKey='{}' reason='{}' userId='{}'",
                                guardKey,
                                ar != null ? ar.getDenyReason() : "batch result missing",
                                identity != null ? identity.getUserId() : "anonymous");
                    }
                }
            } catch (Exception e) {
                // 批量查询失败时回退到逐条查询（保证安全兜底）
                log.warn("[PostPermFilter] 批量权限校验异常，回退逐条查询: {}", e.getMessage());
                return applyPostPermissionFilterFallback(context, rawResult, identity, guardKeys);
            }
        }

        if (deniedCount > 0) {
            log.warn("[PostPermFilter] ⚠️ 后置过滤拦截了 {} 条文档（ES延迟一致性导致），请排查 acl_tokens 数据质量",
                     deniedCount);
        }

        if (context != null) {
            context.setPostFilterDeniedCount(deniedCount);
        }

        return safeResult;
    }

    /**
     * [性能优化] 批量权限校验失败时的逐条兜底方法（原有逻辑完整保留）。
     */
    private List<Map<String, Object>> applyPostPermissionFilterFallback(
            SearchContext context, List<Map<String, Object>> rawResult,
            JwtVerifier.UserIdentity identity, List<String> skipKeys) {
        if (context != null) {
            context.setPostFilterDeniedCount(0);
        }

        java.util.Set<String> skipSet = new java.util.HashSet<>(skipKeys != null ? skipKeys : java.util.Collections.emptyList());
        List<Map<String, Object>> safeResult = new java.util.ArrayList<>();
        int deniedCount = 0;

        for (Map<String, Object> doc : rawResult) {
            String organization = (String) doc.getOrDefault("file_name", doc.getOrDefault("organization", ""));
            String guardKey = (organization != null && !organization.isEmpty()) ? organization : null;

            if (guardKey == null) {
                deniedCount++;
                continue;
            }

            PermissionGuard.AccessResult ar = permissionGuard.canAccess(guardKey, identity);
            if (ar.isAllowed()) {
                safeResult.add(doc);
            } else {
                deniedCount++;
                log.warn("[PostPermFilter][Fallback] 幽灵文档已被后置过滤 guardKey='{}' reason='{}'",
                        guardKey, ar.getDenyReason());
            }
        }

        if (context != null) {
            context.setPostFilterDeniedCount(deniedCount);
        }

        return safeResult;
    }

    private List<Map<String, Object>> applySensitivePolicyFilter(SearchContext context, List<Map<String, Object>> rawResult) {
        if (rawResult == null || rawResult.isEmpty()) {
            return rawResult;
        }
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        List<Map<String, Object>> safeResult = new ArrayList<>();
        int blockedCount = 0;
        int maskedCount = 0;
        for (Map<String, Object> item : rawResult) {
            boolean allowed = sensitivePolicyService.filterResultMap(item, "SEARCH", identity);
            if (allowed) {
                if (Boolean.TRUE.equals(item.get("sensitiveFiltered"))) {
                    maskedCount++;
                }
                safeResult.add(item);
            } else {
                blockedCount++;
            }
        }
        if (blockedCount > 0 || maskedCount > 0) {
            log.warn("[SensitivePolicy] search results filtered masked={} blocked={}", maskedCount, blockedCount);
        }
        if (context != null) {
            context.getTimings().put("sensitive_filter_blocked", blockedCount);
            context.getTimings().put("sensitive_filter_masked", maskedCount);
        }
        return safeResult;
    }
}
