package com.boyang.search.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 引擎调用防腐层 (Gateway)
 * 封装发往 Python 端的所有 AI 能力访问，提供稳定的接口重试、降级和统一的超时配置。
 */
@Component
public class AiEngineGateway {

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiHost;

    private RestTemplate defaultRestTemplate; // 3s/15s
    private RestTemplate fastRestTemplate; // 3s/5s （Rewrite/HyDE 合并专用）
    private RestTemplate llmRestTemplate; // 5s/60s （LLM Rerank 长时调用专用）
    private RestTemplate ltrRestTemplate; // 2s/5s （LTR 预测专用）
    private RestTemplate colbertRestTemplate; // 2s/5s（ColBERT 重排专用，超时快速降级）
    // [Bug 修复] 从局部变量提升为字段：原 fetchSparseVector() 每次调用都 new RestTemplate，
    // 导致每次查询都有 TCP 三次握手开销（20-50ms）+ JVM 分配压力。
    // 与其他4个 RestTemplate 统一在 init() 中初始化，保持一致性。
    private RestTemplate sparseRestTemplate; // 1.5s/1.5s（稀疏向量专用，快速失败降级）

    private final ObjectMapper mapper = new ObjectMapper();

    // Health check cache
    private volatile String cachedAcceleration = null;
    private volatile long lastHealthCheckMs = 0L;
    private static final long HEALTH_CACHE_TTL_MS = 60_000L;

    // ColBERT Local Cache
    private final java.util.concurrent.ConcurrentHashMap<String, List<Double>> colbertCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> colbertCacheTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COLBERT_CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    @PostConstruct
    public void init() {
        // [操作日志] 创建一个 RestTemplate 拦截器，自动将 MDC 中的 traceId 注入到向上游（AI服务）请求的 Header 中
        ClientHttpRequestInterceptor traceIdInterceptor = new ClientHttpRequestInterceptor() {
            @Override
            public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                    throws IOException {
                String traceId = MDC.get("traceId");
                if (traceId != null && !traceId.isEmpty()) {
                    request.getHeaders().add("X-Trace-Id", traceId);
                }
                return execution.execute(request, body);
            }
        };

        SimpleClientHttpRequestFactory defaultFactory = new SimpleClientHttpRequestFactory();
        defaultFactory.setConnectTimeout(3000);
        defaultFactory.setReadTimeout(15000);
        this.defaultRestTemplate = new RestTemplate(defaultFactory);
        this.defaultRestTemplate.getInterceptors().add(traceIdInterceptor);

        SimpleClientHttpRequestFactory fastFactory = new SimpleClientHttpRequestFactory();
        fastFactory.setConnectTimeout(3000);
        fastFactory.setReadTimeout(5000);
        this.fastRestTemplate = new RestTemplate(fastFactory);
        this.fastRestTemplate.getInterceptors().add(traceIdInterceptor);

        SimpleClientHttpRequestFactory llmFactory = new SimpleClientHttpRequestFactory();
        llmFactory.setConnectTimeout(5000);
        llmFactory.setReadTimeout(60000);
        this.llmRestTemplate = new RestTemplate(llmFactory);
        this.llmRestTemplate.getInterceptors().add(traceIdInterceptor);

        SimpleClientHttpRequestFactory ltrFactory = new SimpleClientHttpRequestFactory();
        ltrFactory.setConnectTimeout(2000);
        ltrFactory.setReadTimeout(5000);
        this.ltrRestTemplate = new RestTemplate(ltrFactory);
        this.ltrRestTemplate.getInterceptors().add(traceIdInterceptor);

        SimpleClientHttpRequestFactory colbertFactory = new SimpleClientHttpRequestFactory();
        colbertFactory.setConnectTimeout(2000);
        colbertFactory.setReadTimeout(5000);
        this.colbertRestTemplate = new RestTemplate(colbertFactory);
        this.colbertRestTemplate.getInterceptors().add(traceIdInterceptor);

        SimpleClientHttpRequestFactory sparseFactory = new SimpleClientHttpRequestFactory();
        sparseFactory.setConnectTimeout(1500);
        sparseFactory.setReadTimeout(1500);
        this.sparseRestTemplate = new RestTemplate(sparseFactory);
        this.sparseRestTemplate.getInterceptors().add(traceIdInterceptor);

        System.out.println("[Gateway] AI Engine specialized RestTemplates initialized with traceId interceptor.");
    }

    private String getBaseUrl() {
        return aiHost.replace("localhost", "127.0.0.1");
    }

    /**
     * 获取 GPU 加速状态（带有 60s 本地缓存）
     */
    public String getCachedAcceleration() {
        long now = System.currentTimeMillis();
        if (cachedAcceleration == null || (now - lastHealthCheckMs) > HEALTH_CACHE_TTL_MS) {
            try {
                String statusUrl = getBaseUrl() + "/api/ai/health";
                Map<String, Object> health = defaultRestTemplate.getForObject(statusUrl, Map.class);
                cachedAcceleration = (health != null) ? (String) health.getOrDefault("acceleration", "CPU") : "CPU";
                lastHealthCheckMs = now;
            } catch (Exception e) {
                cachedAcceleration = "CPU"; // 退化为 CPU
            }
        }
        return cachedAcceleration;
    }

    /**
     * 调用 Python NLP 服务进行文本归一化（拼音和特殊处理）
     */
    public Map<String, String> normalizeQuery(String text) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", text);
            String url = getBaseUrl() + "/api/ai/nlp/normalize";
            String respJson = colbertRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                return (Map<String, String>) respMap.get("data");
            }
        } catch (Exception e) {
            System.err.println("[Gateway-NLP] Normalize fallback: " + e.getMessage());
        }
        Map<String, String> fallback = new HashMap<>();
        fallback.put("normalized", text);
        fallback.put("pinyin", "");
        return fallback;
    }

    /**
     * 执行合并改写和 HyDE (合并 5s 超时)
     * 向后兼容版本：queryType=null，走通用 HyDE 路径
     */
    public Map<String, Object> fetchRewriteAndHyde(String query, boolean needVector) {
        return fetchRewriteAndHyde(query, needVector, null);
    }

    /**
     * 执行合并改写和 HyDE (合并 5s 超时)
     *
     * 业务功能：调用 Python /api/ai/intent/rewrite_and_hyde，同时完成「意图改写」和「HyDE 假设文档」生成。
     * 关键参数：queryType 填入时 Python 侧切换专属 Prompt（如"公示"→生成人员名单格式假设文档），
     * 不填时走通用 HyDE 路径。
     *
     * @param query      归一化后的查询文本
     * @param needVector 是否需要返回假设文档向量（false 时 Python 跳过 BGE 编码）
     * @param queryType  文档子类型；null 时走通用逻辑；"公示"/"法规"/"通知" 触发专属 Prompt
     * @return { rewritten_query, vector }；失败时返回 { query, null }
     */
    public Map<String, Object> fetchRewriteAndHyde(String query, boolean needVector, String queryType) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("rewritten_query", query);
        fallback.put("vector", null);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("need_vector", needVector);
            // 文档类型子类型：非 null 时 Python 侧切换专属 HyDE Prompt
            if (queryType != null && !queryType.isEmpty()) {
                payload.put("query_type", queryType);
            }
            String url = getBaseUrl() + "/api/ai/intent/rewrite_and_hyde";

            String respJson = fastRestTemplate.postForObject(url, payload, String.class);
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) respMap.get("data");
                if (data != null) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("rewritten_query", data.getOrDefault("rewritten_query", query));

                    Object vecObj = data.get("vector");
                    if (vecObj instanceof List) {
                        List<?> rawVec = (List<?>) vecObj;
                        List<Double> vec = new ArrayList<>(rawVec.size());
                        for (Object v : rawVec) {
                            if (v instanceof Number)
                                vec.add(((Number) v).doubleValue());
                        }
                        result.put("vector", vec.isEmpty() ? null : vec);
                    } else {
                        result.put("vector", null);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-HyDE] Rewrite & HyDE failure: " + e.getMessage());
        }
        return fallback;
    }

    /**
     * 单独获取查询的向量 BGE-M3 (5s 超时)
     */
    public List<Double> fetchQueryVector(String text) {
        int estimatedTokens = 0;
        for (char c : text.toCharArray()) {
            estimatedTokens += (c >= '\u4e00' && c <= '\u9fa5') ? 2 : 1;
        }
        estimatedTokens = estimatedTokens / 2;
        if (estimatedTokens > 5500) {
            System.err.printf("[Gateway-Vector] WARN: text estimatedTokens=%d exceeds limit %n", estimatedTokens);
        }
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", text);
            String url = getBaseUrl() + "/api/ai/vector/query";
            String respJson = fastRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("vector") != null) {
                    List<?> rawVector = (List<?>) dataMap.get("vector");
                    List<Double> result = new ArrayList<>(rawVector.size());
                    for (Object num : rawVector) {
                        if (num instanceof Number)
                            result.add(((Number) num).doubleValue());
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-Vector] " + e.getMessage());
        }
        return null;
    }

    /**
     * 单独获取 HyDE 扩展向量
     */
    public List<Double> fetchHydeVector(String query) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", query);
            String url = getBaseUrl() + "/api/ai/vector/hyde";
            String respJson = defaultRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("vector") != null) {
                    List<?> rawVector = (List<?>) dataMap.get("vector");
                    List<Double> result = new ArrayList<>(rawVector.size());
                    for (Object num : rawVector) {
                        if (num instanceof Number)
                            result.add(((Number) num).doubleValue());
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-Vector] HyDE direct fetch failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取查询的稀疏向量（SPLADE 风格的词权重 Map）。
     *
     * 业务功能：调用 Python AI 服务的 /api/ai/vector/sparse 接口，
     * 返回 token → weight 的稀疏权重字典，用于 ES rank_features 查询。
     * 注意：接口当前为近似实现（L2 norm 权重），稳定性需测试验证。
     * 使用 1.5s 短超时 + 降级返回 null，确保接口失败时搜索主链路不受影响。
     *
     * @param text 查询文本（经过归一化处理后的版本）
     * @return token→权重的稀疏向量 Map；接口超时/失败时返回 null（调用方需判 null）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Double> fetchSparseVector(String text) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", text);
            String url = getBaseUrl() + "/api/ai/vector/sparse";
            // [Bug 修复] 使用字段级 sparseRestTemplate，不再每次创建新对象
            String respJson = sparseRestTemplate.postForObject(url, payload, String.class);
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("sparse_vector") != null) {
                    Map<String, Object> rawSparse = (Map<String, Object>) dataMap.get("sparse_vector");
                    Map<String, Double> result = new java.util.LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : rawSparse.entrySet()) {
                        if (entry.getValue() instanceof Number) {
                            result.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
                        }
                    }
                    System.out.printf("[Gateway-Sparse] Query sparse vector: terms=%d%n", result.size());
                    return result.isEmpty() ? null : result;
                }
            }
        } catch (Exception e) {
            // 接口失败（超时/不可用）时静默降级，sparse 通道贡献为零，不影响主链路
            System.err.println("[Gateway-Sparse] fetch failed (degrading to null): " + e.getMessage());
        }
        return null;
    }

    /**
     * 请求 ColBERT 后期交互算分
     */
    public List<Double> fetchColbertScores(String query, List<String> documents) {
        if (query == null || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            // [Cache Check]
            String cacheKey = query + "|" + documents.hashCode();
            Long cacheTime = colbertCacheTimes.get(cacheKey);
            if (cacheTime != null && (System.currentTimeMillis() - cacheTime < COLBERT_CACHE_TTL_MS)) {
                List<Double> cachedScores = colbertCache.get(cacheKey);
                if (cachedScores != null) {
                    System.out.println("[Gateway-ColBERT] Cache hit for query: " + query);
                    return cachedScores;
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("documents", documents);
            String url = getBaseUrl() + "/api/ai/colbert/score";
            String respJson = defaultRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("scores") != null) {
                    List<?> rawScores = (List<?>) dataMap.get("scores");
                    List<Double> result = new ArrayList<>(rawScores.size());
                    for (Object s : rawScores) {
                        result.add(s instanceof Number ? ((Number) s).doubleValue() : 0.0);
                    }
                    
                    // [Cache Store]
                    colbertCache.put(cacheKey, result);
                    colbertCacheTimes.put(cacheKey, System.currentTimeMillis());
                    // Cleanup old entries randomly to prevent memory leak (simple heuristic)
                    if (colbertCache.size() > 5000) {
                        colbertCache.clear();
                        colbertCacheTimes.clear();
                    }
                    
                    return result;
                }
            }
        } catch (org.springframework.web.client.ResourceAccessException e) {
            System.out.println("[Gateway-ColBERT] score timeout/unavailable, degrading to RRF.");
        } catch (Exception e) {
            System.err.println("[Gateway-ColBERT] score fetch failed, degrading to RRF: " + e.getMessage());
        }
        return null;
    }

    /**
     * 请求 LLM Reranker (Qwen2.5 等，使用 60s 专属 RestTemplate)
     */
    public List<Double> fetchLlmRerankScores(String query, List<String> documents) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("documents", documents);
            String url = getBaseUrl() + "/api/ai/llm/rerank";
            String respJson = llmRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && "success".equals(respMap.get("msg"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("scores") != null) {
                    List<?> rawScores = (List<?>) dataMap.get("scores");
                    List<Double> result = new ArrayList<>(rawScores.size());
                    for (Object s : rawScores) {
                        result.add(s instanceof Number ? ((Number) s).doubleValue() : 0.0);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-LLM] rerank failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 请求 LTR rank (XGBoost/LightGBM 等，使用 5s 专属 RestTemplate)
     */
    public List<Double> fetchLtrScores(List<Map<String, Object>> features) {
        if (features == null || features.isEmpty())
            return new ArrayList<>();
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("features", features);
            String url = getBaseUrl() + "/api/ai/ltr/rank";
            String respJson = ltrRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) respMap.get("data");
                if (data != null && data.get("scores") != null) {
                    List<?> rawScores = (List<?>) data.get("scores");
                    List<Double> result = new ArrayList<>(rawScores.size());
                    for (Object s : rawScores) {
                        result.add(s instanceof Number ? ((Number) s).doubleValue() : 0.0);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-LTR] score fetch failed: " + e.getMessage());
        }

        // 容灾处理
        List<Double> fallbacks = new ArrayList<>();
        for (Map<String, Object> feat : features) {
            double seqScore = feat.get("normalized_es_score") instanceof Number
                    ? ((Number) feat.get("normalized_es_score")).doubleValue()
                    : 0.0;

            Object rrfObj = feat.get("rrf_score");
            if (rrfObj instanceof Number && ((Number) rrfObj).doubleValue() > 0) {
                seqScore = Math.max(seqScore, ((Number) rrfObj).doubleValue() * 10.0);
            }
            if (feat.get("raw_es_score") instanceof Number
                    && ((Number) feat.get("raw_es_score")).doubleValue() <= 1.0) {
                seqScore = 0.0001;
            }
            fallbacks.add(seqScore);
        }
        return fallbacks;
    }

    /**
     * 单次 HTTP 同时获取 dense + sparse 双模向量（合并接口，消除 BGE-M3 重复推理）。
     *
     * 业务功能：调用 Python /api/ai/vector/dual，一次 forward pass 同时产出：
     * - dense_vector：1024维稠密向量，供 VectorFetchStep → KNN 检索使用
     * - sparse_vector：token→weight 稀疏权重字典，供 EsRecallStep → rank_features 使用
     *
     * 设计原因：
     * 原链路：VectorFetchStep 调 /vector/query（600ms）+ EsRecallStep 调
     * /vector/sparse（600ms）
     * = 串行两次 HTTP + 两次 ONNX 推理 ≈ 1200ms。
     * 优化后：单次 /vector/dual 调用，Python 端复用同一缓存 ≈ 600ms，节省 ~500ms。
     *
     * 返回值约定：
     * Map key "dense" → List<Double> （可能为 null，若 Python 编码失败）
     * Map key "sparse" → Map<String, Double> （可能为 null 或空，降级为不走 sparse 通道）
     * 整体返回 null 时：调用方应降级到 fetchQueryVector + fetchSparseVector 原双路调用。
     *
     * @param text 归一化后的查询文本
     * @return 含 dense 和 sparse 的 Map；失败时返回 null
     */
    /**
     * Non-streaming LLM call used by structured QA planning steps.
     * Returns only the model-visible content; callers own JSON/schema validation.
     */
    @SuppressWarnings("unchecked")
    public String fetchChatCompletion(List<Map<String, String>> messages,
                                      String modelKey,
                                      double temperature,
                                      int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("messages", messages);
            payload.put("model_key", modelKey == null || modelKey.trim().isEmpty() ? "QA_LLM_MODEL" : modelKey);
            payload.put("temperature", temperature);
            payload.put("max_tokens", maxTokens);
            String url = getBaseUrl() + "/api/ai/llm/chat";
            String respJson = llmRestTemplate.postForObject(url, payload, String.class);
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap == null || !Integer.valueOf(200).equals(respMap.get("code"))) {
                System.err.println("[Gateway-Chat] non-success: " + (respMap == null ? "null" : respMap.get("msg")));
                return "";
            }
            Object data = respMap.get("data");
            if (data instanceof Map) {
                Object content = ((Map<String, Object>) data).get("content");
                return content == null ? "" : content.toString().trim();
            }
        } catch (Exception e) {
            System.err.println("[Gateway-Chat] fetchChatCompletion failed: " + e.getMessage());
        }
        return "";
    }

    public LlmStreamResponse openChatStream(List<Map<String, String>> messages,
                                            String modelKey,
                                            double temperature,
                                            int maxTokens,
                                            int connectTimeoutMs,
                                            int readTimeoutMs) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messages", messages);
        payload.put("model_key", modelKey == null || modelKey.trim().isEmpty() ? "QA_LLM_MODEL" : modelKey);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);

        String url = getBaseUrl() + "/api/ai/llm/chat_stream";
        long startMs = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "text/event-stream");
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isEmpty()) {
            conn.setRequestProperty("X-Trace-Id", traceId);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);

        byte[] bodyBytes = mapper.writeValueAsBytes(payload);
        conn.getOutputStream().write(bodyBytes);
        conn.getOutputStream().flush();

        int statusCode = conn.getResponseCode();
        long connectMs = System.currentTimeMillis() - startMs;
        if (statusCode < 200 || statusCode >= 300) {
            String errorBody = readBody(conn.getErrorStream());
            conn.disconnect();
            throw new LlmStreamHttpException(statusCode, connectMs, bodyBytes.length, url, errorBody);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        return new LlmStreamResponse(conn, reader, connectMs, bodyBytes.length);
    }

    private String readBody(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static class LlmStreamResponse implements AutoCloseable {
        private final HttpURLConnection connection;
        private final BufferedReader reader;
        private final long connectMs;
        private final int requestBytes;

        LlmStreamResponse(HttpURLConnection connection,
                          BufferedReader reader,
                          long connectMs,
                          int requestBytes) {
            this.connection = connection;
            this.reader = reader;
            this.connectMs = connectMs;
            this.requestBytes = requestBytes;
        }

        public BufferedReader getReader() {
            return reader;
        }

        public long getConnectMs() {
            return connectMs;
        }

        public int getRequestBytes() {
            return requestBytes;
        }

        @Override
        public void close() throws IOException {
            try {
                reader.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    public static class LlmStreamHttpException extends IOException {
        private final int statusCode;
        private final long connectMs;
        private final int requestBytes;
        private final String url;
        private final String errorBody;

        LlmStreamHttpException(int statusCode,
                               long connectMs,
                               int requestBytes,
                               String url,
                               String errorBody) {
            super("AI service call failed(" + statusCode + "): "
                    + (errorBody == null || errorBody.isEmpty() ? url : errorBody));
            this.statusCode = statusCode;
            this.connectMs = connectMs;
            this.requestBytes = requestBytes;
            this.url = url;
            this.errorBody = errorBody == null ? "" : errorBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public long getConnectMs() {
            return connectMs;
        }

        public int getRequestBytes() {
            return requestBytes;
        }

        public String getUrl() {
            return url;
        }

        public String getErrorBody() {
            return errorBody;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchDualVector(String text) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", text);
            String url = getBaseUrl() + "/api/ai/vector/dual";
            String respJson = fastRestTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap == null || !Integer.valueOf(200).equals(respMap.get("code")))
                return null;

            Map<String, Object> data = (Map<String, Object>) respMap.get("data");
            if (data == null)
                return null;

            // ── 解析 dense vector ─────────────────────────────────────────────────
            List<Double> dense = null;
            Object denseObj = data.get("dense_vector");
            if (denseObj instanceof List) {
                List<?> rawVec = (List<?>) denseObj;
                dense = new ArrayList<>(rawVec.size());
                for (Object v : rawVec) {
                    if (v instanceof Number)
                        dense.add(((Number) v).doubleValue());
                }
                if (dense.isEmpty())
                    dense = null;
            }

            // ── 解析 sparse vector ───────────────────────────────────────────────
            Map<String, Double> sparse = null;
            Object sparseObj = data.get("sparse_vector");
            if (sparseObj instanceof Map) {
                Map<String, Object> rawSparse = (Map<String, Object>) sparseObj;
                sparse = new java.util.LinkedHashMap<>(rawSparse.size());
                for (Map.Entry<String, Object> entry : rawSparse.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        sparse.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
                    }
                }
                if (sparse.isEmpty())
                    sparse = null;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("dense", dense);
            result.put("sparse", sparse);
            System.out.printf("[Gateway-Dual] text='%s' | dense=%s | sparse=%d terms%n",
                    text.length() > 20 ? text.substring(0, 20) : text,
                    dense != null ? dense.size() + "d" : "null",
                    sparse != null ? sparse.size() : 0);
            return result;
        } catch (Exception e) {
            System.err.println("[Gateway-Dual] fetchDualVector failed, will fallback: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 QA 索引检索高置信度问答对，供 QaInjectionStep 注入候选池。
     *
     * 业务功能：调用 Python /api/ai/qa/search，用 dense 向量对 QA 索引做 KNN 检索。
     * 返回 top_k 条 QA 候选（含 _rrf_score，由 Java 侧 QaInjectionStep 执行 Domain Filter）。
     *
     * 设计决策：使用 fastRestTemplate（3s/5s 超时）。QA 检索必须快速，超时降级空列表，
     * 不阻断主管道（与 ColBERT Semaphore 配合，统一确保主链路稳定性）。
     *
     * @param queryVector 预取的 dense query 向量（1024 维 BGE-M3）
     * @param queryText   原始查询词（传给 Python，供 bigram 重叠 Domain Filter 用）
     * @param forceSource 租户数据源过滤，null 时不过滤
     * @param topK        最多返回的 QA 条数
     * @return QA 候选列表；超时/异常时返回空列表（永不返回 null）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchQaResults(
            List<Double> queryVector, String queryText, String forceSource, int topK) {
        if (queryVector == null || queryVector.isEmpty())
            return new java.util.ArrayList<>();
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("vector", queryVector);
            payload.put("query_text", queryText != null ? queryText : "");
            payload.put("top_k", topK);
            if (forceSource != null && !forceSource.isEmpty()) {
                payload.put("force_source", forceSource);
            }
            // [权限对齐] 将当前用户的 ACL Token 集合注入 payload
            // 来源：UserContextHolder.getAclTokens()（由 JwtAuthInterceptor 预计算）
            // Python 侧用于构建 ES terms filter，与主索引 buildLegacyPermFilter 完全对称
            java.util.Set<String> aclTokens = com.boyang.search.security.UserContextHolder.getAclTokens();
            if (aclTokens != null && !aclTokens.isEmpty()) {
                payload.put("acl_tokens", new java.util.ArrayList<>(aclTokens));
            }
            String url = getBaseUrl() + "/api/ai/qa/search";
            String respJson = fastRestTemplate.postForObject(url, payload, String.class);
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) respMap.get("data");
                if (data != null) {
                    System.out.printf("[Gateway-QA] Fetched %d QA candidates | query='%s'%n",
                            data.size(), queryText != null && queryText.length() > 20
                                    ? queryText.substring(0, 20) + "..."
                                    : queryText);
                    return data;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-QA] fetchQaResults failed (degrading to empty): " + e.getMessage());
        }
        return new java.util.ArrayList<>();
    }

    /**
     * [T8] 关键词模式 QA BM25 降级召回。
     *
     * 业务功能：在无 queryVector（keyword 模式跳过了 VectorFetchStep）时，
     * 改用 BM25 全文检索 QA 索引的 question 和 answer_content 字段召回相关 QA。
     * 与 fetchQaResults 返回结构完全一致（含 _qa_confidence），对 QaInjectionStep 无感知。
     *
     * 关键流程：
     * 1. 调用 Python /api/ai/qa/search/bm25 端点（BM25 multi_match）
     * 2. Python 侧已完成 BM25 分归一化（÷10）→ _qa_confidence 对齐 knn 量级
     * 3. 降级：超时/异常返回空列表，不阻断主管道
     *
     * @param queryText   原始查询词（BM25 全文搜索输入）
     * @param forceSource 租户数据源过滤，null 时不过滤
     * @param topK        最多返回的 QA 条数
     * @return QA 候选列表；超时/异常时返回空列表（永不返回 null）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchQaResultsByBm25(String queryText, String forceSource, int topK) {
        if (queryText == null || queryText.trim().isEmpty())
            return new java.util.ArrayList<>();
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query_text", queryText);
            payload.put("top_k", topK);
            if (forceSource != null && !forceSource.isEmpty()) {
                payload.put("force_source", forceSource);
            }
            // [权限对齐] BM25 降级路径同样注入 ACL Token，两路权限校验规则保持一致
            java.util.Set<String> aclTokensBm25 = com.boyang.search.security.UserContextHolder.getAclTokens();
            if (aclTokensBm25 != null && !aclTokensBm25.isEmpty()) {
                payload.put("acl_tokens", new java.util.ArrayList<>(aclTokensBm25));
            }
            String url = getBaseUrl() + "/api/ai/qa/search/bm25";
            String respJson = fastRestTemplate.postForObject(url, payload, String.class);
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) respMap.get("data");
                if (data != null) {
                    System.out.printf("[Gateway-QA-BM25] Fetched %d QA candidates (BM25) | query='%s'%n",
                            data.size(), queryText.length() > 20 ? queryText.substring(0, 20) + "..." : queryText);
                    return data;
                }
            }
        } catch (Exception e) {
            System.err.println("[Gateway-QA-BM25] fetchQaResultsByBm25 failed (degrading to empty): " + e.getMessage());
        }
        return new java.util.ArrayList<>();
    }
}
