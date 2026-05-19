package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.pipeline.keyword.KeywordQueryPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts keyword document matches into the frontend result shape.
 */
@Component
public class KeywordResultAssembleStep implements SearchPipelineStep {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) {
        List<Map<String, Object>> docs = context.getKeywordDocumentHits();
        if (docs == null || docs.isEmpty()) {
            context.setFinalResult(new ArrayList<>());
            return;
        }

        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        List<String> requiredTerms = plan != null ? plan.safeRequiredTerms() : context.getKeywordFilterTerms();
        Set<String> required = new LinkedHashSet<>(requiredTerms);
        double topRankScore = numberValue(docs.get(0).get("keyword_rank_score"));
        if (topRankScore <= 0.001) {
            topRankScore = 1.0;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(context.getTopK(), docs.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> doc = docs.get(i);
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) doc.get("chunks");
            List<Map<String, Object>> evidenceChunks = chooseEvidenceChunks(chunks, required);
            Map<String, Object> source = castMap(doc.get("_source"));
            if ((source == null || source.isEmpty()) && !evidenceChunks.isEmpty()) {
                source = castMap(evidenceChunks.get(0).get("_source"));
            }
            Map<String, Object> metadata = source != null ? castMap(source.get("metadata")) : null;

            Map<String, Object> result = new LinkedHashMap<>();
            String docId = stringValue(doc.get("doc_id"));
            String fileName = metadata != null ? stringValue(metadata.get("source")) : "";
            String owner = metadata != null ? stringValue(metadata.get("owner")) : "";
            String visibility = metadata != null ? stringValue(metadata.get("visibility")) : "";
            result.put("doc_id", docId);
            result.put("organization", !owner.isEmpty() ? owner : fileName);
            result.put("file_name", fileName);
            result.put("publish_time", metadata != null ? metadata.getOrDefault("publish_time", "") : "");
            result.put("tags", metadata != null ? metadata.get("tags") : null);
            result.put("custom_tags", metadata != null ? metadata.get("custom_keywords") : null);
            result.put("dept_code", metadata != null ? metadata.getOrDefault("owner_dept_id", "") : "");
            result.put("visibility", visibility);
            result.put("cacheable", isCacheable(visibility));
            result.put("matched_terms", new ArrayList<>(required));

            double rankScore = numberValue(doc.get("keyword_rank_score"));
            double score = Math.max(0.05, Math.min(0.99, 0.50 + (rankScore / topRankScore) * 0.49));
            result.put("score", Math.round(score * 10000.0) / 10000.0);

            List<Map<String, Object>> outputChunks = new ArrayList<>();
            for (Map<String, Object> evidence : evidenceChunks) {
                Map<String, Object> chunkSource = castMap(evidence.get("_source"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("chunk_id", evidence.get("_id"));
                out.put("chunk_index", extractChunkIndex(evidence, chunkSource));
                out.put("matched_terms", toStringList(evidence.get("matched_terms")));
                out.put("chunk_granularity", chunkSource != null ? chunkSource.get("chunk_granularity") : null);
                out.put("chunk_gran", chunkSource != null ? chunkSource.get("chunk_granularity") : null);
                out.put("chunk_text", buildSnippet(chunkSource, toStringList(evidence.get("matched_terms"))));
                outputChunks.add(out);
            }
            result.put("chunks", outputChunks);
            result.put("chunk_text", outputChunks.isEmpty() ? "" : outputChunks.get(0).get("chunk_text"));
            results.add(result);
        }

        context.setFinalResult(results);
        System.out.printf("[KeywordResult] assembled=%d%n", results.size());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chooseEvidenceChunks(List<Map<String, Object>> chunks, Set<String> required) {
        List<Map<String, Object>> chosen = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return chosen;
        }
        // 关键词模式要求前端至少展示 3 个 coarse 分片，不能再按“最少覆盖集合”压缩成单条证据。
        int limit = Math.min(chunks.size(), 3);
        for (int i = 0; i < limit; i++) {
            chosen.add(chunks.get(i));
        }
        return chosen;
    }

    private String buildSnippet(Map<String, Object> source, List<String> terms) {
        if (source == null) {
            return "";
        }
        String content = stringValue(source.get("content"));
        if (content.isEmpty()) {
            content = stringValue(source.get("display_content"));
        }
        String snippet = locateSnippet(content, terms);
        return highlight(snippet, terms);
    }

    private String locateSnippet(String content, List<String> terms) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int bestIdx = -1;
        String bestTerm = "";
        for (String term : terms) {
            int idx = content.indexOf(term);
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
                bestTerm = term;
            }
        }
        if (bestIdx < 0) {
            return content.substring(0, Math.min(160, content.length()));
        }
        int start = Math.max(0, bestIdx - 50);
        int end = Math.min(content.length(), bestIdx + bestTerm.length() + 90);
        String snippet = content.substring(start, end);
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < content.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private String highlight(String text, List<String> terms) {
        String result = text == null ? "" : text;
        List<String> sorted = new ArrayList<>(terms);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String term : sorted) {
            if (term == null || term.trim().isEmpty()) {
                continue;
            }
            result = result.replaceAll(Pattern.quote(term),
                    Matcher.quoteReplacement("<em class='highlight'>" + term + "</em>"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
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

    @SuppressWarnings("unchecked")
    private int extractChunkIndex(Map<String, Object> evidence, Map<String, Object> source) {
        if (source != null) {
            Map<String, Object> metadata = castMap(source.get("metadata"));
            if (metadata != null && metadata.get("chunk_id") instanceof Number) {
                return ((Number) metadata.get("chunk_id")).intValue();
            }
        }
        String id = stringValue(evidence.get("_id"));
        int idx = id.lastIndexOf("_chunk_");
        if (idx >= 0) {
            try {
                return Integer.parseInt(id.substring(idx + 7));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private boolean isCacheable(String visibility) {
        return "PUBLIC".equals(visibility) || "INTERNAL".equals(visibility);
    }

    private String stringValue(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private double numberValue(Object obj) {
        return obj instanceof Number ? ((Number) obj).doubleValue() : 0.0;
    }
}
