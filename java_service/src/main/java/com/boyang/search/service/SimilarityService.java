package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 向量相似度探针服务
 *
 * 业务功能：为后台管理提供两类相似度分析能力：
 *   1. 双文本余弦对比（Text-vs-Text）：代理调用 AI Service 计算 BGE-M3 Dense 向量余弦相似度
 *   2. ES 全库批量扫描（Query-vs-Corpus）：从 ES scroll 取文档文本，批量调用 AI Service 计算余弦，
 *      返回按分数降序的文档列表及各分数段分布（用于前端绘制直方图）
 *
 * 关键方法：
 *   - compareSimilarity(textA, textB)  → 调用 /api/ai/similarity/compare
 *   - corpusScan(index, queryText, limit) → ES scroll + 批量 compare → 分布统计
 *
 * 注意：corpusScan 默认限制最多 2000 条文档 chunk，防止超时。
 */
@Service
public class SimilarityService {

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiHost;

    /** 带超时的 RestTemplate（与 SearchService 保持一致） */
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimilarityService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(30000); // 全库扫描单批次可能稍慢，适当放宽
        this.restTemplate = new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 双文本余弦相似度对比
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：计算两段文本在 BGE-M3 向量空间中的余弦相似度
     * 关键流程：将 text_a / text_b 发到 Python AI Service /api/ai/similarity/compare，
     *           返回 cosine（[-1,1]）和语义等级标签
     *
     * @param textA 文本 A
     * @param textB 文本 B
     * @return 包含 cosine、label、costMs 的 Map；失败时返回 code=500
     */
    public Map<String, Object> compareSimilarity(String textA, String textB) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text_a", textA);
            payload.put("text_b", textB);

            String url = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/similarity/compare";
            String respJson = restTemplate.postForObject(url, payload, String.class);

            Map<String, Object> respMap = objectMapper.readValue(respJson, Map.class);
            if (respMap != null && Integer.valueOf(200).equals(respMap.get("code"))) {
                result.put("code", 200);
                result.put("data", respMap.get("data"));
            } else {
                result.put("code", 500);
                result.put("msg", "AI Service 返回异常: " + respJson);
            }
        } catch (Exception e) {
            System.err.println("❌ [SimilarityService#compareSimilarity] " + e.getMessage());
            result.put("code", 500);
            result.put("msg", "调用 AI Service 失败: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ES 全库批量扫描
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：对指定 ES 索引中的文档批量计算与 queryText 的余弦相似度
     *
     * 关键流程：
     *   1. ES scroll 取前 limit 条 content 字段（跳过向量字段，节省传输带宽）
     *   2. 每 BATCH_SIZE 条为一批，串行调用 AI Service compare 接口
     *   3. 收集所有文档的 cosine 分，按降序排序后返回；
     *      同时统计 [0-0.2, 0.2-0.4, 0.4-0.6, 0.6-0.8, 0.8-1.0] 五段分布
     *
     * @param index     ES 索引名（不传时用 knowledge_base_*）
     * @param queryText 查询文本
     * @param limit     最多扫描文档数，默认 2000，最大 5000
     * @return Map { code, data: { items:[{docId,title,chunkText,cosine,label}], distribution, total, costMs } }
     */
    public Map<String, Object> corpusScan(String index, String queryText, int limit) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        // 上限保护，防止过大请求拖垮服务
        int safeLimit = Math.min(Math.max(limit, 1), 5000);
        // 每批 25 条：AI Service 单次 encode 耗时约 50-200ms，批量但不要太大
        final int BATCH_SIZE = 25;

        try {
            // Step 1: ES scroll 取文档（只取 content 和 metadata，不取 vector）
            List<Map<String, Object>> rawDocs = scrollEs(index, safeLimit);
            System.out.printf("🔬 [CorpusScan] Fetched %d docs from ES%n", rawDocs.size());

            // Step 2: 批量调用 AI Service 计算余弦
            List<Map<String, Object>> items = new ArrayList<>();
            // 五段分布计数器 [0-0.2), [0.2-0.4), [0.4-0.6), [0.6-0.8), [0.8-1.0]
            int[] distribution = new int[5];

            for (int i = 0; i < rawDocs.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, rawDocs.size());
                List<Map<String, Object>> batch = rawDocs.subList(i, end);

                for (Map<String, Object> doc : batch) {
                    String chunkText = (String) doc.getOrDefault("content", "");
                    if (chunkText == null || chunkText.trim().isEmpty()) continue;

                    // 单条调用（复用 compareSimilarity，避免重复写 HTTP 逻辑）
                    Map<String, Object> cmpResult = compareSimilarity(queryText, chunkText);
                    double cosine = 0.0;
                    String label = "语义疏远";
                    if (Integer.valueOf(200).equals(cmpResult.get("code"))) {
                        Map<String, Object> data = (Map<String, Object>) cmpResult.get("data");
                        if (data != null) {
                            Object cosObj = data.get("cosine");
                            cosine = cosObj instanceof Number ? ((Number) cosObj).doubleValue() : 0.0;
                            label = (String) data.getOrDefault("label", label);
                        }
                    }

                    // 分布统计
                    int bucket = Math.min((int)(cosine / 0.2), 4);
                    if (bucket >= 0) distribution[bucket]++;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", doc.getOrDefault("_id", ""));
                    Map<String, Object> meta = (Map<String, Object>) doc.get("metadata");
                    item.put("title", meta != null ? meta.getOrDefault("source", "未知文档") : "未知文档");
                    // 截取前 120 字作为摘要展示
                    int previewLen = Math.min(chunkText.length(), 120);
                    item.put("chunkText", chunkText.substring(0, previewLen) + (chunkText.length() > previewLen ? "..." : ""));
                    item.put("cosine", Math.round(cosine * 10000.0) / 10000.0);
                    item.put("label", label);
                    items.add(item);
                }
            }

            // Step 3: 按余弦降序排序
            items.sort((a, b) -> {
                double ca = ((Number) a.get("cosine")).doubleValue();
                double cb = ((Number) b.get("cosine")).doubleValue();
                return Double.compare(cb, ca);
            });

            // 构建分布直方图数据
            List<Map<String, Object>> dist = new ArrayList<>();
            String[] labels = {"0.0–0.2", "0.2–0.4", "0.4–0.6", "0.6–0.8", "0.8–1.0"};
            for (int i = 0; i < 5; i++) {
                Map<String, Object> seg = new HashMap<>();
                seg.put("range", labels[i]);
                seg.put("count", distribution[i]);
                dist.add(seg);
            }

            long costMs = System.currentTimeMillis() - startTime;
            System.out.printf("✅ [CorpusScan] Done. %d docs scanned in %dms%n", items.size(), costMs);

            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            data.put("distribution", dist);
            data.put("total", items.size());
            data.put("costMs", costMs);

            result.put("code", 200);
            result.put("data", data);

        } catch (Exception e) {
            System.err.println("❌ [SimilarityService#corpusScan] " + e.getMessage());
            result.put("code", 500);
            result.put("msg", "全库扫描失败: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3: 文档相似性搜索
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：文档级相似性搜索 —— 给定一段文本，在知识库中找最相似的文档。
     * 核心原理：
     *   1. 将输入文本通过 AI Service /api/ai/vector/long-doc 接口向量化（分段均值池化）
     *   2. 在 kb_doc_meta 索引上对 doc_vector 字段做 KNN 检索
     *   3. 按余弦相似度降序返回 top-K 唯一文档（过滤 excludeSource 自身）
     * 与 corpusScan 的区别：
     *   - corpusScan：chunk 级别，逐条调用 AI Service 比对，用于调试向量空间分布
     *   - findSimilarDocs：文档级别，利用预计算的 doc_vector 做 KNN，生产级别接口
     *
     * 关键流程：text → /api/ai/vector/long-doc → doc_vector → kb_doc_meta KNN
     *          → 过滤 excludeSource → topK 文档 → 返回
     *
     * @param text          输入文本（短文本或长文档均可）
     * @param topK          返回文档数（建议 3-10）
     * @param excludeSource 排除的文档名（通常为来源文档自身，避免自查）
     * @return Map { code, data: { items:[{source, similarity, chunkCount}], costMs } }
     */
    public Map<String, Object> findSimilarDocs(String text, int topK, String excludeSource) {
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: 获取输入文本的文档级向量
            Map<String, Object> vectorPayload = new HashMap<>();
            vectorPayload.put("text", text);

            String vectorUrl = aiHost.replace("localhost", "127.0.0.1") + "/api/ai/vector/long-doc";
            String vectorRespJson = restTemplate.postForObject(vectorUrl, vectorPayload, String.class);
            Map<String, Object> vectorResp = objectMapper.readValue(vectorRespJson, Map.class);

            if (!Integer.valueOf(200).equals(vectorResp.get("code"))) {
                result.put("code", 500);
                result.put("msg", "AI Service 向量化失败: " + vectorRespJson);
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> vectorData = (Map<String, Object>) vectorResp.get("data");
            @SuppressWarnings("unchecked")
            List<Double> queryVec = (List<Double>) vectorData.get("vector");
            int segments = vectorData.containsKey("segments") ?
                    ((Number) vectorData.get("segments")).intValue() : 1;

            System.out.printf("📄 [SimilarDocs] Text vectorized: %d segments%n", segments);

            // Step 2: KNN on kb_doc_meta.doc_vector
            final List<Double> finalQueryVec = queryVec;
            int kFetch = Math.min(topK + 3, 20); // 多取几个，过滤后留 topK

            SearchRequest knnReq = new SearchRequest.Builder()
                    .index("kb_doc_meta")
                    .knn(k -> k.field("doc_vector")
                            .queryVector(finalQueryVec)
                            .k(kFetch)
                            .numCandidates(50))
                    .size(kFetch)
                    .build();

            SearchResponse<Object> knnResp = esClient.search(knnReq, Object.class);

            // Step 3: 构建结果列表（过滤 excludeSource，取 topK）
            List<Map<String, Object>> items = new ArrayList<>();
            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : knnResp.hits().hits()) {
                if (items.size() >= topK) break;

                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) continue;

                String source = (String) src.getOrDefault("source", "");
                // 过滤自身文档
                if (excludeSource != null && !excludeSource.isEmpty()
                        && source.equals(excludeSource)) continue;

                double similarity = hit.score() != null ? hit.score() : 0.0;
                int chunkCount = src.containsKey("chunk_count") ?
                        ((Number) src.get("chunk_count")).intValue() : 0;

                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("docId",      hit.id());
                item.put("source",     source);
                item.put("similarity", Math.round(similarity * 10000.0) / 10000.0);
                item.put("chunkCount", chunkCount);
                item.put("label",      similarity >= 0.85 ? "高度相似" :
                                       similarity >= 0.70 ? "语义相近" :
                                       similarity >= 0.55 ? "相关" : "弱相关");
                items.add(item);
            }

            long costMs = System.currentTimeMillis() - startTime;
            System.out.printf("✅ [SimilarDocs] Found %d similar docs in %dms%n", items.size(), costMs);

            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            data.put("costMs", costMs);
            data.put("total", items.size());

            result.put("code", 200);
            result.put("data", data);

        } catch (Exception e) {
            System.err.println("❌ [SimilarityService#findSimilarDocs] " + e.getMessage());
            result.put("code", 500);
            result.put("msg", "相似文档搜索失败: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有辅助：ES scroll 取文档
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 业务功能：通过 ES search（分页模拟 scroll）取前 limit 条文档的 content + metadata 字段
     * 跳过 vector 字段（1024 维数组），大幅减少网络传输量
     *
     * @param index  ES 索引名，null 时用通配符 knowledge_base_*
     * @param limit  最多取多少条
     * @return 原始文档列表，每条含 _id, content, metadata
     */
    private List<Map<String, Object>> scrollEs(String index, int limit) throws Exception {
        String indexPattern = (index != null && !index.trim().isEmpty()) ? index : "knowledge_base_*";
        List<Map<String, Object>> docs = new ArrayList<>();

        int pageSize = Math.min(limit, 500); // 单次最多取 500 条
        int fetched = 0;

        while (fetched < limit) {
            int size = Math.min(pageSize, limit - fetched);
            final int currentFrom = fetched;

            SearchRequest req = new SearchRequest.Builder()
                    .index(indexPattern)
                    .from(currentFrom)
                    .size(size)
                    // 只取需要的字段，跳过 vector（节省约 98% 的传输量）
                    .source(s -> s.filter(f -> f.includes(java.util.Arrays.asList("content", "metadata"))))
                    .build();

            SearchResponse<Object> resp = esClient.search(req, Object.class);
            List<Hit<Object>> hits = resp.hits().hits();
            if (hits.isEmpty()) break;

            for (Hit<Object> hit : hits) {
                if (hit.source() instanceof Map) {
                    Map<String, Object> src = (Map<String, Object>) hit.source();
                    src.put("_id", hit.id());
                    docs.add(src);
                }
            }
            fetched += hits.size();
            if (hits.size() < size) break; // 已无更多数据
        }
        return docs;
    }
}
