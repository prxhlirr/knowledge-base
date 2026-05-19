package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将关键词召回阶段枚举出的文档集合求交集。
 *
 * 召回阶段已经按每个关键词完整枚举“命中过该词的文档 ID”，这里不再依赖 topN chunk，
 * 只保留同时出现在所有关键词集合中的文档，满足“关键词分散在同一文档不同分片也命中”的要求。
 */
@Component
public class KeywordDocumentMatchStep implements SearchPipelineStep {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) {
        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        List<String> requiredTerms = plan != null ? plan.safeRequiredTerms() : context.getKeywordFilterTerms();
        if (requiredTerms == null || requiredTerms.isEmpty()) {
            context.setKeywordDocumentHits(new ArrayList<>());
            return;
        }

        Map<String, Set<String>> docIdsByTerm = context.getKeywordDocIdsByTerm();
        if (docIdsByTerm == null || docIdsByTerm.isEmpty()) {
            context.setKeywordDocumentHits(new ArrayList<>());
            context.setKeywordMatchedDocIds(new LinkedHashSet<>());
            return;
        }

        Set<String> matchedDocIds = null;
        for (String term : requiredTerms) {
            Set<String> termDocIds = docIdsByTerm.get(term);
            if (termDocIds == null || termDocIds.isEmpty()) {
                matchedDocIds = new LinkedHashSet<>();
                break;
            }
            if (matchedDocIds == null) {
                matchedDocIds = new LinkedHashSet<>(termDocIds);
            } else {
                matchedDocIds.retainAll(termDocIds);
            }
            if (matchedDocIds.isEmpty()) {
                break;
            }
        }

        if (matchedDocIds == null) {
            matchedDocIds = new LinkedHashSet<>();
        }
        context.setKeywordMatchedDocIds(matchedDocIds);

        Map<String, Map<String, Object>> sourcesById = context.getKeywordDocSourcesById();
        Map<String, Double> scoresById = context.getKeywordDocScoresById();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (String docId : matchedDocIds) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("doc_id", docId);
            doc.put("_source", sourcesById != null ? sourcesById.get(docId) : null);
            doc.put("matched_terms", new LinkedHashSet<String>(requiredTerms));
            doc.put("chunks", new ArrayList<Map<String, Object>>());
            doc.put("max_es_score", scoresById != null && scoresById.get(docId) != null ? scoresById.get(docId) : 0.0);
            doc.put("term_coverage", 1.0);
            docs.add(doc);
        }
        docs.sort((a, b) -> Double.compare(numberValue(b.get("max_es_score")), numberValue(a.get("max_es_score"))));

        context.setKeywordDocumentHits(docs);
        System.out.printf("[KeywordDocumentMatch] docs=%d requiredTerms=%s%n", docs.size(), requiredTerms);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    private Set<String> castSet(Object obj) {
        if (obj instanceof Set) {
            return (Set<String>) obj;
        }
        Set<String> set = new LinkedHashSet<>();
        if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                if (item != null) {
                    set.add(item.toString());
                }
            }
        }
        return set;
    }

    private boolean sourceContainsTerm(Map<String, Object> source, String term) {
        String haystack = normalizeForContains(buildSearchableText(source));
        String needle = normalizeForContains(term);
        return !needle.isEmpty() && haystack.contains(needle);
    }

    @SuppressWarnings("unchecked")
    private String buildSearchableText(Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        append(sb, source.get("content"));
        append(sb, source.get("display_content"));
        append(sb, source.get("doc_title"));
        append(sb, source.get("keywords"));
        Map<String, Object> metadata = castMap(source.get("metadata"));
        if (metadata != null) {
            append(sb, metadata.get("title"));
            append(sb, metadata.get("source"));
            append(sb, metadata.get("document_number"));
            append(sb, metadata.get("tags"));
            append(sb, metadata.get("tags_kw"));
            append(sb, metadata.get("search_queries"));
            append(sb, metadata.get("dynamic_meta"));
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

    private String buildDocKey(String hitId, Map<String, Object> source) {
        Map<String, Object> metadata = castMap(source.get("metadata"));
        if (metadata != null) {
            String docId = firstNonEmpty(
                    stringValue(metadata.get("doc_id")),
                    stringValue(metadata.get("content_hash")),
                    stringValue(metadata.get("doc_hash")));
            if (!docId.isEmpty()) {
                return docId;
            }
        }
        return extractDocHash(hitId);
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
        if (vMarkerIdx > 0 && vMarkerIdx + 2 < esId.length()
                && Character.isDigit(esId.charAt(vMarkerIdx + 2))) {
            return esId.substring(0, vMarkerIdx);
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

    private String normalizeForContains(String text) {
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

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private double numberValue(Object obj) {
        return obj instanceof Number ? ((Number) obj).doubleValue() : 0.0;
    }
}
