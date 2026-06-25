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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * [性能/可回滚] 每个 doc 槽位拉取的匹配 coarse 分片数上限。
     * 展示仅需 MAX_DISPLAY_COARSE=3 条，原硬编码 50 远超需求（首页 topK=50 时一次拉 7500 chunk）。
     * 默认 12（给 chooseCoverageChunks 留选取余量），可通过 search.keyword.coarse.matching-size 调整。
     */
    @Value("${search.keyword.coarse.matching-size:12}")
    private int matchingCoarseSize;

    /**
     * [性能/可回滚] 取证文档数 = min(matched, max(returnTopK, ceil(returnTopK * docMultiplier)))。
     * 原逻辑固定取 returnTopK*3，首页 topK=50 时取证 150 篇。默认 1.5（topK=50→75 篇），
     * 可通过 search.keyword.coarse.doc-multiplier 调整（设 3.0 即恢复原行为）。
     */
    @Value("${search.keyword.coarse.doc-multiplier:1.5}")
    private double coarseDocMultiplier;

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
        if (indexPattern != null && indexPattern.contains(",")) {
            if (indexPattern.contains("kb_document_")) {
                indexPattern = "kb_document";
            } else {
                indexPattern = indexPattern.split(",")[0];
            }
        }

        Map<String, List<Map<String, Object>>> chunksByDoc = new LinkedHashMap<>();
        List<Map<String, Object>> enrichedDocs = new ArrayList<>();
        // [性能/可回滚] 取证文档数受 coarseDocMultiplier 控制（默认 1.5），原硬编码为 3。
        int limit = Math.min(docs.size(), Math.max(context.getReturnTopK(),
                (int) Math.ceil(context.getReturnTopK() * coarseDocMultiplier)));

        List<Map<String, Object>> limitedDocs = new ArrayList<>();
        List<String> docIds = new ArrayList<>();
        Map<String, Set<String>> metadataTermsByDoc = new LinkedHashMap<>();
        Map<String, String> fileNameByDoc = new LinkedHashMap<>();
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

            // 提取文件名作为关联上下文，支持从 metadata.source 进行多级 fallback
            String fileName = "";
            if (source != null) {
                fileName = stringValue(source.get("source"));
                if (fileName.isEmpty()) {
                    fileName = stringValue(source.get("source_name"));
                }
                if (fileName.isEmpty()) {
                    Map<String, Object> metadata = castMap(source.get("metadata"));
                    if (metadata != null) {
                        fileName = stringValue(metadata.get("source"));
                    }
                }
            }
            fileNameByDoc.put(docId, fileName);
        }

        Map<String, List<Map<String, Object>>> candidatesByDoc;
        // [性能优化] 尝试使用批量查询（2-4 次 ES 往返替代 N×2 次），失败时回退原 MSearch
        try {
            candidatesByDoc = fetchMatchingCoarseBatched(indexPattern, docIds, terms, fileNameByDoc);
        } catch (Exception e) {
            System.err.println("[KeywordCoarseEvidence] 批量查询失败，回退原始 MSearch: " + e.getMessage());
            candidatesByDoc = fetchMatchingCoarseBatch(indexPattern, docIds, terms, fileNameByDoc);
        }

        List<String> fallbackDocIds = new ArrayList<>();
        for (int i = 0; i < limitedDocs.size(); i++) {
            Map<String, Object> doc = limitedDocs.get(i);
            String docId = docIds.get(i);
            Set<String> metadataMatchedTerms = metadataTermsByDoc.get(docId);
            List<Map<String, Object>> chunks = chooseCoverageChunks(candidatesByDoc.get(docId), terms, metadataMatchedTerms);
            if (chunks.isEmpty()) {
                fallbackDocIds.add(docId);
            }

            chunksByDoc.put(docId, chunks);
            doc.put("metadata_matched_terms", metadataMatchedTerms);
            doc.put("chunks", chunks);
        }

        if (!fallbackDocIds.isEmpty()) {
            Map<String, List<Map<String, Object>>> fallbackByDoc;
            try {
                fallbackByDoc = fetchFallbackCoarseBatched(indexPattern, fallbackDocIds, fileNameByDoc);
            } catch (Exception e) {
                System.err.println("[KeywordCoarseEvidence] Fallback 批量查询失败，回退原始 MSearch: " + e.getMessage());
                fallbackByDoc = fetchFallbackCoarseBatch(indexPattern, fallbackDocIds, fileNameByDoc);
            }
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
                                                                            List<String> terms,
                                                                            Map<String, String> fileNameByDoc) throws Exception {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }
        List<RequestItem> searches = new ArrayList<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
            String fileName = fileNameByDoc != null ? fileNameByDoc.get(docId) : "";
            searches.add(buildMatchingCoarseRequestItem(indexPattern, docId, terms, fileName));
        }
        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 6))
            .build(), Object.class);
        fillBatchResult(result, docIds, response, terms);
        return result;
    }

    private Map<String, List<Map<String, Object>>> fetchFallbackCoarseBatch(String indexPattern,
                                                                            List<String> docIds,
                                                                            Map<String, String> fileNameByDoc) throws Exception {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }
        List<RequestItem> searches = new ArrayList<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
            String fileName = fileNameByDoc != null ? fileNameByDoc.get(docId) : "";
            searches.add(buildFallbackCoarseRequestItem(indexPattern, docId, fileName));
        }
        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
            .searches(searches)
            .maxConcurrentSearches((long) Math.min(searches.size(), 6))
            .build(), Object.class);
        fillBatchResult(result, docIds, response, new ArrayList<>());
        return result;
    }

    // ─── [性能优化] 批量查询方法：2-4 次 ES 往返替代 N×2 次 ──────────────

    /**
     * [性能优化] 批量匹配查询：将 N 个文档的 per-doc MSearch 合并为 2 个分组查询。
     * 按 docId 类型（哈希 ID vs 文件名）分区，每组用一个 terms 过滤批量查询。
     * 返回结果按 docId 分组。
     */
    private Map<String, List<Map<String, Object>>> fetchMatchingCoarseBatched(
            String indexPattern, List<String> docIds, List<String> terms,
            Map<String, String> fileNameByDoc) throws Exception {

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
        }
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }

        // 按 docId 类型分区
        List<String> hashDocIds = new ArrayList<>();
        List<String> filenameDocIds = new ArrayList<>();
        for (String docId : docIds) {
            boolean isFileName = docId.contains(".") || !docId.matches("^[a-zA-Z0-9_\\-]+$");
            if (isFileName) {
                filenameDocIds.add(docId);
            } else {
                hashDocIds.add(docId);
            }
        }

        List<RequestItem> searches = new ArrayList<>();
        // 子查询 1：哈希 ID 组 — 用 terms 批量过滤 metadata.doc_id
        if (!hashDocIds.isEmpty()) {
            searches.add(buildBatchHashCoarseRequestItem(indexPattern, hashDocIds, terms, fileNameByDoc));
        }
        // 子查询 2：文件名组 — 用 terms 批量过滤 metadata.source
        if (!filenameDocIds.isEmpty()) {
            searches.add(buildBatchFilenameCoarseRequestItem(indexPattern, filenameDocIds, terms));
        }

        if (searches.isEmpty()) {
            return result;
        }

        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
                .searches(searches)
                .maxConcurrentSearches(2L)
                .build(), Object.class);

        // 按文档分组结果
        fillGroupedBatchResult(result, hashDocIds, filenameDocIds, response, terms, fileNameByDoc);
        return result;
    }

    /**
     * [性能优化] 批量 fallback 查询：对匹配查询失败的文档做无 term 过滤的宽松查询。
     */
    private Map<String, List<Map<String, Object>>> fetchFallbackCoarseBatched(
            String indexPattern, List<String> docIds,
            Map<String, String> fileNameByDoc) throws Exception {

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String docId : docIds) {
            result.put(docId, new ArrayList<>());
        }
        if (docIds == null || docIds.isEmpty()) {
            return result;
        }

        List<String> hashDocIds = new ArrayList<>();
        List<String> filenameDocIds = new ArrayList<>();
        for (String docId : docIds) {
            boolean isFileName = docId.contains(".") || !docId.matches("^[a-zA-Z0-9_\\-]+$");
            if (isFileName) {
                filenameDocIds.add(docId);
            } else {
                hashDocIds.add(docId);
            }
        }

        List<RequestItem> searches = new ArrayList<>();
        if (!hashDocIds.isEmpty()) {
            searches.add(buildBatchHashFallbackRequestItem(indexPattern, hashDocIds, fileNameByDoc));
        }
        if (!filenameDocIds.isEmpty()) {
            searches.add(buildBatchFilenameFallbackRequestItem(indexPattern, filenameDocIds));
        }

        if (searches.isEmpty()) {
            return result;
        }

        MsearchResponse<Object> response = esClient.msearch(new MsearchRequest.Builder()
                .searches(searches)
                .maxConcurrentSearches(2L)
                .build(), Object.class);

        fillGroupedBatchResult(result, hashDocIds, filenameDocIds, response, new ArrayList<>(), fileNameByDoc);
        return result;
    }

    /**
     * 构建哈希 ID 组的批量匹配查询：用 terms 过滤所有 metadata.doc_id。
     */
    private RequestItem buildBatchHashCoarseRequestItem(String indexPattern, List<String> hashDocIds,
                                                         List<String> terms, Map<String, String> fileNameByDoc) {
        List<co.elastic.clients.elasticsearch._types.FieldValue> idValues = hashDocIds.stream()
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                .collect(Collectors.toList());

        return new RequestItem.Builder()
                .header(h -> h.index(indexPattern))
                .body(b -> {
                    b.trackTotalHits(h -> h.enabled(false));
                    b.size(matchingCoarseSize * hashDocIds.size());
                    b.source(s -> s.filter(f -> f.includes(
                            "content", "display_content", "chunk_granularity",
                            "metadata.source", "metadata.chunk_id", "metadata.is_latest", "metadata.doc_id")));
                    b.query(q -> q.bool(bool -> {
                        // 文档过滤：metadata.doc_id IN (hashDocIds) + 文件名 fallback
                        bool.filter(f -> f.bool(docFilter -> {
                            docFilter.should(s -> s.terms(t -> t.field("metadata.doc_id")
                                    .terms(tv -> tv.value(idValues))));
                            for (String docId : hashDocIds) {
                                String fn = fileNameByDoc != null ? fileNameByDoc.getOrDefault(docId, "") : "";
                                if (fn != null && !fn.trim().isEmpty()) {
                                    docFilter.should(s -> s.term(t2 -> t2.field("metadata.source").value(fn)));
                                }
                            }
                            return docFilter.minimumShouldMatch("1");
                        }));
                        // is_latest 过滤
                        bool.filter(f -> f.bool(latestBool -> latestBool
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        // term 匹配
                        bool.must(m -> m.bool(anyTerm -> {
                            for (String term : terms) {
                                anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                                        .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                                        .should(ss -> ss.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(2.0f)))
                                        .minimumShouldMatch("1")));
                            }
                            return anyTerm.minimumShouldMatch("1");
                        }));
                        // 粒度加权
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
                        bool.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
                        return bool;
                    }));
                    return b;
                })
                .build();
    }

    /**
     * 构建文件名组的批量匹配查询：用 terms 过滤所有 metadata.source。
     */
    private RequestItem buildBatchFilenameCoarseRequestItem(String indexPattern, List<String> filenameDocIds,
                                                              List<String> terms) {
        List<co.elastic.clients.elasticsearch._types.FieldValue> fnValues = filenameDocIds.stream()
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                .collect(Collectors.toList());

        return new RequestItem.Builder()
                .header(h -> h.index(indexPattern))
                .body(b -> {
                    b.trackTotalHits(h -> h.enabled(false));
                    b.size(matchingCoarseSize * filenameDocIds.size());
                    b.source(s -> s.filter(f -> f.includes(
                            "content", "display_content", "chunk_granularity",
                            "metadata.source", "metadata.chunk_id", "metadata.is_latest")));
                    b.query(q -> q.bool(bool -> {
                        // 文件名过滤：metadata.source IN (filenameDocIds)
                        bool.filter(f -> f.bool(docFilter -> {
                            docFilter.should(s -> s.terms(t -> t.field("metadata.source")
                                    .terms(tv -> tv.value(fnValues))));
                            return docFilter.minimumShouldMatch("1");
                        }));
                        // is_latest 过滤
                        bool.filter(f -> f.bool(latestBool -> latestBool
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        // term 匹配
                        bool.must(m -> m.bool(anyTerm -> {
                            for (String term : terms) {
                                anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                                        .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                                        .should(ss -> ss.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(2.0f)))
                                        .minimumShouldMatch("1")));
                            }
                            return anyTerm.minimumShouldMatch("1");
                        }));
                        // 粒度加权
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
                        bool.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
                        return bool;
                    }));
                    return b;
                })
                .build();
    }

    /**
     * 构建哈希 ID 组的批量 fallback 查询（无 term 匹配条件）。
     */
    private RequestItem buildBatchHashFallbackRequestItem(String indexPattern, List<String> hashDocIds,
                                                           Map<String, String> fileNameByDoc) {
        List<co.elastic.clients.elasticsearch._types.FieldValue> idValues = hashDocIds.stream()
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                .collect(Collectors.toList());

        return new RequestItem.Builder()
                .header(h -> h.index(indexPattern))
                .body(b -> {
                    b.trackTotalHits(h -> h.enabled(false));
                    b.size(MAX_DISPLAY_COARSE * hashDocIds.size());
                    b.source(s -> s.filter(f -> f.includes(
                            "content", "display_content", "chunk_granularity",
                            "metadata.source", "metadata.chunk_id", "metadata.is_latest", "metadata.doc_id")));
                    b.query(q -> q.bool(bool -> {
                        bool.filter(f -> f.bool(docFilter -> {
                            docFilter.should(s -> s.terms(t -> t.field("metadata.doc_id")
                                    .terms(tv -> tv.value(idValues))));
                            for (String docId : hashDocIds) {
                                String fn = fileNameByDoc != null ? fileNameByDoc.getOrDefault(docId, "") : "";
                                if (fn != null && !fn.trim().isEmpty()) {
                                    docFilter.should(s -> s.term(t2 -> t2.field("metadata.source").value(fn)));
                                }
                            }
                            return docFilter.minimumShouldMatch("1");
                        }));
                        bool.filter(f -> f.bool(latestBool -> latestBool
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
                        bool.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
                        return bool;
                    }));
                    return b;
                })
                .build();
    }

    /**
     * 构建文件名组的批量 fallback 查询（无 term 匹配条件）。
     */
    private RequestItem buildBatchFilenameFallbackRequestItem(String indexPattern, List<String> filenameDocIds) {
        List<co.elastic.clients.elasticsearch._types.FieldValue> fnValues = filenameDocIds.stream()
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                .collect(Collectors.toList());

        return new RequestItem.Builder()
                .header(h -> h.index(indexPattern))
                .body(b -> {
                    b.trackTotalHits(h -> h.enabled(false));
                    b.size(MAX_DISPLAY_COARSE * filenameDocIds.size());
                    b.source(s -> s.filter(f -> f.includes(
                            "content", "display_content", "chunk_granularity",
                            "metadata.source", "metadata.chunk_id", "metadata.is_latest")));
                    b.query(q -> q.bool(bool -> {
                        bool.filter(f -> f.bool(docFilter -> {
                            docFilter.should(s -> s.terms(t -> t.field("metadata.source")
                                    .terms(tv -> tv.value(fnValues))));
                            return docFilter.minimumShouldMatch("1");
                        }));
                        bool.filter(f -> f.bool(latestBool -> latestBool
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
                        bool.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
                        bool.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
                        return bool;
                    }));
                    return b;
                })
                .build();
    }

    /**
     * [性能优化] 将批量查询返回的 ES 命中结果按原始 docId 分组。
     * 哈希组通过 metadata.doc_id 或 metadata.source 匹配回原始 docId；
     * 文件名组通过 metadata.source 精确匹配回原始 docId。
     */
    @SuppressWarnings("unchecked")
    private void fillGroupedBatchResult(Map<String, List<Map<String, Object>>> result,
                                         List<String> hashDocIds, List<String> filenameDocIds,
                                         MsearchResponse<Object> response, List<String> terms,
                                         Map<String, String> fileNameByDoc) {
        if (response == null || response.responses() == null) {
            return;
        }

        // 构建 source → docId 反向映射（用于通过 metadata.source 找到原始 docId）
        Map<String, String> sourceToDocId = new HashMap<>();
        if (fileNameByDoc != null) {
            for (Map.Entry<String, String> entry : fileNameByDoc.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    sourceToDocId.put(entry.getValue(), entry.getKey());
                }
            }
        }
        // 文件名 docId 自身就是 source
        for (String fn : filenameDocIds) {
            sourceToDocId.put(fn, fn);
        }
        // 哈希 docId 集合（用于通过 metadata.doc_id 匹配）
        Set<String> hashIdSet = new LinkedHashSet<>(hashDocIds);

        int responseIdx = 0;
        // 处理哈希组（如果有）
        if (!hashDocIds.isEmpty() && responseIdx < response.responses().size()) {
            MultiSearchResponseItem<Object> item = response.responses().get(responseIdx);
            if (item != null && item.isResult()) {
                processGroupedHits(item.result(), terms, hashIdSet, sourceToDocId, result);
            }
            responseIdx++;
        }
        // 处理文件名组（如果有）
        if (!filenameDocIds.isEmpty() && responseIdx < response.responses().size()) {
            MultiSearchResponseItem<Object> item = response.responses().get(responseIdx);
            if (item != null && item.isResult()) {
                processGroupedHits(item.result(), terms, hashIdSet, sourceToDocId, result);
            }
            responseIdx++;
        }
    }

    /**
     * 处理一组批量查询的命中结果，按 metadata.doc_id / metadata.source 映射回原始 docId。
     */
    @SuppressWarnings("unchecked")
    private void processGroupedHits(MultiSearchItem<Object> searchResult, List<String> terms,
                                     Set<String> hashIdSet, Map<String, String> sourceToDocId,
                                     Map<String, List<Map<String, Object>>> result) {
        if (searchResult == null || searchResult.hits() == null || searchResult.hits().hits() == null) {
            return;
        }
        for (Hit<Object> hit : searchResult.hits().hits()) {
            Map<String, Object> source = castMap(hit.source());
            if (source == null) continue;

            // 从 _source 中提取 metadata.doc_id 和 metadata.source，映射回原始 docId
            String matchedDocId = null;
            String metaDocId = "";
            String metaSource = "";
            Map<String, Object> metadata = castMap(source.get("metadata"));
            if (metadata != null) {
                metaDocId = stringValue(metadata.get("doc_id"));
                metaSource = stringValue(metadata.get("source"));
            }

            // 优先通过 metadata.doc_id 匹配哈希 ID
            if (!metaDocId.isEmpty() && hashIdSet.contains(metaDocId)) {
                matchedDocId = metaDocId;
            }
            // 通过 metadata.source 匹配
            if (matchedDocId == null && !metaSource.isEmpty() && sourceToDocId.containsKey(metaSource)) {
                matchedDocId = sourceToDocId.get(metaSource);
            }

            if (matchedDocId == null || !result.containsKey(matchedDocId)) {
                continue;
            }

            List<String> matchedTerms = matchedTerms(source, terms);
            if (terms != null && !terms.isEmpty() && matchedTerms.isEmpty()) {
                continue;
            }

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("_id", stringValue(hit.id()));
            chunk.put("_source", source);
            chunk.put("_es_score", hit.score() != null ? hit.score() : 0.0);
            chunk.put("matched_terms", matchedTerms);
            result.get(matchedDocId).add(chunk);
        }
    }

    /**
     * 业务功能：从 Msearch 的分包结果中提取对齐的物理 evidence 分片列表并填充至结果 Map。
     * 关键方法：fillBatchResult
     * 流程描述：
     *   1. 遍历 msearch 响应的每个文档对应 item。
     *   2. 调用 toEvidenceChunks 并配合关键词 terms 进行严格的包含高亮强匹配过滤。
     *   3. [防误杀宽容度退化兜底机制]：若严格过滤后结果为空，但 ES 物理上确实召回了 Hits（说明存在正文乱码或同义词转换未对准的差异），
     *      则重新调用 toEvidenceChunks 并传入空 list 进行宽容退化，保留原始打分最高的前几项以备下游展示，根治 chunk_text 变空缺陷。
     */
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
            List<Map<String, Object>> chunks = toEvidenceChunks(item.result(), terms);
            if (chunks.isEmpty() && item.result() != null && item.result().hits() != null && !item.result().hits().hits().isEmpty()) {
                chunks = toEvidenceChunks(item.result(), new ArrayList<>());
            }
            result.put(docIds.get(i), chunks);
        }
    }

    private RequestItem buildMatchingCoarseRequestItem(String indexPattern, String docId, List<String> terms, String fileName) {
        return new RequestItem.Builder()
            .header(h -> h.index(indexPattern))
            .body(b -> {
                b.trackTotalHits(h -> h.enabled(false));
                b.size(matchingCoarseSize);
                b.source(s -> s.filter(f -> f.includes(
                        "content",
                        "display_content",
                        "chunk_granularity",
                        "metadata.source",
                        "metadata.chunk_id",
                        "metadata.is_latest"
                )));
                b.query(q -> q.bool(bool -> {
                    addDocAndCoarseFilters(bool, docId, fileName);
                    bool.must(m -> m.bool(anyTerm -> {
                        for (String term : terms) {
                            anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                                .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                                .should(ss -> ss.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(2.0f)))
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

    private RequestItem buildFallbackCoarseRequestItem(String indexPattern, String docId, String fileName) {
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
                    addDocAndCoarseFilters(bool, docId, fileName);
                    return bool;
                }))
            )
            .build();
    }

    private List<Map<String, Object>> fetchMatchingCoarse(String indexPattern, String docId, List<String> terms) throws Exception {
        SearchRequest request = baseCoarseRequest(indexPattern, docId)
            .size(matchingCoarseSize)
            .query(q -> q.bool(b -> {
                addDocAndCoarseFilters(b, docId, null);
                b.must(m -> m.bool(anyTerm -> {
                    for (String term : terms) {
                        anyTerm.should(s -> s.bool(oneTerm -> oneTerm
                            .should(ss -> ss.matchPhrase(mp -> mp.field("content").query(term).slop(0).boost(8.0f)))
                            .should(ss -> ss.matchPhrase(mp -> mp.field("display_content").query(term).slop(0).boost(2.0f)))
                            .minimumShouldMatch("1")
                        ));
                    }
                    return anyTerm.minimumShouldMatch("1");
                }));
                return b;
            }))
            .build();
        SearchResponse<Object> response = esClient.search(request, Object.class);
        List<Map<String, Object>> chunks = toEvidenceChunks(response, terms);
        if (chunks.isEmpty() && response != null && response.hits() != null && !response.hits().hits().isEmpty()) {
            chunks = toEvidenceChunks(response, new ArrayList<>());
        }
        return chunks;
    }

    private List<Map<String, Object>> fetchFallbackCoarse(String indexPattern, String docId) throws Exception {
        SearchRequest request = baseCoarseRequest(indexPattern, docId)
            .size(MAX_DISPLAY_COARSE)
            .query(q -> q.bool(b -> {
                addDocAndCoarseFilters(b, docId, null);
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

    /**
     * 业务功能：在检索分片证据时添加文档关联与粗粒度分片过滤条件（优化双轨制检索）
     * 关键方法：addDocAndCoarseFilters
     * 流程描述：
     *   1. 判断 docId 的格式：如果 docId 包含 "." 或者包含非标准的哈希 ID 字符（比如中文等），说明它是退化的文件名而非标准的哈希 ID。
     *   2. 针对文件名退化情况，使用 metadata.source 字段进行 matchPhrase 匹配过滤；
     *   3. 针对标准的哈希 ID 情况，使用 keyword 类型的 metadata.doc_id 进行 term 精确过滤，以保障生产环境的高性能。
     *   4. 过滤最新版本分片：仅拉取标记为 is_latest 的分片，或没有该标志的分片（降级兼容）。
     *   5. 限制为粗粒度分片：为了在前端提供可读性高的段落证据，排除粒度为 fine 的精细分片。
     */
    private void addDocAndCoarseFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                        String docId, String fileName) {
        boolean isFileName = docId.contains(".") || !docId.matches("^[a-zA-Z0-9_\\-]+$");
        if (isFileName) {
            // 为什么这么做：由于 metadata.source 字段在 ES 映射中是 keyword 类型，在部分 ES 实例或分词环境下，
            // 仅使用 matchPhrase 可能会因为分词匹配原理导致无法正确召回分片。我们采用 term 精确匹配与 matchPhrase 
            // 的 should 并集组合，优先并精确地基于 keyword 匹配来获取 coarse 证据分片，从而彻底解决分片召回为空的问题。
            b.filter(f -> f.bool(boolQuery -> boolQuery
                .should(s -> s.term(t -> t.field("metadata.source").value(docId)))
                .should(s -> s.matchPhrase(m -> m.field("metadata.source").query(docId)))
                .minimumShouldMatch("1")
            ));
        } else {
            // [防 ID 孤岛关联漏洞]
            // 解释：针对物理索引缺失字段或 prefix 匹配在特定 ES 版本及配置下性能拦截失效的问题，
            // 精确哈希匹配的同时并入文件名的 should 条件，进行双向闭环保障，彻底解决 chunk_text 召回为空的问题。
            b.filter(f -> f.bool(boolQuery -> {
                boolQuery.should(s -> s.term(t -> t.field("metadata.doc_id").value(docId)));
                boolQuery.should(s -> s.term(t -> t.field("metadata.doc_id.keyword").value(docId)));
                if (fileName != null && !fileName.trim().isEmpty()) {
                    boolQuery.should(s -> s.term(t -> t.field("metadata.source").value(fileName)));
                    boolQuery.should(s -> s.matchPhrase(m -> m.field("metadata.source").query(fileName)));
                }
                return boolQuery.minimumShouldMatch("1");
            }));
        }
        b.filter(f -> f.bool(boolQuery -> boolQuery
            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
            .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
            .minimumShouldMatch("1")
        ));
        // 业务功能：兼容部分可能由于错误分类而全量被归入 fine 细粒度的物理分片。
        // 流程描述：不再使用 filter 强行滤除 fine 细粒度分片，而是在大 bool 查询中直接引入 should 子句。
        // 针对 coarse（粗粒度）和 missing（空粒度）赋予高权重 (boost=5.0f)，保证两类共存时 coarse 优先展示，且无 coarse 时 fine 自动兜底，彻底根治 Evidence 为空。
        b.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse").boost(5.0f)));
        b.should(s -> s.bool(missing -> missing.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity"))).boost(5.0f)));
        b.should(s -> s.term(t -> t.field("chunk_granularity").value("fine").boost(1.0f)));
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

        // 如果已选出部分证据但未能覆盖全部关键词，仍然保留已有证据（而非丢弃）。
        // 丢弃会导致 chunk_text 为空，前端无法展示任何内容。
        // 部分覆盖的证据对用户仍有参考价值。

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
        append(sb, source.get("title"));
        append(sb, source.get("source"));
        append(sb, source.get("document_number"));
        append(sb, source.get("keywords"));
        append(sb, source.get("tags"));
        append(sb, source.get("entities"));
        append(sb, source.get("section_titles"));
        append(sb, source.get("doc_terms"));
        append(sb, source.get("summary"));
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
