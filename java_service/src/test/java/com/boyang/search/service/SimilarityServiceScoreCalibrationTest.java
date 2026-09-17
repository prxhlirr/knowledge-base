package com.boyang.search.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class SimilarityServiceScoreCalibrationTest {

    @Test
    void editorSimilarityKeepsNormalizedCosineScoreFromEs() {
        SimilarityService service = new SimilarityService();

        double calibrated = invokeCalibrateEsVectorScore(service, 0.7320);

        assertEquals(0.7320, calibrated, 0.0001);
    }

    @Test
    void editorSimilarityClampsOutOfRangeScore() {
        SimilarityService service = new SimilarityService();

        assertEquals(1.0, invokeCalibrateEsVectorScore(service, 1.2), 0.0001);
        assertEquals(0.0, invokeCalibrateEsVectorScore(service, -0.1), 0.0001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void editorSimilarityRejectsCandidateWhenRerankerStronglyDisagrees() {
        SimilarityService service = serviceWithRerankResponse(
                "{\"code\":200,\"data\":{\"scores\":[0.0],\"rawScores\":[-10.6791],"
                        + "\"scoreType\":\"sigmoid_probability\",\"rawScoreType\":\"bge_reranker_relevance_logit\"}}");
        configureEditorRerank(service);

        List<Map<String, Object>> items = ReflectionTestUtils.invokeMethod(
                service,
                "rerankEditorCandidates",
                "静宁苹果静宁苹果",
                candidates(1, 0.7406),
                5);

        assertTrue(items.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editorSimilarityFallsBackToVectorScoreOnlyWhenRerankerUnavailable() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("reranker timeout"));
        SimilarityService service = new SimilarityService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        configureEditorRerank(service);

        List<Map<String, Object>> items = ReflectionTestUtils.invokeMethod(
                service,
                "rerankEditorCandidates",
                "静宁苹果静宁苹果",
                candidates(1, 0.7406),
                5);

        assertEquals(1, items.size());
        assertEquals(0.7406, ((Number) items.get(0).get("similarity")).doubleValue(), 0.0001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void editorSimilarityLimitsCandidatesSentToSlowReranker() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> payload = invocation.getArgument(1);
                    List<?> documents = (List<?>) payload.get("documents");
                    assertEquals(3, documents.size());
                    return "{\"code\":200,\"data\":{\"scores\":[0.9,0.8,0.7],"
                            + "\"scoreType\":\"sigmoid_probability\"}}";
                });
        SimilarityService service = new SimilarityService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        configureEditorRerank(service);
        ReflectionTestUtils.setField(service, "editorSimilarityRerankMaxCandidates", 3);

        List<Map<String, Object>> items = ReflectionTestUtils.invokeMethod(
                service,
                "rerankEditorCandidates",
                "静宁苹果静宁苹果",
                candidates(6, 0.7406),
                5);

        assertEquals(3, items.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editorSimilarityExtractsDocSearchTermsFromRepeatedEntityInput() {
        SimilarityService service = new SimilarityService();

        List<String> terms = ReflectionTestUtils.invokeMethod(
                service,
                "extractEditorDocSearchTerms",
                "静宁苹果静宁苹果静宁苹果静宁苹果");

        assertTrue(terms.contains("静宁苹果"));
        assertTrue(terms.contains("静宁"));
        assertTrue(terms.contains("苹果"));
    }

    @Test
    void editorSimilarityExplainsEmptyResultAfterRerankerRejectsCandidates() {
        SimilarityService service = new SimilarityService();

        String reason = ReflectionTestUtils.invokeMethod(
                service,
                "resolveEditorSimilarityEmptyReason",
                6,
                6,
                0,
                0,
                0,
                0);

        assertEquals("reranker_rejected_all", reason);
    }

    @Test
    void editorSimilarityExplainsEmptyResultWhenNoCandidateExists() {
        SimilarityService service = new SimilarityService();

        String reason = ReflectionTestUtils.invokeMethod(
                service,
                "resolveEditorSimilarityEmptyReason",
                0,
                0,
                0,
                0,
                0,
                0);

        assertEquals("no_candidates", reason);
    }

    /**
     * 业务功能：调用编辑器相似度分数校准方法，验证 ES 向量分数进入阈值过滤前的口径。
     * 关键流程：通过反射访问私有方法，只锁定业务契约，不扩大生产代码可见性。
     */
    private double invokeCalibrateEsVectorScore(SimilarityService service, double rawScore) {
        Object value = ReflectionTestUtils.invokeMethod(service, "calibrateEsVectorScore", rawScore);
        return ((Number) value).doubleValue();
    }

    /**
     * 业务功能：构造编辑器相似度候选，用于验证向量召回与 reranker 判别的融合规则。
     * 关键流程：只填充私有排序方法真正依赖的字段，避免把测试耦合到 ES 或权限链路。
     */
    private List<Map<String, Object>> candidates(int count, double vectorScore) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", "doc-" + i);
            item.put("source", "dummy-" + i + ".pdf");
            item.put("title", "Dummy PDF file");
            item.put("vectorScore", vectorScore);
            item.put("_rerankText", "title: Dummy PDF file\nsummary: Dummy PDF file");
            candidates.add(item);
        }
        return candidates;
    }

    /**
     * 业务功能：构造带固定 rerank 响应的 SimilarityService。
     * 关键流程：替换内部 RestTemplate，使测试只覆盖 Java 侧门控规则，不依赖 Python AI 服务。
     */
    private SimilarityService serviceWithRerankResponse(String responseJson) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(responseJson);
        SimilarityService service = new SimilarityService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        return service;
    }

    /**
     * 业务功能：固定编辑器相似度的核心阈值配置。
     * 关键流程：显式写入测试所需配置，避免默认值变更造成测试语义漂移。
     */
    private void configureEditorRerank(SimilarityService service) {
        ReflectionTestUtils.setField(service, "rerankHost", "http://127.0.0.1:8001");
        ReflectionTestUtils.setField(service, "editorSimilarityRerankEnabled", true);
        ReflectionTestUtils.setField(service, "editorSimilarityRerankMinScore", 0.50);
        ReflectionTestUtils.setField(service, "editorSimilarityRerankWeightWithEvidence", 0.60);
        ReflectionTestUtils.setField(service, "editorSimilarityRerankWeightWithoutEvidence", 0.25);
        ReflectionTestUtils.setField(service, "editorSimilarityVectorHighConfidenceThreshold", 0.85);
        ReflectionTestUtils.setField(service, "editorSimilarityVectorHighConfidenceFloorRatio", 0.70);
        ReflectionTestUtils.setField(service, "editorSimilarityConflictRerankThreshold", 0.20);
    }
}
