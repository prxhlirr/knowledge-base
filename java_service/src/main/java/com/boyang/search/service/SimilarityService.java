package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.security.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ? *
 * ?
 * 1. ext-vs-Text ?AI Service ?BGE-M3 Dense ? * 2. ES y-vs-Corpus ?ES scroll ?AI
 * Service ? * ?
 *
 * ? * - compareSimilarity(textA, textB) ? ?/api/ai/similarity/compare
 * - corpusScan(index, queryText, limit) ?ES scroll + ?compare ? ?
 *
 * pusScan ?2000 ?chunk ?
 */
@Service
public class SimilarityService {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private PermissionGuard permissionGuard;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiHost;

    @Value("${ai.service.embedding-host:http://127.0.0.1:8001}")
    private String embeddingHost;

    @Value("${ai.service.rerank-host:http://127.0.0.1:8001}")
    private String rerankHost;

    @Value("${editor.similarity.min-input-chars:80}")
    private int editorSimilarityMinInputChars;

    @Value("${editor.similarity.max-input-chars:4000}")
    private int editorSimilarityMaxInputChars;

    @Value("${editor.similarity.min-score:0.55}")
    private double editorSimilarityMinScore;

    @Value("${editor.similarity.cache-ttl-seconds:60}")
    private long editorSimilarityCacheTtlSeconds;

    @Value("${editor.similarity.meta-index:kb_doc_meta_read}")
    private String editorSimilarityMetaIndex;

    @Value("${editor.similarity.chunk-fallback-index:kb_document}")
    private String editorSimilarityChunkFallbackIndex;

    @Value("${editor.similarity.max-candidates:20}")
    private int editorSimilarityMaxCandidates;

    @Value("${editor.similarity.knn-num-candidates:120}")
    private int editorSimilarityKnnNumCandidates;

    @Value("${editor.similarity.rerank-enabled:true}")
    private boolean editorSimilarityRerankEnabled;

    @Value("${editor.similarity.rerank-min-score:0.50}")
    private double editorSimilarityRerankMinScore;

    @Value("${editor.similarity.rerank-weight:0.30}")
    private double editorSimilarityRerankWeight;

    @Value("${editor.similarity.rerank-weight-with-evidence:0.60}")
    private double editorSimilarityRerankWeightWithEvidence;

    @Value("${editor.similarity.rerank-weight-without-evidence:0.25}")
    private double editorSimilarityRerankWeightWithoutEvidence;

    @Value("${editor.similarity.vector-high-confidence-threshold:0.85}")
    private double editorSimilarityVectorHighConfidenceThreshold;

    @Value("${editor.similarity.vector-high-confidence-floor-ratio:0.70}")
    private double editorSimilarityVectorHighConfidenceFloorRatio;

    @Value("${editor.similarity.conflict-rerank-threshold:0.20}")
    private double editorSimilarityConflictRerankThreshold;

    @Value("${editor.similarity.rerank-evidence-max-chars:1200}")
    private int editorSimilarityRerankEvidenceMaxChars;

    @Value("${editor.similarity.evidence-enabled:true}")
    private boolean editorSimilarityEvidenceEnabled;

    @Value("${editor.similarity.evidence-fetch-docs:10}")
    private int editorSimilarityEvidenceFetchDocs;

    @Value("${editor.similarity.evidence-top-k:2}")
    private int editorSimilarityEvidenceTopK;

    @Value("${editor.similarity.evidence-max-chars:1200}")
    private int editorSimilarityEvidenceMaxChars;

    /** ?RestTemplate ?SearchService ? */
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimilarityService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(30000); // ?
        this.restTemplate = new RestTemplate(factory);
    }

    private String normalizeBaseUrl(String host) {
        String selected = (host == null || host.trim().isEmpty()) ? aiHost : host;
        return selected.replace("localhost", "127.0.0.1");
    }

    // ?
    // ?
    // ?

    /**
     * ?BGE-M3 ? * ?text_a / text_b ?Python AI Service /api/ai/similarity/compare ?
     * * ?cosine ?1,1] ?
     *
     * @param textA ?A
     * @param textB ?B
     * @return ?cosine el tMs ?Map ?code=500
     */
    public Map<String, Object> compareSimilarity(String textA, String textB) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text_a", textA);
            payload.put("text_b", textB);

            String url = normalizeBaseUrl(embeddingHost) + "/api/ai/similarity/compare";
            String respJson = restTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = objectMapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                result.put("code", 200);
                result.put("data", respMap.get("data"));
            } else {
                result.put("code", 500);
                result.put("msg", "AI Service                                          ? " + respJson);
            }
        } catch (Exception e) {
            System.err.println(" ?[SimilarityService#compareSimilarity] " + e.getMessage());
            result.put("code", 500);
            result.put("msg", "                   ?AI Service                    ? " + e.getMessage());
        }
        return result;
    }

    // ?
    // ES ?
    // ?

    /**
     * ?ES ?queryText ?
     *
     * ? * 1. ES scroll ?limit ?content ? * 2. ?BATCH_SIZE ?AI Service compare ?
     * 3. ?cosine ? * ?[0-0.2, 0.2-0.4, 0.4-0.6, 0.6-0.8, 0.8-1.0] ?
     *
     * @param index ES ?knowledge_base_* ? * @param queryText ?
     * @param limit ?2000 ?5000
     * @return Map { code, data: { items:[{docId,title,chunkText,cosine,label}],
     *         distribution, total, costMs } }
     */
    public Map<String, Object> corpusScan(String index, String queryText, int limit) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        int safeLimit = Math.min(Math.max(limit, 1), 5000);
        final int BATCH_SIZE = 25;

        try {
            // 1. ES scroll 拉取文档原文及元数据
            List<Map<String, Object>> rawDocs = scrollEs(index, safeLimit);
            System.out.printf("[CorpusScan] Fetched %d docs from ES%n", rawDocs.size());

            List<Map<String, Object>> items = new ArrayList<>();
            // 统计 5 个区间的文档数量: [0-0.2), [0.2-0.4), [0.4-0.6), [0.6-0.8), [0.8-1.0]
            int[] distribution = new int[5];

            // 2. 分批比对，控制对 AI 服务的并发压力
            for (int i = 0; i < rawDocs.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, rawDocs.size());
                List<Map<String, Object>> batch = rawDocs.subList(i, end);

                for (Map<String, Object> doc : batch) {
                    String docText = (String) doc.get("content");
                    // 边界防御：过滤无实质内容的无效 chunk
                    if (docText == null || docText.trim().isEmpty()) {
                        continue;
                    }

                    // 调用 compareSimilarity 计算语义余弦值
                    Map<String, Object> compResult = compareSimilarity(queryText, docText);
                    if (compResult != null && Integer.valueOf(200).equals(compResult.get("code"))) {
                        Map<String, Object> compData = (Map<String, Object>) compResult.get("data");
                        double cosine = compData.containsKey("cosine") ? ((Number) compData.get("cosine")).doubleValue()
                                : 0.0;
                        String label = (String) compData.getOrDefault("label", "weak");

                        String docId = (String) doc.get("_id");
                        String title = "";
                        // 从元数据中提取标题，多级降级兜底
                        if (doc.containsKey("metadata") && doc.get("metadata") instanceof Map) {
                            Map<String, Object> meta = (Map<String, Object>) doc.get("metadata");
                            title = (String) meta.getOrDefault("title", "");
                            if (title == null || title.isEmpty()) {
                                title = (String) meta.getOrDefault("source", "");
                            }
                        }

                        // 预览文本截断，防止海量返回导致 Java OOM
                        String chunkText = docText;
                        if (chunkText.length() > 120) {
                            chunkText = chunkText.substring(0, 120) + "...";
                        }

                        Map<String, Object> item = new HashMap<>();
                        item.put("docId", docId);
                        item.put("title", title);
                        item.put("chunkText", chunkText);
                        item.put("cosine", cosine);
                        item.put("label", label);
                        items.add(item);

                        // 计算余弦值对应的区间索引，做强制上下界溢出防御
                        int distIdx = (int) (cosine * 5.0);
                        if (distIdx < 0)
                            distIdx = 0;
                        if (distIdx > 4)
                            distIdx = 4;
                        distribution[distIdx]++;
                    }
                }
            }

            // 3. 组装接口数据
            Map<String, Object> data = new HashMap<>();
            data.put("items", items);

            List<Map<String, Object>> distList = new ArrayList<>();
            String[] ranges = { "0.0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1.0" };
            for (int d = 0; d < 5; d++) {
                Map<String, Object> distMap = new HashMap<>();
                distMap.put("range", ranges[d]);
                distMap.put("count", distribution[d]);
                distList.add(distMap);
            }
            data.put("distribution", distList);
            data.put("total", rawDocs.size());
            data.put("costMs", System.currentTimeMillis() - startTime);

            result.put("code", 200);
            result.put("data", data);

        } catch (Exception e) {
            System.err.println("[SimilarityService#corpusScan] Exception: " + e.getMessage());
            result.put("code", 500);
            result.put("msg", "Error in corpus scan: " + e.getMessage());
        }
        return result;
    }

    /**
     * ? ? ? * ? * 1. ?AI Service /api/ai/vector/long-doc ?
     * 2. ?kb_doc_meta ?doc_vector ?KNN ? * 3. ?top-K ?excludeSource ? * ?corpusScan
     * ?
     * - corpusScan nk ?AI Service ? * - findSimilarDocs ?doc_vector ?KNN ? *
     * ?/api/ai/vector/long-doc ?doc_vector ?kb_doc_meta KNN
     * ? ?excludeSource ?topK ? ? ?
     *
     * @param text ? * @param topK ?3-10 ? * @param excludeSource ? * @return Map {
     *             code, data: { items:[{source, similarity, chunkCount}], costMs }
     *             }
     */
    public Map<String, Object> findSimilarDocs(String text, int topK, String excludeSource) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: ?
            Map<String, Object> vectorPayload = new HashMap<>();
            vectorPayload.put("text", text);

            String vectorUrl = normalizeBaseUrl(embeddingHost) + "/api/ai/vector/long-doc";
            String vectorRespJson = restTemplate.postForObject(vectorUrl, vectorPayload, String.class);
            Map<String, Object> vectorResp = objectMapper.readValue(vectorRespJson, Map.class);

            if (!Integer.valueOf(200).equals(vectorResp.get("code"))) {
                result.put("code", 500);
                result.put("msg", "AI Service                                                ? " + vectorRespJson);
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> vectorData = (Map<String, Object>) vectorResp.get("data");
            @SuppressWarnings("unchecked")
            List<Double> queryVec = (List<Double>) vectorData.get("vector");
            int segments = vectorData.containsKey("segments") ? ((Number) vectorData.get("segments")).intValue() : 1;

            System.out.printf("             ?[SimilarDocs] Text vectorized: %d segments%n", segments);

            // Step 2: KNN on kb_doc_meta.doc_vector
            final List<Double> finalQueryVec = queryVec;
            int kFetch = Math.min(topK + 3, 20); // ?topK

            SearchRequest knnReq = new SearchRequest.Builder()
                    .index(editorSimilarityMetaIndex)
                    .knn(k -> k.field("doc_vector")
                            .queryVector(finalQueryVec)
                            .k(kFetch)
                            .numCandidates(Math.max(kFetch, editorSimilarityKnnNumCandidates))
                            .filter(f -> f.bool(b -> {
                                appendLatestFilter(b, "is_latest");
                                return b;
                            })))
                    .size(kFetch)
                    .source(s -> s.filter(f -> f.excludes(Arrays.asList("doc_vector"))))
                    .build();

            SearchResponse<Object> knnResp = esClient.search(knnReq, Object.class);

            List<Map<String, Object>> items = new ArrayList<>();
            // Step 3: ?excludeSource ?topK ? List<Map<String, Object>> items = new
            // ArrayList<>();
            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : knnResp.hits().hits()) {
                if (items.size() >= topK)
                    break;

                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null)
                    continue;

                String source = (String) src.getOrDefault("source", "");
                // ?
                if (excludeSource != null && !excludeSource.isEmpty()
                        && source.equals(excludeSource))
                    continue;

                double similarity = hit.score() != null ? hit.score() : 0.0;
                int chunkCount = src.containsKey("chunk_count") ? ((Number) src.get("chunk_count")).intValue() : 0;

                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("docId", hit.id());
                item.put("source", source);
                item.put("similarity", Math.round(similarity * 10000.0) / 10000.0);
                item.put("chunkCount", chunkCount);
                item.put("label", similarityLabel(similarity));
                items.add(item);
            }

            long costMs = System.currentTimeMillis() - startTime;
            System.out.printf(" ?[SimilarDocs] Found %d similar docs in %dms%n", items.size(), costMs);

            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            data.put("costMs", costMs);
            data.put("total", items.size());
            data.put("metaIndex", editorSimilarityMetaIndex);

            result.put("code", 200);
            result.put("data", data);

        } catch (Exception e) {
            System.err.println(" ?[SimilarityService#findSimilarDocs] " + e.getMessage());
            result.put("code", 500);
            result.put("msg",
                    "                                                                                        ? "
                            + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> findSimilarDocsForEditor(String appCode,
            String text,
            int topK,
            String excludeDocId,
            String excludeSource,
            JwtVerifier.UserIdentity identity) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> timings = new LinkedHashMap<>();
        String normalizedText = normalizeEditorText(text);

        data.put("items", Collections.emptyList());
        data.put("total", 0);
        data.put("queryChars", normalizedText.length());
        data.put("cacheHit", false);
        data.put("timings", timings);

        if (normalizedText.length() < Math.max(1, editorSimilarityMinInputChars)) {
            data.put("skipped", true);
            data.put("skipReason", "input_too_short");
            data.put("costMs", System.currentTimeMillis() - startTime);
            result.put("code", 200);
            result.put("data", data);
            return result;
        }

        topK = Math.max(1, Math.min(topK, 10));
        String cacheKey = editorSimilarityCacheKey(appCode, identity, normalizedText, topK, excludeDocId,
                excludeSource);
        Map<String, Object> cached = readEditorSimilarityCache(cacheKey);
        if (cached != null) {
            cached.put("cacheHit", true);
            result.put("code", 200);
            result.put("data", cached);
            return result;
        }

        try {
            long vectorStart = System.currentTimeMillis();
            Map<String, Object> vectorPayload = new HashMap<>();
            vectorPayload.put("text", normalizedText);
            String vectorUrl = normalizeBaseUrl(embeddingHost) + "/api/ai/vector/long-doc";
            String vectorRespJson = restTemplate.postForObject(vectorUrl, vectorPayload, String.class);
            Map<String, Object> vectorResp = objectMapper.readValue(vectorRespJson, Map.class);
            timings.put("vector_ms", System.currentTimeMillis() - vectorStart);

            if (!Integer.valueOf(200).equals(vectorResp.get("code"))) {
                result.put("code", 500);
                result.put("msg", "AI Service                                                ? " + vectorRespJson);
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> vectorData = (Map<String, Object>) vectorResp.get("data");
            @SuppressWarnings("unchecked")
            List<Double> queryVec = (List<Double>) vectorData.get("vector");
            if (queryVec == null || queryVec.isEmpty()) {
                result.put("code", 500);
                result.put("msg", "AI Service did not return a valid document vector");
                return result;
            }

            List<Map<String, Object>> candidates = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();
            int deniedCount = 0;
            int belowThresholdCount = 0;
            int candidateCount = 0;
            boolean fallbackUsed = false;

            int fetchSize = Math.min(Math.max(topK * 4, topK + 5), Math.max(topK, editorSimilarityMaxCandidates));
            long knnStart = System.currentTimeMillis();
            try {
                SearchRequest knnReq = new SearchRequest.Builder()
                        .index(editorSimilarityMetaIndex)
                        .knn(k -> k.field("doc_vector")
                                .queryVector(queryVec)
                                .k(fetchSize)
                                .numCandidates(Math.max(fetchSize, editorSimilarityKnnNumCandidates))
                                .filter(f -> f.bool(b -> {
                                    appendLatestFilter(b, "is_latest");
                                    appendAclFilter(b, "acl_tokens", identity);
                                    return b;
                                })))
                        .size(Math.min(fetchSize, 50))
                        .source(s -> s.filter(f -> f.excludes(Arrays.asList("doc_vector"))))
                        .build();
                SearchResponse<Object> knnResp = esClient.search(knnReq, Object.class);
                timings.put("knn_ms", System.currentTimeMillis() - knnStart);
                candidateCount = knnResp.hits().hits().size();

                long permissionStart = System.currentTimeMillis();
                for (Hit<Object> hit : knnResp.hits().hits()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) hit.source();
                    if (src == null) {
                        continue;
                    }
                    String docId = stringValue(src.getOrDefault("doc_id", hit.id()));
                    String source = stringValue(src.getOrDefault("source", src.getOrDefault("file_name", "")));
                    if (isSameDoc(docId, source, excludeDocId, excludeSource)) {
                        continue;
                    }
                    double rawScore = hit.score() != null ? hit.score() : 0.0;
                    String title = firstNonBlank(src.get("title"), src.get("name"), source);
                    String snippet = buildDocMetaSnippet(src);
                    double vectorScore = calibrateEsVectorScore(rawScore);
                    if (vectorScore < editorSimilarityMinScore) {
                        belowThresholdCount++;
                        continue;
                    }
                    if (source.isEmpty() || !permissionGuard.canAccess(source, identity).isAllowed()) {
                        deniedCount++;
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", docId);
                    item.put("source", source);
                    item.put("title", title);
                    item.put("similarity", round4(vectorScore));
                    item.put("vectorScore", round4(vectorScore));
                    item.put("rawEsScore", round4(rawScore));
                    item.put("chunkCount", intValue(src.get("chunk_count")));
                    item.put("snippet", snippet);
                    item.put("_rerankText", buildRerankText(title, source, snippet));
                    candidates.add(item);
                }
                timings.put("permission_ms", System.currentTimeMillis() - permissionStart);
            } catch (Exception metaKnnError) {
                fallbackUsed = true;
                timings.put("meta_knn_error", abbreviate(metaKnnError.getMessage(), 240));
                long fallbackStart = System.currentTimeMillis();
                SearchResponse<Object> chunkResp = searchEditorSimilarChunks(queryVec, fetchSize, identity);
                timings.put("chunk_fallback_ms", System.currentTimeMillis() - fallbackStart);
                candidateCount = chunkResp.hits().hits().size();

                long permissionStart = System.currentTimeMillis();
                int[] counters = appendEditorChunkFallbackItems(
                        chunkResp, candidates, fetchSize, excludeDocId, excludeSource, identity);
                deniedCount += counters[0];
                belowThresholdCount += counters[1];
                timings.put("permission_ms", System.currentTimeMillis() - permissionStart);
            }
            long evidenceStart = System.currentTimeMillis();
            int evidenceCount = enrichEditorCandidatesWithEvidence(queryVec, candidates, identity);
            timings.put("evidence_ms", System.currentTimeMillis() - evidenceStart);
            timings.put("evidence_count", evidenceCount);

            long rerankStart = System.currentTimeMillis();
            items = rerankEditorCandidates(normalizedText, candidates, topK);
            timings.put("rerank_ms", System.currentTimeMillis() - rerankStart);
            timings.put("candidate_count", candidateCount);
            timings.put("accessible_candidate_count", candidates.size());
            timings.put("denied_count", deniedCount);
            timings.put("below_threshold_count", belowThresholdCount);
            timings.put("fallback_used", fallbackUsed);
            timings.put("meta_index", editorSimilarityMetaIndex);
            timings.put("chunk_fallback_index", editorSimilarityChunkFallbackIndex);

            data.put("items", items);
            data.put("total", items.size());
            data.put("appCode", appCode == null ? "" : appCode);
            data.put("costMs", System.currentTimeMillis() - startTime);
            data.put("queryChars", normalizedText.length());
            data.put("cacheHit", false);
            writeEditorSimilarityCache(cacheKey, data);

            result.put("code", 200);
            result.put("data", data);
        } catch (Exception e) {
            System.err.println(" ?[SimilarityService#findSimilarDocsForEditor] " + e.getMessage());
            result.put("code", 500);
            result.put("msg",
                    "                                                                                                                    ? "
                            + e.getMessage());
        }
        return result;
    }

    // ?
    // ?scroll ? // ?

    /**
     * ?ES search ?scroll ?limit ?content + metadata ?
     * ?vector ?024 ?
     *
     * @param index ES ll ?knowledge_base_*
     * @param limit ? * @return ?_id, content, metadata
     */
    private String normalizeEditorText(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int maxChars = Math.max(200, editorSimilarityMaxInputChars);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(normalized.length() - maxChars);
    }

    /**
     * ?Rerank Token ? * @param text ? * @param limit ?800 ?500 Token ? * @return
     */
    private String extractFocusedText(String text, int limit) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - limit);
    }

    private String editorSimilarityCacheKey(String appCode,
            JwtVerifier.UserIdentity identity,
            String text,
            int topK,
            String excludeDocId,
            String excludeSource) {
        String userId = identity == null ? "anonymous" : stringValue(identity.getUserId());
        String raw = stringValue(appCode) + "|" + userId + "|" + topK + "|"
                + stringValue(excludeDocId) + "|" + stringValue(excludeSource) + "|"
                + editorSimilarityEvidenceEnabled + "|" + editorSimilarityEvidenceFetchDocs + "|"
                + editorSimilarityEvidenceTopK + "|" + editorSimilarityEvidenceMaxChars + "|"
                + editorSimilarityRerankEvidenceMaxChars + "|"
                + editorSimilarityRerankWeight + "|" + editorSimilarityRerankWeightWithEvidence + "|"
                + editorSimilarityRerankWeightWithoutEvidence + "|" + editorSimilarityRerankMinScore + "|"
                + editorSimilarityVectorHighConfidenceThreshold + "|"
                + editorSimilarityVectorHighConfidenceFloorRatio + "|"
                + editorSimilarityConflictRerankThreshold + "|" + text;
        return "editor:similar:" + stringValue(appCode) + ":" + userId + ":" + sha256(raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEditorSimilarityCache(String key) {
        if (redisTemplate == null || key == null || key.isEmpty() || editorSimilarityCacheTtlSeconds <= 0) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            System.err.println("[EditorSimilarityCache] read failed: " + e.getMessage());
            return null;
        }
    }

    private void writeEditorSimilarityCache(String key, Map<String, Object> data) {
        if (redisTemplate == null || key == null || key.isEmpty()
                || data == null || editorSimilarityCacheTtlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(data),
                    editorSimilarityCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[EditorSimilarityCache] write failed: " + e.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((value == null ? "" : value).hashCode());
        }
    }

    private boolean isSameDoc(String docId, String source, String excludeDocId, String excludeSource) {
        return (!stringValue(excludeDocId).isEmpty() && stringValue(excludeDocId).equals(docId))
                || (!stringValue(excludeSource).isEmpty() && stringValue(excludeSource).equals(source));
    }

    private String similarityLabel(double score) {
        if (score >= 0.85) {
            return "high";
        }
        if (score >= 0.70) {
            return "near";
        }
        if (score >= 0.55) {
            return "related";
        }
        return "weak";
    }

    private String buildDocMetaSnippet(Map<String, Object> src) {
        String text = firstNonBlank(src.get("summary"), src.get("abstract"), src.get("content"),
                src.get("description"));
        if (text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("\\s+", " ").trim();
        int maxLen = 160;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private void appendLatestFilter(BoolQuery.Builder b, String field) {
        b.filter(ft -> ft.bool(boolQuery -> boolQuery
                .should(s -> s.term(t -> t.field(field).value(true)))
                .should(s -> s.bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field(field)))))
                .minimumShouldMatch("1")));
    }

    private void appendAclFilter(BoolQuery.Builder b,
            String field,
            JwtVerifier.UserIdentity identity) {
        if (identity != null && identity.isSuperAdmin()) {
            return;
        }
        Set<String> tokens = (identity != null && identity.getAclTokens() != null && !identity.getAclTokens().isEmpty())
                ? identity.getAclTokens()
                : UserContextHolder.getAclTokens();
        if (tokens == null || tokens.isEmpty()) {
            tokens = Collections.singleton("_PUBLIC");
        }
        List<FieldValue> values = new ArrayList<>();
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty()) {
                values.add(FieldValue.of(token.trim()));
            }
        }
        if (values.isEmpty()) {
            values.add(FieldValue.of("_PUBLIC"));
        }
        if ("acl_tokens".equals(field)) {
            b.filter(ft -> ft.bool(aclb -> aclb
                    .should(s -> s.terms(t -> t.field("acl_tokens").terms(tv -> tv.value(values))))
                    .should(s -> s.terms(t -> t.field("metadata.acl_tokens").terms(tv -> tv.value(values))))
                    .minimumShouldMatch("1")));
        } else {
            b.filter(ft -> ft.terms(t -> t.field(field).terms(tv -> tv.value(values))));
        }
    }

    private SearchResponse<Object> searchEditorSimilarChunks(List<Double> queryVec,
            int fetchSize,
            JwtVerifier.UserIdentity identity) throws Exception {
        int size = Math.min(Math.max(fetchSize * 3, 30), 80);
        return esClient.search(new SearchRequest.Builder()
                .index(editorSimilarityChunkFallbackIndex)
                .knn(k -> k.field("vector")
                        .queryVector(queryVec)
                        .k(size)
                        .numCandidates(Math.max(100, size * 4))
                        .filter(f -> f.bool(b -> {
                            appendLatestFilter(b, "metadata.is_latest");
                            appendAclFilter(b, "acl_tokens", identity);
                            return b;
                        })))
                .size(size)
                .source(s -> s.filter(f -> f.excludes(Arrays.asList("vector", "sparse_vector", "colloquial_vector"))))
                .build(), Object.class);
    }

    private int enrichEditorCandidatesWithEvidence(List<Double> queryVec,
            List<Map<String, Object>> candidates,
            JwtVerifier.UserIdentity identity) {
        if (!editorSimilarityEvidenceEnabled || queryVec == null || queryVec.isEmpty()
                || candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int limit = Math.min(candidates.size(), Math.max(0, editorSimilarityEvidenceFetchDocs));
        int enriched = 0;
        for (int i = 0; i < limit; i++) {
            Map<String, Object> item = candidates.get(i);
            String docId = stringValue(item.get("docId"));
            String source = stringValue(item.get("source"));
            String evidence = fetchBestChunkEvidence(queryVec, docId, source, identity);
            if (evidence.isEmpty()) {
                continue;
            }

            item.put("evidence", abbreviate(evidence, 300));
            item.put("evidenceUsed", true);
            item.put("_rerankText", buildRerankText(
                    stringValue(item.get("title")),
                    source,
                    stringValue(item.get("snippet")),
                    abbreviate(evidence, Math.max(300, editorSimilarityRerankEvidenceMaxChars))));
            enriched++;
        }
        return enriched;
    }

    private String fetchBestChunkEvidence(List<Double> queryVec,
            String docId,
            String source,
            JwtVerifier.UserIdentity identity) {
        if (stringValue(docId).isEmpty() && stringValue(source).isEmpty()) {
            return "";
        }
        int topK = Math.max(1, editorSimilarityEvidenceTopK);
        int maxChars = Math.max(200, editorSimilarityEvidenceMaxChars);
        try {
            SearchResponse<Object> resp = esClient.search(new SearchRequest.Builder()
                    .index(editorSimilarityChunkFallbackIndex)
                    .knn(k -> k.field("vector")
                            .queryVector(queryVec)
                            .k(topK)
                            .numCandidates(Math.max(20, topK * 10))
                            .filter(f -> f.bool(b -> {
                                appendLatestFilter(b, "metadata.is_latest");
                                appendAclFilter(b, "acl_tokens", identity);
                                appendDocIdentityFilter(b, docId, source);
                                return b;
                            })))
                    .size(topK)
                    .source(s -> s.filter(f -> f.excludes(Arrays.asList("vector", "sparse_vector", "colloquial_vector"))))
                    .build(), Object.class);

            StringBuilder sb = new StringBuilder();
            Set<String> seen = new LinkedHashSet<>();
            for (Hit<Object> hit : resp.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) {
                    continue;
                }
                Map<String, Object> metadata = nestedMap(src.get("metadata"));
                String text = firstNonBlank(src.get("content"), src.get("chunkText"), src.get("text"),
                        metadata.get("content"), metadata.get("text"));
                text = normalizeEvidenceText(text);
                if (text.isEmpty() || !seen.add(text)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                int remaining = maxChars - sb.length();
                if (remaining <= 0) {
                    break;
                }
                sb.append(text, 0, Math.min(text.length(), remaining));
                if (sb.length() >= maxChars) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[EditorSimilarityEvidence] fetch failed: " + e.getMessage());
            return "";
        }
    }

    private void appendDocIdentityFilter(BoolQuery.Builder b, String docId, String source) {
        String cleanDocId = stringValue(docId);
        String cleanSource = stringValue(source);
        if (cleanDocId.isEmpty() && cleanSource.isEmpty()) {
            return;
        }
        b.filter(ft -> ft.bool(match -> {
            if (!cleanDocId.isEmpty()) {
                match.should(s -> s.term(t -> t.field("metadata.doc_id").value(cleanDocId)));
                match.should(s -> s.term(t -> t.field("doc_id").value(cleanDocId)));
            }
            if (!cleanSource.isEmpty()) {
                match.should(s -> s.term(t -> t.field("metadata.source").value(cleanSource)));
                match.should(s -> s.term(t -> t.field("source").value(cleanSource)));
                match.should(s -> s.term(t -> t.field("file_name").value(cleanSource)));
            }
            return match.minimumShouldMatch("1");
        }));
    }

    private String normalizeEvidenceText(String text) {
        String value = stringValue(text).replaceAll("\\s+", " ").trim();
        return value;
    }

    private int[] appendEditorChunkFallbackItems(SearchResponse<Object> chunkResp,
            List<Map<String, Object>> items,
            int topK,
            String excludeDocId,
            String excludeSource,
            JwtVerifier.UserIdentity identity) {
        int deniedCount = 0;
        int belowThresholdCount = 0;
        Map<String, Map<String, Object>> bestBySource = new LinkedHashMap<>();

        for (Hit<Object> hit : chunkResp.hits().hits()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) hit.source();
            if (src == null) {
                continue;
            }
            Map<String, Object> metadata = nestedMap(src.get("metadata"));
            String source = firstNonBlank(metadata.get("source"), src.get("source"), src.get("file_name"));
            String docId = firstNonBlank(metadata.get("doc_id"), src.get("doc_id"), source, hit.id());
            if (isSameDoc(docId, source, excludeDocId, excludeSource)) {
                continue;
            }
            double rawScore = hit.score() != null ? hit.score() : 0.0;
            String title = firstNonBlank(metadata.get("title"), src.get("title"), src.get("name"), source);
            String snippet = buildDocMetaSnippet(src);
            double vectorScore = calibrateEsVectorScore(rawScore);
            if (vectorScore < editorSimilarityMinScore) {
                belowThresholdCount++;
                continue;
            }
            if (source.isEmpty() || !permissionGuard.canAccess(source, identity).isAllowed()) {
                deniedCount++;
                continue;
            }

            Map<String, Object> existing = bestBySource.get(source);
            double existingScore = existing != null && existing.get("_rawScore") instanceof Number
                    ? ((Number) existing.get("_rawScore")).doubleValue()
                    : -1.0;
            if (existing != null && existingScore >= vectorScore) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", docId);
            item.put("source", source);
            item.put("title", title);
            item.put("similarity", round4(vectorScore));
            item.put("vectorScore", round4(vectorScore));
            item.put("rawEsScore", round4(rawScore));
            item.put("chunkCount", 0);
            item.put("snippet", snippet);
            item.put("_rerankText", buildRerankText(title, source, snippet));
            item.put("_rawScore", vectorScore);
            bestBySource.put(source, item);
        }

        List<Map<String, Object>> ranked = new ArrayList<>(bestBySource.values());
        ranked.sort((a, b) -> Double.compare(
                ((Number) b.get("_rawScore")).doubleValue(),
                ((Number) a.get("_rawScore")).doubleValue()));
        for (Map<String, Object> item : ranked) {
            if (items.size() >= topK) {
                break;
            }
            item.remove("_rawScore");
            items.add(item);
        }
        return new int[] { deniedCount, belowThresholdCount };
    }

    private List<Map<String, Object>> rerankEditorCandidates(String queryText,
            List<Map<String, Object>> candidates,
            int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> documents = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            documents.add(stringValue(candidate.get("_rerankText")));
        }

        RerankResult rerankResult = editorSimilarityRerankEnabled ? callAiRerank(queryText, documents) : null;
        List<Double> rerankScores = rerankResult != null ? rerankResult.scores : null;
        List<Double> rawRerankScores = rerankResult != null ? rerankResult.rawScores : null;
        boolean rerankUsed = rerankScores != null;
        double threshold = rerankUsed ? clamp01(editorSimilarityRerankMinScore) : editorSimilarityMinScore;
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>(candidates.get(i));
            boolean evidenceUsed = Boolean.TRUE.equals(item.get("evidenceUsed"));
            double vectorScore = item.get("vectorScore") instanceof Number
                    ? ((Number) item.get("vectorScore")).doubleValue()
                    : 0.0;
            double rerankScore = (rerankUsed && i < rerankScores.size())
                    ? clamp01(rerankScores.get(i))
                    : vectorScore;
            Double rawRerankScore = (rawRerankScores != null && i < rawRerankScores.size())
                    ? rawRerankScores.get(i)
                    : null;
            double rerankWeight = effectiveRerankWeight(evidenceUsed);
            double businessScore = !rerankUsed
                    ? vectorScore
                    : clamp01(rerankScore * rerankWeight + vectorScore * (1.0 - rerankWeight));
            boolean scoreConflict = rerankUsed
                    && vectorScore >= clamp01(editorSimilarityVectorHighConfidenceThreshold)
                    && rerankScore <= clamp01(editorSimilarityConflictRerankThreshold);
            double scoreFloor = scoreConflict
                    ? vectorScore * clamp01(editorSimilarityVectorHighConfidenceFloorRatio)
                    : 0.0;
            boolean vectorHighConfidenceProtected = scoreConflict && businessScore < scoreFloor;
            if (vectorHighConfidenceProtected) {
                businessScore = clamp01(scoreFloor);
            }
            if (businessScore < threshold) {
                continue;
            }

            item.put("similarity", round4(businessScore));
            item.put("rerankScore", round4(rerankScore));
            if (rawRerankScore != null) {
                item.put("rawRerankScore", round4(rawRerankScore));
            }
            item.put("label", similarityLabel(businessScore));
            item.put("reason", buildRerankReason(rerankResult, rerankScore, rawRerankScore,
                    vectorScore, rerankWeight, threshold, evidenceUsed, scoreConflict,
                    vectorHighConfidenceProtected, scoreFloor));
            item.put("_rankScore", businessScore);
            item.remove("_rerankText");
            item.remove("_rawScore");
            item.remove("evidenceUsed");
            accepted.add(item);
        }

        accepted.sort((a, b) -> Double.compare(
                ((Number) b.get("_rankScore")).doubleValue(),
                ((Number) a.get("_rankScore")).doubleValue()));
        List<Map<String, Object>> limited = new ArrayList<>();
        for (Map<String, Object> item : accepted) {
            if (limited.size() >= topK) {
                break;
            }
            item.remove("_rankScore");
            limited.add(item);
        }
        return limited;
    }

    @SuppressWarnings("unchecked")
    private RerankResult callAiRerank(String queryText, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", queryText);
            payload.put("documents", documents);
            String url = normalizeBaseUrl(rerankHost) + "/api/ai/rerank/document-similarity";
            String respJson = restTemplate.postForObject(url, payload, String.class);
            Map<String, Object> resp = objectMapper.readValue(respJson, Map.class);
            if (!Integer.valueOf(200).equals(resp.get("code"))) {
                return null;
            }
            Map<String, Object> body = (Map<String, Object>) resp.get("data");
            if (body == null || !(body.get("scores") instanceof List)) {
                return null;
            }
            List<Object> scoreValues = (List<Object>) body.get("scores");
            List<Double> scores = toDoubleList(scoreValues);
            if (scores.size() != documents.size()) {
                return null;
            }

            List<Double> rawScores = body.get("rawScores") instanceof List
                    ? toDoubleList((List<Object>) body.get("rawScores"))
                    : Collections.emptyList();
            String scoreType = stringValue(body.get("scoreType"));
            String rawScoreType = stringValue(body.get("rawScoreType"));

            if (!"sigmoid_probability".equals(scoreType)) {
                rawScores = new ArrayList<>(scores);
                List<Double> normalizedScores = new ArrayList<>();
                for (Double score : scores) {
                    normalizedScores.add(sigmoid(score));
                }
                scores = normalizedScores;
                scoreType = "java_sigmoid_probability";
                rawScoreType = rawScoreType.isEmpty() ? "legacy_reranker_score" : rawScoreType;
            }
            if (rawScores.size() != documents.size()) {
                rawScores = Collections.emptyList();
            }
            return new RerankResult(scores, rawScores, scoreType, rawScoreType);
        } catch (Exception e) {
            System.err.println("[EditorSimilarityRerank] fallback to vector score: " + e.getMessage());
            return null;
        }
    }

    private List<Double> toDoubleList(List<Object> values) {
        List<Double> scores = new ArrayList<>();
        if (values == null) {
            return scores;
        }
        for (Object score : values) {
            if (score instanceof Number) {
                scores.add(((Number) score).doubleValue());
            }
        }
        return scores;
    }

    private Map<String, Object> buildRerankReason(RerankResult rerankResult,
            double rerankScore,
            Double rawRerankScore,
            double vectorScore,
            double rerankWeight,
            double threshold,
            boolean evidenceUsed,
            boolean scoreConflict,
            boolean vectorHighConfidenceProtected,
            double scoreFloor) {
        boolean rerankUsed = rerankResult != null;
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("rerankUsed", rerankUsed);
        reason.put("rerankScore", round4(rerankScore));
        if (rawRerankScore != null) {
            reason.put("rawRerankScore", round4(rawRerankScore));
        }
        reason.put("vectorScore", round4(vectorScore));
        reason.put("threshold", round4(threshold));
        reason.put("evidenceUsed", evidenceUsed);
        reason.put("effectiveRerankWeight", round4(rerankWeight));
        reason.put("scoreConflict", scoreConflict);
        reason.put("vectorHighConfidenceProtected", vectorHighConfidenceProtected);
        if (scoreConflict) {
            reason.put("scoreFloor", round4(scoreFloor));
        }
        if (rerankUsed) {
            reason.put("scoreType", rerankResult.scoreType);
            reason.put("rawScoreType", rerankResult.rawScoreType);
            reason.put("rerankWeight", round4(rerankWeight));
        }
        reason.put("summary", !rerankUsed
                ? "Reranker unavailable; vector score fallback"
                : (scoreConflict
                        ? "Vector score is high but reranker disagreed; vector protection evaluated"
                        : "AI reranker judged document-level relevance"));
        return reason;
    }

    private double effectiveRerankWeight(boolean evidenceUsed) {
        if (evidenceUsed) {
            return clamp01(editorSimilarityRerankWeightWithEvidence);
        }
        return clamp01(editorSimilarityRerankWeightWithoutEvidence);
    }

    private double sigmoid(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        if (score >= 0.0) {
            double z = Math.exp(-score);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(score);
        return z / (1.0 + z);
    }

    private static class RerankResult {
        private final List<Double> scores;
        private final List<Double> rawScores;
        private final String scoreType;
        private final String rawScoreType;

        private RerankResult(List<Double> scores, List<Double> rawScores, String scoreType, String rawScoreType) {
            this.scores = scores;
            this.rawScores = rawScores;
            this.scoreType = scoreType;
            this.rawScoreType = rawScoreType;
        }
    }

    private double calibrateEsVectorScore(double rawScore) {
        if (rawScore <= 1.0 && rawScore >= 0.0) {
            return clamp01(rawScore * 2.0 - 1.0);
        }
        return clamp01(rawScore);
    }

    private String buildRerankText(String title, String source, String snippet) {
        return buildRerankText(title, source, snippet, "");
    }

    private String buildRerankText(String title, String source, String snippet, String evidence) {
        StringBuilder sb = new StringBuilder();
        if (!stringValue(title).isEmpty()) {
            sb.append("title: ").append(title).append('\n');
        }
        if (!stringValue(source).isEmpty()) {
            sb.append("source: ").append(source).append('\n');
        }
        if (!stringValue(snippet).isEmpty()) {
            sb.append("summary: ").append(snippet).append('\n');
        }
        if (!stringValue(evidence).isEmpty()) {
            sb.append("matched_content:\n").append(evidence);
        }
        return sb.toString().trim();
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private String abbreviate(String text, int maxLen) {
        String value = stringValue(text);
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private List<Map<String, Object>> scrollEs(String index, int limit) throws Exception {
        String indexPattern = (index != null && !index.trim().isEmpty()) ? index : "knowledge_base_*";
        List<Map<String, Object>> docs = new ArrayList<>();

        int pageSize = Math.min(limit, 500); // ?500 ? int fetched = 0;
        int fetched = 0;

        while (fetched < limit) {
            int size = Math.min(pageSize, limit - fetched);
            final int currentFrom = fetched;

            SearchRequest req = new SearchRequest.Builder()
                    .index(indexPattern)
                    .from(currentFrom)
                    .size(size)
                    // ?vector ?98% ? .source(s -> s.filter(f ->
                    // f.includes(java.util.Arrays.asList("content", "metadata"))))
                    .build();

            SearchResponse<Object> resp = esClient.search(req, Object.class);
            List<Hit<Object>> hits = resp.hits().hits();
            if (hits.isEmpty())
                break;

            for (Hit<Object> hit : hits) {
                if (hit.source() instanceof Map) {
                    Map<String, Object> src = (Map<String, Object>) hit.source();
                    src.put("_id", hit.id());
                    docs.add(src);
                }
            }
            fetched += hits.size();
            if (hits.size() < size)
                break; // ?
        }
        return docs;
    }
}
