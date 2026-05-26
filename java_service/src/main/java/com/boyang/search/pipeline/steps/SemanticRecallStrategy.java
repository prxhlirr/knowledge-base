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
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 语义召回策略（KNN稠密向量 + Sparse稀疏向量 + BM25尌底 + QA KNN 第四路并行）
 *
 * 业务功能：
 *   执行完整的「语义检索」三路并行召回 + QA 第四路：
 *   - KNN（稠密向量）  : 1024维 BGE-M3 FP16 余弦相似度检索，捕捉深层语义相似性
 *   - Sparse（稀疏向量）: rank_features + saturation，语义感知稀疏表示（类 SPLADE），
 *                        补充 KNN 在低频专业术语场景的召回不足（两者语义空间互补）
 *   - BM25 尌底   : 共同并行，保障文号/数字标识符召回
 *   - QA KNN第四路: Q2Q KNN 语义召回，结果写入 context.qaHits 供 RrfFusionStep 第四路融合
 *   BM25 写入 null，跳过纯词汇层面匹配。
 *
 * [架构重构] QA 第四路并行：
 *   semantic 模式有 queryVector，走 Q2Q KNN 召回 QA（精度最高），
 *   与 KNN/Sparse/BM25 并发执行，总耗时 = max(KNN, Sparse, BM25, QA_KNN)。
 *
 * 为何语义搜索包含 Sparse？
 *   Sparse 向量由 AI 模型生成，本质是"语义感知的词汇权重分布"，区别于 BM25 的 IDF 统计，
 *   能识别同义词和近义词关系。两者共同构成完整语义空间，缺任一都会在不同场景下低召回：
 *   - KNN alone  : 对低频术语（如特定法规编号）语义漂移，余弦相似度偏低
 *   - Sparse alone: 对意图理解（"如何申请..."->"申请流程"）弱于稠密向量
 *
 * 性能说明：
 *   四路并行执行（CompletableFuture.allOf），总耗时 = max(KNN, Sparse, BM25, QA_KNN)。
 *   Sparse 内置 1.5s 超时降级，接口失败后 sparseResponse = null，RrfFusion 自动跳过。
 */
@Component
public class SemanticRecallStrategy implements RecallStrategy {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private EsRecallUtils utils;

    @Override
    public void recall(SearchContext context) throws Exception {
        String normalizedQuery  = context.getNormalizedQuery();
        SysTenantPolicy   policy  = context.getTenantPolicy();
        SysAiTuningConfig config  = context.getTuningConfig();
        Map<String, Object> filters     = context.getFilters();
        List<Double>        queryVector = context.getQueryVector();
        String indexPattern = context.getResolvedIndexPattern() != null
                ? context.getResolvedIndexPattern()
                : policy.getIndexPattern();

        // 解析权限相关变量
        String userId       = filters != null ? (String) filters.get("user_id") : null;
        String userDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        final List<FieldValue> deptValues  = utils.buildDeptValues(userDeptCode);
        final boolean isAnonymous = (userId == null || userId.trim().isEmpty());
        final String  finalUserId = userId;
        String forceSource = filters != null ? (String) filters.get("data_source") : null;

        // ── 1. 构建 KNN 检索 DSL ──────────────────────────────────────────────
        // 向量为 null 说明 VectorFetchStep 降级（skipEmbedding=true），退化为空结果
        SearchRequest knnRequest = null;
        if (queryVector != null && !queryVector.isEmpty()) {
            knnRequest = new SearchRequest.Builder()
                .index(indexPattern)
                .knn(k -> {
                    int kVal = Math.max(context.getRecallTopK(), 100);
                    // [P1 修复] ES 要求 numCandidates >= k，取 max 确保约束成立，避免 ES 报错
                    int numCandidates = Math.max(config.getKnnNumCandidates(), kVal * 2);
                    return k.field("vector").queryVector(queryVector)
                        .k(kVal)
                        .numCandidates(numCandidates)
                        .filter(f -> f.bool(b -> {
                            // [Bug Fix - Phase1] 版本过滤（保留）
                            // 原来此处有 coarse-only 硬过滤（should coarse + minimumShouldMatch("1")），
                            // 导致所有 fine chunk（人员信息/条文级内容）被完全排除出 KNN 候选池。
                            // 正确设计：在 RrfFusionStep 中用软权重体现粒度偏好，而非在召回层硬切。
                            // 根因：公示文档的人员信息存储在 fine chunk，硬过滤令其永远无法被语义搜索命中。
                            b.filter(ft -> ft.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                            ));
                            b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                            if (forceSource != null && !forceSource.isEmpty()) {
                                b.filter(ft -> ft.term(t -> t.field("metadata.data_source").value(forceSource)));
                            }
                            return b;
                        }));
                })
                .highlight(h -> h.fields("content", hf -> hf
                    .preTags("<em class='highlight'>").postTags("</em>").fragmentSize(150)
                    .highlightQuery(hq -> hq.match(ma -> ma
                        .field("content").query(utils.stripNoiseWords(normalizedQuery, config))
                        .minimumShouldMatch("1")))))
                .build();
        }

        // ── 2. 构建 Sparse 检索并双路并发执行 ─────────────────────────────────
        final SearchRequest fKnnReq = knnRequest;
        CompletableFuture<SearchResponse<Object>> knnFuture = (knnRequest != null)
            ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try { return esClient.search(fKnnReq, Object.class); } catch (Exception e) { throw new RuntimeException(e); }
              })
            : CompletableFuture.completedFuture(null);

        final String sparseQuery = normalizedQuery;
        final List<FieldValue> sparsePermDeptValues = deptValues;
        CompletableFuture<SearchResponse<Object>> sparseFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            try {
                // 优先使用 VectorFetchStep 预取的稀疏向量（dual-prefetch 优化，跳过重复 HTTP）
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
                    .timeout("2000ms")
                    .query(q -> q.bool(b -> {
                        for (int i = 0; i < TOP_N_SPARSE; i++) {
                            final String token  = sortedEntries.get(i).getKey();
                            final float  weight = sortedEntries.get(i).getValue().floatValue();
                            b.should(s -> s.rankFeature(rf -> rf.field("sparse_vector." + token)
                                .saturation(sat -> sat.pivot(weight))));
                        }
                        b.minimumShouldMatch("1");
                        // [Bug Fix - Phase1] 同步移除 Sparse 通道的 coarse-only 硬过滤，与 KNN 通道保持一致。
                        // 原理：稀疏向量也应该能召回 fine chunk（人员信息短句），不应在召回层屏蔽。
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        ));
                        b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, sparsePermDeptValues));
                        if (forceSource != null && !forceSource.isEmpty()) {
                            b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                        }
                        return b;
                    }))
                    .build();
                return esClient.search(sparseReq, Object.class);
            } catch (Exception e) {
                System.err.println("[SemanticStrategy] Sparse query failed (degrading): " + e.getMessage());
                return null;
            }
        });

        // ── 3. 低权重 BM25 兜底通道（并发加入三路）────────────────────────────
        // 设计理由：语义模式的核心是 KNN+Sparse，但对文号、数字标识符（如 "国办发〔2024〕1号"）
        // 这类 token，向量 embedding 精度差，纯语义模式会严重漏召回。
        // BM25 兜底不主导排序（由 RrfFusionStep 分配低权重），但能保障精确词汇被捞上来。
        // 这里使用 30% minimumShouldMatch（宽松），避免过度过滤
        final String bm25Query = normalizedQuery;
        CompletableFuture<SearchResponse<Object>> bm25FallbackFuture =
            com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try {
                    SearchRequest bm25Req = new SearchRequest.Builder()
                        .index(indexPattern)
                        .size(Math.max(context.getRecallTopK(), 20))
                        .timeout("2000ms")
                        .query(q -> q.bool(b -> {
                            // 宽松 BM25（30%），目的是兜底，不是主导排序
                            b.should(sh -> sh.match(ma -> ma.field("content").query(bm25Query)
                                .minimumShouldMatch("30%")));
                            // 元数据字段精确命中（文号、标题）
                            b.should(s -> s.multiMatch(mm -> mm.query(bm25Query)
                                .fields("metadata.title^10.0", "metadata.document_number^10.0", "keywords^5.0")
                                .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)));
                            b.minimumShouldMatch("1");
                            b.filter(f -> f.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                            ));
                            b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                            if (forceSource != null && !forceSource.isEmpty()) {
                                b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                            }
                            return b;
                        }))
                        .build();
                    return esClient.search(bm25Req, Object.class);
                } catch (Exception e) {
                    System.err.println("[SemanticStrategy] BM25 fallback failed (degrading): " + e.getMessage());
                    return null;
                }
            });

        // [TEMP] 普通语义检索暂时禁用 QA 第四路，避免 QA 问答候选进入 RRF/重排评分。
        // final List<Double> finalQueryVec = queryVector;
        // final String finalQueryTextForQa = context.getQueryText();
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
        //             System.err.println("[SemanticStrategy] QA 第四路召回失败（降级空列表）: " + e.getMessage());
        //             return java.util.Collections.emptyList();
        //         }
        //     });
        CompletableFuture<List<Map<String, Object>>> qaFuture =
            CompletableFuture.completedFuture(java.util.Collections.emptyList());

        // 四路并行等待
        try {
            CompletableFuture.allOf(knnFuture, sparseFuture, bm25FallbackFuture, qaFuture)
                .get(config.getEsQueryTimeout(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[SemanticStrategy] KNN+Sparse+BM25+QA allOf 超时，取已完成结果降级继续");
        } catch (Exception e) {
            System.err.println("[SemanticStrategy] allOf 异常: " + e.getMessage());
        }

        SearchResponse<Object> knnResponse         = null;
        SearchResponse<Object> sparseResponse      = null;
        SearchResponse<Object> bm25FallbackResp    = null;
        List<Map<String, Object>> qaHits           = null;
        try { knnResponse      = knnFuture.isDone()          ? knnFuture.getNow(null)          : null; } catch (Exception ignored) {}
        try { sparseResponse   = sparseFuture.isDone()       ? sparseFuture.getNow(null)       : null; } catch (Exception ignored) {}
        try { bm25FallbackResp = bm25FallbackFuture.isDone() ? bm25FallbackFuture.getNow(null) : null; } catch (Exception ignored) {}
        try { qaHits           = qaFuture.isDone()           ? qaFuture.getNow(null)           : null; } catch (Exception ignored) {}

        // 写入三路结果（BM25 尌底参与 RRF 融合，权重由 RrfFusionStep 自适应分配）
        context.setBm25Response(bm25FallbackResp);
        context.setKnnResponse(knnResponse);
        context.setSparseResponse(sparseResponse);
        // [架构重构] 写入 QA 第四路召回结果，供 RrfFusionStep 作为第四路融合
        context.setQaHits(qaHits != null ? qaHits : java.util.Collections.emptyList());
        context.setBm25Hits(bm25FallbackResp != null ? bm25FallbackResp.hits().hits().size() : 0);
        context.setKnnHits(knnResponse != null ? knnResponse.hits().hits().size() : 0);
        context.setSparseHits(sparseResponse != null ? sparseResponse.hits().hits().size() : 0);
        context.setQaHitsCount(qaHits != null ? qaHits.size() : 0);

        // 更新 BM25 命中数（供 RrfFusionStep 动态权重计算）
        long bm25Hits = (bm25FallbackResp != null && bm25FallbackResp.hits() != null
                && bm25FallbackResp.hits().total() != null) ? bm25FallbackResp.hits().total().value() : 0L;
        context.setBm25TextHits(bm25Hits);
        // 语义模式 BM25 只是尌底，不用 FlatnessRatio 驱动 RRF 权重倾斜（固定低权重即可）
        context.setBm25FlatnessRatio(1.5);

        System.out.printf("[SemanticStrategy] KNN hits: %d | Sparse hits: %d | BM25 fallback hits: %d | QA hits: %d%n",
            knnResponse      != null ? knnResponse.hits().hits().size()      : 0,
            sparseResponse   != null ? sparseResponse.hits().hits().size()   : 0,
            bm25FallbackResp != null ? bm25FallbackResp.hits().hits().size() : 0,
            qaHits           != null ? qaHits.size() : 0);
    }
}
