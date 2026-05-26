package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为关键词命中文档补齐 coarse 展示证据。
 *
 * 文档是否命中已经由“每词文档集合交集”决定，本步骤只负责展示证据：
 * 返回正文包含关键词的 coarse 分片证据。
 * 选择规则不是要求每个 coarse 都包含全部关键词，而是要求前端展示的一组 coarse
 * 合起来覆盖全部输入关键词；如果 1 个 coarse 已覆盖全部关键词，就只展示这 1 个。
 */
@Component
public class KeywordCoarseEvidenceStep implements SearchPipelineStep {

    private static final int MAX_DISPLAY_COARSE = 3;
    private static final int MATCHING_COARSE_SIZE = 50;

    @Autowired
    private ElasticsearchClient esClient;

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) throws Exception {
        List<Map<String, Object>> docs = context.getKeywordDocumentHits();
        if (docs == null || docs.isEmpty()) {
            context.setKeywordCoarseChunksByDoc(new LinkedHashMap<>());
            return;
        }

        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        List<String> terms = plan != null ? plan.safeRequiredTerms() : context.getKeywordFilterTerms();
        String indexPattern = context.getResolvedIndexPattern() != null
                ? context.getResolvedIndexPattern()
                : context.getTenantPolicy().getIndexPattern();

        Map<String, List<Map<String, Object>>> chunksByDoc = new LinkedHashMap<>();
        List<Map<String, Object>> enrichedDocs = new ArrayList<>();
        int limit = Math.min(docs.size(), Math.max(context.getReturnTopK() * 3, context.getReturnTopK()));

        List<Map<String, Object>> limitedDocs = new ArrayList<>();
        List<String> docIds = new ArrayList<>();
        Map<String, Set<String>> metadataTermsByDoc = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            Map<String, Object> doc = docs.get(i);
            String docId = stringValue(doc.get("doc_id"));
            if (docId.isEmpty()) {
                continue;
            }

            Map<String, Object> source = castMap(doc.get("_source"));
            Set<String> metadataMatchedTerms = metadataMatchedTerms(source, terms);
            limitedDocs.add(doc);
            docIds.add(docId);
            metadataTermsByDoc.put(docId, metadataMatchedTerms);
        }

        Map<String, List<Map<String, Object>>> candidatesByDoc = fetchMatchingCoarseBatch(indexPattern, docIds, terms);
        List<String> fallbackDocIds = new ArrayList<>();
        for (int i = 0; i < limitedDocs.size(); i++) {
            Map<String, Object> doc = limitedDocs.get(i);
            String docId = docIds.get(i);
            Set<String> metadataMatchedTerms = metadataTermsByDoc.get(docId);
            List<Map<String, Object>> chunks = chooseCoverageChunks(candidatesByDoc.get(docId), terms, metadataMatchedTerms);
            if (chunks.isEmpty() && coversAllTerms(metadataMatchedTerms, terms)) {
                fallbackDocIds.add(docId);
            }

            chunksByDoc.put(docId, chunks);
            doc.put("metadata_matched_terms", metadataMatchedTerms);
            doc.put("chunks", chunks);
        }

        if (!fallbackDocIds.isEmpty()) {
            Map<String, List<Map<String, Object>>> fallbackByDoc = fetchFallbackCoarseBatch(indexPattern, fallbackDocIds);
            for (Map<String, Object> doc : limitedDocs) {
                String docId = stringValue(doc.get("doc_id"));
                List<Map<String, Object>> chunks = castChunkList(doc.get("chunks"));
                if ((chunks == null || chunks.isEmpty()) && fallbackByDoc.containsKey(docId)) {
                    chunks = fallbackByDoc.get(docId);
                    chunksByDoc.put(docId, chunks);
                    doc.put("chunks", chunks);
                }
            }
        }

        for (Map<String, Object> doc : limitedDocs) {
            String docId = stringValue(doc.get("doc_id"));
            List<Map<String, Object>> chunks = castChunkList(doc.get("chunks"));
            if ((chunks != null && !chunks.isEmpty()) || coversAllTerms(metadataTermsByDoc.get(docId), terms)) {
                enrichedDocs.add(doc);
            }
        }

        context.setKeywordCoarseChunksByDoc(chunksByDoc);
        context.setKeywordDocumentHits(enrichedDocs);
        System.out.printf("[KeywordCoarseEvidence] docs=%d%n", chunksByDoc.size());
    }

    private Map<String, List<Map<String, Object>>> fetchMatchingCoarseBatch(String indexPattern,
                                                                            List<String> docIds,
                                                                            List<String> terms) throws Exception {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }
        List<RequestItem> searches = new ArrayList<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
            searches.add(buildMatchingCoarseRequestItem(indexPattern, docId, terms));
        }
        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 6))
            .build(), Object.class);
        fillBatchResult(result, docIds, response, terms);
        return result;
    }

    private Map<String, List<Map<String, Object>>> fetchFallbackCoarseBatch(String indexPattern,
                                                                            List<String> docIds) throws Exception {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }
        List<RequestItem> searches = new ArrayList<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
            searches.add(buildFallbackCoarseRequestItem(indexPattern, docId));
        }
        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 6))
            .build(), Object.class);
        fillBatchResult(result, docIds, response, new ArrayList<>());
        return result;
    }

    private void fillBatchResult(Map<String, List<Map<String, Object>>> result,
                                 List<String> docIds,
                                 MsearchResponse<Object> response,
                                 List<String> terms) {
        if (response == null || response.responses() == null) {
            return;
        }
        int limit = Math.min(docIds.size(), response.responses().size());
        for (int i = 0; i < limit; i++) {
            MultiSearchResponseItem<Object> item = response.responses().get(i);
            if (item == null || !item.isResult()) {
                if (item != null && item.isFailure()) {
                    System.out.printf("[KeywordCoarseEvidence] doc='%s' msearch failure=%s%n", docIds.get(i), item.failure());
                }
                continue;
            }
            result.put(docIds.get(i), toEvidenceChunks(item.result(), terms));
        }
    }

    private RequestItem buildMatchingCoarseRequestItem(String indexPattern, String docId, List<String> terms) {
        return new RequestItem.Builder()
            .header(h -> h.index(indexPattern))
            .body(b -> {
                b.trackTotalHits(h -> h.enabled(false));
                b.size(MATCHING_COARSE_SIZE);
                b.source(s -> s.filter(f -> f.includes(
                        "content",
                        "display_content",
                        "chunk_granularity",
                        "metadata.source",
                        "metadata.chunk_id",
                        "metadata.is_latest"
                )));
                b.query(q -> q.bool(bool -> {
                    addDocAndCoarseFilters(bool, docId);
                    bool.must(m -> m.bool(anyTerm -> {
                        for (String term : terms) {
                            anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                                .should(ss -> ss.match(mp -> mp.field("content").query(term).analyzer("ik_max_word")))
                                .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                                .should(ss -> ss.match(mp -> mp.field("display_content").query(term).analyzer("ik_max_word").boost(2.0f)))
                                .minimumShouldMatch("1")
                            ));
                        }
                        return anyTerm.minimumShouldMatch("1");
                    }));
                    return bool;
                }));
                return b;
            })
            .build();
    }

    private RequestItem buildFallbackCoarseRequestItem(String indexPattern, String docId) {
        return new RequestItem.Builder()
            .header(h -> h.index(indexPattern))
            .body(b -> b
                .trackTotalHits(h -> h.enabled(false))
                .size(MAX_DISPLAY_COARSE)
                .source(s -> s.filter(f -> f.includes(
                        "content",
                        "display_content",
                        "chunk_granularity",
                        "metadata.source",
                        "metadata.chunk_id",
                        "metadata.is_latest"
                )))
                .query(q -> q.bool(bool -> {
                    addDocAndCoarseFilters(bool, docId);
                    return bool;
                }))
            )
            .build();
    }

    private List<Map<String, Object>> fetchMatchingCoarse(String indexPattern, String docId, List<String> terms) throws Exception {
        SearchRequest request = baseCoarseRequest(indexPattern, docId)
            .size(MATCHING_COARSE_SIZE)
            .query(q -> q.bool(b -> {
                addDocAndCoarseFilters(b, docId);
                b.must(m -> m.bool(anyTerm -> {
                    for (String term : terms) {
                        anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                            .should(ss -> ss.match(mp -> mp.field("content").query(term).analyzer("ik_max_word")))
                            .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                            .should(ss -> ss.match(mp -> mp.field("display_content").query(term).analyzer("ik_max_word").boost(2.0f)))
                            .minimumShouldMatch("1")
                        ));
                    }
                    return anyTerm.minimumShouldMatch("1");
                }));
                return b;
            }))
            .build();
        return toEvidenceChunks(esClient.search(request, Object.class), terms);
    }

    private List<Map<String, Object>> fetchFallbackCoarse(String indexPattern, String docId) throws Exception {
        SearchRequest request = baseCoarseRequest(indexPattern, docId)
            .size(MAX_DISPLAY_COARSE)
            .query(q -> q.bool(b -> {
                addDocAndCoarseFilters(b, docId);
                return b;
            }))
            .build();
        return toEvidenceChunks(esClient.search(request, Object.class), new ArrayList<>());
    }

    private SearchRequest.Builder baseCoarseRequest(String indexPattern, String docId) {
        return new SearchRequest.Builder()
            .index(indexPattern)
            .trackTotalHits(h -> h.enabled(false))
            .source(s -> s.filter(f -> f.includes(
                    "content",
                    "display_content",
                    "chunk_granularity",
                    "metadata.source",
                    "metadata.chunk_id",
                    "metadata.is_latest"
            )));
    }

    private void addDocAndCoarseFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                        String docId) {
        b.filter(f -> f.term(t -> t.field("metadata.source").value(docId)));
        b.filter(f -> f.bool(boolQuery -> boolQuery
            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
            .minimumShouldMatch("1")
        ));
        // 历史数据可能没有 chunk_granularity 字段；只排除明确标记为 fine 的分片。
        b.filter(f -> f.bool(gran -> gran
            .should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")))
            .should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))))
            .minimumShouldMatch("1")
        ));
    }

    private List<Map<String, Object>> toEvidenceChunks(SearchResponse<Object> response, List<String> terms) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (response == null || response.hits() == null) {
            return chunks;
        }
        return toEvidenceChunks(response.hits().hits(), terms);
    }

    private List<Map<String, Object>> toEvidenceChunks(MultiSearchItem<Object> response, List<String> terms) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (response == null || response.hits() == null) {
            return chunks;
        }
        return toEvidenceChunks(response.hits().hits(), terms);
    }

    private List<Map<String, Object>> toEvidenceChunks(List<Hit<Object>> hits, List<String> terms) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (hits == null || hits.isEmpty()) {
            return chunks;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Hit<Object> hit : hits) {
            Map<String, Object> source = castMap(hit.source());
            String id = stringValue(hit.id());
            if (!seen.add(id)) {
                continue;
            }
            Map<String, Object> chunk = new LinkedHashMap<>();
            List<String> matchedTerms = matchedTerms(source, terms);
            if (terms != null && !terms.isEmpty() && matchedTerms.isEmpty()) {
                continue;
            }
            chunk.put("_id", id);
            chunk.put("_source", source);
            chunk.put("_es_score", hit.score() != null ? hit.score() : 0.0);
            chunk.put("matched_terms", matchedTerms);
            chunks.add(chunk);
        }
        return chunks;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castChunkList(Object obj) {
        return obj instanceof List ? (List<Map<String, Object>>) obj : null;
    }

    private List<Map<String, Object>> chooseCoverageChunks(List<Map<String, Object>> candidates,
                                                           List<String> terms,
                                                           Set<String> preCoveredTerms) {
        List<Map<String, Object>> chosen = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return chosen;
        }

        Set<String> missing = new LinkedHashSet<>(terms != null ? terms : new ArrayList<>());
        if (preCoveredTerms != null) {
            missing.removeAll(preCoveredTerms);
        }
        Set<String> usedIds = new LinkedHashSet<>();
        while (!missing.isEmpty() && chosen.size() < MAX_DISPLAY_COARSE) {
            Map<String, Object> best = null;
            int bestGain = 0;
            double bestScore = -1.0;
            for (Map<String, Object> chunk : candidates) {
                String id = stringValue(chunk.get("_id"));
                if (usedIds.contains(id)) {
                    continue;
                }
                List<String> matched = toStringList(chunk.get("matched_terms"));
                int gain = 0;
                for (String term : matched) {
                    if (missing.contains(term)) {
                        gain++;
                    }
                }
                double score = numberValue(chunk.get("_es_score"));
                if (gain > bestGain || (gain == bestGain && score > bestScore)) {
                    best = chunk;
                    bestGain = gain;
                    bestScore = score;
                }
            }
            if (best == null || bestGain == 0) {
                break;
            }
            chosen.add(best);
            usedIds.add(stringValue(best.get("_id")));
            missing.removeAll(toStringList(best.get("matched_terms")));
        }

        // 如果 3 个以内的 coarse 仍无法覆盖全部关键词，就不要展示误导性的部分证据。
        if (!missing.isEmpty()) {
            return new ArrayList<>();
        }

        for (Map<String, Object> chunk : candidates) {
            if (chosen.size() >= MAX_DISPLAY_COARSE) {
                break;
            }
            String id = stringValue(chunk.get("_id"));
            if (usedIds.add(id)) {
                chosen.add(chunk);
            }
        }
        return chosen;
    }

    private boolean coversAllTerms(Set<String> matchedTerms, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }
        return matchedTerms != null && matchedTerms.containsAll(terms);
    }

    private Set<String> metadataMatchedTerms(Map<String, Object> source, List<String> terms) {
        Set<String> matched = new LinkedHashSet<>();
        if (source == null || terms == null || terms.isEmpty()) {
            return matched;
        }
        String haystack = normalize(buildMetadataText(source));
        for (String term : terms) {
            String needle = normalize(term);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                matched.add(term);
            }
        }
        return matched;
    }

    private List<String> matchedTerms(Map<String, Object> source, List<String> terms) {
        List<String> matched = new ArrayList<>();
        // 展示层只统计正文命中。标题、文号、文件名等元数据只参与文档召回，不参与分片展示。
        String haystack = normalize(buildBodyText(source));
        for (String term : terms) {
            String needle = normalize(term);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                matched.add(term);
            }
        }
        return matched;
    }

    @SuppressWarnings("unchecked")
    private String buildBodyText(Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        if (source == null) {
            return "";
        }
        append(sb, source.get("content"));
        append(sb, source.get("display_content"));
        return sb.toString();
    }

    private String buildMetadataText(Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        append(sb, source.get("doc_title"));
        Map<String, Object> metadata = castMap(source.get("metadata"));
        if (metadata != null) {
            append(sb, metadata.get("title"));
            append(sb, metadata.get("source"));
            append(sb, metadata.get("document_number"));
            append(sb, metadata.get("tags"));
            append(sb, metadata.get("tags_kw"));
            append(sb, metadata.get("search_queries"));
        }
        return sb.toString();
    }

    private void append(StringBuilder sb, Object obj) {
        if (obj == null) {
            return;
        }
        if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                append(sb, item);
            }
            return;
        }
        if (obj instanceof Map) {
            for (Object item : ((Map<?, ?>) obj).values()) {
                append(sb, item);
            }
            return;
        }
        sb.append('\n').append(obj);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    private String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u3000') {
                chars[i] = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                chars[i] = (char) (c - 0xFEE0);
            }
        }
        return new String(chars).toLowerCase(java.util.Locale.ROOT);
    }

    private String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        List<String> list = new ArrayList<>();
        if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                if (item != null) {
                    list.add(item.toString());
                }
            }
        }
        return list;
    }

    private double numberValue(Object obj) {
        return obj instanceof Number ? ((Number) obj).doubleValue() : 0.0;
    }
}
