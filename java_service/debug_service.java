package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.math.BigDecimal;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.service.SysAiTuningConfigService;
import com.boyang.search.service.SysTenantPolicyService;
import com.boyang.search.service.SearchAuditLogService;
import com.boyang.search.service.SysGovSynonymService;

/**
 * ?
 * v8.11 expandSynonyms hybridSearch ?
 * localhost ?127.0.0.1 ?Windows ?
 *
 * @deprecated [2026-04-02] 此类为遗留的巨石架构（上帝类），已不再维护�?
 *             现已�?SearchServiceV2 及其底层�?Pipeline 架构（SearchPipelineStep）替代�?
 *             旧版的这套逻辑会绕过最新优化的 AiEngineGateway。请勿在此添加任何新特性�?
 */
@Deprecated
@Service
public class SearchService {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    @Autowired
    private SysTenantPolicyService sysTenantPolicyService;

    @Autowired
    private SearchAuditLogService auditLogService;

    /**
     * 政务同义词服务：提供内存缓存的缩略语展开词典�?
     * GovAbbrExpander 在查询时展开缩略词为 BM25 should 子句，消除词汇鸿沟�?
     */
    @Autowired
    private SysGovSynonymService govSynonymService;

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiHost;

    @Value("${search.mock.default-org:}")
    private String defaultOrg;

    @Value("${search.mock.default-date:2024-01-10}")
    private String defaultDate;

    // 通用 RestTemplate�?s/15s 超时�?
    private final RestTemplate restTemplate;
    // [H08 修复] 专用 RestTemplate 提升为类字段，避免热路径每次 new RestTemplate() 重建 TCP 连接
    // 根因：SimpleClientHttpRequestFactory 不维护连接池，频繁创建会产生大量 TIME_WAIT
    private RestTemplate llmRestTemplate;
    private RestTemplate ltrRestTemplate;

    // [4] health check acceleration 60s TTL 缓存
    private volatile String cachedAcceleration = null;
    private volatile long lastHealthCheckMs = 0L;
    private static final long HEALTH_CACHE_TTL_MS = 60_000L;

    // [P0.5] ColBERT 背压保护 Semaphore
    // GPU 实际已串行推理，允许最�?COLBERT_SEMAPHORE_MAX 个并发请求同时调�?ColBERT
    // 超出时快速降�?RRF 排序，不进入 GPU 排队
    private static final int COLBERT_SEMAPHORE_MAX = 4;
    private static final java.util.concurrent.Semaphore COLBERT_SEMAPHORE = new java.util.concurrent.Semaphore(
            COLBERT_SEMAPHORE_MAX, true); // fair=true 避免饥死

    public SearchService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add(createTraceIdInterceptor());
    }

    private ClientHttpRequestInterceptor createTraceIdInterceptor() {
        return new ClientHttpRequestInterceptor() {
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
    }

    /**
     * [H08 修复] 初始化专用的 RestTemplate 实例（@PostConstruct 保证在依赖注入完成后执行）�?
     * �?LLM�?0s 超时）和 LTR�?s 超时）的 RestTemplate 一次性创建为类字段，
     * 避免热路径中每次请求�?new RestTemplate() 导致 TCP 连接爆炸�?
     */
    @PostConstruct
    public void initSpecializedRestTemplates() {
        SimpleClientHttpRequestFactory llmFactory = new SimpleClientHttpRequestFactory();
        llmFactory.setConnectTimeout(5000);
        llmFactory.setReadTimeout(60000); // LLM 7B 模型最�?60s
        this.llmRestTemplate = new RestTemplate(llmFactory);
        this.llmRestTemplate.getInterceptors().add(createTraceIdInterceptor());

        SimpleClientHttpRequestFactory ltrFactory = new SimpleClientHttpRequestFactory();
        ltrFactory.setConnectTimeout(2000);
        ltrFactory.setReadTimeout(5000);
        this.ltrRestTemplate = new RestTemplate(ltrFactory);
        this.ltrRestTemplate.getInterceptors().add(createTraceIdInterceptor());
        System.out.println("[H08] Specialized RestTemplates initialized (LLM/LTR) with traceId interceptor.");
    }

    /**
     * 4] ?TTL ?AI
     * cceleration ?HTTP ?
     * ?0s TTL ?HTTP ?
     */
    private String getCachedAcceleration() {
        long now = System.currentTimeMillis();
        if (cachedAcceleration == null || (now - lastHealthCheckMs) > HEALTH_CACHE_TTL_MS) {
            try {
                String statusUrl = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/health";
                Map<String, Object> health = restTemplate.getForObject(statusUrl, Map.class);
                cachedAcceleration = (health != null) ? (String) health.getOrDefault("acceleration", "CPU") : "CPU";
                lastHealthCheckMs = now;
                System.out.println("?[HealthCache]  acceleration=" + cachedAcceleration);
            } catch (Exception e) {
                cachedAcceleration = "CPU"; // ?CPU
            }
        }
        return cachedAcceleration;
    }

    /**
     * 1] LLM ? yDE ?
     * ?Ollama ewrite + HyDE
     * Ollama Rewrite(5~8s)+HyDE(5~15s) ?10~23s?
     * ython rewritten_query ?queryector ?query ?
     * ap { "rewritten_query": String, "vector": List<Double>|null }
     */
    private Map<String, Object> fetchRewriteAndHyde(String query, boolean needVector) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("rewritten_query", query);
        fallback.put("vector", null);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("need_vector", needVector);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/intent/rewrite_and_hyde";
            // 使用临时长超�?RestTemplate 并注�?TraceId
            org.springframework.web.client.RestTemplate mergedRt = new org.springframework.web.client.RestTemplate();
            mergedRt.getInterceptors().add(createTraceIdInterceptor());
            org.springframework.http.client.SimpleClientHttpRequestFactory mf = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            mf.setConnectTimeout(3000);
            mf.setReadTimeout(5000); // [Fix3] LLM限时3s + BGE编码400ms�?s足够
            mergedRt.setRequestFactory(mf);
            String respJson = mergedRt.postForObject(url, payload, String.class);
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) respMap.get("data");
                if (data != null) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("rewritten_query", data.getOrDefault("rewritten_query", query));
                    // ?Python ?List<Double>
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
                    System.out.printf(" [RewriteHyDE] '%s' ?rewritten='%s' | vector=%s%n",
                            query.length() > 20 ? query.substring(0, 20) : query,
                            result.get("rewritten_query"),
                            result.get("vector") != null ? "OK" : "null");
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println(" [RewriteHyDE] ? " + e.getMessage());
        }
        return fallback;
    }

    /**
     * 标准化查询文本（P0.6 修复：全角字符标准化 + 停用词移除）�?
     * 流程�?
     * 1. 本地预处理：全角 �?半角字符转换，净化空�?
     * 2. 调用 Python NLP 服务获取拼音、分词等完整处理
     * 全角写字输入不再造成 BM25 召回漏洞�?
     */
    private Map<String, String> normalizeQuery(String text) {
        // 本地预处理：全角 �?半角，常见输入吴差标准化
        String localNorm = fullWidthToHalf(text.trim());

        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", localNorm);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/nlp/normalize";
            String respJson = restTemplate.postForObject(url, payload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && ((Integer) respMap.get("code")) == 200) {
                return (Map<String, String>) respMap.get("data");
            }
        } catch (Exception e) {
            System.err.println("标准化失败，使用本地预处理结�? " + e.getMessage());
        }
        Map<String, String> fallback = new HashMap<>();
        fallback.put("normalized", localNorm);
        fallback.put("pinyin", "");
        return fallback;
    }

    /**
     * 全角字符转半角（P0.6）�?
     * 覆盖：全角字�?\uFF01-\uFF5E) �?半角对应字符；全角空�?�?普通空格�?
     * 不处理繁体字（等�?Python 处理）�?
     */
    private static String fullWidthToHalf(String s) {
        if (s == null || s.isEmpty())
            return s;
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u3000') { // 全角空格
                chars[i] = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') { // 全角印刚字符
                chars[i] = (char) (c - 0xFEE0);
            }
        }
        return new String(chars);
    }

    /**
     * ?HyDEypothetical Document Embedding?
     * ?Python ?LLM ?query query ?
     * LLM Python ?query ?
     */
    private List<Double> fetchHydeVector(String query) {
        try {

            Map<String, String> requestPayload = new HashMap<>();
            requestPayload.put("text", query);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/vector/hyde";
            String respJson = restTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);

            if (respMap != null && respMap.get("code") != null && ((Integer) respMap.get("code")) == 200) {
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
            System.err.println(" HyDE Vector fetch failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * ?ColBERT Late Interaction MaxSim ?
     * GE-M3 last_hidden_stateoken Python ?
     * MaxSim(query_tokens, doc_tokens)?[0, 1] sigmoid ?
     * BGE-Reranker Cross-Encoder ?token ?
     * ?style bias ?
     * ython ?encode_colbert ?BGE-Reranker?
     */
    private List<Double> fetchColbertScores(String query, List<String> documents) {
        try {
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("query", query);
            requestPayload.put("documents", documents);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/colbert/score";
            String respJson = restTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && respMap.get("code") != null && ((Integer) respMap.get("code")) == 200) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("scores") != null) {
                    List<?> rawScores = (List<?>) dataMap.get("scores");
                    List<Double> result = new ArrayList<>(rawScores.size());
                    for (Object s : rawScores) {
                        result.add(s instanceof Number ? ((Number) s).doubleValue() : 0.0);
                    }
                    String mode = (String) dataMap.getOrDefault("mode", "colbert");
                    System.out.printf(" [ColBERT %s] Query: '%s' | %d docs scored%n", mode,
                            query.length() > 20 ? query.substring(0, 20) : query, result.size());
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println(" ColBERT score fetch failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * ?LLM Reranker wen2.5:7b ?
     * ""?"?
     * BGE-M3 ?
     * uery + top-N docs ??Prompt ?7B N 0-10 ?
     * ?2-5s?N ?
     * LM null?ColBERT ?
     */
    private List<Double> fetchLlmRerankScores(String query, List<String> documents) {
        try {
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("query", query);
            requestPayload.put("documents", documents);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/llm/rerank";
            // [H08 修复] 使用 @PostConstruct 初始化的类字�?llmRestTemplate，避免热路径重建
            String respJson = llmRestTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && "success".equals(respMap.get("msg"))) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("scores") != null) {
                    List<?> rawScores = (List<?>) dataMap.get("scores");
                    List<Double> result = new ArrayList<>(rawScores.size());
                    for (Object s : rawScores) {
                        result.add(s instanceof Number ? ((Number) s).doubleValue() : 0.0);
                    }
                    System.out.printf(" [LLM Reranker] Query: '%s' | %d docs scored via %s%n",
                            query.length() > 20 ? query.substring(0, 20) : query,
                            result.size(), dataMap.getOrDefault("model", "?"));
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println(" LLM Reranker fetch failed (degrading to ColBERT): " + e.getMessage());
        }
        return null;
    }

    /**
     * 调用 Python LTR 特征预测接口预估每个特征的综合分�?
     */
    private List<Double> fetchLtrScores(List<Map<String, Object>> features) {
        if (features == null || features.isEmpty())
            return new ArrayList<>();
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("features", features);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/ltr/rank";
            // [H08 修复] 使用 @PostConstruct 初始化的类字�?ltrRestTemplate，避免热路径重建
            String respJson = ltrRestTemplate.postForObject(url, payload, String.class);
            ObjectMapper mapper = new ObjectMapper();
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
            System.err.println(" [LTR Predict] Failed: " + e.getMessage());
        }

        // 分数预测容灾：若 Python 端不可用（例如网络中�?/ 进程挂掉），退化为本地基准打分
        List<Double> fallbacks = new ArrayList<>();
        for (Map<String, Object> feat : features) {
            double seqScore = feat.get("normalized_es_score") instanceof Number
                    ? ((Number) feat.get("normalized_es_score")).doubleValue()
                    : 0.0;

            // 补偿 RRF 分（如果�?NavigationalBypass 跳过了语义重排，可以�?rrf分数放大作为候选分数）
            Object rrfObj = feat.get("rrf_score");
            if (rrfObj instanceof Number && ((Number) rrfObj).doubleValue() > 0) {
                seqScore = Math.max(seqScore, ((Number) rrfObj).doubleValue() * 10.0);
            }

            if (feat.get("raw_es_score") instanceof Number
                    && ((Number) feat.get("raw_es_score")).doubleValue() <= 1.0) {
                seqScore = 0.0001; // Sibling expansion zero score
            }

            // 【修复】fallback 不能乘以 0.1，否则会导致 seqScore (0.2~0.3) 变成 0.02�?
            // 必然低于 outOfCorpusThresholdNoRerank (0.08)，导致所有纯 BM25 检索全部由于阈值过高而无结果�?
            fallbacks.add(seqScore);
        }
        return fallbacks;
    }

    /**
     * 请求 Python 嵌入向量服务（P2.14 修复：加�?token 超限告警）�?
     * BGE-M3 最�?8192 token（约 5500 汉字），超限�?Python 侧静默截断�?
     * 在请求前先估�?token 数，超限时记�?WARN�?
     */
    private List<Double> fetchQueryVector(String text) {
        // 估算 token 数：1 中文�?�?1.5 token�? 英文字符 �?0.3 token
        int estimatedTokens = 0;
        for (char c : text.toCharArray()) {
            estimatedTokens += (c >= '\u4e00' && c <= '\u9fa5') ? 2 : 1; // 中文字计 2，其他计 1
        }
        estimatedTokens = estimatedTokens / 2; // �?2 近似 token
        if (estimatedTokens > 5500) {
            System.err.printf("[Embedding] WARN: 查询文本超出建议长度 estimatedTokens=%d (limit~5500) 将被 Python 端截�?n",
                    estimatedTokens);
        }
        try {
            Map<String, String> requestPayload = new HashMap<>();
            requestPayload.put("text", text);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/vector/query";
            String respJson = restTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);

            if (respMap != null && respMap.get("code") != null && ((Integer) respMap.get("code")) == 200) {
                Map<String, Object> dataMap = (Map<String, Object>) respMap.get("data");
                if (dataMap != null && dataMap.get("vector") != null) {
                    List<?> rawVector = (List<?>) dataMap.get("vector");
                    List<Double> result = new ArrayList<>(rawVector.size());
                    for (Object num : rawVector) {
                        if (num instanceof Number) {
                            result.add(((Number) num).doubleValue());
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println(" Query : " + e.getMessage());
        }
        return null;
    }

    /**
     * 业务功能：调�?Python /api/ai/vector/sparse 获取查询�?BGE-M3 稀疏向量词权重字典�?
     * 返回格式：Map<token_str, weight>，直接用于构�?ES rank_features 子句�?
     * 降级策略：任何异常（网络Timeout / Python 未就绪）返回 null，调用方不发�?sparse 查询�?
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> fetchSparseVector(String text) {
        try {
            Map<String, String> requestPayload = new HashMap<>();
            requestPayload.put("text", text);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/vector/sparse";
            String respJson = restTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
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
                    System.out.printf("[SparseVector] Query: '%s' | Terms: %d%n",
                            text.length() > 20 ? text.substring(0, 20) : text, result.size());
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("[SparseVector] fetch failed (degrading): " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, Object>> hybridSearch(String appCode, String queryText, int topK,
            Map<String, Object> filters) throws Exception {
        long startTime = System.currentTimeMillis();
        SysAiTuningConfig config = tuningConfigService.getGlobalConfig();
        SysTenantPolicy policy = sysTenantPolicyService.getByAppCode(appCode);
        if (policy == null)
            throw new RuntimeException(", AppCode ");

        // F1: 查询归一化（调用 Python NLP，最多等 3s�?
        // [精确查询短路] 精确查询（文档编�?档案�?编码类）不需�?NLP 归一化（拼音补全/停用词移除）�?
        // 直接使用原始查询词，避免消�?3s 网络调用而吃�?SLA 预算导致超时降级�?
        // 注意：此处用原始 queryText 做初步判断（因为还未归一化），足以识别大多数精确查询�?
        String normalizedQuery;
        String queryPinyin;
        if (isExactQuery(queryText.trim())) {
            // 精确查询：跳�?Python normalizeQuery，直接用全角转半角本地处�?
            System.out.println("[ExactQuery Fast-Norm] 精确查询，跳�?Python NLP 归一化，使用本地全角→半角预处理�?);
            normalizedQuery = fullWidthToHalf(queryText.trim());
            queryPinyin = "";
        } else {
            CompletableFuture<Map<String, String>> normFuture = CompletableFuture
                    .supplyAsync(() -> normalizeQuery(queryText));
            Map<String, String> normData;
            try {
                normData = normFuture.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[Timeout/Error] Query normalization blocked. Using fallback.");
                normData = new HashMap<>();
                normData.put("normalized", queryText);
                normData.put("pinyin", "");
            }
            normalizedQuery = normData.get("normalized");
            queryPinyin = normData.get("pinyin");
        }

        // [合并接口] 单次 LLM 同时完成 Rewrite + HyDE，减�?Ollama 串行等待
        // Ollama 内部串行：Rewrite(5~8s) + HyDE(5~15s) = 10~23s，合并后 max(LLM, BGE-M3) = 8~15s
        final int lexicalFastPathLen = config.getLexicalFastPathMaxLength();
        // [Bug3修复] 去除基于 lexicalFastPathLen 的向量禁用逻辑�?
        // 原逻辑：≤4字查�?mightNeedVector=false �?不做 KNN 向量检�?�?�?BM25 排序质量极差
        // 根因：BGE-M3 �?�?字短词的语义捕获�?BM25 更准（不�?IK 分词质量影响），
        // "调解"/"审批" 等短词向量搜索可直接命中语义最�?chunk，BM25 无法做到�?
        // 修复：mightNeedVector 不再以查询长度为门槛，只要不触发熔断就始终做向量化�?
        // 不同查询长度选择不同的向量化策略（短词直�?BGE，长词走 LLM+HyDE�?
        final boolean mightNeedVector = !Boolean.TRUE.equals(config.getCircuitBreakerEnabled());
        final boolean isShortQuery = (normalizedQuery != null && normalizedQuery.trim().length() <= 15);

        // [P1 #1 修复] 精确查询跳过 LLM 改写�?Rewrite
        // 根因：LLM 对精确查询（如文号、纯数字）会改写成通用短语，降低精度且带来 5~15s 延迟�?
        // 精确查询特征�?
        // - 包含引号（用户明确想要精确匹配）
        // - 包含官方文号格式（《》、号、发等）
        // - 长度�? 且不含动�?介词（大概率为关键词搜索�?
        // - 纯数字或数字+字母的序列号
        final boolean skipLlmRewrite = isExactQuery(normalizedQuery);
        if (skipLlmRewrite) {
            System.out.println("[ExactQuery] 检测到精确查询，跳�?LLM 改写�?" + normalizedQuery + "'");
        }
        final String queryForMerged = normalizedQuery;
        CompletableFuture<Map<String, Object>> mergedFuture;
        if (skipLlmRewrite || isShortQuery) {
            // 精确查询 �?短查询（�?5字，含原来被排除�?�?字极短查询）�?
            // 跳过 LLM 改写，直�?BGE-M3 向量化（~300ms，无任何延迟放大）�?
            // [Bug3修复] 原代�?�?字走 fetchRewriteAndHyde(query, false) �?Python 返回 vector=null
            // �?KNN 完全被禁 �?�?BM25，排序质量极差�?
            // 现在：所�?�?5�?查询统一�?BGE-M3 直接编码，进�?KNN+BM25 融合路径�?
            mergedFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                Map<String, Object> r = new HashMap<>();
                r.put("rewritten_query", queryForMerged);
                r.put("vector", mightNeedVector ? fetchQueryVector(queryForMerged) : null);
                return r;
            });
        } else {
            // 长查�?>15 �?：开�?LLM 改写 + HyDE，语义扩写价值最�?
            mergedFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> fetchRewriteAndHyde(queryForMerged, mightNeedVector));
        }

        Map<String, Object> mergedResult;
        try {
            mergedResult = mergedFuture.get(5_000, TimeUnit.MILLISECONDS); // [Fix3] BGE编码400ms+LLM最�?s�?s足够
        } catch (Exception e) {
            System.err.println(" [RewriteHyDE Timeout] Merged call timed out, using fallbacks.");
            mergedFuture.cancel(true);
            mergedResult = new HashMap<>();
            mergedResult.put("rewritten_query", normalizedQuery);
            mergedResult.put("vector", null);
        }

        String rewrittenQuery = (String) mergedResult.getOrDefault("rewritten_query", normalizedQuery);
        @SuppressWarnings("unchecked")
        List<Double> mergedVector = (List<Double>) mergedResult.get("vector");

        System.out.println("====== [Data Flow] Node 1: Input & Normalization ======");
        System.out.println("  - Original Query: '" + queryText + "'");
        System.out.println("  - Normalized: '" + normalizedQuery + "'");
        System.out.println("  - Rewritten : '" + rewrittenQuery + "'");
        System.out.println("  - Pinyin    : '" + queryPinyin + "'");

        long t1 = System.currentTimeMillis();
        List<Double> queryVector = null;

        // [Bug3修复联动] skipEmbedding 不再以查询长度为判断依据�?
        // 原逻辑：rewrittenQuery.length() <= lexicalFastPathLen(4) �?skipEmbedding=true
        // �?即便 mergedVector 已包含向量，�?03�?!skipEmbedding 判断也会拦截，queryVector 永远�?null
        // �?KNN 路径被彻底关闭，短词只走 BM25�?
        // 修复：skipEmbedding 只在熔断器开启（mightNeedVector=false）时才为 true�?
        // 即只有管理员手动打开熔断开关时才禁用向量。正常情况下一律走 KNN+BM25 融合�?
        boolean skipEmbedding = !mightNeedVector;
        // [P1.8 HyDE 质量门控] 计算 HyDE 向量与原�?query 向量的余弦相似度
        // < 0.6 说明 LLM 幻觉严重，丢�?HyDE 向量改用原始查询向量
        List<Double> originalQueryVector = null; // 设置打存原始向量
        if (!skipEmbedding && mergedVector != null && !mergedVector.isEmpty()) {
            if (isShortQuery || skipLlmRewrite) {
                // [短查询快速路径] mergedVector 就是原始 BGE-M3 向量（未经过 LLM，无漂移风险�?
                // 根因：原代码对所有查询额外调用一�?fetchQueryVector，浪�?200~500ms�?
                // 短查询的 mergedVector 已是 BGE 向量，无需二次计算�?
                queryVector = mergedVector;
                originalQueryVector = mergedVector;
                System.out.println("[HyDE Gate] SKIP (short/exact query) �?直接使用 BGE 向量，跳过二�?BGE 调用");
            } else {
                // 长查�?>15�?：执�?HyDE 质量门控
                try {
                    originalQueryVector = fetchQueryVector(normalizedQuery);
                } catch (Exception ignored) {
                }

                double hydeSim = (originalQueryVector != null)
                        ? cosineSimilarity(mergedVector, originalQueryVector)
                        : 1.0;
                System.out.printf("[HyDE Gate] cos_sim(HyDE, original)=%.4f%n", hydeSim);

                if (hydeSim >= config.getHydeMinSim()) {
                    queryVector = mergedVector;
                    System.out.println("[HyDE Gate] PASS �?使用 HyDE 向量");
                } else {
                    queryVector = originalQueryVector;
                    System.out.println(
                            "[HyDE Gate] FAIL (漂移 cos=" + String.format("%.3f", hydeSim) + ") �?改用原始 query 向量");
                }
            }
        } else if (!skipEmbedding) {
            // mergedVector 为空（非向量路径）：直接请求 BGE-M3 向量
            try {
                queryVector = fetchQueryVector(normalizedQuery);
                originalQueryVector = queryVector;
            } catch (Exception e) {
                System.err.println(" [Embedding Fallback] " + e.getMessage());
            }
        }

        // --- Node 1.5: Pre-Flight Probe（精确文件名/文号命中探针�?--
        // [修复] 原条�?!skipEmbedding 会在短查询词（≤ lexicalFastPathLen）时跳过此探�?
        // 根因：Pre-Flight 查的�?metadata.source（文件名字段），与是否需要向量毫无关�?
        // 短查询词触发 skipEmbedding=true 导致此路径被绕过，退化为完整 BM25 管道
        // 从而让不相关文档（仅因某一个字命中）也出现在结果中
        // 修复：去�?!skipEmbedding 条件，只�?query.length() > 3 就执行探�?
        if (rewrittenQuery != null && rewrittenQuery.length() > 3) {
            try {
                // ════════════════ Pre-Flight 查询构建 ════════════════
                // [最终根因] metadata.source �?ES mapping 中是�?keyword 类型
                // �?没有 .keyword 子字段，所�?.keyword 后缀的查询均返回 0 命中
                // �?keyword 字段不走分词analyzer，matchPhrase 对其无效
                // �?应直接对 metadata.source（keyword 类型）做 wildcard + term 精确匹配
                String pfForceSource = filters != null ? (String) filters.get("data_source") : null;
                final String wildcardPattern = "*" + normalizedQuery + "*";
                final String wildcardPatternQ = "*" + queryText.trim() + "*"; // 保留"�?等原始助�?
                SearchRequest preFlightReq = new SearchRequest.Builder()
                        .index(policy.getIndexPattern())
                        .size(5)
                        .timeout("1000ms")
                        .query(q -> q.bool(b -> {
                            // [P0 性能修复] 移除了对 metadata.source 极其缓慢�?wildcard 扫表查询 (1.7s时延元凶)�?
                            // 全文子串命中已由主线 BM25 中的 metadata.source^Boost 接管，探针仅需拦截 "完全相等" 即可实现精确定位�?
                            // �?term 精确等价（normalizedQuery 恰好等于文件名时使用�?
                            b.should(s -> s.term(t -> t
                                    .field("metadata.source")
                                    .value(normalizedQuery)));
                            // �?文号完全匹配
                            b.should(s -> s.term(t -> t
                                    .field("metadata.document_number")
                                    .value(normalizedQuery)));
                            b.minimumShouldMatch("1");

                            // is_latest 过滤：is_latest=true OR 字段不存在（存量文档兼容�?
                            b.filter(f -> f.bool(boolQuery -> boolQuery
                                    .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                    .should(s -> s.bool(bNot -> bNot.mustNot(
                                            mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                            if (pfForceSource != null && !pfForceSource.isEmpty()) {
                                b.filter(f -> f.term(t -> t
                                        .field("metadata.data_source")
                                        .value(pfForceSource)));
                            }
                            return b;
                        }))
                        .build();

                SearchResponse<Object> preFlightRes = esClient.search(preFlightReq, Object.class);
                int preFlightHitCount = preFlightRes.hits().hits().size();
                System.out.printf("[Pre-Flight] query='%s' wildcard='%s' hits=%d%n",
                        normalizedQuery, wildcardPattern, preFlightHitCount);
                if (preFlightHitCount > 0) {
                    System.out.println("====== [Data Flow] Node 1.5: Pre-Flight Probe MATCHED ======");
                    System.out.println(
                            " [Fast-Track] Exact Title/ID match detected! Short-circuiting AI Engine completely.");

                    // [修复] Fast-Track 与主管道保持一致：
                    // �?构建前端依赖字段（organization/publish_time/doc_id/chunk_text�?
                    // �?�?metadata.source 去重（同文档多个 chunk 只返回最佳片段）
                    // �?移除 _source（与主管道一致，避免暴露原始 ES 结构�?
                    // �?score 改为 0.95�?5%），避免前端显示 100% 造成语义误导
                    Map<String, Map<String, Object>> ftSourceDeduped = new java.util.LinkedHashMap<>();
                    String highlightSource = (queryText + " " + rewrittenQuery).trim();
                    for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : preFlightRes.hits().hits()) {
                        Map<String, Object> rawSrc = (Map<String, Object>) hit.source();
                        if (rawSrc == null)
                            continue;
                        Map<String, Object> meta = (Map<String, Object>) rawSrc.get("metadata");
                        String sourceName = meta != null ? (String) meta.getOrDefault("source", defaultOrg)
                                : defaultOrg;

                        // �?source 去重，保留第一个（ES 按相关度排序，第一个最佳）
                        if (ftSourceDeduped.containsKey(sourceName))
                            continue;

                        Map<String, Object> docMap = new HashMap<>();
                        // �?前端必要字段
                        String rawCnt = (String) rawSrc.getOrDefault("content", "");
                        String snippet = generateFallbackSnippet(rawCnt, queryText, rewrittenQuery);
                        docMap.put("chunk_text", highlightText(snippet, highlightSource));
                        docMap.put("organization", sourceName);
                        docMap.put("publish_time",
                                meta != null ? meta.getOrDefault("publish_time", defaultDate) : defaultDate);
                        docMap.put("doc_id", meta != null ? meta.get("doc_id") : null);
                        docMap.put("custom_tags", meta != null ? meta.getOrDefault("custom_keywords", null) : null);
                        docMap.put("tags", meta != null ? meta.getOrDefault("tags", null) : null);
                        // �?元数据（供上层缓存和日志使用�?
                        docMap.put("_id", hit.id());
                        docMap.put("score", 0.95); // 95%：精确文件名命中，诚实分�?
                        // �?不暴�?_source（与主管道一致）
                        ftSourceDeduped.put(sourceName, docMap);
                    }

                    List<Map<String, Object>> fastTrackDocs = new ArrayList<>(ftSourceDeduped.values());
                    long fastTrackCost = System.currentTimeMillis() - startTime;
                    System.out.printf("[Fast-Track] %d unique docs returned in %dms%n", fastTrackDocs.size(),
                            fastTrackCost);
                    return fastTrackDocs;
                } else {
                    System.out.println("[Pre-Flight] No exact filename match �?falling through to full pipeline.");
                }
            } catch (Exception e) {
                System.err.println("Pre-Flight check failed, falling back to full pipeline: " + e.getMessage());
            }
        }
        // --- END PRE-FLIGHT ---

        // ══════════════════════════════════════════════════════════════════════�?
        // [查询意图路由] Query Intent Router �?提前声明，RRF 融合�?Reranker 均依赖此标志
        // ══════════════════════════════════════════════════════════════════════�?
        // NAVIGATIONAL：纯名词/关键词（字数 �?8 且无疑问词）�?BM25 主导 + 跳过 Reranker
        // INFORMATIONAL：含疑问�?逻辑关系�?�?保持 KNN 均等权重 + 进入 Reranker
        final java.util.regex.Pattern INTENT_PATTERN = java.util.regex.Pattern.compile(
                "怎么|如何|为什么|是否|有没有|多少|哪些|哪个|区别|差异|�?*关系|应当|不得|禁止|�?*外|否则|条件|情形|流程|步骤");
        // [H03 修复] navigationalBypass 字符阈值从 8 降至 4�?
        // 根因�?政务云平�?�?字）等精确多词组合被误判�?NAVIGATIONAL，导�?Reranker 被跳过�?
        // Reranker 恰恰在这类精确语义匹配中价值最高�?
        // 4 字阈值仅覆盖真正的极短单词如"月华""环评"，不再误�?-8字的有效复合词�?
        boolean navigationalBypass = queryText != null
                && queryText.trim().length() <= 4
                && !INTENT_PATTERN.matcher(queryText).find();
        if (navigationalBypass) {
            System.out.println("[IntentRouter] NAVIGATIONAL query ('" + queryText + "'), wBM25�?+ Reranker bypass.");
        }

        if (skipEmbedding) {
            System.out.println(
                    "?[Lexical Fast-Path] Bypassing Embedding for short query '" + rewrittenQuery.trim() + "'.");
        }

        System.out.println("====== [Data Flow] Node 2: Vectorization ======");
        if (queryVector != null && !queryVector.isEmpty()) {
            System.out.println("  - Vector Size: " + queryVector.size());
            if (queryVector.size() >= 3) {
                System.out.println("  - Sample [0..2]: [" + queryVector.get(0) + ", " + queryVector.get(1) + ", "
                        + queryVector.get(2) + ", ...]");
            }
        } else {
            System.out.println("  - Vector is NULL/Empty (Skipped or Timeout)");
        }

        long embeddingCost = System.currentTimeMillis() - t1;

        // --- [] ?IDF ---
        List<String> coreTerms = extractCoreTerms(normalizedQuery, policy.getIndexPattern());
        final List<String> anchorTerms = coreTerms;

        // [根治] 统一路径：废�?SLOGAN/INTENT 路由，所有查询走相同管道
        System.out.println("  - Query Mode: UNIFIED INTENT (SLOGAN routing removed, ColBERT decides relevance)");

        // ─────────────────────────────────────────────────────────────────────────
        // 权限过滤层（P0 #8/#9 修复�?
        // 规则矩阵�?
        // user_id=null（匿名） �?只允�?PUBLIC 文档（不再全量放行！�?
        // user_id 存在 �?PUBLIC + INTERNAL + 该用户所属部门的 DEPT 文档
        // + PRIVATE �?uploader_id=user_id 的文�?
        // + GRANT �?granted_users 包含 user_id 的文�?
        // ─────────────────────────────────────────────────────────────────────────
        String userDeptCode = filters != null ? (String) filters.get("user_dept_code") : null;
        String userId = filters != null ? (String) filters.get("user_id") : null;
        final List<co.elastic.clients.elasticsearch._types.FieldValue> deptAncestorValues;
        if (userDeptCode != null && !userDeptCode.trim().isEmpty()) {
            List<String> ancestors = buildDeptAncestorPaths(userDeptCode);
            deptAncestorValues = ancestors.stream()
                    .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                    .collect(java.util.stream.Collectors.toList());
            System.out.println(
                    "  [PermFilter] userDeptCode=" + userDeptCode + " userId=" + userId + " ancestors=" + ancestors);
        } else {
            deptAncestorValues = null;
        }
        // 是否为匿名用户（�?user_id�?
        final boolean isAnonymous = (userId == null || userId.trim().isEmpty());
        final String finalUserId = userId;

        // F4: 构�?ES BM25 全文检索请�?
        String forceSource = filters != null ? (String) filters.get("data_source") : null;
        SearchRequest textRequest = new SearchRequest.Builder()
                .index(policy.getIndexPattern())
                .trackTotalHits(h -> h.count(200)) // 1000 200?CPU
                .size(Math.max(topK * 2, 60)) // ?RRF
                .timeout(config.getEsQueryTimeout() + "ms") // Fail-Fast?
                .query(q -> q.bool(b -> {
                    // [] Should Must
                    if (!anchorTerms.isEmpty()) {
                        b.should(m -> m.bool(coreBool -> {
                            for (String coreTerm : anchorTerms) {
                                coreBool.should(s -> s.match(ma -> ma
                                        .field("content")
                                        .query(coreTerm)
                                        .boost(5.0f)));
                            }
                            coreBool.minimumShouldMatch("1");
                            return coreBool;
                        }));
                    }
                    // 1. 原始查询�?BM25 匹配
                    // [根因A修复] minimumShouldMatch("50%")：IK 拆词后至�?50% token 需命中
                    // 效果�?纯电动汽车的续航瓶颈" 拆出4~5词，GAGW.doc 仅匹�?�?(1�? �?低于50%门槛
                    // 得分清零，不再排到正确文档之�?
                    b.should(sh -> sh.match(ma -> ma
                            .field("content")
                            .query(normalizedQuery)
                            .minimumShouldMatch("50%")));
                    b.should(sh -> sh.multiMatch(mm -> mm
                            .query(normalizedQuery)
                            .fields("metadata.source^" + config.getTitleBoost(), "keywords^2.0")));

                    // [词汇鸿沟修复 X1] 政务缩略语查询时展开（应用层，无需 ES Reindex�?
                    // 根因�?环评"�?IK 分词后是单一 token，文档中�?环境影响评价"则完全不命中�?
                    // 修复：从内存缓存中查找命中的缩略语，展开为多�?should match 子句�?
                    // 每个展开词以独立 should 加入，让 BM25 在所有可能的全称中寻找最佳匹配�?
                    final String queryForExpansion = normalizedQuery;
                    final java.util.Map<String, java.util.List<String>> synonymMap = govSynonymService.getSynonymMap();
                    if (synonymMap != null && !synonymMap.isEmpty() && queryForExpansion != null) {
                        // 逐个检查查询词中的缩略语（简单空格分词，保留原始词不替换�?
                        for (java.util.Map.Entry<String, java.util.List<String>> entry : synonymMap.entrySet()) {
                            if (queryForExpansion.contains(entry.getKey())) {
                                // 命中缩略语：将展开词列表（不含 abbr 自身，避免重复）注入额外 should 子句
                                java.util.List<String> expandedTerms = entry.getValue();
                                for (String expandedTerm : expandedTerms) {
                                    if (!expandedTerm.equals(entry.getKey())) {
                                        // 展开词加分权�?= 3.0：低�?phrase(15x)，高于普�?match(默认1x)
                                        // 不设 minimumShouldMatch，只要文档包含任一展开词即可加�?
                                        final String term = expandedTerm;
                                        b.should(sh -> sh.match(ma -> ma
                                                .field("content")
                                                .query(term)
                                                .boost(3.0f)));
                                    }
                                }
                                System.out.println("[GovAbbrExpander] 展开 '" + entry.getKey() + "' �?" + expandedTerms);
                            }
                        }
                    }

                    // [根治修复] LLM 改写词注�?BM25 检�?
                    // 根因：原始查�?问话流程防篡改记录留�?与文�?一律全程录音录�?无词汇重�?
                    // LLM 已将查询改写为包�?录音录像"等业务词汇，但改写词从未参与 BM25�?
                    // 修复：将 rewrittenQuery 以高权重注入 BM25 should 子句
                    // 效果：chunk_8("录音录像") �?BM25 分从 ~0 提升到有效分，进�?ColBERT 候选池
                    final String rewrittenQueryForBM25 = rewrittenQuery;
                    if (rewrittenQueryForBM25 != null && !rewrittenQueryForBM25.isEmpty()
                            && !rewrittenQueryForBM25.equals(normalizedQuery)) {
                        b.should(sh -> sh.match(ma -> ma
                                .field("content")
                                .query(rewrittenQueryForBM25)
                                .boost(8.0f) // 改写词命中权重高于原始词(因为语义更精�?
                        ));
                        b.should(sh -> sh.matchPhrase(mp -> mp
                                .field("content")
                                .query(rewrittenQueryForBM25)
                                .slop(5)
                                .boost(20.0f) // 改写词短语命中额外加�?
                        ));
                    }

                    // content ?should S ?

                    // 2. ??(Best Fields)
                    // 注：由于 python 层将 source/keywords 等字段直接建立为�?keyword 模式（非
                    // text+keyword子字段模式），所以不需要加 .keyword 后缀
                    b.should(s -> s.multiMatch(mm -> mm
                            .query(normalizedQuery)
                            .fields("metadata.source^20.0", "metadata.document_number^20.0", "keywords^15.0",
                                    "metadata.tags_kw^10.0")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)));

                    // 3. (Phrase Anchoring)
                    if (normalizedQuery != null && normalizedQuery.length() > 2) {
                        b.should(s -> s.matchPhrase(mp -> mp
                                .field("content")
                                .query(normalizedQuery)
                                .slop(3) // slop 3?
                                .boost(15.0f)));
                    }

                    // 3.5 核心�?Boost 子句
                    // [短词修复] minimumShouldMatch 自适应策略�?
                    // - anchorTerms.size()==1（短查询�?月华"）→ "1"：整词必须命中才激�?20x boost
                    // - anchorTerms.size()>=2（多核心词）�?"2"：至�?词命中，防止单字"�?等误触发
                    // 原代码固�?"2"，对单词 anchor 永远无法满足条件，boost 完全失效�?
                    if (!anchorTerms.isEmpty()) {
                        String coreTermClause = String.join(" ", anchorTerms);
                        String msm = anchorTerms.size() == 1 ? "1" : "2";
                        b.should(s -> s.match(ma -> ma
                                .field("content")
                                .query(coreTermClause)
                                .minimumShouldMatch(msm)
                                .boost(20.0f)));

                        b.should(s -> s.matchPhrase(ma -> ma
                                .field("content")
                                .query(coreTermClause)
                                .slop(15)
                                .boost(30.0f)));
                    }

                    // 4. 仅检索最新版本（含存量兼容：字段缺失时放行）
                    b.filter(f -> f.bool(boolQuery -> boolQuery
                            .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                            .should(s -> s
                                    .bool(bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                    // 5. 权限过滤（P0 #8/#9 修复�?
                    // 所有用户均需经过权限过滤（匿名用户只能看 PUBLIC�?
                    b.filter(f -> f.bool(permBool -> {
                        if (isAnonymous) {
                            // 匿名用户：只允许 visibility=PUBLIC
                            permBool.should(s -> s.term(t -> t
                                    .field("metadata.visibility").value("PUBLIC")));
                            // 存量文档字段缺失 �?仍放行（避免大量历史文档全部不可见）
                            permBool.should(s -> s.bool(bNot -> bNot.mustNot(
                                    mn -> mn.exists(e -> e.field("metadata.visibility")))));
                        } else {
                            // 登录用户
                            // a. PUBLIC + INTERNAL 文档始终可见
                            permBool.should(s -> s.terms(t -> t.field("metadata.visibility")
                                    .terms(tv -> tv.value(java.util.Arrays.asList(
                                            co.elastic.clients.elasticsearch._types.FieldValue.of("PUBLIC"),
                                            co.elastic.clients.elasticsearch._types.FieldValue.of("INTERNAL"))))));
                            // b. 存量文档字段缺失 �?视为 INTERNAL 放行
                            permBool.should(s -> s.bool(bNot -> bNot.mustNot(
                                    mn -> mn.exists(e -> e.field("metadata.visibility")))));
                            // c. DEPT：文档归属部门是用户部门的祖�?
                            if (deptAncestorValues != null && !deptAncestorValues.isEmpty()) {
                                permBool.should(s -> s.terms(t -> t.field("metadata.dept_code_full")
                                        .terms(tv -> tv.value(deptAncestorValues))));
                            }
                            // d. PRIVATE：文档上传�?= 当前用户（P0 #8 修复�?
                            permBool.should(s -> s.bool(bPrivate -> bPrivate
                                    .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("PRIVATE")))
                                    .must(m2 -> m2.term(t -> t.field("metadata.uploader_id").value(finalUserId)))));
                            // e. GRANT：文�?granted_users 包含当前用户 ID（P0 #8 修复�?
                            permBool.should(s -> s.bool(bGrant -> bGrant
                                    .must(m1 -> m1.term(t -> t.field("metadata.visibility").value("GRANT")))
                                    .must(m2 -> m2.term(t -> t.field("metadata.granted_users").value(finalUserId)))));
                        }
                        permBool.minimumShouldMatch("1");
                        return permBool;
                    }));
                    if (forceSource != null && !forceSource.isEmpty()) {
                        b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                    }
                    return b;

                }))
                .highlight(h -> h
                        .fields("content", hf -> hf
                                .preTags("<em class='highlight'>")
                                .postTags("</em>")
                                .fragmentSize(150)
                                .numberOfFragments(1)
                                .noMatchSize(100)))
                .build();

        // F5: 构�?KNN 向量检索请求（coarse + fine 共享配置�?
        // [P2 #4] numCandidates 提升到两�?KNN block 的外层，消除作用域问�?
        final int knnNumCandidates = config.getKnnNumCandidates();
        SearchRequest knnRequest = null;
        if (queryVector != null && !queryVector.isEmpty()) {
            final List<Double> finalQueryVector = queryVector;
            knnRequest = new SearchRequest.Builder()
                    .index(policy.getIndexPattern())
                    .knn(k -> k.field("vector")
                            .queryVector(finalQueryVector)
                            .k(Math.max(topK * 3, 100))
                            .numCandidates(knnNumCandidates)
                            // KNN ?
                            .filter(f -> f.bool(b -> {
                                // is_latest 过滤
                                b.filter(ft -> ft.bool(boolQuery -> boolQuery
                                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                        .should(s -> s.bool(bNot -> bNot
                                                .mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                                // coarse chunk 优先
                                b.should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")));
                                b.should(s -> s.bool(
                                        bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))));
                                b.minimumShouldMatch("1");
                                // 部门权限过滤（与 BM25 逻辑一致）
                                if (deptAncestorValues != null && !deptAncestorValues.isEmpty()) {
                                    b.filter(ft -> ft.bool(permBool -> {
                                        permBool.should(s -> s.terms(t -> t.field("metadata.visibility")
                                                .terms(tv -> tv.value(java.util.Arrays.asList(
                                                        co.elastic.clients.elasticsearch._types.FieldValue.of("PUBLIC"),
                                                        co.elastic.clients.elasticsearch._types.FieldValue
                                                                .of("INTERNAL"))))));
                                        // [P0.2 修复] 删除 must_not exists visibility 匹名用户可见漏洞
                                        // 根因：字段缺失时对匿名用户也可见，老文档或入库异常的文档都氢出权限控制
                                        // 修复：直接删除此分支，配�?ES mapping null_value="PRIVATE" 局充默认最严格权限
                                        permBool.should(s -> s.terms(t -> t.field("metadata.dept_code_full")
                                                .terms(tv -> tv.value(deptAncestorValues))));
                                        permBool.minimumShouldMatch("1");
                                        return permBool;
                                    }));
                                }
                                if (forceSource != null && !forceSource.isEmpty())
                                    b.filter(ft -> ft.term(t -> t.field("metadata.data_source").value(forceSource)));
                                return b;
                            })))
                    .highlight(h -> h
                            .fields("content", hf -> hf
                                    .preTags("<em class='highlight'>")
                                    .postTags("</em>")
                                    .fragmentSize(150)
                                    .numberOfFragments(1)
                                    .noMatchSize(100)))
                    .build();
        }

        System.out.println("====== [Data Flow] Node 2.5: Generated ES DSL Scripts ======");
        System.out.println("  --> [Text DSL]: " + textRequest.toString());
        if (knnRequest != null) {
            // ?knnQuery ?vector ?1024 ?
            // System.out.println(" --> [KNN DSL]: " + knnRequest.toString());
        }

        // [P0.4 修复] 废除无效�?fine KNN
        // 根因：coarse KNN �?fine KNN 使用完全相同的向量，结果重叠�?>80%�?
        // 多一�?ES roundtrip（~100ms）纯属浪费�?
        // 相关代码已全部删除， DocExpansion 小居同一效果�?

        // [Sparse 通道] 异步获取查询稀疏向量（�?BM25+KNN 并行，不占用请求延迟预算�?
        // 跳过条件：引�?BM25 已有充分词汇命中�?2.0）时小加成辽可忽略，这里无条件计算�?
        // skipEmbedding 时课 query 也无需 sparse（短词导�?+ navigational 已推识词汇）�?
        final Map<String, Double> querySparseVector;
        if (!skipEmbedding && normalizedQuery != null && normalizedQuery.length() > 2) {
            querySparseVector = fetchSparseVector(normalizedQuery);
        } else {
            querySparseVector = null;
        }

        // [Sparse] 构�?ES rank_features 查询请求
        // 每个词权重映射为一�?rank_features should 子句，利�?log1p_scaling saturation logger
        SearchRequest sparseRequest = null;
        if (querySparseVector != null && !querySparseVector.isEmpty()) {
            // 为减�?ES 请求中的子句数，仅保留权�?TOP-16 个词
            List<Map.Entry<String, Double>> topSparseTerms = querySparseVector.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(16)
                    .collect(java.util.stream.Collectors.toList());
            final List<Map.Entry<String, Double>> finalSparseTerms = topSparseTerms;
            sparseRequest = new SearchRequest.Builder()
                    .index(policy.getIndexPattern())
                    .size(Math.max(topK * 3, 30))
                    .query(q -> q.bool(b -> {
                        // is_latest 过滤
                        b.filter(ft -> ft.bool(bq -> bq
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(
                                        bNot -> bNot.mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        // coarse chunk 优先（与 KNN 一致）
                        b.filter(ft -> ft.bool(bq -> bq
                                .should(s -> s.term(t -> t.field("chunk_granularity").value("coarse")))
                                .should(s -> s.bool(
                                        bMiss -> bMiss.mustNot(mn -> mn.exists(e -> e.field("chunk_granularity")))))
                                .minimumShouldMatch("1")));
                        // 每个词对应的 sparse_vector 字段�?rank_features should 子句
                        // saturation(t -> {}) 使用 ES 默认 pivot（字段均值），无需手动配置
                        // 权重统一�?RRF wSparse 系数控制，单词级别不额外设置 boost
                        for (Map.Entry<String, Double> entry : finalSparseTerms) {
                            final String term = entry.getKey();
                            b.should(s -> s.rankFeature(rf -> rf
                                    .field("sparse_vector." + term)
                                    .saturation(sat -> sat)));
                        }
                        b.minimumShouldMatch("1"); // 至少命中一个词
                        return b;
                    }))
                    .build();
        }

        long t3 = System.currentTimeMillis();
        // BM25 + KNN + Sparse 三路并行查询
        java.util.concurrent.CompletableFuture<SearchResponse<Object>> textFuture = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return esClient.search(textRequest, Object.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        final SearchRequest finalKnnRequest = knnRequest;
        java.util.concurrent.CompletableFuture<SearchResponse<Object>> knnFuture = (finalKnnRequest != null)
                ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                    try {
                        return esClient.search(finalKnnRequest, Object.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                : java.util.concurrent.CompletableFuture.completedFuture(null);

        // [Sparse] 稀疏向量检索并行调起（sparse_vector 字段必须已入库，旧文档若无此字段则无命中自然降级�?
        final SearchRequest finalSparseRequest = sparseRequest;
        java.util.concurrent.CompletableFuture<SearchResponse<Object>> sparseFuture = (finalSparseRequest != null)
                ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                    try {
                        return esClient.search(finalSparseRequest, Object.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                : java.util.concurrent.CompletableFuture.completedFuture(null);

        SearchResponse<Object> textResponse = null;
        SearchResponse<Object> knnResponse = null;
        SearchResponse<Object> sparseResponse = null;

        try {
            textResponse = textFuture.get(config.getEsQueryTimeout(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println(" [Timeout] ES Text search blocked. Breaking...");
            textFuture.cancel(true);
        } catch (Exception e) {
            System.err.println("ES Text Search Error: " + e.getMessage());
        }

        try {
            knnResponse = (knnFuture != null) ? knnFuture.get(config.getEsQueryTimeout(), TimeUnit.MILLISECONDS) : null;
        } catch (TimeoutException e) {
            System.err.println(" [Timeout] ES KNN coarse search blocked. Breaking...");
            if (knnFuture != null)
                knnFuture.cancel(true);
        } catch (Exception e) {
            System.err.println("ES KNN Coarse Search Error: " + e.getMessage());
        }

        // [Sparse] sparse 超时设为 1500ms（保守），超时降级不影响主链�?
        try {
            sparseResponse = (sparseFuture != null) ? sparseFuture.get(1500, TimeUnit.MILLISECONDS) : null;
        } catch (TimeoutException e) {
            System.err.println("[Timeout] ES Sparse search blocked (degrading).");
            if (sparseFuture != null)
                sparseFuture.cancel(true);
        } catch (Exception e) {
            System.err.println("ES Sparse Search Error (degrading): " + e.getMessage());
        }

        System.out.println("====== [Data Flow] Node 3: Elasticsearch Retrieval ======");
        long textHits = (textResponse != null && textResponse.hits() != null) ? textResponse.hits().total().value() : 0;
        long knnHits = (knnResponse != null && knnResponse.hits() != null && knnResponse.hits().total() != null)
                ? knnResponse.hits().total().value()
                : 0;
        long sparseHits = (sparseResponse != null && sparseResponse.hits() != null
                && sparseResponse.hits().total() != null) ? sparseResponse.hits().total().value() : 0;
        System.out.println("  - Total Text Hits: " + textHits);
        System.out.println("  - Total KNN Coarse Hits: " + knnHits);
        System.out.println("  - Total Sparse Hits: " + sparseHits);

        long esSearchCost = System.currentTimeMillis() - t3;

        // ──────────────────────────────────────────────────────────────────────
        // [方案C] BM25 弱信号检测（词汇鸿沟场景噪声抑制�?
        // 问题根因：Query "定向财务援助" 等词与文�?"专项保护资金" 零重叠时�?
        // BM25 对偶然命�?"机构" 等公共词的无关文档赋�?rank=1 的高 RRF 分，
        // 使无关文档排在真正的语义相关文档前面，RRF 成为噪声放大器�?
        // 检测依据：ES BM25 原始最高分 < 2.0 说明无有效词汇命中（正常命中通常 > 5.0）�?
        // ──────────────────────────────────────────────────────────────────────
        double bm25MaxScore = 0.0;
        if (textResponse != null && textResponse.hits() != null
                && !textResponse.hits().hits().isEmpty()
                && textResponse.hits().hits().get(0).score() != null) {
            bm25MaxScore = textResponse.hits().hits().get(0).score();
        }
        System.out.printf("[LexGap] BM25 max raw score = %.2f%n", bm25MaxScore);

        // ─────────────────────────────────────────────────────────────────────
        // [方案A] BM25 命中数量检测：高频泛义词信�?
        // 根因：短查询（≤4字）精确命中通常 < 20 份，命中�?> 50 说明查询词高度泛化（�?苹果"），
        // 此时 BM25 �?IDF 区分度完全失效，不能不经 Reranker 核验就输出结果�?
        // 无需任何外部依赖，直接复�?L1154 已计算的 textHits 变量，零额外开销�?
        // 阈�?50 已硬编码为保守初始值，后续可迁移到 sys_ai_tuning_config 热配置�?
        // ─────────────────────────────────────────────────────────────────────
        final boolean bm25HighFreqTerm = navigationalBypass
                && queryText != null
                && queryText.trim().length() <= 4
                && textHits > 50;
        if (bm25HighFreqTerm) {
            System.out.printf("[HighFreqTerm] 短查�?BM25 命中�?%d > 50，词汇高频泛义，将强�?Reranker 消歧%n", textHits);
        }

        // ─────────────────────────────────────────────────────────────────────
        // [方案B] BM25 分数平坦度检测：分布均衡 = 无明显赢�?= 歧义词信�?
        // 原理：精确词命中�?top-1 分远高于 top-5 均值（ratio > 2.0，有赢家）；
        // 歧义词因 BM25 对各候�?一视同�?，各分接近（ratio < 1.3，无赢家，分布平坦）�?
        // 覆盖场景：中频歧义词（如"通知""苹果"），弥补方案A只覆盖超高频词的盲区�?
        // 默认 ratio=99（不平坦），只在数据充分时才计算，避免样本不足时误判�?
        // ─────────────────────────────────────────────────────────────────────
        double bm25FlatnessRatio = 99.0;
        if (navigationalBypass && textResponse != null && textResponse.hits().hits().size() >= 5) {
            double bfTop1 = textResponse.hits().hits().get(0).score() != null
                    ? textResponse.hits().hits().get(0).score()
                    : 0.0;
            double bfTop5Avg = textResponse.hits().hits().subList(0, 5).stream()
                    .mapToDouble(h -> h.score() != null ? h.score() : 0.0)
                    .average().orElse(0.0);
            // 防止除零：top5Avg < 0.001 时保持默认�?99（视为不平坦�?
            bm25FlatnessRatio = bfTop5Avg > 0.001 ? bfTop1 / bfTop5Avg : 99.0;
            System.out.printf("[BM25Flatness] top1=%.2f top5Avg=%.2f ratio=%.2f%n",
                    bfTop1, bfTop5Avg, bm25FlatnessRatio);
        }
        final boolean bm25IsFlat = navigationalBypass && bm25FlatnessRatio < 1.3;
        if (bm25IsFlat) {
            System.out.printf("[BM25Flatness] ratio=%.2f < 1.3，分数无赢家，将强制 Reranker 消歧%n", bm25FlatnessRatio);
        }

        // RRF 融合：BM25 + KNN + Sparse 三通道
        List<Map<String, Object>> candidates = rrfMerge(textResponse, knnResponse, sparseResponse, topK, config,
                navigationalBypass);

        // [方案C 续] BM25 弱信�?�?压制�?BM25 候选的 RRF 分，�?KNN 语义信号主导
        if (bm25MaxScore < 2.0 && queryVector != null && !candidates.isEmpty()) {
            int suppressedCount = 0;
            for (Map<String, Object> cand : candidates) {
                // _max_knn_score 缺失�?< 0.15 �?该候选未�?KNN 命中 = �?BM25 噪声
                Object knnScoreObj = cand.get("_max_knn_score");
                double knnSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : 0.0;
                if (knnSim < 0.15) {
                    double rrf = (double) cand.getOrDefault("_rrf_score", 0.0);
                    cand.put("_rrf_score", rrf * 0.05); // 压制至原�?5%，消除伪高排�?
                    suppressedCount++;
                }
            }
            if (suppressedCount > 0) {
                // 重新按调整后�?_rrf_score 降序排列，确保真正的 KNN 命中排在前面
                candidates.sort((a, b) -> Double.compare(
                        (double) b.getOrDefault("_rrf_score", 0.0),
                        (double) a.getOrDefault("_rrf_score", 0.0)));
                System.out.printf(
                        "[LexGap] Suppressed %d pure-BM25 noise candidates (bm25MaxScore=%.2f < 2.0 threshold)%n",
                        suppressedCount, bm25MaxScore);
            }
        }

        System.out.println("====== [Data Flow] Node 4: RRF Fusion ======");
        System.out.println("  - Merged Candidates Pool Size: " + candidates.size());
        if (!candidates.isEmpty()) {
            System.out.println("  - Highest RRF Score: " + candidates.get(0).get("_rrf_score"));
        }

        long rrfCost = System.currentTimeMillis() - (t3 + esSearchCost);

        // ============================================================
        // [Document Expansion] 文档扩展：让 ColBERT 看到同一文档的所�?chunk
        // ============================================================
        // 根因：chunk_12("一律全程录音录�?) �?BM25+KNN 分数低，永远进不�?RRF 候选池
        // 但它才是语义最相关�?chunk。chunk_13("执法综合管理") BM25 高于 chunk_12�?
        // 方案：对 RRF 候选中每个 doc_id，从 ES 拉取该文档的所�?sibling chunk�?
        // 补充加入 ColBERT 候选池（给予低 RRF 分），由 ColBERT MaxSim 决定最�?chunk�?
        try {
            java.util.Set<String> seenDocIds = new java.util.LinkedHashSet<>();
            java.util.Set<String> existingChunkIds = new java.util.HashSet<>();
            for (Map<String, Object> c : candidates) {
                existingChunkIds.add((String) c.get("_id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) c.get("_source");
                if (src != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                    if (meta != null) {
                        String docId = (String) meta.getOrDefault("doc_id",
                                meta.getOrDefault("source", ""));
                        if (docId != null && !docId.isEmpty())
                            seenDocIds.add(docId);
                    }
                }
            }

            if (!seenDocIds.isEmpty()) {
                // 批量查询所有候选文档的 sibling chunk（一�?ES 请求�?
                List<co.elastic.clients.elasticsearch._types.FieldValue> docIdValues = seenDocIds.stream()
                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                        .collect(java.util.stream.Collectors.toList());

                SearchRequest expansionReq = new SearchRequest.Builder()
                        .index(policy.getIndexPattern())
                        .size(300) // 最多扩�?300 �?sibling chunk
                        .query(q -> q.bool(b -> {
                            // [修复] 使用 metadata.source 字段（实际存在），而非 metadata.doc_id（不存在�?
                            b.filter(f -> f.terms(t -> t
                                    .field("metadata.source")
                                    .terms(tv -> tv.value(docIdValues))));
                            // 只取 is_latest 或无 is_latest 标记�?chunk
                            b.filter(f -> f.bool(boolQuery -> boolQuery
                                    .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                    .should(s -> s.bool(bNot -> bNot
                                            .mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                            return b;
                        }))
                        .build();

                // [修复] sibling chunk 的入场分设为当前最低候选分�?50%（确保进�?ColBERT top-N�?
                // 旧设�?rrf_score=0.001 �?sibling 永远排在候选末尾，ColBERT 永远看不到它�?
                double minExistingRrf = candidates.stream()
                        .mapToDouble(c -> {
                            Object v = c.get("_rrf_score");
                            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
                        }).min().orElse(0.01);
                double siblingScore = Math.max(minExistingRrf * 0.75, 0.01);

                SearchResponse<Object> expansionResp = esClient.search(expansionReq, Object.class);
                int expandedCount = 0;
                // [H05 修复] 每轮扩展总量上限 = 文档�?* 10，防止单文档 100 �?sibling 打爆 Reranker
                final int maxSiblingTotal = seenDocIds.size() * 10;
                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : expansionResp.hits().hits()) {
                    if (expandedCount >= maxSiblingTotal)
                        break; // 达到上限，不再扩�?
                    if (!existingChunkIds.contains(hit.id())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> siblingDoc = new HashMap<>((Map<String, Object>) hit.source());
                        siblingDoc.put("_source", hit.source());
                        siblingDoc.put("_id", hit.id());
                        siblingDoc.put("_rrf_score", siblingScore);
                        // [H05 修复] 注入 _max_knn_score=0.0，使 Layered Veto 能正常过滤低质量 sibling
                        // 根因：缺少此字段�?knnSim=-1.0，Veto 判断 knnSim>=0 �?false，sibling 永不被过�?
                        siblingDoc.put("_max_knn_score", 0.0);
                        candidates.add(siblingDoc);
                        existingChunkIds.add(hit.id());
                        expandedCount++;
                    }
                }
                System.out.println("  [DocExpansion] Expanded " + expandedCount
                        + " sibling chunks from " + seenDocIds.size() + " docs into ColBERT pool (limit="
                        + maxSiblingTotal + ")");
            }
        } catch (Exception ex) {
            System.err.println(" [DocExpansion] Failed: " + ex.getMessage());
        }
        System.out.println("====== [Data Flow] Node 4.5: DocExpansion Pool ======");
        System.out.println("  - ColBERT Candidate Pool (after expansion): " + candidates.size());

        // [H02 修复] 删除此处的第一�?Context Roll-up（fine→coarse 上下文回溯）�?
        // 根因：相同的 parent 回溯逻辑�?L1513 排序后也会执行一次（第二轮），两轮逻辑完全重复�?
        // 1. 多一�?ES 批量查询（~50-100ms 无谓延迟�?
        // 2. 第一轮替换后第二轮再次遍�?parentMap 找同一 chunk，逻辑混乱
        // 修复：只保留排序后的第二轮执行（L1513），此处删除�?

        // ─────────────────────────────────────────────────────────────────────────────────────
        // [方案A] 长复合句多向�?KNN 扩展召回（词汇鸿沟根治）
        // 触发条件：BM25 弱信号（< 2.0�? Query 长度 > 20 + 含中文分句标�?
        // 问题根因：BGE-M3 �?3 独立子句�?MeanPool �?质心向量偏离各子语义�?
        // 每个子句覆盖�?chunk 余弦相似度降�?0.55-0.65，KNN 排名跌至 40+�?
        // 解决方案：按分句符分割为 N 个子�?�?各自编码 �?独立 KNN �?注入 candidates HEAD�?
        // 子句向量聚焦单一语义，与对应 chunk 相似度可恢复�?0.75+ 水平�?
        // ─────────────────────────────────────────────────────────────────────────────────────
        if (bm25MaxScore < 2.0 && queryVector != null && normalizedQuery != null
                && normalizedQuery.length() > 20
                && normalizedQuery.matches(".*[�?�?、。！？].*")) {
            try {
                // [方案H 废除] colloquial_vector KNN 搜索已移除（死代码）
                // 根因：colloquial_vector 字段由后�?daemon 线程异步回填�?0%+ �?chunk 此字段为空，
                // 每次 hybridSearch 都发出一个必然返回空结果�?ES KNN 请求（~100ms 额外延迟）�?
                // 替代：同义词扩展（方案F/X1）在 BM25 层已覆盖口语化检索场景，无需独立向量通道�?
                System.out.println(" [Colloquial] Deprecated: replaced by synonym expansion (Plan F/X1)");

                String[] subParts = normalizedQuery.split("[�?�?、。！？]+");
                java.util.List<String> validClauses = java.util.Arrays.stream(subParts)
                        .map(String::trim)
                        .filter(s -> s.length() > 6)
                        .collect(java.util.stream.Collectors.toList());

                if (validClauses.size() >= 2) {
                    System.out.printf("[SubQuery] Long compound query split into %d sub-clauses �?multi-vector KNN%n",
                            validClauses.size());

                    // 并行为每个子句获�?BGE-M3 向量（每路最多等�?2s 超时�?
                    java.util.List<CompletableFuture<List<Double>>> subVecFutures = new ArrayList<>();
                    for (String clause : validClauses) {
                        subVecFutures.add(com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> fetchQueryVector(clause)));
                    }

                    // 记录当前候选池已有�?chunk ID，避免重复注�?
                    java.util.Set<String> existingIds = candidates.stream()
                            .map(c -> (String) c.get("_id"))
                            .collect(java.util.stream.Collectors.toSet());

                    int subTotalAdded = 0;
                    final String subIndexPattern = policy.getIndexPattern();

                    for (int sci = 0; sci < validClauses.size(); sci++) {
                        try {
                            List<Double> subVec = subVecFutures.get(sci).get(2000, TimeUnit.MILLISECONDS);
                            if (subVec == null || subVec.isEmpty())
                                continue;

                            // 子句向量独立 KNN 检索（top-20，numCandidates=100�?
                            final List<Double> fSubVec = subVec;
                            SearchRequest subKnnReq = new SearchRequest.Builder()
                                    .index(subIndexPattern)
                                    .knn(k -> k.field("vector")
                                            .queryVector(fSubVec)
                                            .k(20)
                                            .numCandidates(100)
                                            .filter(f -> f.bool(b -> {
                                                // is_latest 过滤，兼容存量无此字段的 chunk
                                                b.filter(ft -> ft.bool(bq -> bq
                                                        .should(s -> s
                                                                .term(t -> t.field("metadata.is_latest").value(true)))
                                                        .should(s -> s.bool(bNot -> bNot.mustNot(
                                                                mn -> mn.exists(
                                                                        e -> e.field("metadata.is_latest")))))));
                                                return b;
                                            })))
                                    .size(20)
                                    .build();

                            SearchResponse<Object> subResp = esClient.search(subKnnReq, Object.class);
                            int subAdded = 0;
                            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : subResp.hits().hits()) {
                                double subSim = hit.score() != null ? hit.score() : 0.0;
                                // KNN 结果按分降序，低�?0.50 后续全部跳过（快速截断）
                                if (subSim < 0.50)
                                    break;
                                if (existingIds.contains(hit.id()))
                                    continue;
                                Map<String, Object> subDoc = convertHitToMap(hit);
                                // 将余弦相似度映射�?RRF 分量�?
                                // top-5 命中（subSim�?.75）→ 0.75×0.015=0.011，与主管�?KNN top-10 量级相当
                                subDoc.put("_rrf_score", subSim * 0.015);
                                subDoc.put("_max_knn_score", subSim);
                                candidates.add(subDoc);
                                existingIds.add(hit.id());
                                subAdded++;
                                subTotalAdded++;
                            }
                            System.out.printf("[SubQuery] Sub-clause #%d '%.14s...' �?%d new chunks added%n",
                                    sci + 1, validClauses.get(sci), subAdded);
                        } catch (Exception e) {
                            System.err.printf("[SubQuery] Sub-clause #%d KNN failed: %s%n", sci + 1, e.getMessage());
                        }
                    }
                    System.out.printf("[SubQuery] Total injected %d new chunks (pool size: %d �?%d)%n",
                            subTotalAdded, candidates.size() - subTotalAdded, candidates.size());
                }
            } catch (Exception e) {
                System.err.println("[SubQuery] Multi-vector expansion failed: " + e.getMessage());
            }
        }
        // [Data Flow] Node 4.6: SubQuery Multi-Vector展示在日志中

        // LM ?fine chunk ?query ?
        // ?0.95 ?Reranker?
        //
        // [] /isSloganQuery=true
        // BGE-M3
        // "? vs ""?
        // ?sim ?
        // ? ?"?
        // RF + colloquial_vector + Reranker ?
        // ?ColBERT ?MaxSim ?LLM Reranker ?
        // [根治] Q&A 注入无条件执行（�?INTENT-only），统一路径
        if (queryVector != null && !queryVector.isEmpty()) {
            List<Map<String, Object>> qaCandidates = fetchQaResults(
                    queryVector, queryText, forceSource, 3);

            // [Domain Filter v3] Q&A 结果二次筛选：Bigram 域内过滤 + 高分直�?
            // 原逻辑：纯 bigram 重叠过滤，对口语改写场景过于严格
            // 例："调解机构要多少钱" vs Q&A"需要多少钱�? �?bigram 重叠弱但语义高度相关
            // 新逻辑：KNN �?�?0.75 �?Q&A 命中直接接受（高置信度不�?bigram 校验）；
            // KNN �?< 0.75 的仍需 bigram 重叠，防止低相关性噪声注�?
            if (!qaCandidates.isEmpty()) {
                final java.util.Set<String> queryBigrams = new java.util.HashSet<>();
                for (int qi = 0; qi < queryText.length() - 1; qi++) {
                    queryBigrams.add(queryText.substring(qi, qi + 2));
                }
                qaCandidates = qaCandidates.stream()
                        .filter(c -> {
                            // KNN 高分（≥0.75）直通，语义置信度足够无需 bigram 校验
                            Object rrfObj = c.get("_rrf_score");
                            double qaRrf = (rrfObj instanceof Number) ? ((Number) rrfObj).doubleValue() : 0.0;
                            // RRF �?�?0.012 约等�?KNN top-3 命中（k=60, rank=1: 1/61�?.016�?
                            // 作为高置信度代理直通条件（实际 KNN cosine 无法在此直接获取�?
                            if (qaRrf >= 0.012)
                                return true;

                            // 低分 Q&A：需�?bigram 域内重叠校验
                            Object srcObj = c.get("_source");
                            if (!(srcObj instanceof Map))
                                return false;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> srcMap = (Map<String, Object>) srcObj;
                            Object contentObj = srcMap.get("content");
                            String ans = contentObj != null ? contentObj.toString() : "";
                            return queryBigrams.stream().anyMatch(bigram -> ans.contains(bigram));
                        })
                        .collect(java.util.stream.Collectors.toList());
                if (qaCandidates.isEmpty()) {
                    System.out.println(" [Q&A] ALL hits rejected by domain filter (bigram+rrf)");
                } else {
                    System.out.println(" [Q&A] " + qaCandidates.size() + " hits passed domain filter (bigram+rrf)");
                }
            }

            if (!qaCandidates.isEmpty()) {
                List<Map<String, Object>> merged = new ArrayList<>(qaCandidates);
                merged.addAll(candidates);
                candidates = merged;
                System.out.println(" [Q&A] Injected " + qaCandidates.size()
                        + " QA hits into candidates HEAD.");
            }
        }

        // [方案H 废除] colloquial_vector KNN 搜索死代码已移除
        // 根因：colloquial_vector 字段由后�?daemon 线程异步回填�?0%+ 的文档此字段为空�?
        // 每次检索都发出一次必然返回空结果�?ES KNN 请求（~100ms 无谓延迟）�?
        // 替代方案：同义词扩展（方案F/X1）在 BM25 层已覆盖口语化检索，无需独立向量通道�?
        System.out.println(" [Colloquial] Deprecated: replaced by synonym expansion (Plan F/X1)");

        // [?C] RRF AnchorPostFilter?
        // RRF KNN
        // KNN BM25 ?RRF ?
        // obAnchorTerms ?BM25 ?should ?
        // Reranker Veto olBERT/LLM?
        System.out.println(" [AnchorPostFilter] Skipped (first-principles fix): full RRF candidates = "
                + candidates.size() + " docs ?Reranker will decide.");

        // --- Adaptive Breaker ?---
        // [] ?topRawScore ?
        // 1. BM25?0~100) vs KNN(0~1) ?
        // 2. ?BreakerRawScoreThreshold=1.0 BM25 < 1.0?
        // 3. Reranker Vetoigmoid<0.005?
        boolean qualityBreakerActive = false;
        double topRrfScore = 0.0;

        if (candidates.isEmpty()) {
            qualityBreakerActive = true;
        } else {
            topRrfScore = (double) candidates.get(0).getOrDefault("_rrf_score", 0.0);
            // [Phrase Gate] ?5?
            // ?Reranker?
            // GPU Reranker (100-300ms) Reranker ?
            boolean phraseVerified = false;
            if (queryText != null && queryText.length() > 0 && queryText.length() <= 15) {
                try {
                    final String probeQuery = queryText;
                    SearchRequest phraseProbe = new SearchRequest.Builder()
                            .index(policy.getIndexPattern())
                            .size(1)
                            .timeout("50ms")
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.matchPhrase(mp -> mp
                                        .field("content")
                                        .query(probeQuery)
                                        .slop(1)));
                                b.filter(f -> f.bool(boolQuery -> boolQuery
                                        .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                        .should(s -> s.bool(bNot -> bNot
                                                .mustNot(mn -> mn.exists(e -> e.field("metadata.is_latest")))))));
                                if (forceSource != null && !forceSource.isEmpty()) {
                                    b.filter(f -> f.term(t -> t.field("metadata.data_source").value(forceSource)));
                                }
                                return b;
                            }))
                            .build();
                    SearchResponse<Object> probeRes = esClient.search(phraseProbe, Object.class);
                    phraseVerified = probeRes.hits().hits().size() > 0;
                } catch (Exception e) {
                    System.err.println("Phrase Probe failed, fallback to Rerank: " + e.getMessage());
                }
                if (phraseVerified) {
                    // [Fix] ColBERT finalScore=BM25??.15)<0.20OC?
                    // phrase probeColBERT""
                    System.out.println(
                            "[Phrase-Verified] Phrase probe HIT. ColBERT will still run for accurate scoring.");
                } else {
                    System.out.println("[Phrase Gate BLOCKED] Probe MISS. Forcing AI Rerank.");
                }
            } else {
                System.out.println("[Phrase Gate SKIPPED] Query too long ("
                        + (queryText != null ? queryText.length() : 0) + " chars), forcing Reranker.");
            }
        }

        System.out.println("  - Final Quality Breaker Active Status: " + qualityBreakerActive);

        // [DocExpansion] candidates �?RRF 分降序排列，确保分数合理�?sibling chunks 排在前面
        // 否则 sibling chunks 会排在末尾，�?rerankLimit 截断
        candidates.sort((a, b) -> {
            double scoreA = a.get("_rrf_score") instanceof Number ? ((Number) a.get("_rrf_score")).doubleValue() : 0.0;
            double scoreB = b.get("_rrf_score") instanceof Number ? ((Number) b.get("_rrf_score")).doubleValue() : 0.0;
            return Double.compare(scoreB, scoreA); // 降序
        });

        // ══════════════════════════════════════════════════════════════════════�?
        // [fine→coarse 上下文回溯] Small-to-Big Retrieval (小进大出)
        // ══════════════════════════════════════════════════════════════════════�?
        // 根因：系统已�?粗细两层"切好 chunk，细粒度 chunk 携带 parent_chunk_id 指向母块�?
        // 但检索命中细粒度 50 字条款时，直接把�?50 字丢�?LLM�?
        // 大模型只看到"基于本法第三条处�?，根本不知道"第三�?是什么，从而产生幻觉�?
        // 方案：在 candidates 组装前拦截，对所�?chunk_granularity="fine" 且有
        // parent_chunk_id 的条目，用一�?ES 批量查询拉取对应粗粒度母块原文，
        // 热替�?_source.content，确�?LLM 拿到完整上下文�?
        // 分数（_rrf_score）不变，保留细粒度命中的高精准匹配优势�?
        try {
            // Step1: 收集需要回溯的父块 ID（去重）
            java.util.Set<String> parentIdSet = new java.util.LinkedHashSet<>();
            for (Map<String, Object> cand : candidates) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                if (src != null && "fine".equals(src.get("chunk_granularity"))) {
                    String pid = (String) src.get("parent_chunk_id");
                    if (pid != null && !pid.isEmpty()) {
                        parentIdSet.add(pid);
                    }
                }
            }

            if (!parentIdSet.isEmpty()) {
                // Step2: 批量查询父块（一�?ES 请求，按 ID 精确获取�?
                List<String> pidList = new ArrayList<>(parentIdSet);
                SearchRequest parentReq = new SearchRequest.Builder()
                        .index(policy.getIndexPattern())
                        .size(pidList.size())
                        .query(q -> q.ids(i -> i.values(pidList)))
                        .build();
                SearchResponse<Object> parentResp = esClient.search(parentReq, Object.class);

                // Step3: 构建 ID→_source 的本地缓�?Map，避免多次网络往�?
                Map<String, Map<String, Object>> parentMap = new java.util.LinkedHashMap<>();
                for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : parentResp.hits().hits()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pSrc = (Map<String, Object>) hit.source();
                    if (pSrc != null) {
                        parentMap.put(hit.id(), pSrc);
                    }
                }

                // Step4: 热替�?content，保留高优分�?
                int replaced = 0;
                for (Map<String, Object> cand : candidates) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) cand.get("_source");
                    if (src == null)
                        continue;
                    if (!"fine".equals(src.get("chunk_granularity")))
                        continue;
                    String pid = (String) src.get("parent_chunk_id");
                    if (pid == null || !parentMap.containsKey(pid))
                        continue;

                    Map<String, Object> pSrc = parentMap.get(pid);
                    String parentText = (String) pSrc.get("content");
                    if (parentText != null && !parentText.isEmpty()) {
                        // 只替换内容，不动排序分数（保留细粒度命中召回精度优势�?
                        src.put("content", parentText);
                        // 同步更新 metadata.chunk_text（前端引用），确保展示层一�?
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                        if (meta != null) {
                            meta.put("chunk_text", parentText);
                        }
                        replaced++;
                    }
                }
                System.out.println("[fine→coarse] Fetched " + parentMap.size() + " parent chunks, replaced content of "
                        + replaced + " fine candidates.");
            }
        } catch (Exception ex) {
            // 回溯失败时安全降级，继续用原始细粒度内容，不影响主流�?
            System.err.println("[fine→coarse] Context roll-up failed, fallback to original: " + ex.getMessage());
        }
        // ══════════════════════════════════════════════════════════════════════�?

        // ══════════════════════════════════════════════════════════════════════�?
        // [New Veto Gates] Layered Veto: 在送入 GPU 之前剪枝纯噪�?
        // ══════════════════════════════════════════════════════════════════════�?
        int preVetoSize = candidates.size();
        candidates.removeIf(cand -> {
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));
            if (qaHit)
                return false;

            Object knnScObj = cand.get("_max_knn_score");
            double knnSim = (knnScObj instanceof Number) ? ((Number) knnScObj).doubleValue() : -1.0;

            Object rrfObj = cand.get("_rrf_score");
            double rrfScore = (rrfObj instanceof Number) ? ((Number) rrfObj).doubleValue() : 0.0;

            // 如果既没有语义向量相似度（纯靠BM25单字偶然命中，knnSim < 0.15）并且RRF倒数�? 0.005�?
            // 直接 Veto，节省宝贵的 Reranker 令牌与时�?
            return (knnSim >= 0 && knnSim < 0.15 && rrfScore < 0.005);
        });
        System.out.println("====== [Data Flow] Node 4.8: Layered Veto Gates ======");
        System.out.println("  [Veto Gates] Filtered out " + (preVetoSize - candidates.size())
                + " pure noise chunks before GPU logic.");

        List<String> docTexts = new ArrayList<>();
        // F7:
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> docMap = new HashMap<>();
            Map<String, Object> source = (Map<String, Object>) candidate.get("_source");
            if (source != null) {
                docMap.put("_source", source);
                docMap.put("_id", candidate.get("_id"));
                docMap.put("_rrf_score", candidate.get("_rrf_score"));
                docMap.put("_es_score", candidate.get("_es_score"));

                String rawContent = (String) source.get("content");
                docMap.put("highlight",
                        rawContent != null && rawContent.length() > 200 ? rawContent.substring(0, 200) + "..."
                                : rawContent);
                int maxChars = config.getRerankMaxChars() != null ? config.getRerankMaxChars() : 500;
                // [Fix 2] Prepend document filename so LLM Reranker can match title-query
                // similarity.
                // Without this, "??doc" looks like generic employment text to LLM.
                // With this, LLM sees "[Source: ??doc]" and correctly ranks it high.
                String docSource = "";
                Map<String, Object> srcMetaForRerank = (Map<String, Object>) source.get("metadata");
                if (srcMetaForRerank != null && srcMetaForRerank.get("source") != null) {
                    docSource = "[Source: " + srcMetaForRerank.get("source") + "] ";
                }
                String rerankText = docSource
                        + (rawContent != null && rawContent.length() > maxChars ? rawContent.substring(0, maxChars)
                                : (rawContent != null ? rawContent : ""));
                docTexts.add(rerankText);
            }
        }

        long t5 = System.currentTimeMillis();
        List<Double> rerankScores = null;

        // navigationalBypass 已在方法入口（Pre-Flight 结束后）声明，此处仅引用

        // [架构修正：动态打破密集向量假阳�?(Hubness)]
        // BGE-M3 向量在面对毫无关系的孤立短句时常产生虚假的底噪相似度(�?.53)�?
        // 如果因为 navigationalBypass(短查�? 跳过 Reranker，这部分底噪将直接祸害前端�?
        // 方案：提取本次召回队列中最高的 BM25 (es_score)，如果连 1.0 都没有（说明纯靠向量瞎猜），
        // 就必须强行唤醒大模型(Reranker) 重新进行判决�?
        boolean forceRerankForSemantics = false;
        if (navigationalBypass && !candidates.isEmpty()) {
            double highestBM25 = candidates.stream().mapToDouble(c -> {
                Object esScoreObj = c.get("_es_score");
                return (esScoreObj instanceof Number) ? ((Number) esScoreObj).doubleValue() : 0.0;
            }).max().orElse(0.0);

            if (highestBM25 < 1.0) {
                forceRerankForSemantics = true;
                System.out.println(
                        "⚠️[Semantic Fallback] 短查询未匹配到实体字�?Max ES: " + highestBM25 + ")，触发强�?Reranker 核验，刺破稠密向量假阳性！");
            }

            // [方案A] BM25 命中数量 > 50 �?查询词是高频泛义词，BM25 排序完全不可�?
            // 根因�?苹果"等词�?IDF 极低，所有含该词的文�?BM25 分数接近，无法区分语�?
            if (bm25HighFreqTerm) {
                forceRerankForSemantics = true;
                System.out.printf("⚠️[HighFreqTerm] BM25命中=%d > 50，强制Reranker消歧%n", textHits);
            }

            // [方案B] BM25 分数平坦（ratio < 1.3）→ 无明显赢家，歧义词特征，必须 Reranker 裁决
            // 注意：bm25IsFlat 未触发方案A时也可能独立触发（覆盖中频歧义词�?
            if (bm25IsFlat) {
                forceRerankForSemantics = true;
                System.out.printf("⚠️[BM25Flatness] ratio=%.2f < 1.3，强制Reranker消歧%n", bm25FlatnessRatio);
            }
        }

        // 综合判断是否执行 Reranker�?
        // 1. 不是 Q&A 强截�?(qualityBreakerActive=false)
        // 2. 不是纯粹字面短查询跳�?(!navigationalBypass)，或者触发了短词语义盲猜的兜底唤�?
        // (forceRerankForSemantics)
        boolean shouldRunReranker = !candidates.isEmpty()
                && !qualityBreakerActive
                && (!navigationalBypass || forceRerankForSemantics)
                && !Boolean.TRUE.equals(config.getCircuitBreakerEnabled())
                && !docTexts.isEmpty();

        // Quality Breaker
        if (shouldRunReranker) {
            // [Bug] ?rerankLimit ?GPU/CPU ?
            // rerankLimit class ?
            // GPU CPU 10?config 15?
            int rerankLimit = config.getRerankLimit() != null ? config.getRerankLimit() : 15;
            try {
                String acceleration = getCachedAcceleration();
                if (!"CUDA".equals(acceleration) && !"DML".equals(acceleration)) {
                    // [根治] CPU 模式 rerankLimit 强制�?3�?
                    // fine→coarse 回溯后每�?~400 字，10篇�?00�?4000�?× CPU 矩阵乘法 = 8s 不可接受�?
                    // Top 5 已覆盖所有有竞争关系的候选（其余�?RRF 拉开差距，Reranker 无额外价值）�?
                    rerankLimit = Math.min(rerankLimit, 5);
                    System.out.println("⚠️[RerankLimit] CPU mode, limit capped to " + rerankLimit + " docs.");
                } else {
                    // GPU/CUDA/DML 15 ?<200ms?
                    System.out.println(
                            " [RerankLimit] GPU mode (" + acceleration + "), rerankLimit=" + rerankLimit + " docs.");
                }
            } catch (Exception e) {
                rerankLimit = Math.min(rerankLimit, 10); //
            }

            // [根治修复] "GPU 令牌熔断�? 约束 Rerank 算力 (ColBERT Overload Limit)
            // 细粒度膨胀(fine->coarse)带来了高质量文档，但也导致原本单�?50 字膨胀到了 500 字�?
            // 设定全局 Token 字符容量，硬性阻�?ColBERT 显存/时延击穿�?
            final int MAX_GLOBAL_CHARS_BUDGET = 3500; // 确保 BGE-M3 前向计算时间限制�?< 1.0s
            final int maxRerankChars = 500;
            List<String> limitedDocs = new ArrayList<>();
            int currentTotalChars = 0;

            int effectiveLimit = Math.min(docTexts.size(), rerankLimit);
            for (int i = 0; i < effectiveLimit; i++) {
                String textOrig = docTexts.get(i);
                String docToInclude = (textOrig != null && textOrig.length() > maxRerankChars)
                        ? textOrig.substring(0, maxRerankChars)
                        : (textOrig != null ? textOrig : "");

                // 如果当前文档加上去会超过全局最大限制预�?
                if (currentTotalChars + docToInclude.length() > MAX_GLOBAL_CHARS_BUDGET) {
                    // 仅截取剩余可用配额字符（若配额太小则直接丢弃不加入）
                    int remainingQuota = Math.max(0, MAX_GLOBAL_CHARS_BUDGET - currentTotalChars);
                    if (remainingQuota > 50) { // 至少�?50 字供重排，否则直接放弃该�?
                        limitedDocs.add(docToInclude.substring(0, remainingQuota));
                        currentTotalChars += remainingQuota;
                    }
                    System.out.println("⚠️ [Rerank Token Budget] Reached global char limit: " +
                            MAX_GLOBAL_CHARS_BUDGET + ". Dropping tail documents (kept " + limitedDocs.size() + ").");
                    break;
                }

                limitedDocs.add(docToInclude);
                currentTotalChars += docToInclude.length();
            }

            // [?- LLM Reranker
            // wen2.5:7b ?CPU ?8-15s?
            // ?90%+ BM25+KNN
            // LLM ?HNSW ?
            // olBERT MaxSim?-3s?Reranker 25s 3-5s?
            final List<String> finalLimitedDocs = limitedDocs;

            // [H04 修复] 移除 ColBERT 随机 shuffle 逻辑�?
            // 根因：ColBERT MaxSim 计算每个 doc token �?query token 的最大余弦相似度�?
            // 本质上与文档在列表中的顺序无关（非自回归模型，无位置偏见）�?
            // 使用无种�?new Random() 导致同一 query 的结果不可复现，A/B 测试完全失效�?
            final List<String> docsToRerank = finalLimitedDocs;

            // [P0.5 ColBERT 背压 Semaphore]
            // GPU 实际串行推理，高并发时限制同时进�?ColBERT 的请求数（上�?4�?
            // tryAcquire 失败�?colbertScores 保持 null，后续使�?RRF 分数降级
            List<Double> colbertScores = null;
            if (COLBERT_SEMAPHORE.tryAcquire()) {
                try {
                    // [Hubness Fix] forceRerankForSemantics=true 时（短词�?BM25 命中，纯靠向量盲猜）
                    // 必须�?ColBERT 足够时间完成重排才能拒绝假阳性�?
                    // CPU 模式�?5 个文档约需 1-2s，原 800ms 必然超时，等于让防御逻辑空转�?
                    // 普通查询保�?800ms 快速降级，不影响正常延迟体验�?
                    long colbertTimeoutMs = forceRerankForSemantics ? 4000L : 800L;
                    List<Double> rawScores = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                        List<Double> scores = fetchColbertScores(queryText, docsToRerank);
                        if (scores != null)
                            return scores;
                        System.err.println(" [ColBERT] 除错，回退 BGE-Reranker");
                        return fetchRerankScores(queryText, docsToRerank);
                    }).get(colbertTimeoutMs, TimeUnit.MILLISECONDS);

                    colbertScores = rawScores;
                } catch (Exception e) {
                    System.err.println(" [ColBERT] Timeout/Error: " + e.getMessage());
                } finally {
                    COLBERT_SEMAPHORE.release(); // 必须�?finally 中释�?
                }
            } else {
                System.out.println(" [ColBERT Semaphore] 超出并发上限 " + COLBERT_SEMAPHORE_MAX + "，降级为 RRF 排序");
            }

            // [P2.12 集体 Veto 信号] ColBERT 打分后检查集体被 Veto 的比�?
            if (colbertScores != null && !colbertScores.isEmpty()) {
                long vetoCount = colbertScores.stream()
                        .filter(s -> s != null && s < config.getColbertVetoThreshold())
                        .count();
                double vetoRatio = (double) vetoCount / colbertScores.size();
                System.out.printf("  [Collective Veto] vetoRatio=%.2f (vetoCount=%d/%d)%n",
                        vetoRatio, vetoCount, colbertScores.size());
                // [Collective Veto Fix] 修复原逻辑误杀"局部相�?场景�?Bug�?
                // 原条件仅检�?vetoRatio > 0.70，当语料库只�?1 篇相关文档（�?月华"�?月光"）时�?
                // 月光doc ColBERT=0.72 (通过), 其余4�?0.05-0.15 (被淘�? �?vetoRatio=80% �?误杀
                // 根治：只有在"没有任何文档达到 veto_threshold（即没有赢家�?时才触发集体淘汰�?
                // hasWinner=true 说明语料库确有相关内容，此时即便大多数候选噪音，也不应返回空�?
                boolean hasWinner = colbertScores.stream()
                        .anyMatch(s -> s != null && s >= config.getColbertVetoThreshold());
                if (vetoRatio > 0.70 && colbertScores.size() >= 5 && !hasWinner) {
                    System.out.println(" [Collective Veto] 超过 70% 候選�?Veto 且无赢家，语料库不含相关内容，返回空结果");
                    return java.util.Collections.emptyList();
                }
                if (vetoRatio > 0.70 && colbertScores.size() >= 5 && hasWinner) {
                    System.out.println(" [Collective Veto] vetoRatio=" + String.format("%.2f", vetoRatio) +
                            " 但存在赢家（hasWinner=true），保留相关结果，不触发集体淘汰�?);
                }
            }

            if (colbertScores != null && !colbertScores.isEmpty()) {
                rerankScores = colbertScores;
                System.out.println(" [Rerank] ColBERT scores applied (" + rerankScores.size() + " docs)");
            } else {
                System.err.println(" [Rerank] ColBERT 未返回分数，降级�?RRF 排序");
            }

            System.out.println("====== [Data Flow] Node 5: AI Rerank ======");
            System.out.println("  - Documents Sent to Reranker: " + effectiveLimit);
            System.out.println("  - Reranker Scores Length: "
                    + (rerankScores != null ? rerankScores.size() : "0 (Failed or Skipped)"));
        }
        long rerankCost = System.currentTimeMillis() - t5;

        // --- ---
        System.out.println(
                String.format(" [Search Performance] Query: '%s', Embedding: %dms, ES: %dms, RRF: %dms, Rerank: %dms",
                        queryText, embeddingCost, esSearchCost, rrfCost, rerankCost));

        // --- 1. ---
        List<Map<String, Object>> scoredResults = new ArrayList<>();
        double esNormBase = config.getEsNormBase() != null ? config.getEsNormBase().doubleValue() : 20.0;
        // 0.0 0 ?
        if (esNormBase < 0.001) {
            System.out.println(" [NormBase Guard] esNormBase override triggered (current: " + esNormBase
                    + ") to 20.0, preventing score collapse.");
            esNormBase = 20.0;
        }

        // 90% ?+ 10% ES?
        // ?7B LLM / ColBERT
        // 废弃硬编码公�?fusionRatio = 0.9，改为采�?LTR 端点打分

        List<Map<String, Object>> ltrFeaturesList = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> cand = candidates.get(i);

            Object knnScoreObj = cand.get("_max_knn_score");
            double knnCosineSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : -1.0;

            Object scoreObj = cand.get("_es_score");
            double rawEsScore = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;

            double rrfScore = (double) cand.getOrDefault("_rrf_score", 0.0);
            double normalizedEsScore = rawEsScore / (rawEsScore + esNormBase);
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));

            Map<String, Object> features = new HashMap<>();
            features.put("qa_hit", qaHit);
            features.put("raw_es_score", rawEsScore);
            features.put("normalized_es_score", normalizedEsScore);
            features.put("knn_score", knnCosineSim);
            features.put("rrf_score", rrfScore);

            if (rerankScores != null && !rerankScores.isEmpty()) {
                if (i < rerankScores.size()) {
                    features.put("rerank_score", rerankScores.get(i));
                } else {
                    // [逻辑闭环修复] 如果全链路执行了大模型重排，但本文档因为达到预算截断（rerankLimit等）未能送测�?
                    // 必须给它赋予极低的败选分 (0.0)。如果不打负分，它在 Python 层就会被视为 "Global Fallback"�?
                    // 从而带着未经验证�?0.54 基础 KNN 向量噪声直达用户界面，成为“漏网之鱼”�?
                    features.put("rerank_score", 0.0);
                }
            }
            ltrFeaturesList.add(features);
        }

        // 调用 Python LTR API 获取所有候选最终特征排序分
        List<Double> ltrScores = fetchLtrScores(ltrFeaturesList);

        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> cand = candidates.get(i);
            Map<String, Object> source = (Map<String, Object>) cand.get("_source");

            double finalScore = (ltrScores != null && ltrScores.size() > i) ? ltrScores.get(i) : 0.0001;

            // 日志提取
            String diagDocName = "unknown";
            if (source != null) {
                Map<String, Object> diagMeta = (Map<String, Object>) source.get("metadata");
                if (diagMeta != null && diagMeta.get("source") != null) {
                    String fullName = diagMeta.get("source").toString();
                    diagDocName = fullName.length() > 30 ? "..." + fullName.substring(fullName.length() - 30)
                            : fullName;
                }
            }

            Object knnScoreObj = cand.get("_max_knn_score");
            double knnCosineSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : -1.0;
            Object scoreObj = cand.get("_es_score");
            double rawEsScore = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;
            double rrfScore = (double) cand.getOrDefault("_rrf_score", 0.0);

            // ── 向量相似度与 LTR 诊日�?──
            System.out.printf("  [VecSim] Doc%02d | cosine=%-6s | rrf=%.4f | esScore=%.2f | ltrScore=%.4f | '%s'%n",
                    i + 1,
                    knnCosineSim >= 0 ? String.format("%.4f", knnCosineSim) : " N/A  ",
                    rrfScore, rawEsScore, finalScore, diagDocName);

            String diagContent = source != null ? (String) source.getOrDefault("content", "") : "";
            String diagPreview = diagContent.length() > 100
                    ? diagContent.substring(0, 100).replace("\n", " ") + "�?
                    : diagContent.replace("\n", " ");
            System.out.printf("  [VecSim] Doc%02d | text: %s%n", i + 1, diagPreview);

            // 打印最终得分（含向量余弦）
            System.out.printf("  [VecSim] Doc%02d | finalScore=%.4f | '%s'%n",
                    i + 1, finalScore, diagDocName);

            Map<String, Object> docMap = new HashMap<>();
            docMap.put("_source", source);
            docMap.put("_id", cand.get("_id"));
            docMap.put("raw_score", finalScore); // ?

            // ES ?(?
            String snippetText = null;
            if (cand.containsKey("highlight") && cand.get("highlight") != null) {
                Map<String, List<String>> hlMap = (Map<String, List<String>>) cand.get("highlight");
                List<String> hlContent = hlMap.get("content");
                if (hlContent != null && !hlContent.isEmpty()) {
                    snippetText = String.join(" ... ", hlContent);
                    // 清除 content 中形�?[语篇语境: 乡道] 的上下文标注前缀，不应展示给用户
                    // 修复：原正则 \\[:.*?\\] 只匹配冒号紧贴左括号（如 [: x]），
                    // 新正�?\\[[^\\]]*:[^\\]]*\\] 正确匹配含前缀词的格式（如 [标签: 内容]�?
                    snippetText = snippetText.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
                }
            }

            // ES ? KNN )?
            if (snippetText == null || snippetText.isEmpty()) {
                String content = (String) source.getOrDefault("content", "");
                snippetText = generateFallbackSnippet(content, queryText, rewrittenQuery);
                // Java
                String highlightSource = (queryText + " " + (rewrittenQuery != null ? rewrittenQuery : "")).trim();
                snippetText = highlightText(snippetText, highlightSource);
            }

            docMap.put("chunk_text", snippetText);

            scoredResults.add(docMap);
        }

        System.out.println("====== [DEBUG] ?(+) ======");
        for (Map<String, Object> map : scoredResults) {
            Map<String, Object> src = (Map<String, Object>) map.get("_source");
            String docName = "unknown";
            if (src != null) {
                Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                if (meta != null && meta.get("source") != null) {
                    docName = meta.get("source").toString();
                } else if (src.get("content") != null) {
                    docName = src.get("content").toString().substring(0,
                            Math.min(20, src.get("content").toString().length())) + "...";
                }
            }
            Object rrfVal = map.get("_rrf_score");
            String rrfStr = rrfVal instanceof Number ? String.format("%.4f", ((Number) rrfVal).doubleValue()) : "null";
            System.out.println(String.format("  Doc: %s | raw_score: %.4f | rrf: %s",
                    docName, (Double) map.get("raw_score"), rrfStr));
        }

        // [] RRF ?contains ?
        // ?

        // ?
        scoredResults.sort((a, b) -> Double.compare((Double) b.get("raw_score"), (Double) a.get("raw_score")));

        // --- [1] Out-of-Corpus ---
        // ?
        // 0.35 0.55""2?top-1
        // ?0.55 ?.35 ?"?"?
        // ?? LLM Reranker ?Veto ?
        // [P0 #14 修复] Out-of-Corpus 自适应阈值：区分 reranker 是否运行
        // �?Bug：固�?0.20 是基�?ColBERT 分校准的，Reranker 未运行时（纯 RRF 分）
        // 全量弱相�?query 的最高分可能只有 0.13，被误截断返回空结果�?
        // 修复：Reranker 成功打分时用 outOfCorpusThreshold（默�?0.12）；
        // Reranker 失败/超时时用 outOfCorpusThresholdNoRerank（默�?0.08，更宽松）�?
        if (!scoredResults.isEmpty()) {
            double topScore = (Double) scoredResults.get(0).get("raw_score");
            boolean rerankerRan = (rerankScores != null && !rerankScores.isEmpty());
            double corpusThreshold = rerankerRan
                    ? config.getOutOfCorpusThreshold() // 默认 0.12（有 reranker 加持，分数更可信�?
                    : config.getOutOfCorpusThresholdNoRerank(); // 默认 0.08（纯 RRF 分，降低截断门槛�?
            System.out.println("  [Out-of-Corpus Check] top raw_score=" + String.format("%.4f", topScore)
                    + " threshold=" + corpusThreshold + " rerankerRan=" + rerankerRan);
            if (topScore < corpusThreshold) {
                System.out.println(" [Out-of-Corpus] Top score below threshold, returning empty.");
                return java.util.Collections.emptyList();
            }
        }

        // [Fix B] Quality Breaker 0.06 ?0.18
        // 1134 0.06 BM25 aw?.20?
        // "? ? 91%"? 9%/"" 7% ?
        // ?0.18 0.208 < 0.20 ?raw_score
        double topScoreForFilter = scoredResults.isEmpty() ? 0 : (Double) scoredResults.get(0).get("raw_score");
        // [PhaseQ Fix2-v2] Quality Breaker
        // &A Q&A
        // candidates.anyMatch ?
        final boolean topIsQaHit = !scoredResults.isEmpty()
                && Boolean.TRUE.equals(scoredResults.get(0).get("_qa_hit"))
                && (Double) scoredResults.get(0).getOrDefault("raw_score", 0.0) >= 0.85;
        final double relativeThreshold;
        if (topIsQaHit) {
            // Q&A 命中时严格过滤（topScore * 70%），确保 Q&A 结果具有压倒性优�?
            relativeThreshold = Math.max(topScoreForFilter * 0.70, 0.60);
            System.out.println("  [QB] Mode: Q&A-TOP strict (topScore*0.70)");
        } else {
            // [参数化根治] Quality Breaker 绝对下限从硬编码改为读取
            // sys_ai_tuning_config.quality_breaker_floor
            // 运营人员可通过管理界面直接调整此参数，无需重新发版�?
            // 默认 0.06：Out-of-Corpus 已过滤完全无关内容，此处只做相对清洗�?
            double qbFloor = config.getQualityBreakerFloor();
            relativeThreshold = Math.max(topScoreForFilter * 0.20, qbFloor);
            System.out.println("  [QB] Mode: Normal (topScore*0.20) [unified]");
        }

        int preSize = scoredResults.size();
        scoredResults.removeIf(docMap -> {
            Double raw = (Double) docMap.get("raw_score");
            // >= 0.99 ?" BM25 ?
            return raw != null && raw < relativeThreshold && raw < 0.99;
        });
        System.out.println("====== [Data Flow] Node 5.5: Quality Breaker ======");
        System.out.println(
                "  - Relative Threshold: " + String.format("%.4f", relativeThreshold) + " (topScore*0.20 or 0.06)");
        System.out.println("  - Removed Low Quality Docs: " + (preSize - scoredResults.size()));

        // --- ?---
        // ?Collapsing)?
        if (!scoredResults.isEmpty()) {
            for (int i = 0; i < scoredResults.size(); i++) {
                Map<String, Object> docMap = scoredResults.get(i);
                if (docMap.containsKey("raw_score")) {
                    double raw = (Double) docMap.get("raw_score");
                    double compressed = Math.pow(raw, 1.8);
                    double finalDisplayScore = Math.max(0.05, Math.min(0.99, compressed));
                    docMap.put("score", Math.round(finalDisplayScore * 10000.0) / 10000.0);
                    docMap.remove("raw_score");
                }
            }
        }

        // --- 2.5 ?(Result Collapsing) ---
        //
        Map<String, Map<String, Object>> fileAggregatedMap = new java.util.LinkedHashMap<>();
        for (Map<String, Object> docMap : scoredResults) {
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = (Map<String, Object>) source.get("metadata");

            String docIdBase = null;
            if (metaMap != null && metaMap.containsKey("doc_id")) {
                docIdBase = (String) metaMap.get("doc_id");
            } else {
                String esId = (String) docMap.get("_id");
                if (esId != null && esId.contains("_chunk_")) {
                    docIdBase = esId.substring(0, esId.lastIndexOf("_chunk_"));
                } else if (esId != null && esId.contains("_")) {
                    docIdBase = esId.split("_")[0];
                } else {
                    docIdBase = esId;
                }
            }

            if (docIdBase == null) {
                // doc_id?
                fileAggregatedMap.put(java.util.UUID.randomUUID().toString(), docMap);
                continue;
            }

            // ?docIdBase
            docMap.put("extracted_doc_id", docIdBase);
            String docId = docIdBase;

            if (!fileAggregatedMap.containsKey(docId)) {
                // 首次见到该文档，直接存入
                fileAggregatedMap.put(docId, docMap);
            } else {
                // [修复] 同一文档已存在更高分�?chunk，但需要优先�?含查询词"�?chunk�?
                // 原逻辑：直接忽略后�?chunk（KISS），导致 chunk_13（无关内容）遮蔽 chunk_8（目标内容）�?
                // 修复策略：若当前 chunk �?chunk_text 命中查询词，而已存储�?chunk 未命中，则替换�?
                Map<String, Object> existingMap = fileAggregatedMap.get(docId);
                String existingChunkText = (String) existingMap.getOrDefault("chunk_text", "");
                String currentChunkText = (String) docMap.getOrDefault("chunk_text", "");
                boolean existingHits = existingChunkText != null &&
                        (existingChunkText.contains(queryText) || existingChunkText.contains(normalizedQuery));
                boolean currentHits = currentChunkText != null &&
                        (currentChunkText.contains(queryText) || currentChunkText.contains(normalizedQuery));
                // 只有当前 chunk 命中查询词、而已存的未命中时，才用当�?chunk 替换（分数保持原有高分）
                if (currentHits && !existingHits) {
                    // 内容替换，但保留更高的分数以保证文档排名不降�?
                    double existingScore = existingMap.containsKey("score") ? (Double) existingMap.get("score") : 0.0;
                    double currentScore = docMap.containsKey("score") ? (Double) docMap.get("score") : 0.0;
                    docMap.put("score", Math.max(existingScore, currentScore)); // 保留较高�?
                    fileAggregatedMap.put(docId, docMap);
                    System.out.println("[Collapsing] 用含查询词的 chunk 替换: docId=" + docId);
                }
                // 否则保持原有（高分）chunk 不变
            }
        }

        //
        List<Map<String, Object>> collapsedResults = new ArrayList<>(fileAggregatedMap.values());
        collapsedResults.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));

        // [根因B修复] �?metadata.source（文件名）二次去�?
        // 根因：版本化 doc_id（{hash}_v1 vs {hash}_v2）使现有 docId 折叠无效�?
        // 同一文件的两个版本被视为不同文档，各占一个结果位置�?
        // 修复：以 source（文件名）为 key 再做一�?LinkedHashMap 去重�?
        // 保留分数最高的版本（results 已按 score 降序排列）�?
        Map<String, Map<String, Object>> sourceDeduped = new java.util.LinkedHashMap<>();
        for (Map<String, Object> dm : collapsedResults) {
            Map<String, Object> dmSrc = (Map<String, Object>) dm.get("_source");
            Map<String, Object> dmMeta = dmSrc != null ? (Map<String, Object>) dmSrc.get("metadata") : null;
            String srcName = dmMeta != null ? (String) dmMeta.get("source") : null;
            String dedupeKey = srcName != null ? srcName : java.util.UUID.randomUUID().toString();
            // LinkedHashMap 保持首次插入顺序 = 分数最高的版本
            sourceDeduped.putIfAbsent(dedupeKey, dm);
        }
        collapsedResults = new ArrayList<>(sourceDeduped.values());
        int removedBySource = fileAggregatedMap.size() - collapsedResults.size();
        if (removedBySource > 0) {
            System.out.println("[Source Dedup] 去除重复版本 " + removedBySource + " 条（同文件多版本 is_latest 双真防御层）");
        }

        // topK
        int finalSize = Math.min(collapsedResults.size(), topK);
        scoredResults = collapsedResults.subList(0, finalSize);

        System.out.println("====== [Data Flow] Node 6: Result Collapsing / Dedup ======");
        System.out.println("  - Filtered Sub-Docs Size (Target TopK=" + topK + "): " + scoredResults.size());

        // --- 3. 批量取上下文（N+1 优化�?--
        List<String> docIds = new ArrayList<>();
        List<Integer> chunkIndices = new ArrayList<>();
        for (Map<String, Object> docMap : scoredResults) {
            Map<String, Object> metaMap = (Map<String, Object>) ((Map<String, Object>) docMap.get("_source"))
                    .get("metadata");
            // [修复] 无论 metaMap 是否�?null，都必须 add，保证与 scoredResults 严格下标对齐
            // 根因：原代码仅在 metaMap!=null �?add，导�?chunkIndices.size() < scoredResults.size()
            // 后续 chunkIndices.get(i) �?i 取值时越界 (Index: 5, Size: 5)
            if (metaMap != null) {
                docIds.add((String) metaMap.get("doc_id"));
                Object cIdxObj = metaMap.get("chunk_idx");
                chunkIndices.add((cIdxObj instanceof Number) ? ((Number) cIdxObj).intValue() : 0);
            } else {
                docIds.add(null); // 占位，保持下标对�?
                chunkIndices.add(0); // 占位，默�?chunk 0
            }
        }
        Map<String, String> contextMap = fetchBatchContextTexts(docIds, chunkIndices, policy.getIndexPattern());

        // --- 4. ?---
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < scoredResults.size(); i++) {
            Map<String, Object> docMap = scoredResults.get(i);
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = (Map<String, Object>) source.get("metadata");

            if (metaMap != null) {
                String docId = (String) docMap.getOrDefault("extracted_doc_id", metaMap.get("doc_id"));
                Integer chunkIdx = chunkIndices.get(i);
                docMap.put("context_full", contextMap.getOrDefault(docId + "_" + chunkIdx, ""));
                docMap.put("organization", metaMap.getOrDefault("source", defaultOrg));
                // ?ES mapping ublish_time issue_date?
                Object publishTime = metaMap.get("publish_time");
                docMap.put("publish_time", publishTime != null ? publishTime : defaultDate);
                // tags?
                docMap.put("tags", metaMap.getOrDefault("tags", null)); //
                docMap.put("custom_tags", metaMap.getOrDefault("custom_keywords", null));
                docMap.put("doc_id", docId);
            }
            docMap.remove("_source");
            docMap.remove("extracted_doc_id");
            results.add(docMap);
        }

        long totalCost = System.currentTimeMillis() - startTime;
        recordAuditLog(appCode, queryText, normalizedQuery, results.size(), (int) embeddingCost, (int) esSearchCost,
                (int) rerankCost, (int) totalCost);
        System.out.println(String.format(" Search Stats | Total: %dms | Embed: %dms | ES: %dms | Rerank: %dms",
                totalCost, embeddingCost, esSearchCost, rerankCost));
        return results;
    }

    private void recordAuditLog(String appCode, String query, String normQuery, int hits, int embedCost, int esCost,
            int rerankCost, int totalCost) {
        com.boyang.search.entity.SearchAuditLog log = new com.boyang.search.entity.SearchAuditLog();
        log.setAppCode(appCode);
        log.setQueryText(query);
        log.setNormalizedQuery(normQuery);
        log.setTopHitsCount(hits);
        log.setEmbeddingCostMs(embedCost);
        log.setEsCostMs(esCost);
        log.setRerankCostMs(rerankCost);
        log.setTotalCostMs(totalCost);
        log.setCreateTime(java.time.LocalDateTime.now());
        auditLogService.saveAsync(log);
    }

    // Removed getBestSentence

    // Removed splitIntoSentences

    // ?
    private String generateFallbackSnippet(String content, String queryText, String rewrittenQuery) {
        if (content == null || content.isEmpty())
            return "";
        // OOM guard: truncate before replaceAll to prevent StringBuffer overflow on
        // huge docs
        // replaceAll constructs a StringBuffer of same size as input; unbounded content
        // = OOM
        final int MAX_CONTENT_FOR_SNIPPET = 2000;
        String safeContent = content.length() > MAX_CONTENT_FOR_SNIPPET
                ? content.substring(0, MAX_CONTENT_FOR_SNIPPET)
                : content;
        // 清除形如 [语篇语境: 乡道] 的上下文标注前缀（原正则 \\[:.*?\\] 无法匹配此格式）
        String cleanContent = safeContent.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
        if (queryText == null || queryText.isEmpty())
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));

        String targetKw = queryText;
        int idx = cleanContent.indexOf(targetKw);

        // 1.
        if (idx == -1 && rewrittenQuery != null && !rewrittenQuery.isEmpty() && rewrittenQuery.length() > 1) {
            targetKw = rewrittenQuery;
            idx = cleanContent.indexOf(targetKw);
        }

        // 2. fallback
        if (idx == -1) {
            String dryQuery = queryText;
            java.util.List<String> _dryNoiseList = tuningConfigService.getGlobalConfig().getNoiseWordList();
            for (String noise : _dryNoiseList) {
                // OOM guard: empty string replace exponentially inflates the input string
                if (noise == null || noise.isEmpty())
                    continue;
                dryQuery = dryQuery.replace(noise, " ");
            }
            String[] frags = dryQuery.split("\\s+");
            targetKw = null;
            for (String f : frags) {
                // 2
                if (f.trim().length() >= 2) {
                    idx = cleanContent.indexOf(f.trim());
                    if (idx != -1) {
                        targetKw = f.trim();
                        break;
                    }
                }
            }
        }

        // 3. ?
        if (idx == -1) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }

        //
        int start = Math.max(0, idx - 40);
        int end = Math.min(cleanContent.length(), idx + targetKw.length() + 60);
        String snippet = cleanContent.substring(start, end);

        if (start > 0)
            snippet = "..." + snippet;
        if (end < cleanContent.length())
            snippet = snippet + "...";

        return snippet;
    }

    // Removed fetchBatchSentenceScores

    private Map<String, String> fetchBatchContextTexts(List<String> docIds, List<Integer> chunkIndices,
            String indexName) {
        Map<String, String> resultMap = new HashMap<>();
        if (docIds.isEmpty())
            return resultMap;

        try {
            // ES ?bool query ?doc_id
            List<co.elastic.clients.elasticsearch._types.FieldValue> fieldValues = new java.util.ArrayList<>();
            for (String id : docIds) {
                if (id != null)
                    fieldValues.add(co.elastic.clients.elasticsearch._types.FieldValue.of(id));
            }
            SearchRequest batchRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.terms(t -> t.field("metadata.doc_id").terms(v -> v.value(fieldValues))))))
                    .size(docIds.size() * 3) // ?
                    .build();

            SearchResponse<Object> resp = esClient.search(batchRequest, Object.class);
            Map<String, List<String>> chunksByDoc = new HashMap<>();

            for (Hit<Object> hit : resp.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src != null) {
                    Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                    String dId = (String) meta.get("doc_id");
                    // [H07 修复] chunk_idx 可能�?null（存量非版本化文档），强�?(Number)null �?NPE
                    // 被整�?catch 块吞噬后 context_full 静默返回空字符串，修复为安全转换
                    Object cIdxRaw = meta.get("chunk_idx");
                    int cIdx = (cIdxRaw instanceof Number) ? ((Number) cIdxRaw).intValue() : 0;
                    String key = dId + "_" + cIdx;
                    resultMap.put(key, (String) src.get("content"));
                }
            }

            // ?fragments ?context (?
            // ID
            Map<String, String> finalContexts = new HashMap<>();
            for (int i = 0; i < docIds.size(); i++) {
                String dId = docIds.get(i);
                int cIdx = chunkIndices.get(i);
                String full = "";
                for (int offset = -1; offset <= 1; offset++) {
                    String part = resultMap.get(dId + "_" + (cIdx + offset));
                    if (part != null)
                        full += (full.isEmpty() ? "" : "\n---\n") + part;
                }
                finalContexts.put(dId + "_" + cIdx, full);
            }
            return finalContexts;
        } catch (Exception e) {
            System.err.println("?Batch Context Fetch Failed: " + e.getMessage());
            return resultMap;
        }
    }

    private String rewriteQueryByIntent(String query) {
        if (query == null || query.trim().isEmpty())
            return "";

        // [] query LLM?

        // 1. ???
        if (query.trim().length() <= 8) {
            System.out.println("[LLM Rewrite SKIP] Short precise query: " + query);
            return query;
        }

        // 2. X?
        if (query.matches(".*[[0-9]+[.*")
                || query.contains("") || query.contains("")
                || query.contains("") || query.contains("")) {
            System.out.println("[LLM Rewrite SKIP] Legal/metadata reference: " + query);
            return query;
        }

        // 3. ?? ? ?
        // ? ??
        String[] tokens = query.trim().split("\\s+");
        if (tokens.length >= 4 && (double) query.replace(" ", "").length() / tokens.length <= 3.5) {
            System.out.println("[LLM Rewrite SKIP] Keyword-list query (" + tokens.length + " terms): " + query);
            return query;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/intent/rewrite";
            String respJson = restTemplate.postForObject(url, payload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);

            if (respMap != null && ((Integer) respMap.get("code")) == 200) {
                String rewritten = (String) respMap.get("data");
                if (rewritten == null || rewritten.trim().isEmpty()) {
                    return query;
                }
                // [] LM
                if (rewritten.trim().length() <= 3) {
                    System.out.println("[LLM Rewrite FALLBACK] Result too short '" + rewritten + "', using original.");
                    return query;
                }
                // [] ? 40%?
                // ??4/14=28%) ?
                String cleanOrig = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
                String cleanNew = rewritten.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
                if (!cleanOrig.isEmpty() && (double) cleanNew.length() / cleanOrig.length() < 0.4) {
                    System.out.println("[LLM Rewrite FALLBACK] Over-compressed: '" + rewritten
                            + "' (" + cleanNew.length() + "/" + cleanOrig.length() + " chars < 40%), using original.");
                    return query;
                }
                return rewritten;
            }
        } catch (Exception e) {
            System.err.println("?Remote Intent Rewrite Failed: " + e.getMessage());
        }
        return query; //
    }

    // Removed filterRelevantSentences

    private List<Double> fetchRerankScores(String query, List<String> documents) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", query);
            payload.put("documents", documents);
            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/rerank";
            String respJson = restTemplate.postForObject(url, payload, String.class);
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);
            if (respMap != null && ((Integer) respMap.get("code")) == 200) {
                Map<String, Object> data = (Map<String, Object>) respMap.get("data");
                return (List<Double>) data.get("scores");
            }
        } catch (Exception e) {
            System.err.print("AI : " + e.getMessage());
        }
        return null;
    }

    private String expandSynonyms(String query, String pinyin) {
        if (query == null)
            return "";
        StringBuilder sb = new StringBuilder();

        // ( ES ^)
        sb.append(query).append("^2.5 ");

        if (pinyin != null && !pinyin.isEmpty()) {
            sb.append(pinyin).append("^0.8 ");
        }

        return sb.toString().trim();
    }

    /**
     * 业务功能：将 12 位行政区划编码拆解为各层级祖先路径列表，用于 ES terms 权限过滤�?
     * 原理：用户在部门 X �?拆解 X 的所有上级前缀 �?terms 匹配文档�?dept_code_full
     * 含义：用户可见其所在部门及所有上级部门创建的文档（上级文档下级可见）�?
     * 示例：deptCode="620102900000" �?
     * ["62","6201","620102","620102900","620102900000"]
     */
    private List<String> buildDeptAncestorPaths(String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty())
            return java.util.Collections.emptyList();
        String code = deptCode.trim();
        List<String> paths = new ArrayList<>();
        // GB/T 2260 各级截取位数�?(�?�?(�?�?(区县)�?(乡镇)�?2(机构)
        for (int lvl : new int[] { 2, 4, 6, 9, 12 }) {
            if (code.length() >= lvl)
                paths.add(code.substring(0, lvl));
        }
        if (!paths.contains(code))
            paths.add(code);
        return paths;
    }

    private String extractDigits(String text) {
        if (text == null)
            return "";
        return text.replaceAll("[^0-9]", " ").trim();
    }

    private String highlightText(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty())
            return text;
        // OOM guard: highlightText calls replaceAll N times (one per keyword).
        // Each replaceAll allocates a StringBuffer of the same size as input.
        // Unbounded text length => exponential memory amplification => OOM.
        final int MAX_HIGHLIGHT_LEN = 1500;
        String safeText = text.length() > MAX_HIGHLIGHT_LEN ? text.substring(0, MAX_HIGHLIGHT_LEN) : text;
        //
        java.util.Set<String> kwSet = new java.util.HashSet<>(java.util.Arrays.asList(query.split("\\s+")));
        List<String> sortedKws = new ArrayList<>(kwSet);
        sortedKws.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String result = safeText;
        for (String kw : sortedKws) {
            if (kw.trim().length() < 1)
                continue;
            try {
                String pattern = "(?i)(" + Pattern.quote(kw) + ")";
                result = result.replaceAll(pattern, "<em class='highlight'>$1</em>");
            } catch (Exception e) {
                //
            }
        }
        return result;
    }

    /**
     * RRF (Weighted Reciprocal Rank Fusion)
     * : score = w1 * (1 / (rank1 + k)) + w2 * (1 / (rank2 + k))
     */
    private List<Map<String, Object>> rrfMerge(
            SearchResponse<Object> textResp,
            SearchResponse<Object> knnResp,
            SearchResponse<Object> knnFineResp,
            int topK,
            SysAiTuningConfig config,
            boolean navigational) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Map<String, Object>> docRegistry = new HashMap<>();
        int k = config.getRrfK(); // RRF 平滑因子（来�?DB，默�?60�?

        // [方案J] 信号强度自适应动态权重：取代人工设定�?0.70/0.30 固定�?
        // 原理：BM25 最高分体现词汇重叠质量（归一化到[0,1]，以10分为满信号）
        // KNN 余弦相似度天然处于[0,1]，两者直接可�?
        // 查询意图分流：NAVIGATIONAL（精确关键词）→ BM25 主导�?
        // INFORMATIONAL（语义问句）�?按实际信号强度动态分�?
        double bm25RawMax = 0.0;
        if (textResp != null && !textResp.hits().hits().isEmpty()
                && textResp.hits().hits().get(0).score() != null) {
            bm25RawMax = textResp.hits().hits().get(0).score();
        }
        double knnRawMax = 0.0;
        if (knnResp != null && !knnResp.hits().hits().isEmpty()
                && knnResp.hits().hits().get(0).score() != null) {
            knnRawMax = knnResp.hits().hits().get(0).score();
        }
        // �?sys_ai_tuning_config 获取管理员配置的基准权重（可通过管理界面动态调整）
        double configBm25 = config.getBm25Weight() != null ? config.getBm25Weight().doubleValue() : 1.0;
        double configKnn = config.getVectorWeight() != null ? config.getVectorWeight().doubleValue() : 1.0;

        double wText, wKnn;
        if (navigational) {
            // NAVIGATIONAL：BM25 权重加大，保持原有精确匹配优�?
            wText = Math.max(configBm25, 0.70);
            wKnn = Math.min(configKnn, 0.30);
        } else {
            // INFORMATIONAL：按实际信号强度自适应（方案J核心�?
            double bm25Confidence = Math.min(bm25RawMax / 10.0, 1.0); // 10分为满信�?
            double knnConfidence = knnRawMax; // 余弦相似度已在[0,1]
            double total = bm25Confidence + knnConfidence;
            if (total < 0.2) {
                // 两者都弱（词汇鸿沟+语义漂移）：KNN 稍微主导（泛化能力更强）
                wText = configBm25 * 0.4;
                wKnn = configKnn * 0.6;
            } else {
                // 按比例分配，权重下界 0.2 防止任一通道完全失权
                wText = configBm25 * (bm25Confidence / total * 0.6 + 0.2);
                wKnn = configKnn * (knnConfidence / total * 0.6 + 0.2);
            }
        }
        System.out.printf("[RRF] intent=%s | wBM25=%.2f wKNN=%.2f%n",
                navigational ? "NAVIGATIONAL" : "INFORMATIONAL", wText, wKnn);

        // ?(BM25)
        if (textResp != null) {
            int rank = 1;
            for (Hit<Object> hit : textResp.hits().hits()) {
                String id = hit.id();
                double score = wText * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                docRegistry.put(id, convertHitToMap(hit));
                rank++;
            }
        }

        // ?KNN chunk?
        final double knnMinSimilarity = config.getKnnMinSim(); // 来自 DB，默�?0.15
        if (knnResp != null) {
            int rank = 1;
            for (Hit<Object> hit : knnResp.hits().hits()) {
                double knnScore = hit.score() != null ? hit.score() : 0.0;
                if (knnScore < knnMinSimilarity) {
                    continue;
                }
                String id = hit.id();
                double score = wKnn * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docRegistry.containsKey(id)) {
                    Map<String, Object> map = convertHitToMap(hit);
                    map.put("_max_knn_score", knnScore);
                    docRegistry.put(id, map);
                } else {
                    Map<String, Object> map = docRegistry.get(id);
                    double ex = (Double) map.getOrDefault("_max_knn_score", 0.0);
                    map.put("_max_knn_score", Math.max(ex, knnScore));
                }
                rank++;
            }
        }

        // [ ?KNN chunk
        // fine coarse Knn RRF
        if (knnFineResp != null) {
            int rank = 1;
            for (Hit<Object> hit : knnFineResp.hits().hits()) {
                double knnScore = hit.score() != null ? hit.score() : 0.0;
                if (knnScore < knnMinSimilarity) {
                    continue;
                }
                String id = hit.id();
                double score = wKnn * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docRegistry.containsKey(id)) {
                    Map<String, Object> map = convertHitToMap(hit);
                    map.put("_max_knn_score", knnScore);
                    docRegistry.put(id, map);
                } else {
                    Map<String, Object> map = docRegistry.get(id);
                    double ex = (Double) map.getOrDefault("_max_knn_score", 0.0);
                    map.put("_max_knn_score", Math.max(ex, knnScore));
                }
                rank++;
            }
        }

        // [Sparse 通道] 稀疏向量热性检索结果并�?RRF
        // wSparse �?wKnn �?30%：稀疏向量强化精确词汇信号，不充当主导信�?
        // 正确行为：假�?bm25+knn 都命中了一篇文档，sparse 馉加分；假设 bm25弱信�?knn 词汇骨隆，sparse 能颜外拨出词汇匹配文�?
        double wSparse = wKnn * 0.30;
        if (knnFineResp != null) { // 此处参数已‚语义化‛为 sparseResp
            int rank = 1;
            for (Hit<Object> hit : knnFineResp.hits().hits()) {
                double sparseScore = hit.score() != null ? hit.score() : 0.0;
                // sparse 分数过低�?0.1）说�?rank_features 没有实质命中，跳�?
                if (sparseScore < 0.1)
                    continue;
                String id = hit.id();
                double score = wSparse * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docRegistry.containsKey(id)) {
                    docRegistry.put(id, convertHitToMap(hit));
                }
                rank++;
            }
            System.out.printf("[RRF] wSparse=%.3f | sparseHits=%d%n", wSparse, rank - 1);
        }

        // RRF
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(entry -> {
                    Map<String, Object> doc = docRegistry.get(entry.getKey());
                    doc.put("_rrf_score", entry.getValue());
                    return doc;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> convertHitToMap(Hit<Object> hit) {
        Map<String, Object> map = new HashMap<>();
        map.put("_id", hit.id());
        map.put("_score", hit.score());
        map.put("_es_score", hit.score()); // ?NPE
        map.put("_source", hit.source());
        if (hit.highlight() != null) {
            map.put("highlight", hit.highlight());
        }
        return map;
    }

    /**
     * ?ES ?
     */
    public boolean reloadSearchAnalyzers(String indexName) {
        try {
            co.elastic.clients.elasticsearch.indices.ReloadSearchAnalyzersRequest request = new co.elastic.clients.elasticsearch.indices.ReloadSearchAnalyzersRequest.Builder()
                    .index(indexName)
                    .build();
            esClient.indices().reloadSearchAnalyzers(request);
            System.out.println("?[ES Admin] Successfully reloaded search analyzers for index: " + indexName);
            return true;
        } catch (Exception e) {
            System.err.println("?[ES Admin] Failed to reload search analyzers: " + e.getMessage());
            return false;
        }
    }
    // [硬编码治理] GOV_NOISE_WORDS 静态数组已迁移�?sys_ai_tuning_config.noise_words（DB + Redis
    // 热刷新）
    // 通过 config.getNoiseWordList() 读取，内容在管理界面实时修改无需重启
    // 注意：「要求」「通知」「规定」等实质性政务词不能放入噪词表，否则会破坏锚词提�?

    /**
     * Java ES
     * ?
     */
    private List<String> extractCoreTerms(String query, String indexName) {
        List<String> coreTerms = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return coreTerms;
        }

        // [短词根治] 2~4 字符的短查询（如"月华"�?审批"�?环评"）：
        // 整词本身就是语义核心，直接注入为唯一 anchor，激�?boost(5x/20x/30x) 子句�?
        // 原代�?length <= 4 直接返回空，导致所�?BM25 anchor boost 失效�?
        // BM25 最高分 < 2.0，触�?LexGap 压制 �?ColBERT 候选全部被 Veto �?返回空�?
        String trimmed = query.trim();
        if (trimmed.length() >= 2 && trimmed.length() <= 4) {
            coreTerms.add(trimmed);
            System.out.println(
                    "[Core Term Anchor] Short query injected as self-anchor: [" + trimmed + "] from: " + query);
            return coreTerms;
        }
        // Step 1: 过滤噪词（来�?DB 热配置，无需重启�?
        String remaining = query;
        SysAiTuningConfig _cfg = tuningConfigService.getGlobalConfig();
        java.util.List<String> _noiseList = _cfg.getNoiseWordList();
        for (String noise : _noiseList) {
            // OOM guard: empty string replace exponentially inflates the input string
            if (noise == null || noise.isEmpty())
                continue;
            remaining = remaining.replace(noise, " ");
        }
        // Step 2: ?
        remaining = remaining.replaceAll("[^\\u4e00-\\u9fa5]+", " ").trim();
        String[] fragments = remaining.split("\\s+");
        for (String frag : fragments) {
            if (frag.length() >= 2 && !coreTerms.contains(frag)) {
                coreTerms.add(frag);
                if (coreTerms.size() >= 3)
                    break;
            }
        }
        if (!coreTerms.isEmpty()) {
            System.out.println("[Core Term Anchor] Extracted: " + coreTerms + " from: " + query);
        } else {
            System.out.println("[Core Term Anchor] All noise, skip anchor for: " + query);
        }
        return coreTerms;
    }

    /**
     * Q&A b_qa_pairs?
     * LM fine chunk 2-3 ?
     * BGE-M3 question_vector ?
     * query >= QA_SIMILARITY_THRESHOLD
     * answer_content?
     * ueryVector -> KNN on kb_qa_pairs.question_vector
     * -> ?< 0.75
     * -> rrfMerge ?Map _qa_hit=true ?
     * _source{content, metadata}qa_hit=true?
     * ?hybridSearch ?candidates ?
     * ?953 _qa_hit inalScore = 0.95?
     *
     * @param queryVector query ?BGE-M3 Dense
     * @param queryText   ?
     * @param forceSource null?
     * @param limit       ?QA ?3?
     */
    private List<Map<String, Object>> fetchQaResults(
            List<Double> queryVector, String queryText,
            String forceSource, int limit) {

        // QA 语义准入阈值（来自 DB 热配置，默认 0.82�?
        final double QA_SIMILARITY_THRESHOLD = tuningConfigService.getGlobalConfig().getQaSimThreshold();

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            final List<Double> finalVec = queryVector;
            SearchRequest qaRequest = new SearchRequest.Builder()
                    .index("kb_qa_pairs")
                    .knn(k -> k.field("question_vector")
                            .queryVector(finalVec)
                            .k(limit * 2) // top-k ?
                            .numCandidates(tuningConfigService.getGlobalConfig().getQaKnnCandidates()) // 来自 DB，默�?50
                            .filter(f -> f.term(t -> t.field("is_latest").value(true))))
                    .size(limit * 2)
                    .build();

            SearchResponse<Object> qaResp = esClient.search(qaRequest, Object.class);
            System.out.println("====== [Data Flow] Node 4.5: Q&A Index Search ======");
            System.out.println("  - Q&A KNN Hits: " + qaResp.hits().hits().size());

            for (Hit<Object> hit : qaResp.hits().hits()) {
                // 0.75
                double sim = hit.score() != null ? hit.score() : 0.0;
                if (sim < QA_SIMILARITY_THRESHOLD) {
                    System.out.printf("  [Q&A Filter] Skipped (sim=%.3f < %.2f threshold)%n",
                            sim, QA_SIMILARITY_THRESHOLD);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> qaSource = (Map<String, Object>) hit.source();
                if (qaSource == null)
                    continue;

                String answerContent = (String) qaSource.getOrDefault("answer_content", "");
                String docSource = (String) qaSource.getOrDefault("source", "");
                String question = (String) qaSource.getOrDefault("question", "");

                // forceSource &A
                if (forceSource != null && !forceSource.isEmpty()
                        && !forceSource.equals(qaSource.getOrDefault("data_source", ""))) {
                    // data_source ?kb_qa_pairs source
                    // Q&A ?data_source ?
                }

                // rrfMerge ?_source
                Map<String, Object> syntheticMeta = new HashMap<>();
                syntheticMeta.put("source", docSource);
                syntheticMeta.put("is_latest", true);
                syntheticMeta.put("data_source", "document");

                Map<String, Object> syntheticSource = new HashMap<>();
                syntheticSource.put("content", answerContent);
                syntheticSource.put("metadata", syntheticMeta);

                // ?answer_chunk_id ID?Result Collapsing
                String chunkId = (String) qaSource.getOrDefault("answer_chunk_id", hit.id());

                Map<String, Object> candMap = new HashMap<>();
                candMap.put("_id", chunkId);
                candMap.put("_source", syntheticSource);
                candMap.put("_es_score", sim);
                candMap.put("_rrf_score", sim);
                candMap.put("_qa_hit", true); // hybridSearch ?953 0.95

                // question
                String snippet = generateFallbackSnippet(answerContent, queryText, question);
                candMap.put("chunk_text", highlightText(snippet,
                        (queryText + " " + question).trim()));

                results.add(candMap);
                System.out.printf("  [Q&A Hit] sim=%.3f | Q: '%s' | Ans: '%s...'%n",
                        sim,
                        question.length() > 35 ? question.substring(0, 35) : question,
                        answerContent.length() > 50 ? answerContent.substring(0, 50) : answerContent);

                if (results.size() >= limit)
                    break;
            }
        } catch (Exception e) {
            // Q&A BM25+KNN
            System.err.println(" [Q&A] fetchQaResults failed (degrading gracefully): "
                    + e.getMessage());
        }
        System.out.println("  - Q&A Injected Count: " + results.size());
        return results;
    }

    /**
     * ?/ colloquial_vector ?
     * ?
     * 1. ?20
     * 2. ?Q&A ?
     * 3. //?? FastPath?
     * 
     *
     * @param query
     * @return true = /?colloquial_vector KNN
     */

    // [根治] QueryType SLOGAN/INTENT 路由已彻底废�?
    // �?classifyQuery(), isSloganQuery(), enum QueryType 已删�?
    // 所有查询统一�?INTENT 路径，Q&A �?Colloquial 作为候选补�?
    // 最终由 ColBERT MaxSim 决定文档相关性，无需启发式规则分�?

    private List<Map<String, Object>> fetchColloquialResults(
            List<Double> queryVector, String queryText,
            String indexPattern, String forceSource, int limit) {

        // 口语化向量准入阈值（来自 DB 热配置，默认 0.78�?
        final double COLLOQUIAL_THRESHOLD = tuningConfigService.getGlobalConfig().getColloquialSimThreshold();

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            final List<Double> finalVec = queryVector;
            // ?kb_document_v1 colloquial_vector KNN
            SearchRequest req = new SearchRequest.Builder()
                    .index(indexPattern != null && !indexPattern.isEmpty() ? indexPattern : "kb_document_v1")
                    .knn(k -> k.field("colloquial_vector")
                            .queryVector(finalVec)
                            .k(limit * 2)
                            .numCandidates(tuningConfigService.getGlobalConfig().getColloquialKnnCandidates()) // 来自
                                                                                                               // DB，默�?
                                                                                                               // 80
                            .filter(f -> f.bool(b -> b
                                    .must(m -> m.term(t -> t.field("metadata.is_latest").value(true)))
                                    .must(m -> m.term(t -> t.field("chunk_granularity").value("fine"))))))
                    .size(limit * 2)
                    .build();

            SearchResponse<Object> resp = esClient.search(req, Object.class);
            System.out.println("====== [Data Flow] Node 4.6: Colloquial Vector Search ======");
            System.out.println("  - Colloquial KNN Hits: " + resp.hits().hits().size());

            for (Hit<Object> hit : resp.hits().hits()) {
                double sim = hit.score() != null ? hit.score() : 0.0;
                if (sim < COLLOQUIAL_THRESHOLD) {
                    System.out.printf("  [Colloquial Filter] Skipped (sim=%.3f < %.2f)%n",
                            sim, COLLOQUIAL_THRESHOLD);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null)
                    continue;

                // ?_source?metadata
                Map<String, Object> candMap = new HashMap<>();
                candMap.put("_id", hit.id());
                candMap.put("_source", src);
                candMap.put("_es_score", sim);
                candMap.put("_rrf_score", sim * 0.9); // ?QA ?
                candMap.put("_colloquial_hit", true);
                candMap.put("_max_knn_score", sim);

                results.add(candMap);
                System.out.printf("  [Colloquial Hit] sim=%.3f | id: %s%n", sim, hit.id());

                if (results.size() >= limit)
                    break;
            }
        } catch (Exception e) {
            // colloquial
            System.err.println(" [Colloquial] fetchColloquialResults failed (degrading): "
                    + e.getMessage());
        }
        System.out.println("  - Colloquial Injected Count: " + results.size());
        return results;
    }

    /**
     * 判断是否为精确查询（P1 #1 修复）�?
     * 精确查询不应经过 LLM 改写，否则会把精确信息模糊化、增加不必要延迟�?
     * 判断规则（满足任一即为精确查询）：
     * 1. 包含引号（如 "录音录像"）�?用户主动指定精确�?
     * 2. 包含官方文号特征字符（《》〔〕，号、发、函、令等）
     * 3. 仅由数字/字母/横线组成（序列号、编码类查询�?
     * 4. 长度 �?6 且不含动�?介词（如的、在、是、要、能、和、与等）
     *
     * @param query 归一化后的查询词
     * @return true 表示应跳�?LLM 改写
     */
    private boolean isExactQuery(String query) {
        if (query == null || query.isEmpty())
            return false;

        // 规则 1：包含引�?
        if (query.contains("\"") || query.contains("\u201c") || query.contains("\u201d")
                || query.contains("\u2018") || query.contains("\u2019")) {
            return true;
        }

        // 规则 2：含官方文号特征字符
        if (query.contains("�?) || query.contains("�?) || query.contains("�?)
                || Pattern.compile("[0-9]{4}[^0-9]*(号|发|函|令|�?").matcher(query).find()) {
            return true;
        }

        // 规则 3：仅由数字、字母、横线、下划线、斜杠、点号组成（序列�?编码/档案号）
        // 例如: 000014349/2025-00105, KB-2024-001, v1.0.0
        if (query.matches("[\\dA-Za-z\\-_/.]+")) {
            return true;
        }

        // 规则 5：包含斜杠且斜杠两侧均有数字/字母（文件路径型档案编号�?
        // 例如: 000014349/2025-00105
        if (query.contains("/") && query.matches(".*[\\dA-Za-z]/[\\dA-Za-z].*")) {
            return true;
        }

        // 规则 4：超短查询（�?字）且不含动�?虚词（判断为关键词检索）
        if (query.trim().length() <= 6) {
            // 包含任意一个虚词则认为是问句，不跳过改�?
            String[] interrogativeWords = { "�?, "�?, "�?, "�?, "�?, "�?, "�?, "�?, "�?, "�?,
                    "�?, "什�?, "如何", "为何", "为什", "哪里", "哪些" };
            for (String word : interrogativeWords) {
                if (query.contains(word))
                    return false;
            }
            return true;
        }

        return false;
    }

    /**
     * 计算两个向量的余弦相似度（用�?HyDE 质量门控，P1.8）�?
     * 余弦相似�?= dot(a, b) / (|a| * |b|)
     * 返回值范�?[-1, 1]，两向量方向完全相同时为 1�?
     *
     * @param a 向量 A（HyDE 生成向量�?
     * @param b 向量 B（原�?query 向量�?
     * @return 余弦相似度，向量为空或维度不同时返回 0.0
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty())
            return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i), bi = b.get(i);
            dot += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-12 ? 0.0 : dot / denom;
    }

}

