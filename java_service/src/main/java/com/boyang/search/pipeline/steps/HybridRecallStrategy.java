package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合召回策略（BM25 + KNN稠密 + Sparse稀疏 三路并行）
 *
 * 业务功能：
 *   实现完整的三路并行 ES 召回：
 *   - BM25    : 权重最高的全文检索通道，含同义词展开、核心词 Boost、matchPhrase 短语加权
 *   - KNN     : 稠密向量语义检索（1024 维 BGE-M3 FP16），HNSW 近似最近邻
 *   - Sparse  : 稀疏向量（rank_features + saturation），弥补 BM25 IDF 缺陷
 *   三路结果写入 context 的强类型字段（bm25Response/knnResponse/sparseResponse），
 *   供后续 RrfFusionStep 读取融合。
 *
 * 关键设计：
 *   - 三路 Future 并行提交，总耗时 = max(BM25, KNN, Sparse)，而非三者累加
 *   - allOf 统一超时（esQueryTimeout），超时后取已完成结果降级继续
 *   - Pre-Flight 探针在此策略中前置执行（精确文号/文件名短路命中）
 *   - SubQuery 多向量 KNN 扩展（BM25 弱信号词汇鸿沟场景追加候选）
 *
 * 此类从原 EsRecallStep.execute() 方法直接迁移，逻辑完全等价，零行为变更。
 */
@Component
public class HybridRecallStrategy implements RecallStrategy {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private EsRecallUtils utils;

    // [P1 优化⑧] 搜索查询级缓存：缓存 prefilter 结果，避免重复 ES 查询
    @Autowired
    private com.boyang.search.util.SearchQueryCache searchQueryCache;

    @Value("${search.doc-search.index:#{systemEnvironment['KB_DOC_SEARCH_READ_ALIAS'] ?: 'kb_doc_search'}}")
    private String docSearchIndex;

    @Value("${search.doc-search.enabled:true}")
    private boolean docSearchEnabled;

    @Value("${search.doc-search.home-prefilter-enabled:true}")
    private boolean docSearchHomePrefilterEnabled;

    /**
     * 业务功能：控制普通 /search hybrid 是否启用 kb_doc_search 文档级预过滤。
     * 设计原因：v2.0 过渡版暂不拆 coarse/fine/vector，必须先用文档级候选收窄现有业务 chunk 索引。
     */
    @Value("${search.doc-search.hybrid-prefilter-enabled:true}")
    private boolean docSearchHybridPrefilterEnabled;

    @Value("${search.doc-search.prefilter-max-candidates:500}")
    private int docSearchPrefilterMaxCandidates;

    /**
     * 业务功能：控制是否允许不带 doc_search 候选约束的全局 chunk KNN。
     * 设计原因：v2.0 过渡版暂不拆 vector 索引，亿级下全局 chunk KNN 是重路径，默认关闭。
     */
    @Value("${search.hybrid.global-knn.enabled:false}")
    private boolean hybridGlobalKnnEnabled;

    /**
     * 业务功能：控制候选文档数量超过多少时跳过 chunk KNN。
     * 设计原因：候选过宽时 KNN 过滤收益不足，继续执行会把现有业务 chunk 索引拖回重路径。
     */
    @Value("${search.hybrid.candidate-knn-max-sources:200}")
    private int hybridCandidateKnnMaxSources;

    /**
     * 业务功能：控制是否允许 Hybrid 在没有 doc_search 候选约束时查询全局 chunk 索引。
     * 设计原因：亿级数据下，全局 BM25/Sparse 虽然比 KNN 轻，但仍会把检索打回重路径，默认必须关闭。
     */
    @Value("${search.hybrid.global-chunk-recall.enabled:false}")
    private boolean hybridGlobalChunkRecallEnabled;

    /**
     * 业务功能：控制候选文档数量超过多少时跳过 chunk 级 BM25/Sparse。
     * 设计原因：候选集过宽时 terms 过滤收益下降，继续查询会放大现有业务索引压力。
     */
    @Value("${search.hybrid.candidate-chunk-max-sources:200}")
    private int hybridCandidateChunkMaxSources;

    /**
     * 业务功能：控制 Hybrid 精准短路探测的 ES 超时。
     * 设计原因：该请求位于召回入口，生产环境需要按索引规模动态调节，避免硬编码导致容量升级必须发版。
     */
    @Value("${search.hybrid.preflight-timeout-ms:${SEARCH_HYBRID_PREFLIGHT_TIMEOUT_MS:1000}}")
    private String hybridPreflightTimeoutMs = "1000";

    /**
     * 业务功能：控制 Hybrid 主召回 ES 查询的默认超时。
     * 设计原因：数据库调优配置为空时仍要有可运维的兜底值，避免固定 2s 在大索引下不可调整。
     */
    @Value("${search.hybrid.es-timeout-ms:${SEARCH_HYBRID_ES_TIMEOUT_MS:2000}}")
    private String hybridEsTimeoutMs = "2000";

    /**
     * 业务功能：控制 Hybrid Sparse 召回的 ES 超时。
     * 设计原因：Sparse 是可降级召回分支，独立配置能在高负载时快速收敛资源占用。
     */
    @Value("${search.hybrid.sparse-timeout-ms:${SEARCH_HYBRID_SPARSE_TIMEOUT_MS:2000}}")
    private String hybridSparseTimeoutMs = "2000";

    /**
     * 业务功能：控制 SubQuery 并行扩展的整体等待时间。
     * 设计原因：多子句扩展会放大向量生成和 ES 查询成本，独立超时可保护主链路 SLA。
     */
    @Value("${search.hybrid.sub-query-timeout-ms:${SEARCH_HYBRID_SUB_QUERY_TIMEOUT_MS:5000}}")
    private String hybridSubQueryTimeoutMs = "5000";

    private static final String DEFAULT_ORG  = "默认组织";
    private static final String DEFAULT_DATE = "2024-01-10";

    @Override
    public void recall(SearchContext context) throws Exception {
        String normalizedQuery = context.getNormalizedQuery();
        String queryText       = context.getQueryText();
        String rewrittenQuery  = context.getRewrittenQuery();
        SysTenantPolicy  policy = context.getTenantPolicy();
        SysAiTuningConfig config = context.getTuningConfig();
        Map<String, Object> filters    = context.getFilters();
        List<Double>        queryVector = context.getQueryVector();
        String indexPattern = context.getResolvedIndexPattern() != null
                ? context.getResolvedIndexPattern()
                : policy.getIndexPattern();

        // 提前解析权限相关变量，供 Pre-Flight / BM25 / KNN / Sparse 四路共用
        String pfUserId       = filters != null ? (String) filters.get("user_id") : null;
        String pfUserDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        final List<FieldValue> pfDeptValues = utils.buildDeptValues(pfUserDeptCode);
        final boolean pfIsAnonymous = (pfUserId == null || pfUserId.trim().isEmpty());
        final String  pfFinalUserId = pfUserId;

        // ── 1. Pre-Flight Probe（文号/文件名精准短路，已含权限过滤）─────────────
        if (rewrittenQuery != null && rewrittenQuery.length() > 3) {
            String pfForceSource = filters != null ? (String) filters.get("data_source") : null;
            SearchRequest preFlightReq = new SearchRequest.Builder()
                .index(indexPattern)
                .size(5)
                .timeout(resolvePreflightTimeoutMs() + "ms")
                .query(q -> q.bool(b -> {
                    b.should(s -> s.term(t -> t.field("metadata.source").value(normalizedQuery)));
                    b.should(s -> s.term(t -> t.field("metadata.document_number").value(normalizedQuery)));
                    b.should(s -> s.term(t -> t.field("metadata.title.keyword").value(normalizedQuery)));
                    b.should(s -> s.matchPhrase(mp -> mp.field("metadata.title").query(normalizedQuery).slop(0).boost(5.0f)));
                    b.minimumShouldMatch("1");
                    b.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                    ));
                    b.filter(utils.buildLegacyPermFilter(pfIsAnonymous, pfFinalUserId, pfDeptValues));
                    if (pfForceSource != null && !pfForceSource.isEmpty()) {
                        b.filter(f -> f.term(t -> t.field("metadata.data_source").value(pfForceSource)));
                    }
                    return b;
                }))
                .build();

            try {
                SearchResponse<Object> preFlightRes = esClient.search(preFlightReq, Object.class);
                if (!preFlightRes.hits().hits().isEmpty()) {
                    Map<String, Map<String, Object>> preflightDeduped = new LinkedHashMap<>();
                    for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : preFlightRes.hits().hits()) {
                        Map<String, Object> rawSrc = (Map<String, Object>) hit.source();
                        if (rawSrc == null) continue;
                        String hitId = hit.id();
                        if (hitId == null || preflightDeduped.containsKey(hitId)) continue;

                        Map<String, Object> candidate = new HashMap<>();
                        double esScore = hit.score() != null ? hit.score() : 50.0;
                        candidate.put("_id", hitId);
                        candidate.put("_score", esScore);
                        candidate.put("_es_score", esScore);
                        candidate.put("_source", rawSrc);
                        candidate.put("_preflight_hit", Boolean.TRUE);
                        candidate.put("_preflight_score", 1.0);
                        candidate.put("_max_knn_score", 0.0);
                        preflightDeduped.put(hitId, candidate);
                    }
                    context.setPreflightHits(new ArrayList<>(preflightDeduped.values()));
                    context.setPreflightHitCount(preflightDeduped.size());
                    System.out.printf("[Pre-Flight] MATCHED: Exact Title/ID boosted into hybrid pipeline (%d hits).%n",
                            preflightDeduped.size());
                }
            } catch (Exception e) {
                System.err.println("[Pre-Flight] probe failed: " + e.getMessage());
            }
        }

        // ── 2. 权限变量（复用 Pre-Flight 已计算的结果，避免重复提取）────────────
        final List<FieldValue> deptValues = pfDeptValues;
        final boolean isAnonymous  = pfIsAnonymous;
        final String  finalUserId  = pfFinalUserId;

        // ── 3. 提取核心锚词（BM25 Boost 用）────────────────────────────────────
        List<String> anchorTerms = utils.extractCoreTerms(normalizedQuery, config);
        context.setCoreTerms(anchorTerms);

        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        final List<String> finalAnchorTerms = anchorTerms;
        final boolean useDocSearchPrefilter = shouldUseDocSearchPrefilter(context);
        final long docSearchPrefilterStart = System.currentTimeMillis();
        // [性能优化] 优先使用 SearchServiceV2 提前计算的 prefilter 结果（从并行轨道中获取）
        // 避免在 ES 召回关键路径上同步执行 kb_doc_search 查询（节省 ~50-200ms）
        List<FieldValue> docCandidateSources = context.getDocCandidateSources();
        if (useDocSearchPrefilter && docCandidateSources == null) {
            // [P1 优化⑧] 先查 prefilter 缓存，并把可读索引范围纳入 key，避免权限范围变化后复用旧候选。
            String prefilterCacheKey = normalizedQuery + "|" + (finalUserId != null ? finalUserId : "anon")
                    + "|" + (indexPattern != null ? indexPattern : "default")
                    + "|" + (forceSource != null ? forceSource : "");
            List<FieldValue> cachedPrefilter = searchQueryCache.getPrefilter(prefilterCacheKey);
            if (cachedPrefilter != null) {
                docCandidateSources = cachedPrefilter;
                System.out.printf("[DocSearchPrefilter] Cache HIT, candidates=%d, saved ~50-200ms%n", cachedPrefilter.size());
            } else {
                // 缓存未命中：执行查询并写入缓存
                docCandidateSources = searchDocCandidateSources(normalizedQuery, getEsTimeoutMs(config), isAnonymous,
                        finalUserId, deptValues, forceSource, indexPattern,
                        Math.min(docSearchPrefilterMaxCandidates, Math.max(context.getFusionTopK(), context.getRecallTopK())));
                searchQueryCache.putPrefilter(prefilterCacheKey, docCandidateSources);
            }
        } else if (!useDocSearchPrefilter) {
            docCandidateSources = Collections.emptyList();
        }
        if (useDocSearchPrefilter) {
            context.getTimings().put("doc_search_enabled", 1);
            context.getTimings().put("doc_search_prefilter_ms", System.currentTimeMillis() - docSearchPrefilterStart);
            context.getTimings().put("doc_search_prefilter_candidates", docCandidateSources.size());
            context.getTimings().put("doc_search_prefilter_applied", docCandidateSources.isEmpty() ? 0 : 1);
        } else {
            context.getTimings().put("doc_search_enabled", 0);
            context.getTimings().put("doc_search_prefilter_applied", 0);
        }
        if (!docCandidateSources.isEmpty()) {
            context.getTimings().put("doc_search_prefilter_candidates", docCandidateSources.size());
            System.out.printf("[DocSearchPrefilter] candidates=%d%n", docCandidateSources.size());
        }

        // lambda 捕获要求 effectively-final，赋值给 final 局部变量
        final List<FieldValue> finalDocCandidateSources = docCandidateSources;

        final boolean runChunkTextRecall = shouldRunChunkTextRecall(context, finalDocCandidateSources);

        // ── 4. 构建 BM25 检索 DSL ──────────────────────────────────────────────
        final int esTimeoutMs = getEsTimeoutMs(config);
        SearchRequest textRequest = null;
        if (runChunkTextRecall) {
            textRequest = new SearchRequest.Builder()
                .index(indexPattern)
                .trackTotalHits(h -> h.count(200))
                .size(Math.max(context.getRecallTopK(), 60))
                .timeout(esTimeoutMs + "ms")
                .query(q -> q.bool(b -> {
                // 核心词 Boost 子句（最高优先级）
                if (!finalAnchorTerms.isEmpty()) {
                    b.should(m -> m.bool(coreBool -> {
                        for (String coreTerm : finalAnchorTerms) {
                            coreBool.should(s -> s.match(ma -> ma
                                .field("content").query(coreTerm).boost(5.0f)));
                        }
                        coreBool.minimumShouldMatch("1");
                        return coreBool;
                    }));
                    String coreTermClause = String.join(" ", finalAnchorTerms);
                    String msm = finalAnchorTerms.size() == 1 ? "1" : "2";
                    b.should(s -> s.match(ma -> ma.field("content").query(coreTermClause)
                        .minimumShouldMatch(msm).boost(20.0f)));
                    b.should(s -> s.matchPhrase(ma -> ma.field("content").query(coreTermClause)
                        .slop(15).boost(30.0f)));
                }
                // 原查询词 BM25（任一 token 命中即参与评分）
                b.should(sh -> sh.match(ma -> ma.field("content").query(normalizedQuery).minimumShouldMatch("1")));
                // matchPhrase 短语锚定（^15）
                if (normalizedQuery != null && normalizedQuery.length() > 2) {
                    b.should(sh -> sh.matchPhrase(mp -> mp.field("content").query(normalizedQuery).slop(3).boost(15.0f)));
                }
                if (shouldUseShortChinesePrefix(normalizedQuery)) {
                    b.should(sh -> sh.matchPhrasePrefix(mp -> mp.field("content").query(normalizedQuery).boost(12.0f)));
                }
                // 多字段 multiMatch（title > source > document_number > keywords > tags_kw）
                b.should(s -> s.multiMatch(mm -> mm.query(normalizedQuery)
                    .fields("metadata.title^25.0", "metadata.source^20.0", "metadata.document_number^20.0",
                            "keywords^15.0", "metadata.tags_kw^10.0")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)));
                // 同义词展开（应用层展开，不依赖 ES synonym filter）
                final Map<String, List<String>> synonymMap = utils.getSynonymMap();
                if (synonymMap != null) {
                    for (Map.Entry<String, List<String>> entry : synonymMap.entrySet()) {
                        if (normalizedQuery.contains(entry.getKey())) {
                            for (String expandedTerm : entry.getValue()) {
                                if (!expandedTerm.equals(entry.getKey())) {
                                    final String et = expandedTerm;
                                    b.should(sh -> sh.match(ma -> ma.field("content").query(et).boost(3.0f)));
                                }
                            }
                        }
                    }
                }
                // 改写词注入 BM25 + matchPhrase
                final String rwq = rewrittenQuery;
                if (rwq != null && !rwq.isEmpty() && !rwq.equals(normalizedQuery)) {
                    b.should(sh -> sh.match(ma -> ma.field("content").query(rwq).boost(8.0f)));
                    b.should(sh -> sh.matchPhrase(mp -> mp.field("content").query(rwq).slop(5).boost(20.0f)));
                }
                // 版本过滤（is_latest）
                b.filter(f -> f.bool(boolQuery -> boolQuery
                    .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                    .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                ));
                b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                if (forceSource != null && !forceSource.isEmpty()) {
                    b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                }
                if (!finalDocCandidateSources.isEmpty()) {
                    b.filter(f -> f.terms(t -> t.field("metadata.source").terms(tv -> tv.value(finalDocCandidateSources))));
                }
                    return b;
                }))
                // [P1 优化③] BM25 highlight 延迟到 RerankStep 按需计算。
                // 原因：recall 阶段对 60+ 个文档全部计算 highlight 耗时 ~50-150ms，
                // 但最终只有 top-K (5-10) 文档需要展示 snippet。
                // RerankStep 已有完善的 Java 层 fallback 高亮逻辑（OOM 保护 + 噪词过滤），
                // 延迟计算可节省 ES highlight 开销，整体 latency 降低 ~50-150ms。
                .build();
        } else {
            context.getTimings().put("chunk_text_recall_skipped", 1);
            String reason = shouldUseDocSearchPrefilter(context)
                    ? "doc_search_candidates_empty_or_too_many"
                    : "global_chunk_recall_disabled";
            context.getTimings().put("chunk_text_recall_skipped_reason", reason);
            System.out.printf("[HybridStrategy] skip chunk BM25/Sparse reason=%s candidates=%d%n",
                    reason, finalDocCandidateSources != null ? finalDocCandidateSources.size() : 0);
        }

        // ── 5. 构建 KNN 检索 DSL ──────────────────────────────────────────────
        SearchRequest knnRequest = null;
        if (shouldRunChunkKnn(context, finalDocCandidateSources)) {
            knnRequest = new SearchRequest.Builder()
                .index(indexPattern)
                .knn(k -> {
                    int kVal = Math.max(context.getRecallTopK(), 100);
                    // 统一由调参配置计算候选窗口，避免亿级索引继续沿用过小的历史默认值。
                    int numCandidates = config.resolveKnnNumCandidates(kVal);
                    return k.field("vector").queryVector(queryVector)
                        .k(kVal)
                        .numCandidates(numCandidates)
                        .filter(f -> f.bool(b -> {
                            b.filter(ft -> ft.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                            ));
                            b.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")));
                            b.should(s -> s.bool(bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))));
                            b.minimumShouldMatch("1");
                            b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                            if (forceSource != null && !forceSource.isEmpty()) {
                                b.filter(ft -> ft.term(t -> t.field("metadata.data_source").value(forceSource)));
                            }
                            if (!finalDocCandidateSources.isEmpty()) {
                                b.filter(ft -> ft.terms(t -> t.field("metadata.source").terms(tv -> tv.value(finalDocCandidateSources))));
                            }
                            return b;
                        }));
                })
                // [P1 优化③] KNN highlight 同样延迟到 RerankStep，与 BM25 highlight 策略一致
                .build();
        } else if (queryVector != null && !queryVector.isEmpty()) {
            context.getTimings().put("knn_skipped", 1);
            String reason = shouldUseDocSearchPrefilter(context)
                    ? "doc_search_candidates_empty_or_too_many"
                    : "global_knn_disabled";
            context.getTimings().put("knn_skipped_reason", reason);
            System.out.printf("[HybridStrategy] skip chunk KNN reason=%s candidates=%d%n",
                    reason, finalDocCandidateSources != null ? finalDocCandidateSources.size() : 0);
        }

        // ── 6. 构建 Sparse 检索 DSL 并四路并发执行（含 QA 第四路）─────────────────
        final SearchRequest fTextReq = textRequest;
        CompletableFuture<SearchResponse<Object>> textFuture = (textRequest != null)
            ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try { return esClient.search(fTextReq, Object.class); } catch (Exception e) { throw new RuntimeException(e); }
              })
            : CompletableFuture.completedFuture(null);

        final SearchRequest fKnnReq = knnRequest;
        CompletableFuture<SearchResponse<Object>> knnFuture = (knnRequest != null)
            ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try { return esClient.search(fKnnReq, Object.class); } catch (Exception e) { throw new RuntimeException(e); }
              })
            : CompletableFuture.completedFuture(null);

        final String sparseQuery = normalizedQuery;
        CompletableFuture<SearchResponse<Object>> sparseFuture = runChunkTextRecall
            ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            try {
                Map<String, Double> sparseVector = context.getQuerySparseVector();
                if (sparseVector == null || sparseVector.isEmpty()) {
                    sparseVector = aiEngineGateway.fetchSparseVector(sparseQuery);
                }
                if (sparseVector == null || sparseVector.isEmpty()) return null;
                // 取权重最高的 TOP-16 词构建 rank_features + saturation 查询
                List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(sparseVector.entrySet());
                sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                final int TOP_N_SPARSE = Math.min(16, sortedEntries.size());
                SearchRequest sparseReq = new SearchRequest.Builder()
                    .index(indexPattern)
                    .size(Math.max(context.getRecallTopK(), 40))
                    .timeout(resolveSparseTimeoutMs() + "ms")
                    .query(q -> q.bool(b -> {
                        for (int i = 0; i < TOP_N_SPARSE; i++) {
                            final String token  = sortedEntries.get(i).getKey();
                            final float  weight = sortedEntries.get(i).getValue().floatValue();
                            b.should(s -> s.rankFeature(rf -> rf.field("sparse_vector." + token)
                                .saturation(sat -> sat.pivot(weight))));
                        }
                        b.minimumShouldMatch("1");
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        ));
                        b.filter(f -> f.bool(bq -> bq
                            .should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")))
                            .should(s -> s.bool(bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))))
                            .minimumShouldMatch("1")
                        ));
                        b.filter(utils.buildLegacyPermFilter(pfIsAnonymous, pfFinalUserId, pfDeptValues));
                        if (forceSource != null && !forceSource.isEmpty()) {
                            b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                        }
                        if (!finalDocCandidateSources.isEmpty()) {
                            b.filter(f -> f.terms(t -> t.field("metadata.source").terms(tv -> tv.value(finalDocCandidateSources))));
                        }
                        return b;
                    }))
                    .build();
                return esClient.search(sparseReq, Object.class);
            } catch (Exception e) {
                System.err.println("[HybridStrategy] Sparse query failed (degrading): " + e.getMessage());
                return null;
            }
        })
            : CompletableFuture.completedFuture(null);

        // ── [TEMP] 普通混合检索暂时禁用 QA 第四路，避免 QA 问答候选进入 RRF/重排评分。
        // 原 QA 第四路逻辑保留在注释中，后续恢复时可直接打开。
        // final List<Double> finalQueryVec = queryVector;
        // final String finalQueryTextForQa = queryText;
        // final String finalForceSource = forceSource;
        // CompletableFuture<List<Map<String, Object>>> qaFuture =
        //     com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
        //         try {
        //             if (finalQueryVec != null && !finalQueryVec.isEmpty()) {
        //                 return aiEngineGateway.fetchQaResults(finalQueryVec, finalQueryTextForQa, finalForceSource, 8);
        //             } else {
        //                 return aiEngineGateway.fetchQaResultsByBm25(finalQueryTextForQa, finalForceSource, 5);
        //             }
        //         } catch (Exception e) {
        //             System.err.println("[HybridStrategy] QA 第四路召回失败（降级空列表）: " + e.getMessage());
        //             return java.util.Collections.emptyList();
        //         }
        //     });
        CompletableFuture<List<Map<String, Object>>> qaFuture =
            CompletableFuture.completedFuture(java.util.Collections.emptyList());

        // allOf 并发等待，总耗时 = max(BM25, KNN, Sparse, QA)
        try {
            CompletableFuture.allOf(textFuture, knnFuture, sparseFuture, qaFuture)
                .get(esTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[HybridStrategy] ES 四路 allOf 超时，取已完成结果降级继续");
        } catch (Exception e) {
            System.err.println("[HybridStrategy] allOf 异常: " + e.getMessage());
        }

        SearchResponse<Object> textResponse   = null;
        SearchResponse<Object> knnResponse    = null;
        SearchResponse<Object> sparseResponse = null;
        List<Map<String, Object>> qaHits      = null;
        try { textResponse   = textFuture.isDone()   ? textFuture.getNow(null)   : null; } catch (Exception ignored) {}
        try { knnResponse    = knnFuture.isDone()    ? knnFuture.getNow(null)    : null; } catch (Exception ignored) {}
        try { sparseResponse = sparseFuture.isDone() ? sparseFuture.getNow(null) : null; } catch (Exception ignored) {}
        try { qaHits         = qaFuture.isDone()     ? qaFuture.getNow(null)     : null; } catch (Exception ignored) {}

        // 写入强类型字段（RrfFusionStep 直接读取，不用 filters 魔法 key）
        context.setBm25Response(textResponse);
        context.setKnnResponse(knnResponse);
        context.setSparseResponse(sparseResponse);
        // [架构重构] 写入 QA 第四路召回结果，供 RrfFusionStep 作为第四路融合
        context.setQaHits(qaHits != null ? qaHits : java.util.Collections.emptyList());
        context.setBm25Hits(textResponse != null ? textResponse.hits().hits().size() : 0);
        context.setKnnHits(knnResponse != null ? knnResponse.hits().hits().size() : 0);
        context.setSparseHits(sparseResponse != null ? sparseResponse.hits().hits().size() : 0);
        context.setQaHitsCount(qaHits != null ? qaHits.size() : 0);

        // 写入 BM25 命中数和平坦度（RerankStep 消歧用）
        long textTotalHits = (textResponse != null && textResponse.hits() != null
                && textResponse.hits().total() != null) ? textResponse.hits().total().value() : 0L;
        context.setBm25TextHits(textTotalHits);
        if (textResponse != null && textResponse.hits().hits().size() >= 5) {
            double bfTop1 = textResponse.hits().hits().get(0).score() != null
                    ? textResponse.hits().hits().get(0).score() : 0.0;
            double bfTop5Avg = textResponse.hits().hits().subList(0, 5).stream()
                    .mapToDouble(h -> h.score() != null ? h.score() : 0.0).average().orElse(0.0);
            context.setBm25FlatnessRatio(bfTop5Avg > 0.001 ? bfTop1 / bfTop5Avg : 99.0);
        }

        System.out.printf("[HybridStrategy] BM25 hits: %d (total=%d) | KNN hits: %d | Sparse hits: %d | QA hits: %d%n",
            textResponse  != null ? textResponse.hits().hits().size()   : 0,
            textTotalHits,
            knnResponse   != null ? knnResponse.hits().hits().size()    : 0,
            sparseResponse != null ? sparseResponse.hits().hits().size() : 0,
            qaHits        != null ? qaHits.size() : 0);

        // SubQuery 多向量 KNN 扩展（BM25 弱信号时追加候选，防词汇鸿沟）
        runSubQueryExpansion(context, textTotalHits, indexPattern, finalDocCandidateSources);
    }

    /**
     * 判断当前请求是否启用文档级预过滤。
     * 业务功能：统一 SearchServiceV2 并行预取与 HybridRecallStrategy 兜底查询的启用条件。
     * 关键流程：首页轻量模式走 home 开关，普通 hybrid 走 hybrid 开关，semantic 暂不启用该预过滤。
     */
    public boolean shouldUseDocSearchPrefilter(SearchContext context) {
        if (!docSearchEnabled || context == null) {
            return false;
        }
        if (context.isHomeLightweightMode()) {
            return docSearchHomePrefilterEnabled;
        }
        return docSearchHybridPrefilterEnabled && "hybrid".equals(context.getSearchMode());
    }

    /**
     * 判断是否允许执行 chunk KNN。
     * 业务功能：v2.0 过渡版下避免在现有业务 chunk 索引上执行无候选约束的全局 KNN。
     * 关键流程：有 doc_search 预过滤时必须存在小候选集；无预过滤时只有显式打开全局 KNN 才允许执行。
     */
    public boolean shouldRunChunkKnn(SearchContext context, List<FieldValue> docCandidateSources) {
        if (context == null || context.getQueryVector() == null || context.getQueryVector().isEmpty()) {
            return false;
        }
        if (shouldUseDocSearchPrefilter(context)) {
            int candidateCount = docCandidateSources != null ? docCandidateSources.size() : 0;
            return candidateCount > 0 && candidateCount <= hybridCandidateKnnMaxSources;
        }
        return hybridGlobalKnnEnabled;
    }

    /**
     * 判断是否允许执行 chunk 级 BM25/Sparse 召回。
     * 业务功能：统一约束 Hybrid 文本召回，避免 doc_search 预筛失败后自动退回全局 chunk 扫描。
     * 关键流程：启用 doc_search 预筛时只允许小候选集；未启用预筛时必须显式打开全局回滚开关。
     */
    public boolean shouldRunChunkTextRecall(SearchContext context, List<FieldValue> docCandidateSources) {
        if (context == null) {
            return false;
        }
        if (shouldUseDocSearchPrefilter(context)) {
            int candidateCount = docCandidateSources != null ? docCandidateSources.size() : 0;
            return candidateCount > 0 && candidateCount <= hybridCandidateChunkMaxSources;
        }
        return hybridGlobalChunkRecallEnabled;
    }

    /**
     * [性能优化] 公开方法：执行 DocSearchPrefilter 查询，供 SearchServiceV2 在并行轨道中提前调用。
     * 返回候选 source 列表，写入 SearchContext.docCandidateSources。
     * HybridRecallStrategy.recall() 会优先使用 context 中已缓存的值，跳过内部重复查询。
     */
    public List<FieldValue> prefilterSources(SearchContext context) {
        String query = context.getNormalizedQuery();
        SysAiTuningConfig config = context.getTuningConfig();
        Map<String, Object> filters = context.getFilters();
        String userId = filters != null ? (String) filters.get("user_id") : null;
        String userDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        List<FieldValue> deptValues = utils.buildDeptValues(userDeptCode);
        boolean isAnonymous = (userId == null || userId.trim().isEmpty());
        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        String readableSourceIndexPattern = context.getResolvedIndexPattern();
        int limit = Math.min(docSearchPrefilterMaxCandidates,
                Math.max(context.getFusionTopK(), context.getRecallTopK()));

        // [P1 优化⑧] 并行轨道的 prefilter 也走缓存
        String cacheKey = query + "|" + (userId != null ? userId : "anon")
                + "|" + (readableSourceIndexPattern != null ? readableSourceIndexPattern : "default")
                + "|" + (forceSource != null ? forceSource : "");
        List<FieldValue> cached = searchQueryCache.getPrefilter(cacheKey);
        if (cached != null) {
            System.out.printf("[prefilterSources] Cache HIT, candidates=%d%n", cached.size());
            return cached;
        }
        List<FieldValue> result = searchDocCandidateSources(query, getEsTimeoutMs(config), isAnonymous,
                userId, deptValues, forceSource, readableSourceIndexPattern, limit);
        searchQueryCache.putPrefilter(cacheKey, result);
        return result;
    }

    private List<FieldValue> searchDocCandidateSources(String query,
                                                       int timeoutMs,
                                                       boolean isAnonymous,
                                                       String userId,
                                                       List<FieldValue> deptValues,
                                                       String forceSource,
                                                       String readableSourceIndexPattern,
                                                       int limit) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int size = Math.min(Math.max(limit, 80), 500);
        try {
            SearchRequest request = new SearchRequest.Builder()
                .index(docSearchIndex)
                .trackTotalHits(h -> h.enabled(false))
                .size(size)
                .timeout(timeoutMs + "ms")
                .source(s -> s.filter(f -> f.includes("source")))
                .query(q -> q.bool(b -> {
                    b.should(s -> s.term(t -> t.field("source").value(query).boost(18.0f)));
                    b.should(s -> s.term(t -> t.field("document_number").value(query).boost(18.0f)));
                    b.should(s -> s.term(t -> t.field("title.keyword").value(query).boost(18.0f)));
                    b.should(s -> s.match(mp -> mp.field("title").query(query).analyzer("ik_max_word").boost(10.0f)));
                    b.should(s -> s.match(mp -> mp.field("doc_terms").query(query).analyzer("ik_max_word").boost(4.0f)));
                    b.should(s -> s.matchPhrase(mp -> mp.field("doc_terms").query(query).slop(3).boost(8.0f)));
                    b.should(s -> s.match(mp -> mp.field("summary").query(query).analyzer("ik_max_word").boost(1.5f)));
                    if (shouldUseShortChinesePrefix(query)) {
                        b.should(s -> s.matchPhrasePrefix(mp -> mp.field("doc_terms").query(query).boost(5.0f)));
                        b.should(s -> s.matchPhrasePrefix(mp -> mp.field("summary").query(query).boost(1.0f)));
                    }
                    if (query.length() >= 2) {
                        b.should(s -> s.match(mp -> mp.field("source.ngram").query(query).boost(4.0f)));
                        b.should(s -> s.match(mp -> mp.field("title.ngram").query(query).boost(4.0f)));
                        b.should(s -> s.match(mp -> mp.field("document_number.ngram").query(query).boost(6.0f)));
                    }
                    b.minimumShouldMatch("1");
                    b.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("is_latest")))))
                        .minimumShouldMatch("1")
                    ));
                    // kb_doc_search 索引使用顶层字段，需使用专用权限过滤器
                    b.filter(utils.buildDocSearchPermFilter(isAnonymous, userId, deptValues));
                    b.filter(utils.buildDocSearchSourceIndexFilter(readableSourceIndexPattern));
                    if (forceSource != null && !forceSource.isEmpty()) {
                        b.filter(f -> f.term(t -> t.field("data_source").value(forceSource)));
                    }
                    return b;
                }))
                .build();

            SearchResponse<Object> response = esClient.search(request, Object.class);
            if (response == null || response.hits() == null || response.hits().hits().isEmpty()) {
                return Collections.emptyList();
            }
            List<FieldValue> sources = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : response.hits().hits()) {
                Map<String, Object> src = hit.source() instanceof Map ? (Map<String, Object>) hit.source() : null;
                Object sourceObj = src != null ? src.get("source") : null;
                if (sourceObj != null && seen.add(String.valueOf(sourceObj))) {
                    sources.add(FieldValue.of(String.valueOf(sourceObj)));
                }
            }
            return sources;
        } catch (Exception e) {
            System.err.println("[DocSearchPrefilter] failed, return empty candidates: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 业务功能：识别文档级预筛中可安全启用前缀兜底的短中文查询。
     * 关键流程：仅允许 2-6 个连续中文字符，防止单字查询或英文编号查询扩大候选集合。
     */
    boolean shouldUseShortChinesePrefix(String query) {
        if (query == null) {
            return false;
        }
        String trimmed = query.trim();
        return trimmed.length() >= 2
                && trimmed.length() <= 6
                && trimmed.matches("[\\u4e00-\\u9fa5]+");
    }

    int getEsTimeoutMs(SysAiTuningConfig config) {
        return config != null && config.getEsQueryTimeout() != null
                ? config.getEsQueryTimeout()
                : resolvePositiveTimeoutMs(hybridEsTimeoutMs, 2000);
    }

    int resolvePreflightTimeoutMs() {
        return resolvePositiveTimeoutMs(hybridPreflightTimeoutMs, 1000);
    }

    int resolveSparseTimeoutMs() {
        return resolvePositiveTimeoutMs(hybridSparseTimeoutMs, 2000);
    }

    int resolveSubQueryTimeoutMs() {
        return resolvePositiveTimeoutMs(hybridSubQueryTimeoutMs, 5000);
    }

    int resolvePositiveTimeoutMs(String configured, int defaultValue) {
        if (configured == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * SubQuery 多向量 KNN 扩展。
     *
     * 业务功能：针对「词汇鸿沟」场景（BM25 弱信号 + 含标点的长复合查询），将查询拆分为
     * 子句，各子句独立编码后 KNN 检索，将新命中的 chunk 去重追加到候选池。
     *
     * 触发条件（AND 关系）：
     *   1. bm25TotalHits < 2（弱词汇信号，存在词汇鸿沟）
     *   2. 向量可用（非 skipEmbedding 场景）
     *   3. 查询含中文标点分隔的多子句
     *
     * @param context       当前搜索上下文
     * @param bm25TotalHits BM25 命中总数（弱信号判定阈值）
     * @param policy        租户策略（用于获取索引名）
     */
    private void runSubQueryExpansion(SearchContext context, long bm25TotalHits, String indexPattern,
                                      List<FieldValue> docCandidateSources) {
        if (bm25TotalHits >= 2) return;
        if (context.getQueryVector() == null || context.getQueryVector().isEmpty()) return;
        if (!shouldRunChunkKnn(context, docCandidateSources)) {
            context.getTimings().put("sub_query_knn_skipped", 1);
            return;
        }

        Map<String, Object> filters = context.getFilters();
        String userId = filters != null ? (String) filters.get("user_id") : null;
        String userDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        List<FieldValue> deptValues = utils.buildDeptValues(userDeptCode);
        boolean isAnonymous = (userId == null || userId.trim().isEmpty());
        final String finalUserId = userId;
        final String finalForceSource = forceSource;
        final boolean finalIsAnonymous = isAnonymous;
        final List<FieldValue> finalDeptValues = deptValues;
        final List<FieldValue> finalDocCandidateSources = docCandidateSources != null
                ? docCandidateSources
                : Collections.emptyList();

        String normalizedQuery = context.getNormalizedQuery();
        String[] subParts = normalizedQuery.split("[？?,、。！？]+");
        List<String> validClauses = Arrays.stream(subParts)
                .map(String::trim)
                .filter(s -> s.length() > 6)
                .collect(Collectors.toList());

        if (validClauses.size() < 2) return;

        System.out.printf("[SubQuery] 长复合句 BM25 弱信号（hits=%d），拆分 %d 个子句独立 KNN 扩展%n",
                bm25TotalHits, validClauses.size());

        Set<String> existingIds = new HashSet<>();
        if (context.getCandidateDocs() != null) {
            context.getCandidateDocs().forEach(c -> {
                Object idObj = c.get("_id");
                if (idObj != null) existingIds.add(idObj.toString());
            });
        }

        // [P1 优化⑥] SubQuery 全并行化：向量获取 + KNN 搜索 + 结果收集合并为完整并行 pipeline。
        // 原链路：向量获取并行 → KNN 搜索串行（N × T），总耗时 = T_vec + N × T_knn
        // 优化后：每个子句的「向量获取 → KNN 搜索 → 结果收集」完全并行，总耗时 = max(T_vec + T_knn)
        // 假设 3 个子句，每个 KNN ~100ms：串行 = 300ms → 并行 = ~100ms，节省 ~200ms。
        final SysAiTuningConfig subQueryConfig = context.getTuningConfig() != null
                ? context.getTuningConfig()
                : new SysAiTuningConfig();
        List<CompletableFuture<List<Map<String, Object>>>> subQueryFutures = validClauses.stream()
                .map(clause -> com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                    List<Map<String, Object>> results = new ArrayList<>();
                    try {
                        // Step 1: 获取子句向量
                        List<Double> subVec = aiEngineGateway.fetchQueryVector(clause);
                        if (subVec == null || subVec.isEmpty()) return results;

                        // Step 2: KNN 搜索
                        co.elastic.clients.elasticsearch.core.SearchRequest subKnnReq =
                            new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
                                .index(indexPattern)
                                .knn(knn -> knn.field("vector").queryVector(subVec).k(20).numCandidates(subQueryConfig.resolveKnnNumCandidates(20))
                                    .filter(f -> f.bool(b -> {
                                        b.filter(ft -> ft.bool(boolQuery -> boolQuery
                                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                            .minimumShouldMatch("1")
                                        ));
                                        b.filter(ft -> ft.bool(bq -> bq
                                            .should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")))
                                            .should(s -> s.bool(bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))))
                                            .minimumShouldMatch("1")
                                        ));
                                        b.filter(utils.buildLegacyPermFilter(finalIsAnonymous, finalUserId, finalDeptValues));
                                        if (finalForceSource != null && !finalForceSource.isEmpty()) {
                                            b.filter(ft -> ft.term(t -> t.field("metadata.data_source").value(finalForceSource)));
                                        }
                                        if (!finalDocCandidateSources.isEmpty()) {
                                            b.filter(ft -> ft.terms(t -> t.field("metadata.source").terms(tv -> tv.value(finalDocCandidateSources))));
                                        }
                                        return b;
                                    })))
                                .size(20)
                                .source(s -> s.fetch(true))
                                .build();

                        co.elastic.clients.elasticsearch.core.SearchResponse<Object> subResp =
                                esClient.search(subKnnReq, Object.class);
                        if (subResp == null || subResp.hits() == null) return results;

                        // Step 3: 收集结果
                        for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : subResp.hits().hits()) {
                            String hitId = hit.id();
                            double subSim = hit.score() != null ? hit.score() : 0.0;
                            if (subSim < 0.50) continue;
                            Map<String, Object> subDoc = new HashMap<>();
                            subDoc.put("_id", hitId);
                            subDoc.put("_source", hit.source());
                            subDoc.put("_rrf_score", subSim * 0.015);
                            subDoc.put("_max_knn_score", subSim);
                            subDoc.put("_sub_query_hit", Boolean.TRUE);
                            results.add(subDoc);
                        }
                    } catch (Exception e) {
                        System.err.printf("[SubQuery] 子句 '%s' 处理失败: %s%n",
                                clause.length() > 20 ? clause.substring(0, 20) + "..." : clause, e.getMessage());
                    }
                    return results;
                }))
                .collect(Collectors.toList());

        // 等待所有子句并行完成；超时必须可配置，避免子查询扩展拖垮主链路 SLA。
        int newChunksAdded = 0;
        try {
            CompletableFuture.allOf(subQueryFutures.toArray(new CompletableFuture[0]))
                    .get(resolveSubQueryTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.err.println("[SubQuery] 并行执行超时，取已完成结果降级继续: " + e.getMessage());
        }

        // 合并去重结果
        if (context.getCandidateDocs() == null) {
            context.setCandidateDocs(new ArrayList<>());
        }
        for (CompletableFuture<List<Map<String, Object>>> future : subQueryFutures) {
            if (!future.isDone()) continue;
            try {
                List<Map<String, Object>> subResults = future.getNow(Collections.emptyList());
                for (Map<String, Object> subDoc : subResults) {
                    String hitId = String.valueOf(subDoc.get("_id"));
                    if (existingIds.add(hitId)) {
                        context.getCandidateDocs().add(subDoc);
                        newChunksAdded++;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (newChunksAdded > 0) {
            System.out.printf("[SubQuery] 并行完成，共追加 %d 个新 chunk 到候选池%n", newChunksAdded);
        }
    }
}
