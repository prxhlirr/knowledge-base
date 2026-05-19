package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keyword-only document ranker.
 *
 * Ranking stays lexical and explainable: documents where one chunk covers more
 * terms, or where title/source/document number match, outrank weaker matches.
 */
@Component
public class KeywordRankStep implements SearchPipelineStep {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) {
        List<Map<String, Object>> docs = context.getKeywordDocumentHits();
        if (docs == null || docs.isEmpty()) {
            context.setKeywordDocumentHits(new ArrayList<>());
            return;
        }

        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        List<String> requiredTerms = plan != null ? plan.safeRequiredTerms() : context.getKeywordFilterTerms();
        final int requiredCount = Math.max(requiredTerms != null ? requiredTerms.size() : 0, 1);

        for (Map<String, Object> doc : docs) {
            double maxEsScore = numberValue(doc.get("max_es_score"));
            int bestChunkCoverage = 0;
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) doc.get("chunks");
            if (chunks != null) {
                for (Map<String, Object> chunk : chunks) {
                    Object termsObj = chunk.get("matched_terms");
                    int size = termsObj instanceof Set ? ((Set<?>) termsObj).size()
                            : termsObj instanceof List ? ((List<?>) termsObj).size() : 0;
                    bestChunkCoverage = Math.max(bestChunkCoverage, size);
                }
            }

            Map<String, Object> source = castMap(doc.get("_source"));
            Map<String, Object> metadata = source != null ? castMap(source.get("metadata")) : null;
            int metadataMatches = countMetadataMatches(source, metadata, requiredTerms);
            double singleChunkBonus = bestChunkCoverage >= requiredCount ? 8.0 : 0.0;
            double coverageBonus = ((double) bestChunkCoverage / requiredCount) * 5.0;
            double rankScore = maxEsScore + coverageBonus + singleChunkBonus + metadataMatches * 3.0;
            doc.put("keyword_rank_score", rankScore);
            doc.put("best_chunk_term_count", bestChunkCoverage);
        }

        docs.sort((a, b) -> Double.compare(
                numberValue(b.get("keyword_rank_score")),
                numberValue(a.get("keyword_rank_score"))));
        context.setKeywordDocumentHits(docs);
    }

    private int countMetadataMatches(Map<String, Object> source, Map<String, Object> metadata, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        if (source != null) {
            append(sb, source.get("doc_title"));
        }
        if (metadata != null) {
            append(sb, metadata.get("title"));
            append(sb, metadata.get("source"));
            append(sb, metadata.get("document_number"));
        }
        String text = normalize(sb.toString());
        int count = 0;
        for (String term : terms) {
            if (!term.isEmpty() && text.contains(normalize(term))) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    private void append(StringBuilder sb, Object obj) {
        if (obj != null) {
            sb.append('\n').append(obj);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
    }

    private double numberValue(Object obj) {
        return obj instanceof Number ? ((Number) obj).doubleValue() : 0.0;
    }
}
