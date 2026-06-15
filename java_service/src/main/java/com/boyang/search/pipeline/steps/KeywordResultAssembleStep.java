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
        // [性能优化] LiteralRecall 短路：BM25 管道被跳过，直接从 fastTrackDocs 构建结果
        if (context.isLiteralShortCircuit()) {
            context.setFinalResult(buildLiteralOnlyResults(context));
            System.out.printf("[KeywordResult] literalShortCircuit assembled=%d%n", context.getFinalResult().size());
            return;
        }

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
        int limit = Math.min(context.getReturnTopK(), docs.size());
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
            
            // 提取文件名：优先从 metadata.source 读取，若为空则从外层平铺字段 fallback 适配新检索索引结构
            String fileName = metadata != null ? stringValue(metadata.get("source")) : "";
            if (fileName.isEmpty() && source != null) {
                fileName = firstNonEmpty(
                    stringValue(source.get("source")),
                    stringValue(source.get("source_name")),
                    stringValue(source.get("title")),
                    stringValue(source.get("doc_title"))
                );
            }

            // 提取发布单位：优先从 metadata.owner 读取，若为空则从外层平铺字段 fallback 适配
            String owner = metadata != null ? stringValue(metadata.get("owner")) : "";
            if (owner.isEmpty() && source != null) {
                owner = firstNonEmpty(
                    stringValue(source.get("owner")),
                    stringValue(source.get("owner_name"))
                );
            }

            // 提取发布单位可见度
            String visibility = metadata != null ? stringValue(metadata.get("visibility")) : "";
            if (visibility.isEmpty() && source != null) {
                visibility = stringValue(source.get("visibility"));
            }

            // 提取发布时间
            String publishTime = metadata != null ? stringValue(metadata.getOrDefault("publish_time", "")) : "";
            if (publishTime.isEmpty() && source != null) {
                publishTime = stringValue(source.getOrDefault("publish_time", ""));
            }

            // 提取标签与自定义关键词
            Object tags = metadata != null ? metadata.get("tags") : null;
            if (tags == null && source != null) {
                tags = source.get("tags");
            }
            Object customTags = metadata != null ? metadata.get("custom_keywords") : null;
            if (customTags == null && source != null) {
                String fallbackKw = firstNonEmpty(
                    stringValue(source.get("custom_keywords")),
                    stringValue(source.get("keywords"))
                );
                customTags = !fallbackKw.isEmpty() ? fallbackKw : source.get("custom_keywords");
            }

            // 提取所属部门编码
            String deptCode = metadata != null ? stringValue(metadata.getOrDefault("owner_dept_id", "")) : "";
            if (deptCode.isEmpty() && source != null) {
                deptCode = stringValue(source.getOrDefault("owner_dept_id", ""));
            }

            result.put("doc_id", docId);
            result.put("organization", !owner.isEmpty() ? owner : fileName);
            result.put("file_name", fileName);
            // 提取文档标题：优先从 metadata.title，fallback 到 source 级别 title/doc_title
            String title = metadata != null ? stringValue(metadata.get("title")) : "";
            if (title.isEmpty() && source != null) {
                title = firstNonEmpty(
                    stringValue(source.get("title")),
                    stringValue(source.get("doc_title"))
                );
            }
            result.put("title", title);
            result.put("publish_time", publishTime);
            result.put("tags", tags);
            result.put("custom_tags", customTags);
            result.put("dept_code", deptCode);
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

        // 合并 LiteralRecall 的 fastTrackDocs（keyword 模式下不短路 Pipeline，而是延迟合并）
        List<Map<String, Object>> fastTrack = context.getFastTrackDocs();
        if (fastTrack != null && !fastTrack.isEmpty()) {
            Set<String> existingSources = new HashSet<>();
            for (Map<String, Object> r : results) {
                String fn = stringValue(r.get("file_name"));
                if (!fn.isEmpty()) existingSources.add(fn);
            }
            int bm25Count = results.size();
            List<Map<String, Object>> literalResults = new ArrayList<>();
            for (Map<String, Object> hit : fastTrack) {
                Map<String, Object> source = castMap(hit.get("_source"));
                String fileName = "";
                if (source != null) {
                    fileName = firstNonEmpty(
                        stringValue(source.get("source")),
                        stringValue(source.get("source_name")),
                        stringValue(source.get("title"))
                    );
                    Map<String, Object> metadata = castMap(source.get("metadata"));
                    if (fileName.isEmpty() && metadata != null) {
                        fileName = stringValue(metadata.get("source"));
                    }
                }
                // 跳过已经在 BM25 结果中的文档（去重）
                if (!fileName.isEmpty() && existingSources.contains(fileName)) {
                    continue;
                }
                String owner = "";
                if (source != null) {
                    owner = firstNonEmpty(
                        stringValue(source.get("owner")),
                        stringValue(source.get("owner_name"))
                    );
                    Map<String, Object> metadata = castMap(source.get("metadata"));
                    if (owner.isEmpty() && metadata != null) {
                        owner = stringValue(metadata.get("owner"));
                    }
                }
                Map<String, Object> literalResult = new LinkedHashMap<>();
                literalResult.put("doc_id", hit.get("_id"));
                literalResult.put("organization", !owner.isEmpty() ? owner : fileName);
                literalResult.put("file_name", fileName);
                // 提取文档标题：literal 结果也需要 title 字段
                String literalTitle = "";
                if (source != null) {
                    Map<String, Object> literalMeta = castMap(source.get("metadata"));
                    if (literalMeta != null) {
                        literalTitle = stringValue(literalMeta.get("title"));
                    }
                    if (literalTitle.isEmpty()) {
                        literalTitle = firstNonEmpty(
                            stringValue(source.get("title")),
                            stringValue(source.get("doc_title"))
                        );
                    }
                }
                literalResult.put("title", literalTitle);
                literalResult.put("score", 0.99); // literal 精确命中最高分
                literalResult.put("chunks", new ArrayList<>());
                literalResult.put("chunk_text", "");
                literalResult.put("cacheable", true);
                literalResult.put("matched_terms", new ArrayList<>(required));
                literalResults.add(literalResult);
            }
            // literal 命中插入到结果最前面（优先级最高）
            literalResults.addAll(results);
            results = literalResults;
            System.out.printf("[KeywordResult] merged %d literal + %d BM25 results%n",
                    literalResults.size(), bm25Count);
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

    /**
     * 业务功能：辅助从备选字段列表中提取第一个非空的字符串值
     * 关键方法：firstNonEmpty
     * 流程描述：
     *   1. 遍历可变参数 values。
     *   2. 校验每个值是否为 null 或空字符串。
     *   3. 若不为空则直接返回，若全部为空则返回空字符串。
     */
    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /**
     * [性能优化] LiteralRecall 短路时，直接从 fastTrackDocs 构建前端结果。
     * fastTrackDocs 已由 LiteralRecallStep 完成格式化（含 doc_id / file_name / title / score 等），
     * 无需经过 BM25 召回 → 文档交集 → 证据分片的完整管道。
     *
     * @param context 搜索上下文（含 fastTrackDocs）
     * @return 前端格式化的结果列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildLiteralOnlyResults(SearchContext context) {
        List<Map<String, Object>> fastTrack = context.getFastTrackDocs();
        if (fastTrack == null || fastTrack.isEmpty()) {
            return new ArrayList<>();
        }
        KeywordQueryPlan plan = context.getKeywordQueryPlan();
        List<String> requiredTerms = plan != null ? plan.safeRequiredTerms() : context.getKeywordFilterTerms();
        Set<String> required = new LinkedHashSet<>(requiredTerms != null ? requiredTerms : new ArrayList<>());

        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(context.getReturnTopK(), fastTrack.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> hit = fastTrack.get(i);
            // fastTrackDocs 已具备前端所需字段，补齐 matched_terms 和 chunks 即可
            Map<String, Object> result = new LinkedHashMap<>(hit);
            if (!result.containsKey("matched_terms")) {
                result.put("matched_terms", new ArrayList<>(required));
            }
            if (!result.containsKey("chunks")) {
                result.put("chunks", new ArrayList<>());
            }
            if (!result.containsKey("cacheable")) {
                String vis = stringValue(result.get("visibility"));
                result.put("cacheable", "PUBLIC".equals(vis) || "INTERNAL".equals(vis));
            }
            results.add(result);
        }
        return results;
    }
}
