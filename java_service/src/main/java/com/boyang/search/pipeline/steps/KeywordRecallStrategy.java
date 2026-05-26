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
 * 关键字召回策略（纯 BM25 全文检索，不含 QA）
 *
 * 业务功能：
 * 执行 BM25 全文检索通道，跳过 KNN、Sparse 和 QA 第四路。
 * 适用于用户明确指定关键字匹配场景，例如：
 * - 按文号检索（"国办发〔2024〕1号"）
 * - 精确术语搜索（"碳排放权交易"）
 * - 对语义扩展不敏感的精确匹配需求
 *
 * [设计决策] keyword 模式不拉取 QA 结果：
 * 用户选择关键词检索时，意图是精确匹配文档原文内容，
 * QA 候选来自 Q2Q 语义匹配，与关键词精确检索意图相悖，会引入噪声。
 * qaHits 写空列表，RrfFusionStep 遇空自动跳过第四路，无需其他改动。
 *
 * 实现说明：
 * - knnResponse/sparseResponse 写 null，RrfFusionStep 跳过这两个空通道
 * - qaHits 写 emptyList，RrfFusionStep 跳过 QA 第四路
 * - bm25FlatnessRatio 照常计算，保证 RerankStep 消歧逻辑正常工作
 */
@Component
public class KeywordRecallStrategy implements RecallStrategy {

    private static final int MAX_DOC_CANDIDATES = 500;
    private static final String DOC_KEY_FIELD = "metadata.source";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EsRecallUtils utils;

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
        Map<String, Set<String>> docIdsByTerm = searchCandidateDocsByTerm(indexPattern, requiredTerms,
                getEsTimeoutMs(config), isAnonymous, finalUserId, deptValues, forceSource,
                docSourcesById, docScoresById, context.getRecallTopK());

        // 关键词模式在召回层只枚举“文档集合”，后续再做集合交集和 coarse 证据查询。
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

    private Map<String, Set<String>> searchCandidateDocsByTerm(String indexPattern,
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

        int size = Math.min(MAX_DOC_CANDIDATES, Math.max(topK * 12, 120));
        List<RequestItem> searches = new ArrayList<>();
        for (String term : requiredTerms) {
            docIdsByTerm.put(term, new LinkedHashSet<>());
            searches.add(buildCandidateDocsRequestItem(indexPattern, term, timeoutMs,
                    isAnonymous, finalUserId, deptValues, forceSource, size));
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
                                                      int size) {
        return new RequestItem.Builder()
            .header(h -> h.index(indexPattern))
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
                        "metadata.custom_keywords"
                )))
                .collapse(c -> c.field(DOC_KEY_FIELD))
                .query(q -> q.bool(bool -> {
                    bool.must(m -> buildTermMatchQuery(m, term));
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
        int size = Math.min(MAX_DOC_CANDIDATES, Math.max(topK * 12, 120));
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
                    "metadata.custom_keywords"
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
            ib.should(s -> s.match(mp -> mp.field("content").query(term).analyzer("ik_max_word")));
            ib.should(s -> s.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)));
            ib.should(s -> s.match(mp -> mp.field("display_content").query(term).analyzer("ik_max_word").boost(2.0f)));
            ib.should(s -> s.match(mp -> mp.field("metadata.title").query(term).analyzer("ik_max_word").boost(6.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.source").value(term).boost(12.0f)));
            if (shouldUseLeadingWildcard(term)) {
                ib.should(s -> s.wildcard(q -> q.field("metadata.source").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(5.0f)));
                ib.should(s -> s.wildcard(q -> q.field("metadata.document_number").value("*" + escapeWildcard(term) + "*").caseInsensitive(true).boost(6.0f)));
            }
            ib.should(s -> s.match(mp -> mp.field("doc_title").query(term).analyzer("ik_max_word").boost(6.0f)));
            ib.should(s -> s.match(mp -> mp.field("metadata.document_number.text").query(term).analyzer("ik_max_word").boost(6.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.document_number").value(term).boost(10.0f)));
            ib.should(s -> s.term(t -> t.field("keywords").value(term).boost(4.0f)));
            ib.should(s -> s.term(t -> t.field("metadata.tags_kw").value(term).boost(3.0f)));
            ib.should(s -> s.match(mp -> mp.field("metadata.search_queries").query(term).analyzer("ik_max_word").boost(3.0f)));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    private String buildDocKey(String hitId, Map<String, Object> source) {
        Map<String, Object> metadata = castMap(source != null ? source.get("metadata") : null);
        if (metadata != null) {
            String sourceName = stringValue(metadata.get("source"));
            if (!sourceName.isEmpty()) {
                return sourceName;
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
}
