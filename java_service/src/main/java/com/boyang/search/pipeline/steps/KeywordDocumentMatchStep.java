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

    /**
     * 业务功能：从 _source 中生成用于关键词匹配过滤的可检索文本
     * 关键方法：buildSearchableText
     * 流程描述：
     *   1. 优先提取 content, display_content, doc_title, keywords。
     *   2. 从外层平铺字段中读取 title, source, document_number, tags, tags_kw, search_queries 等。
     *   3. 提取 metadata 中的各子项（如 title, source, document_number, tags, tags_kw, search_queries 等）。
     *   4. 以上各步骤在 metadata 为 null 或存在嵌套数据结构差异时，能够自动 fallback 互补拼接，提高召回率和匹配精度。
     */
    @SuppressWarnings("unchecked")
    private String buildSearchableText(Map<String, Object> source) {
        StringBuilder sb = new StringBuilder();
        if (source == null) {
            return "";
        }
        append(sb, source.get("content"));
        append(sb, source.get("display_content"));
        append(sb, source.get("doc_title"));
        append(sb, source.get("keywords"));
        
        // 兼容平铺元数据模式，直接从根级合并抽取检索字段
        append(sb, source.get("title"));
        append(sb, source.get("source"));
        append(sb, source.get("document_number"));
        append(sb, source.get("tags"));
        append(sb, source.get("tags_kw"));
        append(sb, source.get("search_queries"));

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

    /**
     * 业务功能：提取文档在整条搜索管线中流转的唯一标识（docKey）
     * 关键方法：buildDocKey
     * 流程描述：
     *   1. 优先从底层 _source 或元数据中获取 keyword 类型的 doc_id (如内容 MD5 哈希)。
     *   2. 如果没有 doc_id，退化返回文件名（source 或 metadata.source），保证老旧索引文档能通过文件名进行关联 chunks 检索。
     *   3. 如果前两者均没有，最后退化提取 ES 的 doc ID 哈希（extractDocHash），满足零版本迁移的平滑升级需求。
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
        // 解释：针对物理索引缺失字段反序列化出的 "None" 脏数据进行主动拦截，
        // 确保能退化为文件名，实现和召回策略类同样稳健的 ID 校验逻辑。
        if (docId != null && !docId.trim().isEmpty() && !"none".equalsIgnoreCase(docId.trim())) {
            return docId;
        }
        // 如果缺少新版 doc_id 标识，优先提取文件名作为 docKey 供 downstream match 兼容过滤老分片
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
