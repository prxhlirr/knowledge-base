package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.service.SysGovSynonymService;
import com.boyang.search.service.SysAiTuningConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 管线节点 3：Elasticsearch 多路召回
 * 负责? * - Pre-Flight Probe（文?文件名精准短路拦截，含权限过滤）
 * - 组装及执?BM25 查询（权限策略、别名同义词扩展? * - 组装及执?KNN 向量查询（与 BM25 权限过滤完全对等? * - 把候选结果记录在 Context ?candidateDocs
 *
 * [P0 止血修复]
 * - Pre-Flight 探针已补充权限过滤，防止用户通过猜测文件名探测私密文? * - KNN Filter 已与 BM25 过滤逻辑对齐，消?PRIVATE/GRANT 类文档的向量侧数据穿透漏? * - buildLegacyPermFilter() 统一管理权限DSL，BM25/KNN/PreFlight三路共用，杜绝逻辑不一? */
@Component
public class EsRecallStep implements SearchPipelineStep {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private SysGovSynonymService govSynonymService;

    // [Batch2 新增] 注入 AI 网关，用于获取查询稀疏向量（Sparse 第三路召回）
    @Autowired
    private AiEngineGateway aiEngineGateway;

    // [P0-2 补充] 用于 extractCoreTerms 的噪词表读取
    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    private static final String DEFAULT_ORG  = "默认组织";
    private static final String DEFAULT_DATE = "2024-01-10";

    @Override
    public void execute(SearchContext context) throws Exception {
        String normalizedQuery = context.getNormalizedQuery();
        String queryText = context.getQueryText();
        String rewrittenQuery = context.getRewrittenQuery();
        SysTenantPolicy policy = context.getTenantPolicy();
        SysAiTuningConfig config = context.getTuningConfig();
        Map<String, Object> filters = context.getFilters();
        List<Double> queryVector = context.getQueryVector();

        // 解析权限过滤器（?Pre-Flight 之前提取，供后续三路查询共用?        // 提前提取是为了让 Pre-Flight 也能应用权限校验，防止通过文号枚举探测私密文档
        String pfUserId       = filters != null ? (String) filters.get("user_id") : null;
        String pfUserDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        final List<co.elastic.clients.elasticsearch._types.FieldValue> pfDeptValues = buildDeptValues(pfUserDeptCode);
        final boolean pfIsAnonymous = (pfUserId == null || pfUserId.trim().isEmpty());
        final String  pfFinalUserId = pfUserId;

        // 1. Pre-Flight Probe（[P0修复] 已加权限过滤，防止通过文号/文件名猜测探测私密文档）
        if (rewrittenQuery != null && rewrittenQuery.length() > 3) {
            String pfForceSource = filters != null ? (String) filters.get("data_source") : null;
            SearchRequest preFlightReq = new SearchRequest.Builder()
                .index(policy.getIndexPattern())
                .size(5)
                .timeout("1000ms")
                .query(q -> q.bool(b -> {
                    b.should(s -> s.term(t -> t.field("metadata.source").value(normalizedQuery)));
                    b.should(s -> s.term(t -> t.field("metadata.document_number").value(normalizedQuery)));
                    b.minimumShouldMatch("1");
                    // 版本过滤
                    b.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                    ));
                    // [P0止血] 权限过滤：与 BM25/KNN 使用同一?buildLegacyPermFilter 逻辑
                    b.filter(buildLegacyPermFilter(pfIsAnonymous, pfFinalUserId, pfDeptValues));
                    if (pfForceSource != null && !pfForceSource.isEmpty()) {
                        b.filter(f -> f.term(t -> t.field("metadata.data_source").value(pfForceSource)));
                    }
                    return b;
                }))
                .build();

            try {
                SearchResponse<Object> preFlightRes = esClient.search(preFlightReq, Object.class);
                if (!preFlightRes.hits().hits().isEmpty()) {
                    System.out.println("[Pre-Flight] MATCHED: Exact Title/ID Short-Circuiting.");
                    
                    Map<String, Map<String, Object>> ftSourceDeduped = new LinkedHashMap<>();
                    String highlightSource = (queryText + " " + rewrittenQuery).trim();
                    for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : preFlightRes.hits().hits()) {
                        Map<String, Object> rawSrc = (Map<String, Object>) hit.source();
                        if (rawSrc == null) continue;
                        Map<String, Object> meta = (Map<String, Object>) rawSrc.get("metadata");
                        String sourceName = meta != null ? (String) meta.getOrDefault("source", DEFAULT_ORG) : DEFAULT_ORG;

                        if (ftSourceDeduped.containsKey(sourceName)) continue;

                        Map<String, Object> docMap = new HashMap<>();
                        // [P1-3 补充] ?V1 对齐：PreFlight 结果也进?Java 层高亮处
                        String rawContent = (String) rawSrc.getOrDefault("content", "");
                        String snippet = generateFallbackSnippet(rawContent, queryText);
                        // ?snippet 中命中的查询词加 <em> 高亮标记
                        docMap.put("chunk_text",    highlightText(snippet, highlightSource));
                        docMap.put("organization",  sourceName);
                        docMap.put("publish_time",  meta != null ? meta.getOrDefault("publish_time", DEFAULT_DATE) : DEFAULT_DATE);
                        docMap.put("doc_id",        meta != null ? meta.get("doc_id") : null);
                        docMap.put("score",         0.95);
                        docMap.put("_id",           hit.id());
                        
                        ftSourceDeduped.put(sourceName, docMap);
                    }
                    context.setFastTrackDocs(new ArrayList<>(ftSourceDeduped.values()));
                    return; // 提前退
                }
            } catch (Exception e) {
                System.err.println("[Pre-Flight] probe failed: " + e.getMessage());
            }
        }

        // 2. BM25/KNN 权限变量复用 Pre-Flight 阶段已计算的结果（避免重复提取）
        String userDeptCode = pfUserDeptCode;
        String userId       = pfUserId;
        final List<co.elastic.clients.elasticsearch._types.FieldValue> deptValues = pfDeptValues;
        final boolean isAnonymous = pfIsAnonymous;
        final String  finalUserId = pfFinalUserId;

        // [P0-2 修复] 使用真实?extractCoreTerms，复?V1 逻辑?        // 对短词（2~4字）直接以自身为锚词；对长查询过滤噪词后提取最?个核心词?        // 核心词用?BM25 must-boost 子句（^20/^30），提升精确匹配权重
        List<String> anchorTerms = extractCoreTerms(normalizedQuery, config);
        context.setCoreTerms(anchorTerms);

        // 3. 构建 BM25 检?DSL
        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        final List<String> finalAnchorTerms = anchorTerms;
        SearchRequest textRequest = new SearchRequest.Builder()
            .index(policy.getIndexPattern())
            .trackTotalHits(h -> h.count(200))
            .size(Math.max(context.getTopK() * 2, 60))
            .timeout(config.getEsQueryTimeout() + "ms")
            .query(q -> q.bool(b -> {
                // 1. 核心?Boost 子句（最高优先级?                // [P0-2 修复] ?V1 完全对等：短?1?必须匹配，多锚词至少匹配2
                if (!finalAnchorTerms.isEmpty()) {
                    b.should(m -> m.bool(coreBool -> {
                        for (String coreTerm : finalAnchorTerms) {
                            coreBool.should(s -> s.match(ma -> ma
                                .field("content")
                                .query(coreTerm)
                                .boost(5.0f)));
                        }
                        coreBool.minimumShouldMatch("1");
                        return coreBool;
                    }));
                    String coreTermClause = String.join(" ", finalAnchorTerms);
                    String msm = finalAnchorTerms.size() == 1 ? "1" : "2";
                    b.should(s -> s.match(ma -> ma
                        .field("content")
                        .query(coreTermClause)
                        .minimumShouldMatch(msm)
                        .boost(20.0f)
                    ));
                    b.should(s -> s.matchPhrase(ma -> ma
                        .field("content")
                        .query(coreTermClause)
                        .slop(15)
                        .boost(30.0f)
                    ));
                }

                // 2. 原查询词 BM25 匹配（至?50% token 命中，防止单字误命中
                b.should(sh -> sh.match(ma -> ma.field("content").query(normalizedQuery).minimumShouldMatch("50%")));

                // 3. [P0-1 补充] matchPhrase 短语锚定（^15）：精确词序命中加权
                if (normalizedQuery != null && normalizedQuery.length() > 2) {
                    b.should(sh -> sh.matchPhrase(mp -> mp
                        .field("content")
                        .query(normalizedQuery)
                        .slop(3)
                        .boost(15.0f)));
                }

                // 4. 标题/文号/关键词字段高权重 multiMatch
                // [P0-1 补充] 补充 metadata.tags_kw^10，对齐 V1 的完整字段列表
                // [标题检索修复] 新增 metadata.title^25：text 类型支持 IK 分词
                //   title > source（文件名精确）> document_number > keywords > tags_kw
                b.should(s -> s.multiMatch(mm -> mm.query(normalizedQuery)
                    .fields("metadata.title^25.0", "metadata.source^20.0", "metadata.document_number^20.0",
                            "keywords^15.0", "metadata.tags_kw^10.0")
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)));

                // 5. 同义词政务展开（应用层展开，不依赖 ES synonym filter
                final Map<String, List<String>> synonymMap = govSynonymService.getSynonymMap();
                if (synonymMap != null) {
                    for (Map.Entry<String, List<String>> entry : synonymMap.entrySet()) {
                        if (normalizedQuery.contains(entry.getKey())) {
                            for (String expandedTerm : entry.getValue()) {
                                if (!expandedTerm.equals(entry.getKey())) {
                                    final String et = expandedTerm;
                                    b.should(sh -> sh.match(ma -> ma.field("content").query(et).boost(3.0f)));
                                }
                            }
                            System.out.println("[GovAbbrExpander] 展开 '" + entry.getKey() + "' ?" + entry.getValue());
                        }
                    }
                }

                // 6. [P0-1 补充] 改写词注?BM25 + matchPhrase（语义扩写词命中额外加权
                final String rwq = rewrittenQuery;
                if (rwq != null && !rwq.isEmpty() && !rwq.equals(normalizedQuery)) {
                    b.should(sh -> sh.match(ma -> ma.field("content").query(rwq).boost(8.0f)));
                    // 改写词短语命中额外加分（对应 V1 boost=20 slop=5
                    b.should(sh -> sh.matchPhrase(mp -> mp
                        .field("content")
                        .query(rwq)
                        .slop(5)
                        .boost(20.0f)));
                }

                b.filter(f -> f.bool(boolQuery -> boolQuery
                    .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                    .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                ));

                // [P0修复] 权限过滤调用统一?buildLegacyPermFilter()，与 KNN/PreFlight 逻辑完全对等
                b.filter(buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));

                if (forceSource != null && !forceSource.isEmpty()) {
                    b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                }
                return b;
            }))
            .highlight(h -> h.fields("content", hf -> hf.preTags("<em class='highlight'>").postTags("</em>").fragmentSize(150).numberOfFragments(1)))
            .build();

        // 4. 构建 KNN 检?DSL
        SearchRequest knnRequest = null;
        if (queryVector != null && !queryVector.isEmpty()) {
            knnRequest = new SearchRequest.Builder()
                .index(policy.getIndexPattern())
                .knn(k -> k.field("vector").queryVector(queryVector).k(Math.max(context.getTopK() * 3, 100))
                    .numCandidates(config.getKnnNumCandidates())
                    .filter(f -> f.bool(b -> {
                        // 版本过滤
                        b.filter(ft -> ft.bool(boolQuery -> boolQuery
                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        ));
                        // KNN 分块粒度过滤：优先取粗粒度分块减少噪
                        b.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")));
                        b.should(s -> s.bool(bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))));
                        b.minimumShouldMatch("1");
                        // [P0止血] KNN权限过滤调用统一方法，修复原来仅判断PUBLIC/INTERNAL+DEPT?                        // 导致PRIVATE/GRANT类文档可被任意登录用户通过向量检索穿透拉取的高危漏洞
                        b.filter(buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                        if (forceSource != null && !forceSource.isEmpty()) {
                            b.filter(ft -> ft.term(t -> t.field("metadata.data_source").value(forceSource)));
                        }
                        return b;
                    })))
                .highlight(h -> h.fields("content", hf -> hf.preTags("<em class='highlight'>").postTags("</em>").fragmentSize(150)))
                .build();
        }

        // 5. 并发查询 ES（BM25 + KNN 两路
        CompletableFuture<SearchResponse<Object>> textFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            try { return esClient.search(textRequest, Object.class); } catch (Exception e) { throw new RuntimeException(e); }
        });

        final SearchRequest fKnnReq = knnRequest;
        CompletableFuture<SearchResponse<Object>> knnFuture = (knnRequest != null) ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            try { return esClient.search(fKnnReq, Object.class); } catch (Exception e) { throw new RuntimeException(e); }
        }) : CompletableFuture.completedFuture(null);

        // 6. [Batch2 新增] Sparse 第三路并发查?        // 设计目标：稀疏向量（rank_features）弥?BM25 IDF 缺陷，强化专业术语词汇信号?        // 降级策略：fetchSparseVector 内置 1.5s 超时，接口失败时返回 null?        //           sparseRequest ?null 时，RrfFusionStep 直接跳过 sparse 通道，主链路不受影响
        final String sparseQuery = normalizedQuery;
        CompletableFuture<SearchResponse<Object>> sparseFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            try {
                // [Step2 优化] 短查询路径：sparse 向量已由 VectorFetchStep.fetchDualVector 预取并存?context?                //   直接复用，跳过重复的 Python HTTP 调用（节?~500ms）?                // 长查询路径（HyDE）：context ?querySparseVector ?null，降级到原来?fetchSparseVector 独立调用
                Map<String, Double> sparseVector = context.getQuerySparseVector();
                if (sparseVector == null || sparseVector.isEmpty()) {
                    sparseVector = aiEngineGateway.fetchSparseVector(sparseQuery);
                } else {
                    System.out.printf("[EsRecallStep] Sparse vector from context (dual-prefetch), terms=%d%n", sparseVector.size());
                }
                if (sparseVector == null || sparseVector.isEmpty()) return null;

                // 取权重最高的 TOP-16 词，构?rank_features + saturation 查询
                // 根因：ES rank_features ?saturation 函数对词权重做对数压缩，
                //       ?TOP-16 是性能与精度的折中（权重尾部词对排名贡献可忽略
                List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(sparseVector.entrySet());
                sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                final int TOP_N_SPARSE = Math.min(16, sortedEntries.size());

                SearchRequest sparseReq = new SearchRequest.Builder()
                    .index(policy.getIndexPattern())
                    .size(Math.max(context.getTopK() * 2, 40))
                    .timeout("2000ms")
                    .query(q -> q.bool(b -> {
                        // rank_features 查询：对每个高权?token 构?saturation 子句
                        for (int i = 0; i < TOP_N_SPARSE; i++) {
                            final String token  = sortedEntries.get(i).getKey();
                            final float  weight = sortedEntries.get(i).getValue().floatValue();
                            b.should(s -> s.rankFeature(rf -> rf
                                .field("sparse_vector." + token)
                                .saturation(sat -> sat.pivot(weight))));
                        }
                        b.minimumShouldMatch("1");
                        // 版本过滤（与 BM25/KNN 保持一致）
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        ));
                        // [P1-4 补充] coarse 粒度过滤，与 KNN 保持一致，减少碎片 chunk 混入
                        b.filter(f -> f.bool(bq -> bq
                            .should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")))
                            .should(s -> s.bool(bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))))
                            .minimumShouldMatch("1")
                        ));
                        // 权限过滤（复用统一方法，与 BM25/KNN 完全对等
                        b.filter(buildLegacyPermFilter(pfIsAnonymous, pfFinalUserId, pfDeptValues));
                        if (forceSource != null && !forceSource.isEmpty()) {
                            b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                        }
                        return b;
                    }))
                    .build();

                return esClient.search(sparseReq, Object.class);
            } catch (Exception e) {
                System.err.println("[EsRecallStep] Sparse query failed (degrading): " + e.getMessage());
                return null;
            }
        });

        SearchResponse<Object> textResponse  = null;
        SearchResponse<Object> knnResponse   = null;
        SearchResponse<Object> sparseResponse = null;

        // [性能优化 P0] 三路 ES allOf 并行等待，总耗时 = max(BM25,KNN,Sparse) 而非三者累加
        // 根因：原代码三次串行 .get()，实际等待 = T(BM25)+T(KNN)+T(Sparse)，额外浪费 100~300ms。
        //       三路 Future 已并发 submit，改为 allOf 后总等待 = max 三者，逻辑语义完全一致。
        // 超时策略：以 esQueryTimeout 为统一上限，超时后取各自已完成的结果；未完成的降级 null。
        try {
            CompletableFuture.allOf(textFuture, knnFuture, sparseFuture)
                    .get(config.getEsQueryTimeout(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[EsRecallStep] ES 三路 allOf 超时，取已完成结果降级继续");
        } catch (Exception e) {
            System.err.println("[EsRecallStep] allOf 异常: " + e.getMessage());
        }
        // isDone 包含正常完成和异常完成，getNow(null) 在未完成时返回 null 不抛异常
        try { textResponse   = textFuture.isDone()   ? textFuture.getNow(null)   : null; } catch (Exception ignored) {}
        try { knnResponse    = knnFuture.isDone()    ? knnFuture.getNow(null)    : null; } catch (Exception ignored) {}
        try { sparseResponse = sparseFuture.isDone() ? sparseFuture.getNow(null) : null; } catch (Exception ignored) {}

        // [Batch2] 保存 ES 原始结果?SearchContext 专用强类型字?        // 替换原有 filters.__es_raw_responses 魔法 key 方案（P2-1 修复
        context.setBm25Response(textResponse);
        context.setKnnResponse(knnResponse);
        context.setSparseResponse(sparseResponse);

        // [P1-2 补充] 计算并写?BM25 命中数和平坦度，?RerankStep 高频?平坦度检测使
        long textTotalHits = (textResponse != null && textResponse.hits() != null
                && textResponse.hits().total() != null) ? textResponse.hits().total().value() : 0L;
        context.setBm25TextHits(textTotalHits);
        // [P1-2 修复] 计算平坦度比（top1/top5avg）并写入强类型字段?        // 根因：原方案依赖 context.getFilters() 非空，FastTrack 提前 return ?ratio 未写入，
        //       RerankStep 读到 null 导致 bm25IsFlat 永远 false，平坦度检测静默失效?        // 修复：改?setBm25FlatnessRatio()，默认?99.0（不平坦），完全消除 null 风险
        if (textResponse != null && textResponse.hits().hits().size() >= 5) {
            double bfTop1 = textResponse.hits().hits().get(0).score() != null
                    ? textResponse.hits().hits().get(0).score() : 0.0;
            double bfTop5Avg = textResponse.hits().hits().subList(0, 5).stream()
                    .mapToDouble(h -> h.score() != null ? h.score() : 0.0)
                    .average().orElse(0.0);
            double flatnessRatio = bfTop5Avg > 0.001 ? bfTop1 / bfTop5Avg : 99.0;
            context.setBm25FlatnessRatio(flatnessRatio);  // 强类型字? 非魔?Key
        }

        System.out.println("====== [Pipeline] Node 3: Elasticsearch Retrieval ======");
        System.out.printf("  - BM25 hits: %d (total=%d) | KNN hits: %d | Sparse hits: %d%n",
            textResponse  != null ? textResponse.hits().hits().size()   : 0,
            textTotalHits,
            knnResponse   != null ? knnResponse.hits().hits().size()    : 0,
            sparseResponse != null ? sparseResponse.hits().hits().size() : 0);

        // [P0-2 修复] SubQuery 多向?KNN 扩展（内联，无需独立 Step?        // 触发条件：BM25 弱信号（词汇鸿沟场景? 长复合句含中文标?        // 根因：BGE-M3 对多子句?MeanPool 后质心向量偏移，每个子句覆盖?chunk
        //       余弦相似度从 0.75+ 降至 0.55-0.65，正?chunk 排名跌至 ColBERT 截断窗口之外?        // 修复：拆分子句各自独立编?+ KNN，将?chunk 去重后追加候选池
        runSubQueryExpansion(context, textTotalHits, policy);
    }



    /**
     * SubQuery 多向?KNN 扩展（对?V1 SearchService.java L1357-1446）?     *
     * 业务功能?     *   针对「词汇鸿沟」场景（BM25 弱信?+ 含标点的长复合查询），将查询拆分为子句，
     *   各子句独立编码后?KNN 检索，将新命中?chunk 去重追加到候选池?     *
     * 触发条件（AND 关系，全部满足才触发）：
     *   1. bm25TotalHits < 2.0（弱词汇信号，说明存在词汇鸿沟）
     *   2. 向量可用（非 skipEmbedding 场景?     *   3. 查询含中文标点分隔的多子?     *
     * @param context       当前搜索上下?     * @param bm25TotalHits BM25 命中总数（弱信号判定阈值）
     * @param policy        租户策略（用于获取索引名?     */
    private void runSubQueryExpansion(com.boyang.search.pipeline.SearchContext context,
                                      long bm25TotalHits,
                                      com.boyang.search.entity.SysTenantPolicy policy) {
        // 触发阈值：BM25 命中?< 2，才认为存在词汇鸿沟，触发子查询扩展
        if (bm25TotalHits >= 2) return;
        if (context.getQueryVector() == null || context.getQueryVector().isEmpty()) return;

        String normalizedQuery = context.getNormalizedQuery();
        String[] subParts = normalizedQuery.split("[??、。！？]+");
        List<String> validClauses = java.util.Arrays.stream(subParts)
                .map(String::trim)
                .filter(s -> s.length() > 6)
                .collect(Collectors.toList());

        if (validClauses.size() < 2) return;

        System.out.printf("[SubQuery] 长复合句 BM25 弱信号（hits=%d），拆分 %d 个子句独?KNN 扩展%n",
                bm25TotalHits, validClauses.size());

        // 预取候选池中已?id 集合（用于去重）
        Set<String> existingIds = new HashSet<>();
        if (context.getCandidateDocs() != null) {
            context.getCandidateDocs().forEach(c -> {
                Object idObj = c.get("_id");
                if (idObj != null) existingIds.add(idObj.toString());
            });
        }

        int newChunksAdded = 0;

        // 并行获取子句 dense 向量（各 2s 超时，失败静默跳过）
        List<CompletableFuture<List<Double>>> vecFutures = validClauses.stream()
                .map(clause -> com.boyang.search.util.AsyncContextUtil.supplyAsync(
                        () -> aiEngineGateway.fetchQueryVector(clause)))
                .collect(Collectors.toList());

        for (int i = 0; i < validClauses.size(); i++) {
            try {
                List<Double> subVec = vecFutures.get(i).get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (subVec == null) continue;

                // 子句 KNN：top-20，numCandidates=100
                // 注意：当?ES 客户端版本不支持 KnnQuery.Builder.similarity()?                // 改为在结果循环中手动过滤低于 0.50 相似度的 chunk
                co.elastic.clients.elasticsearch.core.SearchRequest subKnnReq =
                    new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
                        .index(policy.getIndexPattern())
                        .knn(knn -> knn
                            .field("dense_vector")
                            .queryVector(subVec)
                            .k(20)
                            .numCandidates(100))
                        .size(20)
                        .source(s -> s.fetch(true))
                        .build();

                co.elastic.clients.elasticsearch.core.SearchResponse<Object> subResp =
                        esClient.search(subKnnReq, Object.class);

                if (subResp == null || subResp.hits() == null) continue;

                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : subResp.hits().hits()) {
                    String hitId = hit.id();
                    if (hitId == null || existingIds.contains(hitId)) continue;

                    // ES 8.6.2 不支?KnnQuery.similarity()，在此手动过滤低相似度片
                    double subSim = hit.score() != null ? hit.score() : 0.0;
                    if (subSim < 0.50) continue;

                    // 构造与主管道同格式的候?doc
                    Map<String, Object> subDoc = new HashMap<>();
                    subDoc.put("_id", hitId);
                    subDoc.put("_source", hit.source());
                    // _rrf_score 量级对齐主管道（subSim * 0.015
                    subDoc.put("_rrf_score", subSim * 0.015);
                    subDoc.put("_max_knn_score", subSim);
                    subDoc.put("_sub_query_hit", Boolean.TRUE);  // 标记来源

                    context.getCandidateDocs().add(subDoc);
                    existingIds.add(hitId);
                    newChunksAdded++;
                }
                System.out.printf("[SubQuery] 子句#%d '%s...' ?%d new chunks%n",
                        i + 1, validClauses.get(i).substring(0, Math.min(10, validClauses.get(i).length())),
                        newChunksAdded);

            } catch (java.util.concurrent.TimeoutException te) {
                System.err.printf("[SubQuery] 子句#%d 向量获取超时?s），跳过%n", i + 1);
            } catch (Exception e) {
                System.err.printf("[SubQuery] 子句#%d 处理失败: %s%n", i + 1, e.getMessage());
            }
        }

        if (newChunksAdded > 0) {
            System.out.printf("[SubQuery] 共追?%d 个新 chunk 到候选池%n", newChunksAdded);
        }
    }



    /**
     * 统一权限过滤DSL构建器（[Phase 1] 混合双模架构）?     *
     * 核心逻辑?     *   分支A（新架构）：文档 acl_tokens 字段与用?aclTokens 集合进行 terms 求交，O(1) 判定?     *   分支B（旧架构降级）：文档?acl_tokens 字段（history data），降级使用 visibility 多路 bool 判定?     *   超管旁路：用?aclTokens 中包?_SUPER_ADMIN 时直接返?match_all 放行全部文档?     *
     * BM25、KNN、Pre-Flight 三路查询共用此方法，确保权限校验逻辑在全链路保持一致?     *
     * @param isAnonymous  是否为匿名用户（userId为空?     * @param userId       当前用户ID
     * @param deptValues   用户部门编码展开的层级列表（用于旧架构DEPT类文档过滤）
     * @return ES Filter Query
     */
    private Function<co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder, co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>>
        buildLegacyPermFilter(boolean isAnonymous, String userId, List<co.elastic.clients.elasticsearch._types.FieldValue> deptValues) {

        // 取出本线程在拦截器阶段预计算的扁平化 ACL Token 集合
        java.util.Set<String> aclTokens = com.boyang.search.security.UserContextHolder.getAclTokens();
        
        // 如果包含 _SUPER_ADMIN，直接放行全部（通过构建一?match_all query
        if (aclTokens.contains("_SUPER_ADMIN")) {
            return f -> f.matchAll(m -> m);
        }

        // 构?terms 查询所需的值列
        List<co.elastic.clients.elasticsearch._types.FieldValue> tokenValues = new ArrayList<>();
        for (String token : aclTokens) {
            tokenValues.add(co.elastic.clients.elasticsearch._types.FieldValue.of(token));
        }

        return f -> f.bool(mixedBool -> {
            // 分支 A：新架构查询 —?只要文档?acl_tokens 数组中包含用户拥有的任一 token，即算有?            // （扁平化求交，性能极高
            mixedBool.should(s -> s.terms(t -> t.field("acl_tokens").terms(tv -> tv.value(tokenValues))));

            // 分支 B：旧架构降级（兼容历史未刷数的文档） —?文档不存?acl_tokens 字段
            mixedBool.should(s -> s.bool(legacyBool -> {
                // 前置条件：没?acl_tokens 字段（存量脏数据
                legacyBool.mustNot(mn -> mn.exists(e -> e.field("acl_tokens")));
                
                // 接上以前复杂的旧规则判定
                legacyBool.must(m -> m.bool(permBool -> {
                    if (isAnonymous) {
                        permBool.should(sh -> sh.term(t -> t.field("metadata.visibility").value("PUBLIC")));
                        permBool.should(sh -> sh.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.visibility")))));
                    } else {
                        permBool.should(sh -> sh.terms(t -> t.field("metadata.visibility")
                            .terms(tv -> tv.value(Arrays.asList(
                                co.elastic.clients.elasticsearch._types.FieldValue.of("PUBLIC"),
                                co.elastic.clients.elasticsearch._types.FieldValue.of("INTERNAL")
                            )))));
                        permBool.should(sh -> sh.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.visibility")))));
                        if (!deptValues.isEmpty()) {
                            permBool.should(sh -> sh.terms(t -> t.field("metadata.dept_code_full").terms(tv -> tv.value(deptValues))));
                        }
                        permBool.should(sh -> sh.bool(bPrivate -> bPrivate
                            .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("PRIVATE")))
                            .must(m2 -> m2.term(t -> t.field("metadata.uploader_id").value(userId)))));
                        permBool.should(sh -> sh.bool(bGrant -> bGrant
                            .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("GRANT")))
                            .must(m2 -> m2.term(t -> t.field("metadata.granted_users").value(userId)))));
                    }
                    permBool.minimumShouldMatch("1");
                    return permBool;
                }));
                return legacyBool;
            }));

            // 只要命中分支 A (新架构有权限) ?分支 B (老架构有权限) 即可
            mixedBool.minimumShouldMatch("1");
            return mixedBool;
        });
    }

    /**
     * 将用户部门编码展开为层级前缀列表，用于未刷数 DEPT类文档的旧架构权限匹配?     */
    private List<co.elastic.clients.elasticsearch._types.FieldValue> buildDeptValues(String deptCode) {
        if (deptCode == null || deptCode.isEmpty()) return new ArrayList<>();
        List<co.elastic.clients.elasticsearch._types.FieldValue> list = new ArrayList<>();
        String[] parts = deptCode.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("-");
            sb.append(p);
            list.add(co.elastic.clients.elasticsearch._types.FieldValue.of(sb.toString()));
        }
        return list;
    }


    /**
     * 从文档内容中提取关键词定位摘要（对应 V1 generateFallbackSnippet 逻辑）?     *
     * 业务功能?     *   1. OOM Guard：将超长 content 截断?2000 字再处理
     *   2. 清除 [标签: 内容] 格式的上下文标注前缀
     *   3. ?queryText 定位关键词位置，截取前后上下文作为摘?     *   4. 兜底：无法定位时返回?100 ?     *
     * @param content   文档原始 content 字段
     * @param queryText 原始用户查询词（用于定位摘要起点?     */
    private String generateFallbackSnippet(String content, String queryText) {
        if (content == null || content.isEmpty()) return "";
        // OOM guard：replaceAll 对超长字符串会构建等?StringBuffer，需先截
        final int MAX_CONTENT = 2000;
        String safeContent = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        // 清除形如 [语篇语境: 乡道] 的上下文标注前缀
        String cleanContent = safeContent.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
        if (queryText == null || queryText.isEmpty()) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int idx = cleanContent.indexOf(queryText);
        if (idx == -1) {
            // 兜底：返回前 100 
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int start = Math.max(0, idx - 40);
        int end   = Math.min(cleanContent.length(), idx + queryText.length() + 60);
        String snippet = cleanContent.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < cleanContent.length()) snippet = snippet + "...";
        return snippet;
    }

    /**
     * Java 层高亮：?snippet 中命中的查询词包?&lt;em class='highlight'&gt; 标签?     *
     * 业务功能：当 ES highlight 字段为空（KNN 检索或 highlight 配置未触发）时，
     * ?Java 层对 generateFallbackSnippet 返回的摘要手动添加高亮标记?     *
     * @param text  待高亮的摘要文本
     * @param query 查询词（空格分隔多词?     */
    private String highlightText(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty()) return text;
        // OOM Guard：限定高亮操作的文本长度上界，防?replaceAll StringBuffer 溢出
        final int MAX_HIGHLIGHT_LEN = 1500;
        String safeText = text.length() > MAX_HIGHLIGHT_LEN ? text.substring(0, MAX_HIGHLIGHT_LEN) : text;
        // 按词分割后按长度降序排序，确保长词优先匹配（防止短词提前替换破坏长词标记
        Set<String> kwSet = new HashSet<>(Arrays.asList(query.split("\\s+")));
        List<String> sortedKws = new ArrayList<>(kwSet);
        sortedKws.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = safeText;
        for (String kw : sortedKws) {
            if (kw.trim().length() < 1) continue;
            try {
                String pattern = "(?i)(" + Pattern.quote(kw) + ")";
                result = result.replaceAll(pattern, "<em class='highlight'>$1</em>");
            } catch (Exception e) {
                // 正则异常时跳过该词，不影响其他词的高
            }
        }
        return result;
    }

    /**
     * 从归一化查询中提取核心锚词（对?V1 extractCoreTerms 逻辑）?     *
     * 业务功能?     *   - 2~4 字短词直接以自身为锚词（短词即核心）
     *   - 长查询过?DB 噪词表，提取最?3 个核心词用于 BM25 Boost
     *   - 锚词用于 BM25 match^20 + matchPhrase^30 强制加权，显著提升精确命中排?     *
     * @param query  归一化后的查询词
     * @param config 调参配置（读?noiseWords 停用词列表）
     */
    private List<String> extractCoreTerms(String query, SysAiTuningConfig config) {
        List<String> coreTerms = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return coreTerms;
        String trimmed = query.trim();
        // 短词?~4字）：整词即为语义核心，直接作为唯一锚词，激?boost 子句
        if (trimmed.length() >= 2 && trimmed.length() <= 4) {
            coreTerms.add(trimmed);
            System.out.println("[CoreTermAnchor] Short query self-anchor: [" + trimmed + "]");
            return coreTerms;
        }
        // 长查询：过滤噪词 + 提取中文词段
        String remaining = trimmed;
        List<String> noiseList = config != null ? config.getNoiseWordList() : Collections.emptyList();
        for (String noise : noiseList) {
            if (noise == null || noise.isEmpty()) continue;
            remaining = remaining.replace(noise, " ");
        }
        remaining = remaining.replaceAll("[^\\u4e00-\\u9fa5]+", " ").trim();
        String[] fragments = remaining.split("\\s+");
        for (String frag : fragments) {
            if (frag.length() >= 2 && !coreTerms.contains(frag)) {
                coreTerms.add(frag);
                if (coreTerms.size() >= 3) break;
            }
        }
        if (!coreTerms.isEmpty()) {
            System.out.println("[CoreTermAnchor] Extracted: " + coreTerms + " from: " + query);
        }
        return coreTerms;
    }
}

