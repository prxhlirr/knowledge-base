package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.PermissionGuard;
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
                    .index("kb_doc_meta")
                    .knn(k -> k.field("doc_vector")
                            .queryVector(finalQueryVec)
                            .k(kFetch)
                            .numCandidates(50))
                    .size(kFetch)
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

            int fetchSize = Math.max(topK * 4, topK + 5);
            long knnStart = System.currentTimeMillis();
            try {
                SearchRequest knnReq = new SearchRequest.Builder()
                        .index("kb_doc_meta")
                        .knn(k -> k.field("doc_vector")
                                .queryVector(queryVec)
                                .k(Math.min(fetchSize, 50))
                                .numCandidates(Math.max(50, fetchSize * 5)))
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
                SearchResponse<Object> chunkResp = searchEditorSimilarChunks(queryVec, fetchSize);
                timings.put("chunk_fallback_ms", System.currentTimeMillis() - fallbackStart);
                candidateCount = chunkResp.hits().hits().size();

                long permissionStart = System.currentTimeMillis();
                int[] counters = appendEditorChunkFallbackItems(
                        chunkResp, candidates, fetchSize, excludeDocId, excludeSource, identity);
                deniedCount += counters[0];
                belowThresholdCount += counters[1];
                timings.put("permission_ms", System.currentTimeMillis() - permissionStart);
            }
            long rerankStart = System.currentTimeMillis();
            items = rerankEditorCandidates(normalizedText, candidates, topK);
            timings.put("rerank_ms", System.currentTimeMillis() - rerankStart);
            timings.put("candidate_count", candidateCount);
            timings.put("accessible_candidate_count", candidates.size());
            timings.put("denied_count", deniedCount);
            timings.put("below_threshold_count", belowThresholdCount);
            timings.put("fallback_used", fallbackUsed);

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
                + stringValue(excludeDocId) + "|" + stringValue(excludeSource) + "|" + text;
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

    private SearchResponse<Object> searchEditorSimilarChunks(List<Double> queryVec, int fetchSize) throws Exception {
        int size = Math.min(Math.max(fetchSize * 3, 30), 80);
        return esClient.search(new SearchRequest.Builder()
                .index("kb_document")
                .knn(k -> k.field("vector")
                        .queryVector(queryVec)
                        .k(size)
                        .numCandidates(Math.max(100, size * 4))
                        .filter(f -> f.bool(b -> b
                                .filter(ft -> ft.bool(boolQuery -> boolQuery
                                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                        .should(s -> s.bool(bNot -> bNot
                                                .mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest"))))))))))
                .size(size)
                .source(s -> s.filter(f -> f.excludes(Arrays.asList("vector", "sparse_vector", "colloquial_vector"))))
                .build(), Object.class);
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

        List<Double> rerankScores = callAiRerank(queryText, documents);
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>(candidates.get(i));
            double vectorScore = item.get("vectorScore") instanceof Number
                    ? ((Number) item.get("vectorScore")).doubleValue()
                    : 0.0;
            double rerankScore = (rerankScores != null && i < rerankScores.size())
                    ? normalizeRerankScore(rerankScores.get(i))
                    : vectorScore;
            double businessScore = rerankScores == null
                    ? vectorScore
                    : clamp01(rerankScore * 0.85 + vectorScore * 0.15);
            if (businessScore < editorSimilarityMinScore) {
                continue;
            }

            item.put("similarity", round4(businessScore));
            item.put("rerankScore", round4(rerankScore));
            item.put("label", similarityLabel(businessScore));
            item.put("reason", buildRerankReason(rerankScores != null, rerankScore, vectorScore));
            item.put("_rankScore", businessScore);
            item.remove("_rerankText");
            item.remove("_rawScore");
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
    private List<Double> callAiRerank(String queryText, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
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
            List<Object> rawScores = (List<Object>) body.get("scores");
            List<Double> scores = new ArrayList<>();
            for (Object score : rawScores) {
                if (score instanceof Number) {
                    scores.add(((Number) score).doubleValue());
                }
            }
            return scores.size() == documents.size() ? scores : null;
        } catch (Exception e) {
            System.err.println("[EditorSimilarityRerank] fallback to vector score: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRerankReason(boolean rerankUsed, double rerankScore, double vectorScore) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("rerankUsed", rerankUsed);
        reason.put("rerankScore", round4(rerankScore));
        reason.put("vectorScore", round4(vectorScore));
        reason.put("summary", rerankUsed ? "AI reranker judged document-level relevance"
                : "Reranker unavailable; vector score fallback");
        return reason;
    }

    private double calibrateEsVectorScore(double rawScore) {
        if (rawScore <= 1.0 && rawScore >= 0.0) {
            return clamp01(rawScore * 2.0 - 1.0);
        }
        return clamp01(rawScore);
    }

    private double normalizeRerankScore(double score) {
        if (score >= 0.0 && score <= 1.0) {
            return score;
        }
        return 1.0 / (1.0 + Math.exp(-score));
    }

    private String buildRerankText(String title, String source, String snippet) {
        StringBuilder sb = new StringBuilder();
        if (!stringValue(title).isEmpty()) {
            sb.append("title: ").append(title).append('\n');
        }
        if (!stringValue(source).isEmpty()) {
            sb.append("source: ").append(source).append('\n');
        }
        if (!stringValue(snippet).isEmpty()) {
            sb.append("content: ").append(snippet);
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
