package com.boyang.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysSearchTag;
import com.boyang.search.mapper.SysSearchTagMapper;
import com.boyang.search.service.SysSearchTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SysSearchTagServiceImpl extends ServiceImpl<SysSearchTagMapper, SysSearchTag> implements SysSearchTagService {

    @Autowired
    private ElasticsearchClient esClient;

    @Value("${ai.service.host:http://127.0.0.1:8001}")
    private String aiHost;

    @Value("${ai.service.embedding-host:http://127.0.0.1:8001}")
    private String embeddingHost;

    private final RestTemplate restTemplate;

    public SysSearchTagServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(15000); // 提取向量可能较慢
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 业务功能：获取重新生成融合了标签和关键词文本后的向量
     */
    private List<Double> fetchVector(String text) {
        try {
            Map<String, String> requestPayload = new HashMap<>();
            requestPayload.put("text", text);
            String host = (embeddingHost == null || embeddingHost.trim().isEmpty()) ? aiHost : embeddingHost;
            String url = host.replace("localhost", "127.0.0.1") + "/api/ai/vector/query";
            String respJson = restTemplate.postForObject(url, requestPayload, String.class);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> respMap = mapper.readValue(respJson, Map.class);

            if (respMap != null && ((Integer) respMap.get("code")) == 200) {
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
            System.err.println("❌ 向量重新生成失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void syncTagToEsAndAi(Long tagId) throws Exception {
        SysSearchTag tagRecord = this.getById(tagId);
        if (tagRecord == null) {
            throw new RuntimeException("打标记录不存在");
        }

        String docId = tagRecord.getDocId();
        String indexName = tagRecord.getIndexName();
        if (indexName == null || indexName.isEmpty()) {
            indexName = "kb_document_v1"; // 默认 fallback
        }

        // 由于部分环境 metadata 缺少 doc_id，且 ES 中 _id 不支持 wildcard
        // 采用显式 ids 匹配，假设单个文件最多 200 个分片
        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            chunkIds.add(docId + "_chunk_" + i);
        }

        SearchRequest searchReq = new SearchRequest.Builder()
                .index(indexName)
                .query(q -> q.bool(b -> b.should(s -> s.term(t -> t.field("metadata.doc_id").value(docId)))
                                         .should(s -> s.ids(idq -> idq.values(chunkIds)))))
                .size(200) // 最大块数
                .build();

        SearchResponse<Object> response = esClient.search(searchReq, Object.class);
        List<Hit<Object>> hits = response.hits().hits();

        if (hits.isEmpty()) {
            // 没有相关文档，直接标记失败
            tagRecord.setSyncStatus(2);
            tagRecord.setUpdateTime(LocalDateTime.now());
            this.updateById(tagRecord);
            throw new RuntimeException("ES中未找到对应文档切片 (doc_id=" + docId + ")");
        }

        boolean hasError = false;

        // 2. 遍历所有该文档碎片，更新 tags 和 keywords 到 metadata 中，并合并文本重生成向量
        for (Hit<Object> hit : hits) {
            String esId = hit.id();
            Map<String, Object> source = (Map<String, Object>) hit.source();
            if (source == null) continue;

            // 提取原文并合并关键词和标签
            String originalContent = (String) source.getOrDefault("content", "");
            String extendedKeywords = (tagRecord.getTags() != null ? tagRecord.getTags() : "") + " "
                    + (tagRecord.getKeywords() != null ? tagRecord.getKeywords() : "");

            String textToVectorize = originalContent + "\n【补充标签/关键词】：" + extendedKeywords;
            
            // 重新请求 AI 取得新向量
            List<Double> newVector = fetchVector(textToVectorize.trim());

            // 构造需要更新的局部 doc
            Map<String, Object> updateDoc = new HashMap<>();
            
            // 可选：将 tags 和 keywords 明确写入文档的特定可查字段
            if (source.containsKey("metadata")) {
                Map<String, Object> meta = new HashMap<>((Map<String, Object>) source.get("metadata"));
                meta.put("tags", tagRecord.getTags());
                meta.put("custom_keywords", tagRecord.getKeywords());
                updateDoc.put("metadata", meta);
            }
            
            // 补入重新生成后的新向量特征
            if (newVector != null && !newVector.isEmpty()) {
                updateDoc.put("vector", newVector);
            } else {
                hasError = true;
                System.err.println("⚠️ Chunk " + esId + " 向量化失败，仅更新 metadata");
            }

            // 更新到 ES
            UpdateRequest<Object, Object> updateReq = new UpdateRequest.Builder<>()
                    .index(hit.index())
                    .id(esId)
                    .doc(updateDoc)
                    .build();

            esClient.update(updateReq, Object.class);
        }

        // 3. 更新同步状态
        tagRecord.setSyncStatus(hasError ? 2 : 1);
        tagRecord.setUpdateTime(LocalDateTime.now());
        this.updateById(tagRecord);
        
        if (hasError) {
            throw new RuntimeException("部分向量重新生成失败，打标项落盘状态已标为[2:失败]");
        }
    }
}
