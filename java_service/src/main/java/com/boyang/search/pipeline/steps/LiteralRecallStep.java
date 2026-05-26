package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-confidence literal recall for document number, title and source lookups.
 */
@Component
public class LiteralRecallStep implements SearchPipelineStep {

    private static final String DEFAULT_DATE = "2024-01-10";
    private static final String DEFAULT_ORG = "";
    private static final int MAX_LITERAL_HITS = 10;
    private static final int TITLE_PHRASE_MIN_LENGTH = 8;
    private static final String LITERAL_TIMEOUT = "1000ms";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EsRecallUtils utils;

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) {
        if (context == null || !shouldProbe(context)) {
            return;
        }

        List<String> queries = candidateQueries(context);
        if (queries.isEmpty()) {
            return;
        }

        SysTenantPolicy policy = context.getTenantPolicy();
        String indexPattern = context.getResolvedIndexPattern() != null
                ? context.getResolvedIndexPattern()
                : (policy != null ? policy.getIndexPattern() : null);
        if (indexPattern == null || indexPattern.trim().isEmpty()) {
            return;
        }

        Map<String, Object> filters = context.getFilters();
        String userId = filters != null ? (String) filters.get("user_id") : null;
        String userDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        final List<FieldValue> deptValues = utils.buildDeptValues(userDeptCode);
        final boolean isAnonymous = (userId == null || userId.trim().isEmpty());
        final String finalUserId = userId;
        final String finalForceSource = forceSource;
        final String snippetQuery = displayQuery(context);
        int size = Math.max(1, Math.min(context.getReturnTopK(), MAX_LITERAL_HITS));

        try {
            SearchResponse<Object> response = esClient.search(buildExactMetadataRequest(
                    indexPattern, size, queries, isAnonymous, finalUserId, deptValues, finalForceSource),
                    Object.class);
            List<Map<String, Object>> results = extractResults(response, snippetQuery);

            if (results.isEmpty()) {
                SearchRequest titlePhraseReq = buildTitlePhraseRequest(
                        indexPattern, size, queries, isAnonymous, finalUserId, deptValues, finalForceSource);
                if (titlePhraseReq != null) {
                    results = extractResults(esClient.search(titlePhraseReq, Object.class), snippetQuery);
                }
            }

            if (!results.isEmpty()) {
                context.setLiteralHitCount(results.size());
                context.setFinalResult(results);
                System.out.printf("[LiteralRecall] exact metadata hit count=%d index=%s%n",
                        results.size(), indexPattern);
            } else {
                context.setLiteralHitCount(0);
            }
        } catch (Exception e) {
            context.setLiteralHitCount(0);
            System.err.println("[LiteralRecall] probe failed, continue normal pipeline: " + e.getMessage());
        }
    }

    private boolean shouldProbe(SearchContext context) {
        String query = context.getNormalizedQuery() != null ? context.getNormalizedQuery() : context.getQueryText();
        return query != null && query.trim().length() > 3;
    }

    private List<String> candidateQueries(SearchContext context) {
        Set<String> set = new LinkedHashSet<>();
        addQuery(set, context.getNormalizedQuery());
        addQuery(set, context.getQueryText());
        addQuery(set, context.getRewrittenQuery());
        return new ArrayList<>(set);
    }

    private void addQuery(Set<String> set, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 3) {
            set.add(trimmed);
        }
    }

    private SearchRequest buildExactMetadataRequest(String indexPattern,
                                                    int size,
                                                    List<String> queries,
                                                    boolean isAnonymous,
                                                    String userId,
                                                    List<FieldValue> deptValues,
                                                    String forceSource) {
        return new SearchRequest.Builder()
                .index(indexPattern)
                .trackTotalHits(h -> h.enabled(false))
                .size(size)
                .timeout(LITERAL_TIMEOUT)
                .query(q -> q.bool(b -> {
                    for (String query : queries) {
                        b.should(s -> s.term(t -> t.field("metadata.document_number").value(query).boost(20.0f)));
                        b.should(s -> s.term(t -> t.field("metadata.source").value(query).boost(15.0f)));
                        b.should(s -> s.term(t -> t.field("metadata.title.keyword").value(query).boost(15.0f)));
                    }
                    applyCommonFilters(b, isAnonymous, userId, deptValues, forceSource);
                    return b;
                }))
                .build();
    }

    private SearchRequest buildTitlePhraseRequest(String indexPattern,
                                                  int size,
                                                  List<String> queries,
                                                  boolean isAnonymous,
                                                  String userId,
                                                  List<FieldValue> deptValues,
                                                  String forceSource) {
        List<String> phraseQueries = new ArrayList<>();
        for (String query : queries) {
            if (query != null && query.length() >= TITLE_PHRASE_MIN_LENGTH) {
                phraseQueries.add(query);
            }
        }
        if (phraseQueries.isEmpty()) {
            return null;
        }
        return new SearchRequest.Builder()
                .index(indexPattern)
                .trackTotalHits(h -> h.enabled(false))
                .size(size)
                .timeout(LITERAL_TIMEOUT)
                .query(q -> q.bool(b -> {
                    for (String query : phraseQueries) {
                        b.should(s -> s.matchPhrase(mp -> mp.field("metadata.title")
                                .query(query).slop(0).boost(8.0f)));
                    }
                    applyCommonFilters(b, isAnonymous, userId, deptValues, forceSource);
                    return b;
                }))
                .build();
    }

    private void applyCommonFilters(BoolQuery.Builder b,
                                    boolean isAnonymous,
                                    String userId,
                                    List<FieldValue> deptValues,
                                    String forceSource) {
        b.minimumShouldMatch("1");
        b.filter(utils.buildLatestVersionFilter());
        b.filter(utils.buildLegacyPermFilter(isAnonymous, userId, deptValues));
        if (forceSource != null && !forceSource.isEmpty()) {
            b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
        }
    }

    private List<Map<String, Object>> extractResults(SearchResponse<Object> response, String query) {
        if (response == null || response.hits() == null || response.hits().hits().isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Hit<Object> hit : response.hits().hits()) {
            Map<String, Object> source = castMap(hit.source());
            if (source == null) {
                continue;
            }
            Map<String, Object> metadata = castMap(source.get("metadata"));
            String sourceName = metadata != null ? stringValue(metadata.get("source")) : "";
            String docId = metadata != null ? stringValue(metadata.get("doc_id")) : "";
            if (docId.isEmpty()) {
                docId = extractDocHash(hit.id());
            }
            String dedupeKey = !sourceName.isEmpty() ? sourceName : docId;
            if (dedupeKey.isEmpty() || deduped.containsKey(dedupeKey)) {
                continue;
            }
            deduped.put(dedupeKey, toResult(hit, source, metadata, docId, query));
        }
        return new ArrayList<>(deduped.values());
    }

    private Map<String, Object> toResult(Hit<Object> hit, Map<String, Object> source,
                                         Map<String, Object> metadata, String docId, String query) {
        Map<String, Object> result = new LinkedHashMap<>();
        String fileName = metadata != null ? stringValue(metadata.get("source")) : DEFAULT_ORG;
        String owner = metadata != null ? stringValue(metadata.get("owner")) : "";
        String content = stringValue(source.get("content"));

        result.put("doc_id", docId);
        result.put("organization", !owner.isEmpty() ? owner : fileName);
        result.put("file_name", fileName);
        result.put("publish_time", metadata != null ? metadata.getOrDefault("publish_time", DEFAULT_DATE) : DEFAULT_DATE);
        result.put("tags", metadata != null ? metadata.get("tags") : null);
        result.put("custom_tags", metadata != null ? metadata.get("custom_keywords") : null);
        result.put("dept_code", metadata != null ? metadata.getOrDefault("owner_dept_id", "") : "");
        result.put("visibility", metadata != null ? metadata.getOrDefault("visibility", "") : "");
        result.put("chunk_text", utils.generateFallbackSnippet(content, query));
        result.put("score", 0.99);
        result.put("_literal_hit", Boolean.TRUE);
        result.put("_id", hit.id());
        result.put("_es_score", hit.score() != null ? hit.score() : 0.0);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    private String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private String displayQuery(SearchContext context) {
        if (context == null) {
            return "";
        }
        String query = context.getNormalizedQuery();
        if (query == null || query.trim().isEmpty()) {
            query = context.getQueryText();
        }
        return query == null ? "" : query.trim();
    }

    private String extractDocHash(String esId) {
        if (esId == null || esId.isEmpty()) {
            return "";
        }
        int qaIdx = esId.indexOf("_qa_");
        if (qaIdx > 0) {
            return esId.substring(0, qaIdx);
        }
        int vMarkerIdx = esId.indexOf("_v");
        if (vMarkerIdx > 0 && vMarkerIdx + 2 < esId.length() && Character.isDigit(esId.charAt(vMarkerIdx + 2))) {
            return esId.substring(0, vMarkerIdx);
        }
        int chunkIdx = esId.lastIndexOf("_chunk_");
        if (chunkIdx > 0) {
            return esId.substring(0, chunkIdx);
        }
        return esId;
    }
}
