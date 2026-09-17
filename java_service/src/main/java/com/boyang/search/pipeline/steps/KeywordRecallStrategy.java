package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.ObjectBuilder;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键字召回策略（纯 BM25 全文检索,不含 QA）
 *
 * 业务功能：
 * 执行 BM25 全文检索通道,跳过 KNN、Sparse 和 QA 第四路。
 * 适用于用户明确指定关键字匹配场景,例如：
 * - 按文号检索（"国办发〔2024〕1号"）
 * - 精确术语搜索（"碳排放权交易"）
 * - 对语义扩展不敏感的精确匹配需求
 *
 * [设计决策] keyword 模式不拉取 QA 结果：
 * 用户选择关键词检索时,意图是精确匹配文档原文内容,
 * QA 候选来自 Q2Q 语义匹配,与关键词精确检索意图相悖,会引入噪声。
 * qaHits 写空列表,RrfFusionStep 遇空自动跳过第四路,无需其他改动。
 *
 * 实现说明：
 * - knnResponse/sparseResponse 写 null,RrfFusionStep 跳过这两个空通道
 * - qaHits 写 emptyList,RrfFusionStep 跳过 QA 第四路
 * - bm25FlatnessRatio 照常计算,保证 RerankStep 消歧逻辑正常工作
 */
@Component
public class KeywordRecallStrategy implements RecallStrategy {

    private static final String DOC_KEY_FIELD = "metadata.source";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EsRecallUtils utils;

    @Value("${search.doc-search.index:#{systemEnvironment['KB_DOC_SEARCH_READ_ALIAS'] ?: 'kb_doc_search'}}")
    private String docSearchIndex;

    @Value("${search.doc-search.enabled:true}")
    private boolean docSearchEnabled;

    @Value("${search.doc-search.keyword-enabled:true}")
    private boolean docSearchKeywordEnabled;

    /**
     * [性能/可回滚] legacy chunk 索引召回是否启用 leading wildcard（*term*）。
     * 前缀通配符无法走倒排索引，在亿级 chunk 上是 O(term_dict) 全扫描，是召回延迟崩塌的引信之一。
     * 默认 false 关闭：legacy 索引不再做前缀通配（新 kb_doc_search 索引已用 ngram 覆盖该需求）。
     * 若发现精确度下降需回滚，设为 true 即恢复原行为。
     */
    @Value("${search.keyword.legacy-wildcard.enabled:false}")
    private boolean legacyWildcardEnabled;

    /**
     * 业务功能：控制 keyword 在 kb_doc_search 无结果或异常时是否回退到旧 chunk collapse 召回。
     * 设计原因：亿级数据下旧 chunk 召回是重路径，默认关闭；仅用于灰度回滚或应急排障时显式开启。
     */
    @Value("${search.keyword.legacy-fallback.enabled:false}")
    private boolean legacyFallbackEnabled;

    /**
     * 业务功能：控制 keyword 文档候选枚举的最大窗口。
     * 设计原因：亿级数据下候选窗口直接决定 ES 压力，必须能按索引规模和 SLA 动态调节。
     */
    @Value("${search.keyword.doc-candidate-max:${SEARCH_KEYWORD_DOC_CANDIDATE_MAX:500}}")
    private String keywordDocCandidateMax = "500";

    /**
     * 业务功能：控制 keyword 文档候选枚举的最小窗口。
     * 设计原因：小 topK 请求仍需保留一定候选余量，避免多词交集过早丢召回。
     */
    @Value("${search.keyword.doc-candidate-min:${SEARCH_KEYWORD_DOC_CANDIDATE_MIN:120}}")
    private String keywordDocCandidateMin = "120";

    /**
     * 业务功能：控制 keyword 文档候选窗口相对 topK 的放大倍数。
     * 设计原因：不同业务索引的关键词稀疏度不同，固定 topK*12 无法适配所有生产负载。
     */
    @Value("${search.keyword.doc-candidate-multiplier:${SEARCH_KEYWORD_DOC_CANDIDATE_MULTIPLIER:12}}")
    private String keywordDocCandidateMultiplier = "12";

    @Override
    public void recall(SearchContext context) throws Exception {
        String normalizedQuery = context.getNormalizedQuery();
        // 注意：关键字模式不使用 LLM 改写词（避免语义扩展破坏精确匹配意图）
        SysTenantPolicy   policy  = context.getTenantPolicy();
        SysAiTuningConfig config  = context.getTuningConfig();
        Map<String, Object> filters = context.getFilters();
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
        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        final List<String> requiredTerms = plan != null ? plan.safeRequiredTerms() : splitTerms(normalizedQuery);
        context.setKeywordFilterTerms(requiredTerms);

        if (requiredTerms.isEmpty()) {
            context.setKeywordChunkHits(Collections.emptyList());
            context.setKeywordDocIdsByTerm(Collections.emptyMap());
            context.setKeywordDocSourcesById(Collections.emptyMap());
            context.setKeywordDocScoresById(Collections.emptyMap());
            context.setBm25TextHits(0L);
            return;
        }

        Map<String, Map<String, Object>> docSourcesById = new LinkedHashMap<>();
        Map<String, Double> docScoresById = new HashMap<>();
        boolean useDocSearch = docSearchEnabled && docSearchKeywordEnabled;
        long docSearchStart = System.currentTimeMillis();
        Map<String, Set<String>> docIdsByTerm;
        boolean legacyFallbackUsed = false;
        if (useDocSearch) {
            RecallResult newIndexResult = null;
            try {
                Map<String, Map<String, Object>> newSources = new LinkedHashMap<>();
                Map<String, Double> newScores = new HashMap<>();
                Map<String, Set<String>> result = searchCandidateDocsByTerm(docSearchIndex, requiredTerms,
                        getEsTimeoutMs(config), isAnonymous, finalUserId, deptValues, forceSource, indexPattern,
                        newSources, newScores, context.getRecallTopK());
                newIndexResult = new RecallResult(result, newSources, newScores);
            } catch (Exception e) {
                System.err.println("[KeywordStrategy] kb_doc_search 查询失败: " + e.getMessage());
            }
            Map<String, Set<String>> newIndexDocIdsByTerm =
                    newIndexResult != null ? newIndexResult.docIdsByTerm : Collections.emptyMap();

            if (hasIntersection(newIndexDocIdsByTerm) && newIndexResult != null) {
                docIdsByTerm = newIndexDocIdsByTerm;
                docSourcesById.putAll(newIndexResult.sources);
                docScoresById.putAll(newIndexResult.scores);
                useDocSearch = true;
            } else if (legacyFallbackEnabled) {
                System.out.println("[KeywordStrategy] kb_doc_search 无交集，显式开启 legacy fallback，回退旧 chunk 召回");
                Map<String, Map<String, Object>> legacyDocSourcesById = new LinkedHashMap<>();
                Map<String, Double> legacyDocScoresById = new HashMap<>();
                docIdsByTerm = searchLegacyCandidateDocsByTerm(indexPattern, requiredTerms,
                        getEsTimeoutMs(config), isAnonymous, finalUserId, deptValues, forceSource,
                        legacyDocSourcesById, legacyDocScoresById, context.getRecallTopK());
                docSourcesById.putAll(legacyDocSourcesById);
                docScoresById.putAll(legacyDocScoresById);
                legacyFallbackUsed = true;
                useDocSearch = false;
            } else {
                System.out.println("[KeywordStrategy] kb_doc_search 无交集，legacy fallback 未开启，返回空候选");
                docIdsByTerm = newIndexDocIdsByTerm != null ? newIndexDocIdsByTerm : Collections.emptyMap();
                useDocSearch = true;
            }
        } else {
            docIdsByTerm = searchLegacyCandidateDocsByTerm(indexPattern, requiredTerms,
                    getEsTimeoutMs(config), isAnonymous, finalUserId, deptValues, forceSource,
                    docSourcesById, docScoresById, context.getRecallTopK());
            legacyFallbackUsed = true;
        }
        context.getTimings().put("doc_search_enabled", useDocSearch ? 1 : 0);
        context.getTimings().put("keyword_legacy_fallback", legacyFallbackUsed ? 1 : 0);
        context.getTimings().put("doc_search_keyword_ms", System.currentTimeMillis() - docSearchStart);
        context.getTimings().put("doc_search_keyword_candidates", docSourcesById.size());

        // 关键词模式在召回层只枚举"文档集合",后续再做集合交集和 coarse 证据查询。
        context.setKeywordChunkHits(Collections.emptyList());
        context.setKeywordDocIdsByTerm(docIdsByTerm);
        context.setKeywordDocSourcesById(docSourcesById);
        context.setKeywordDocScoresById(docScoresById);
        context.setBm25Response(null);
        context.setKnnResponse(null);
        context.setSparseResponse(null);
        context.setQaHits(Collections.emptyList());
        context.setBm25TextHits(docSourcesById.size());
        context.setBm25Hits(docSourcesById.size());
        context.setKnnHits(0);
        context.setSparseHits(0);
        context.setQaHitsCount(0);

        System.out.printf("[KeywordStrategy] terms=%s docEnumTotal=%d | QA disabled%n",
            requiredTerms, docSourcesById.size());
    }

    /**
     * 判断多关键词文档候选是否存在交集。
     * 业务功能：keyword 文档级召回必须满足所有 required term 都能命中文档，避免单词命中扩大候选范围。
     * 关键流程：逐个关键词集合求交集，交集非空才进入后续 chunk evidence 回表。
     */
    boolean hasIntersection(Map<String, Set<String>> docIdsByTerm) {
        if (docIdsByTerm == null || docIdsByTerm.isEmpty()) {
            return false;
        }
        Set<String> intersection = null;
        for (Set<String> set : docIdsByTerm.values()) {
            if (set == null || set.isEmpty()) {
                return false;
            }
            if (intersection == null) {
                intersection = new java.util.HashSet<>(set);
            } else {
                intersection.retainAll(set);
            }
            if (intersection.isEmpty()) {
                return false;
            }
        }
        return intersection != null && !intersection.isEmpty();
    }

    Map<String, Set<String>> searchLegacyCandidateDocsByTerm(String indexPattern,
                                                             List<String> requiredTerms,
                                                             int timeoutMs,
                                                             boolean isAnonymous,
                                                             String finalUserId,
                                                             List<FieldValue> deptValues,
                                                             String forceSource,
                                                             Map<String, Map<String, Object>> docSourcesById,
                                                             Map<String, Double> docScoresById,
                                                             int topK) throws Exception {
        Map<String, Set<String>> docIdsByTerm = new LinkedHashMap<>();
        if (requiredTerms == null || requiredTerms.isEmpty()) {
            return docIdsByTerm;
        }
        int size = resolveKeywordCandidateSize(topK);
        List<RequestItem> searches = new ArrayList<>();
        for (String term : requiredTerms) {
            docIdsByTerm.put(term, new LinkedHashSet<>());
            searches.add(buildLegacyCandidateDocsRequestItem(indexPattern, term, timeoutMs,
                    isAnonymous, finalUserId, deptValues, forceSource, size));
        }
        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 4))
            .build(), Object.class);
        if (response == null || response.responses() == null) {
            return docIdsByTerm;
        }
        int limit = Math.min(requiredTerms.size(), response.responses().size());
        for (int i = 0; i < limit; i++) {
            String term = requiredTerms.get(i);
            MultiSearchResponseItem<Object> item = response.responses().get(i);
            if (item == null || !item.isResult()) {
                continue;
            }
            collectCandidateHits(item.result(), docIdsByTerm.get(term), docSourcesById, docScoresById);
        }
        return docIdsByTerm;
    }

    Map<String, Set<String>> searchCandidateDocsByTerm(String indexPattern,
                                                       List<String> requiredTerms,
                                                       int timeoutMs,
                                                       boolean isAnonymous,
                                                       String finalUserId,
                                                       List<FieldValue> deptValues,
                                                       String forceSource,
                                                       String readableSourceIndexPattern,
                                                       Map<String, Map<String, Object>> docSourcesById,
                                                       Map<String, Double> docScoresById,
                                                       int topK) throws Exception {
        Map<String, Set<String>> docIdsByTerm = new LinkedHashMap<>();
        if (requiredTerms == null || requiredTerms.isEmpty()) {
            return docIdsByTerm;
        }

        int size = resolveKeywordCandidateSize(topK);
        List<RequestItem> searches = new ArrayList<>();
        for (String term : requiredTerms) {
            docIdsByTerm.put(term, new LinkedHashSet<>());
            searches.add(buildCandidateDocsRequestItem(indexPattern, term, timeoutMs,
                    isAnonymous, finalUserId, deptValues, forceSource, readableSourceIndexPattern, size));
        }

        MsearchRequest request = new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 4))
            .build();
        System.out.println("[KeywordStrategy] bounded candidate msearch terms=" + requiredTerms + " size=" + size);

        MsearchResponse<Object> response = esClient.msearch(request, Object.class);
        if (response == null || response.responses() == null) {
            return docIdsByTerm;
        }

        int limit = Math.min(requiredTerms.size(), response.responses().size());
        for (int i = 0; i < limit; i++) {
            String term = requiredTerms.get(i);
            MultiSearchResponseItem<Object> item = response.responses().get(i);
            if (item == null || !item.isResult()) {
                if (item != null && item.isFailure()) {
                    System.out.printf("[KeywordStrategy] term='%s' msearch failure=%s%n", term, item.failure());
                }
                continue;
            }
            collectCandidateHits(item.result(), docIdsByTerm.get(term), docSourcesById, docScoresById);
        }
        return docIdsByTerm;
    }

    private RequestItem buildCandidateDocsRequestItem(String indexPattern,
                                                      String term,
                                                      int timeoutMs,
                                                      boolean isAnonymous,
                                                      String finalUserId,
                                                      List<FieldValue> deptValues,
                                                      String forceSource,
                                                      String readableSourceIndexPattern,
                                                      int size) {
        return new RequestItem.Builder()
            .header(h -> h.index(msearchIndices(indexPattern)))
            .body(b -> b
                .trackTotalHits(h -> h.enabled(false))
                .size(size)
                .timeout(timeoutMs + "ms")
                .source(s -> s.filter(f -> f.includes(
                        "doc_id",
                        "source",
                        "source_name",
                        "title",
                        "document_number",
                        "keywords",
                        "tags",
                        "entities",
                        "section_titles",
                        "summary",
                        "doc_terms",
                        "visibility",
                        "publish_time",
                        "owner_dept_id",
                        "acl_tokens",
                        "source_index",
                        "index_code",
                        "owner_unit_code",
                        "visible_unit_codes",
                        "permission_version"
                )))
                .query(q -> q.bool(bool -> {
                    bool.must(m -> buildTermMatchQuery(m, term));
                    bool.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("is_latest")))))
                        .minimumShouldMatch("1")
                    ));
                    // kb_doc_search 索引使用顶层字段,需使用专用权限过滤器
                    bool.filter(utils.buildDocSearchPermFilter(isAnonymous, finalUserId, deptValues));
                    bool.filter(utils.buildDocSearchSourceIndexFilter(readableSourceIndexPattern));
                    if (forceSource != null && !forceSource.isEmpty()) {
                        bool.filter(f -> f.term(t -> t.field("data_source").value(forceSource)));
                    }
                    return bool;
                }))
            )
            .build();
    }

    private RequestItem buildLegacyCandidateDocsRequestItem(String indexPattern,
                                                            String term,
                                                            int timeoutMs,
                                                            boolean isAnonymous,
                                                            String finalUserId,
                                                            List<FieldValue> deptValues,
                                                            String forceSource,
                                                            int size) {
        return new RequestItem.Builder()
            .header(h -> h.index(msearchIndices(indexPattern)))
            .body(b -> b
                .trackTotalHits(h -> h.enabled(false))
                .size(size)
                .timeout(timeoutMs + "ms")
                .source(s -> s.filter(f -> f.includes(
                        "doc_title",
                        "keywords",
                        "metadata.source",
                        "metadata.title",
                        "metadata.document_number",
                        "metadata.tags",
                        "metadata.tags_kw",
                        "metadata.search_queries",
                        "metadata.owner",
                        "metadata.visibility",
                        "metadata.publish_time",
                        "metadata.owner_dept_id",
                        "metadata.custom_keywords",
                        "source_index",
                        "index_code",
                        "owner_unit_code",
                        "visible_unit_codes",
                        "permission_version",
                        "metadata.source_index",
                        "metadata.index_code",
                        "metadata.owner_unit_code",
                        "metadata.visible_unit_codes",
                        "metadata.permission_version"
                )))
                .collapse(c -> c.field(DOC_KEY_FIELD))
                .query(q -> q.bool(bool -> {
                    bool.must(m -> buildLegacyTermMatchQuery(m, term));
                    bool.filter(f -> f.bool(boolQuery -> boolQuery
                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                        .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                        .minimumShouldMatch("1")
                    ));
                    bool.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                    if (forceSource != null && !forceSource.isEmpty()) {
                        bool.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                    }
                    return bool;
                }))
            )
            .build();
    }

    private Set<String> searchCandidateDocs(String indexPattern,
                                            String term,
                                            int timeoutMs,
                                            boolean isAnonymous,
                                            String finalUserId,
                                            List<FieldValue> deptValues,
                                            String forceSource,
                                            Map<String, Map<String, Object>> docSourcesById,
                                            Map<String, Double> docScoresById,
                                            int topK) throws Exception {
        Set<String> docIds = new LinkedHashSet<>();
        int size = resolveKeywordCandidateSize(topK);
        SearchRequest request = buildCandidateDocsRequest(indexPattern, term, timeoutMs,
                isAnonymous, finalUserId, deptValues, forceSource, size);
        System.out.println("[KeywordStrategy] term='" + term + "' bounded candidate DSL: " + request);

        SearchResponse<Object> response = esClient.search(request, Object.class);
        if (response == null || response.hits() == null || response.hits().hits().isEmpty()) {
            return docIds;
        }

        collectCandidateHits(response, docIds, docSourcesById, docScoresById);
        return docIds;
    }

    /**
     * 业务功能：将逗号分隔的索引表达式转换为 MSearch header 可识别的索引列表。
     * 关键流程：MSearch header 传入单个逗号字符串时，ES 会把它当成一个物理索引名；
     * keyword 召回在 legacy kb_document_* 降级路径中必须显式拆分，避免跨索引召回整体失败。
     */
    List<String> msearchIndices(String indexPattern) {
        List<String> indices = new ArrayList<>();
        String raw = stringValue(indexPattern).trim();
        if (raw.isEmpty()) {
            indices.add(docSearchIndex);
            return indices;
        }
        for (String part : raw.split(",")) {
            String index = part.trim();
            if (!index.isEmpty()) {
                indices.add(index);
            }
        }
        if (indices.isEmpty()) {
            indices.add(docSearchIndex);
        }
        return indices;
    }

    private void collectCandidateHits(SearchResponse<Object> response,
                                      Set<String> docIds,
                                      Map<String, Map<String, Object>> docSourcesById,
                                      Map<String, Double> docScoresById) {
        if (response == null || response.hits() == null) {
            return;
        }
        collectCandidateHits(response.hits().hits(), docIds, docSourcesById, docScoresById);
    }

    private void collectCandidateHits(MultiSearchItem<Object> response,
                                      Set<String> docIds,
                                      Map<String, Map<String, Object>> docSourcesById,
                                      Map<String, Double> docScoresById) {
        if (response == null || response.hits() == null) {
            return;
        }
        collectCandidateHits(response.hits().hits(), docIds, docSourcesById, docScoresById);
    }

    private void collectCandidateHits(List<Hit<Object>> hits,
                                      Set<String> docIds,
                                      Map<String, Map<String, Object>> docSourcesById,
                                      Map<String, Double> docScoresById) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (Hit<Object> hit : hits) {
            Map<String, Object> source = castMap(hit.source());
            String docKey = buildDocKey(hit.id(), source);
            if (docKey.isEmpty()) {
                continue;
            }
            docIds.add(docKey);
            docSourcesById.putIfAbsent(docKey, source);
            double score = hit.score() != null ? hit.score() : 0.0;
            Double oldScore = docScoresById.get(docKey);
            if (oldScore == null || score > oldScore) {
                docScoresById.put(docKey, score);
            }
        }
    }

    private SearchRequest buildCandidateDocsRequest(String indexPattern,
                                                    String term,
                                                    int timeoutMs,
                                                    boolean isAnonymous,
                                                    String finalUserId,
                                                    List<FieldValue> deptValues,
                                                    String forceSource,
                                                    int size) {
        return new SearchRequest.Builder()
            .index(indexPattern)
            .trackTotalHits(h -> h.enabled(false))
            .size(size)
            .timeout(timeoutMs + "ms")
            .source(s -> s.filter(f -> f.includes(
                    "doc_title",
                    "keywords",
                    "metadata.source",
                    "metadata.title",
                    "metadata.document_number",
                    "metadata.tags",
                    "metadata.tags_kw",
                    "metadata.search_queries",
                    "metadata.owner",
                    "metadata.visibility",
                    "metadata.publish_time",
                    "metadata.owner_dept_id",
                    "metadata.custom_keywords",
                    "source_index",
                    "index_code",
                    "owner_unit_code",
                    "visible_unit_codes",
                    "permission_version",
                    "metadata.source_index",
                    "metadata.index_code",
                    "metadata.owner_unit_code",
                    "metadata.visible_unit_codes",
                    "metadata.permission_version"
            )))
            .collapse(c -> c.field(DOC_KEY_FIELD))
            .query(q -> q.bool(b -> {
                b.must(m -> buildTermMatchQuery(m, term));
                b.filter(f -> f.bool(boolQuery -> boolQuery
                    .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                    .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                    .minimumShouldMatch("1")
                ));
                b.filter(utils.buildLegacyPermFilter(isAnonymous, finalUserId, deptValues));
                if (forceSource != null && !forceSource.isEmpty()) {
                    b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                }
                return b;
            }))
            .build();
    }

    private ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query> buildTermMatchQuery(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder m,
            String term) {
        return m.bool(ib -> {
            ib.should(s -> s.matchPhrase(mp -> mp.field("doc_terms").query(term).slop(0).boost(6.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("summary").query(term).slop(0).boost(1.2f)));
            if (shouldUseShortChinesePrefix(term)) {
                ib.should(s -> s.matchPhrasePrefix(mp -> mp.field("doc_terms").query(term).boost(5.0f)));
                ib.should(s -> s.matchPhrasePrefix(mp -> mp.field("summary").query(term).boost(1.0f)));
            }
            ib.should(s -> s.matchPhrase(mp -> mp.field("title").query(term).slop(0).boost(10.0f)));
            ib.should(s -> s.term(t -> t.field("title.keyword").value(term).boost(18.0f)));
            ib.should(s -> s.term(t -> t.field("source").value(term).boost(16.0f)));
            if (shouldUseLeadingWildcard(term)) {
                ib.should(s -> s.matchPhrase(mp -> mp.field("source.ngram").query(term).slop(0).boost(5.0f)));
                ib.should(s -> s.matchPhrase(mp -> mp.field("title.ngram").query(term).slop(0).boost(5.0f)));
                ib.should(s -> s.matchPhrase(mp -> mp.field("document_number.ngram").query(term).slop(0).boost(8.0f)));
            }
            ib.should(s -> s.matchPhrase(mp -> mp.field("document_number.text").query(term).slop(0).boost(8.0f)));
            ib.should(s -> s.term(t -> t.field("document_number").value(term).boost(16.0f)));
            ib.should(s -> s.term(t -> t.field("keywords").value(term).boost(4.0f)));
            ib.should(s -> s.term(t -> t.field("tags").value(term).boost(3.0f)));
            ib.should(s -> s.term(t -> t.field("entities").value(term).boost(3.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("section_titles").query(term).slop(0).boost(3.0f)));
            return ib.minimumShouldMatch("1");
        });
    }

    private ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query> buildLegacyTermMatchQuery(
            co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder m,
            String term) {
        return m.bool(ib -> {
            ib.should(s -> s.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(2.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("metadata.title").query(term).slop(0).boost(6.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.source").value(term).boost(12.0f)));
            // [性能/可回滚] leading wildcard 默认关闭（search.keyword.legacy-wildcard.enabled=false）。
            // 前缀通配符在亿级 chunk 上是 O(term_dict) 全扫描；新 kb_doc_search 索引已用 ngram 覆盖该需求。
            if (legacyWildcardEnabled && shouldUseLeadingWildcard(term)) {
                ib.should(s -> s.wildcard(q -> q.field("metadata.source").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(5.0f)));
                ib.should(s -> s.wildcard(q -> q.field("metadata.document_number").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(6.0f)));
            }
            ib.should(s -> s.matchPhrase(mp -> mp.field("doc_title").query(term).slop(0).boost(6.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("metadata.document_number.text").query(term).slop(0).boost(6.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.document_number").value(term).boost(10.0f)));
            ib.should(s -> s.term(t -> t.field("keywords").value(term).boost(4.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.tags_kw").value(term).boost(3.0f)));
            ib.should(s -> s.matchPhrase(mp -> mp.field("metadata.search_queries").query(term).slop(0).boost(3.0f)));
            return ib.minimumShouldMatch("1");
        });
    }

    private boolean shouldUseLeadingWildcard(String term) {
        if (term == null) {
            return false;
        }
        String trimmed = term.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        if (trimmed.matches("[A-Za-z0-9_\\-]+") && trimmed.length() < 3) {
            return false;
        }
        return true;
    }

    /**
     * 业务功能：识别需要前缀兜底的短中文关键词。
     * 关键流程：只放行 2-6 个连续中文字符，避免单字和英文数字查询扩大候选面。
     */
    boolean shouldUseShortChinesePrefix(String term) {
        if (term == null) {
            return false;
        }
        String trimmed = term.trim();
        return trimmed.length() >= 2
                && trimmed.length() <= 6
                && trimmed.matches("[\\u4e00-\\u9fa5]+");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    /**
     * 业务功能：提取文档在整条搜索管线中流转的唯一标识（docKey）
     * 关键方法：buildDocKey
     * 流程描述：
     *   1. 优先从底层 _source 或元数据中获取 keyword 类型的 doc_id (如内容 MD5 哈希)。
     *   2. 为什么要优先 doc_id：因为下游的证据切片检索（KeywordCoarseEvidenceStep）使用精确的 term 过滤,
     *      过滤字段为 metadata.doc_id (keyword 类型)。若使用中文文件名作为 docKey 会导致 text 类型字段的 term 检索失效。
     *   3. 如果没有 doc_id,退化返回文件名（source 或 metadata.source）,保证老旧索引文档能通过文件名进行关联 chunks 检索。
     *   4. 如果前两者均没有,最后退化提取 ES 的 doc ID 哈希（extractDocHash）,满足零版本迁移的平滑升级需求。
     */
    private String buildDocKey(String hitId, Map<String, Object> source) {
        String docId = null;
        if (source != null && source.containsKey("doc_id")) {
            docId = stringValue(source.get("doc_id"));
        }
        if ((docId == null || docId.trim().isEmpty() || "none".equalsIgnoreCase(docId.trim())) && source != null) {
            Map<String, Object> metadata = castMap(source.get("metadata"));
            if (metadata != null && metadata.containsKey("doc_id")) {
                docId = stringValue(metadata.get("doc_id"));
            }
        }
        // [防 "None" 脏数据漏洞]
        // 解释：字面量 "None" 代表 ES 中该属性为 null。在此处进行主动过滤拦截,
        // 避免脏数据被错判为合法 Hash,确保安全退化使用文件名作为关联 docKey。
        if (docId != null && !docId.trim().isEmpty() && !"none".equalsIgnoreCase(docId.trim())) {
            return docId;
        }
        // 如果缺少新版 doc_id 标识,优先提取文件名作为 docKey 供 downstream match 兼容过滤老分片
        if (source != null) {
            String sourceFile = stringValue(source.get("source"));
            if (sourceFile.isEmpty()) {
                Map<String, Object> metadata = castMap(source.get("metadata"));
                if (metadata != null) {
                    sourceFile = stringValue(metadata.get("source"));
                }
            }
            if (!sourceFile.isEmpty()) {
                return sourceFile;
            }
        }
        return extractDocHash(hitId);
    }

    private String extractDocHash(String esId) {
        if (esId == null || esId.isEmpty()) {
            return "";
        }
        int chunkIdx = esId.lastIndexOf("_chunk_");
        if (chunkIdx > 0) {
            return esId.substring(0, chunkIdx);
        }
        int fineIdx = esId.lastIndexOf("_fine_");
        if (fineIdx > 0) {
            return esId.substring(0, fineIdx);
        }
        return esId;
    }

    private String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private String escapeWildcard(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?");
    }

    private List<String> splitTerms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        for (String term : query.trim().split("[\\s\\u3000]+")) {
            String trimmed = term.trim();
            if (!trimmed.isEmpty() && !terms.contains(trimmed)) {
                terms.add(trimmed);
            }
        }
        return terms;
    }

    private int getEsTimeoutMs(SysAiTuningConfig config) {
        return config != null && config.getEsQueryTimeout() != null ? config.getEsQueryTimeout() : 2000;
    }

    int resolveKeywordCandidateSize(int topK) {
        int maxCandidates = resolvePositiveInt(keywordDocCandidateMax, 500);
        int minCandidates = resolvePositiveInt(keywordDocCandidateMin, 120);
        int multiplier = resolvePositiveInt(keywordDocCandidateMultiplier, 12);
        int safeTopK = Math.max(1, topK);
        int expanded = Math.max(safeTopK * multiplier, minCandidates);
        return Math.min(maxCandidates, expanded);
    }

    int resolvePositiveInt(String configured, int defaultValue) {
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
     * [并发安全] 新索引异步查询的结果 holder：把 docIdsByTerm / sources / scores 一起从
     * CompletableFuture 返回，替代原 static ThreadLocalHolder 的共享可变字段传递方式。
     * 每个 Future 实例各自持有自己的结果，并发请求互不干扰。
     */
    private static final class RecallResult {
        final Map<String, Set<String>> docIdsByTerm;
        final Map<String, Map<String, Object>> sources;
        final Map<String, Double> scores;

        RecallResult(Map<String, Set<String>> docIdsByTerm,
                     Map<String, Map<String, Object>> sources,
                     Map<String, Double> scores) {
            this.docIdsByTerm = docIdsByTerm;
            this.sources = sources;
            this.scores = scores;
        }
    }
}
