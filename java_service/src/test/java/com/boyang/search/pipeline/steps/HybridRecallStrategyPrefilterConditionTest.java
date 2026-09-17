package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.util.SearchQueryCache;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRecallStrategyPrefilterConditionTest {

    @Test
    void hybridTimeoutsUseDefaultValuesWhenNoConfigProvided() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);

        assertEquals(1000, strategy.resolvePreflightTimeoutMs());
        assertEquals(2000, strategy.getEsTimeoutMs(null));
        assertEquals(2000, strategy.resolveSparseTimeoutMs());
        assertEquals(5000, strategy.resolveSubQueryTimeoutMs());
    }

    @Test
    void hybridTimeoutsUseConfiguredPositiveValues() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ReflectionTestUtils.setField(strategy, "hybridPreflightTimeoutMs", "1500");
        ReflectionTestUtils.setField(strategy, "hybridEsTimeoutMs", "3500");
        ReflectionTestUtils.setField(strategy, "hybridSparseTimeoutMs", "2500");
        ReflectionTestUtils.setField(strategy, "hybridSubQueryTimeoutMs", "7000");

        assertEquals(1500, strategy.resolvePreflightTimeoutMs());
        assertEquals(3500, strategy.getEsTimeoutMs(null));
        assertEquals(2500, strategy.resolveSparseTimeoutMs());
        assertEquals(7000, strategy.resolveSubQueryTimeoutMs());
    }

    @Test
    void hybridTimeoutsFallbackWhenConfiguredValuesAreInvalid() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ReflectionTestUtils.setField(strategy, "hybridPreflightTimeoutMs", "0");
        ReflectionTestUtils.setField(strategy, "hybridEsTimeoutMs", "-1");
        ReflectionTestUtils.setField(strategy, "hybridSparseTimeoutMs", "abc");
        ReflectionTestUtils.setField(strategy, "hybridSubQueryTimeoutMs", " ");

        assertEquals(1000, strategy.resolvePreflightTimeoutMs());
        assertEquals(2000, strategy.getEsTimeoutMs(null));
        assertEquals(2000, strategy.resolveSparseTimeoutMs());
        assertEquals(5000, strategy.resolveSubQueryTimeoutMs());
    }

    @Test
    void esTimeoutPrefersDatabaseTuningConfig() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ReflectionTestUtils.setField(strategy, "hybridEsTimeoutMs", "3500");
        SysAiTuningConfig config = new SysAiTuningConfig();
        config.setEsQueryTimeout(4800);

        assertEquals(4800, strategy.getEsTimeoutMs(config));
    }

    @Test
    void normalHybridSearchUsesDocSearchPrefilterByDefault() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        SearchContext context = new SearchContext();
        context.setSearchMode("hybrid");
        context.setHomeLightweightMode(false);

        assertTrue(strategy.shouldUseDocSearchPrefilter(context));
    }

    @Test
    void semanticSearchDoesNotUseHybridPrefilter() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        SearchContext context = new SearchContext();
        context.setSearchMode("semantic");
        context.setHomeLightweightMode(false);

        assertFalse(strategy.shouldUseDocSearchPrefilter(context));
    }

    @Test
    void homeLightweightModeUsesHomePrefilterSwitch() {
        HybridRecallStrategy strategy = newStrategy(true, false, true);
        SearchContext context = new SearchContext();
        context.setSearchMode("hybrid");
        context.setHomeLightweightMode(true);

        assertFalse(strategy.shouldUseDocSearchPrefilter(context));
    }

    @Test
    void docSearchMasterSwitchDisablesAllPrefilter() {
        HybridRecallStrategy strategy = newStrategy(false, true, true);
        SearchContext context = new SearchContext();
        context.setSearchMode("hybrid");
        context.setHomeLightweightMode(false);

        assertFalse(strategy.shouldUseDocSearchPrefilter(context));
    }

    @Test
    void chunkKnnRequiresNonEmptyDocSearchCandidatesWhenPrefilterEnabled() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkKnn(context, Collections.emptyList()));
        assertTrue(strategy.shouldRunChunkKnn(context, Collections.singletonList(FieldValue.of("doc-a"))));
    }

    @Test
    void chunkKnnSkipsWhenCandidateSourcesExceedThreshold() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ReflectionTestUtils.setField(strategy, "hybridCandidateKnnMaxSources", 1);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkKnn(context, Arrays.asList(
                FieldValue.of("doc-a"),
                FieldValue.of("doc-b"))));
    }

    @Test
    void globalChunkKnnIsDisabledByDefaultWhenPrefilterDisabled() {
        HybridRecallStrategy strategy = newStrategy(false, true, true);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkKnn(context, Collections.emptyList()));
    }

    @Test
    void globalChunkKnnCanBeExplicitlyEnabledForRollback() {
        HybridRecallStrategy strategy = newStrategy(false, true, true);
        ReflectionTestUtils.setField(strategy, "hybridGlobalKnnEnabled", true);
        SearchContext context = newHybridContextWithVector();

        assertTrue(strategy.shouldRunChunkKnn(context, Collections.emptyList()));
    }

    @Test
    void chunkTextRecallRequiresNonEmptyDocSearchCandidatesWhenPrefilterEnabled() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkTextRecall(context, Collections.emptyList()));
        assertTrue(strategy.shouldRunChunkTextRecall(context, Collections.singletonList(FieldValue.of("doc-a"))));
    }

    @Test
    void chunkTextRecallSkipsWhenCandidateSourcesExceedThreshold() {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ReflectionTestUtils.setField(strategy, "hybridCandidateChunkMaxSources", 1);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkTextRecall(context, Arrays.asList(
                FieldValue.of("doc-a"),
                FieldValue.of("doc-b"))));
    }

    @Test
    void globalChunkTextRecallIsDisabledByDefaultWhenPrefilterDisabled() {
        HybridRecallStrategy strategy = newStrategy(false, true, true);
        SearchContext context = newHybridContextWithVector();

        assertFalse(strategy.shouldRunChunkTextRecall(context, Collections.emptyList()));
    }

    @Test
    void globalChunkTextRecallCanBeExplicitlyEnabledForRollback() {
        HybridRecallStrategy strategy = newStrategy(false, true, true);
        ReflectionTestUtils.setField(strategy, "hybridGlobalChunkRecallEnabled", true);
        SearchContext context = newHybridContextWithVector();

        assertTrue(strategy.shouldRunChunkTextRecall(context, Collections.emptyList()));
    }

    @Test
    void recallSkipsAllChunkRecallWhenDocSearchPrefilterReturnsEmptyCandidates() throws Exception {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        AiEngineGateway aiEngineGateway = mock(AiEngineGateway.class);
        EsRecallUtils utils = mock(EsRecallUtils.class);

        when(utils.extractCoreTerms(any(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(strategy, "esClient", esClient);
        ReflectionTestUtils.setField(strategy, "aiEngineGateway", aiEngineGateway);
        ReflectionTestUtils.setField(strategy, "utils", utils);
        ReflectionTestUtils.setField(strategy, "searchQueryCache", mock(SearchQueryCache.class));

        SearchContext context = newHybridContextWithVector();
        context.setNormalizedQuery("任职公示查询");
        context.setQueryText("任职公示查询");
        context.setRewrittenQuery(null);
        context.setResolvedIndexPattern("kb_document_notice");
        context.setTuningConfig(new SysAiTuningConfig());
        context.setFilters(Collections.singletonMap("user_id", "u-1"));
        context.setDocCandidateSources(Collections.emptyList());

        strategy.recall(context);

        assertNull(context.getBm25Response());
        assertNull(context.getKnnResponse());
        assertNull(context.getSparseResponse());
        assertEquals(0, context.getBm25Hits());
        assertEquals(0, context.getKnnHits());
        assertEquals(0, context.getSparseHits());
        assertEquals(1, context.getTimings().get("chunk_text_recall_skipped"));
        assertEquals(1, context.getTimings().get("knn_skipped"));
        assertEquals(1, context.getTimings().get("sub_query_knn_skipped"));
        verify(esClient, never()).search(any(co.elastic.clients.elasticsearch.core.SearchRequest.class), any(Class.class));
        verify(aiEngineGateway, never()).fetchSparseVector(any());
    }

    @Test
    void recallAddsCandidateSourceFilterToAllChunkRequestsWhenDocSearchPrefilterHasCandidates() throws Exception {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        AiEngineGateway aiEngineGateway = mock(AiEngineGateway.class);
        EsRecallUtils utils = mock(EsRecallUtils.class);
        SearchResponse<Object> emptyResponse = emptySearchResponse();

        when(esClient.search(any(SearchRequest.class), eq(Object.class))).thenReturn(emptyResponse);
        when(utils.extractCoreTerms(any(), any())).thenReturn(Collections.emptyList());
        when(utils.buildLegacyPermFilter(anyBoolean(), any(), anyList()))
                .thenReturn(q -> q.matchAll(m -> m));
        ReflectionTestUtils.setField(strategy, "esClient", esClient);
        ReflectionTestUtils.setField(strategy, "aiEngineGateway", aiEngineGateway);
        ReflectionTestUtils.setField(strategy, "utils", utils);
        ReflectionTestUtils.setField(strategy, "searchQueryCache", mock(SearchQueryCache.class));

        SearchContext context = newHybridContextWithVector();
        context.setNormalizedQuery("notice query");
        context.setQueryText("notice query");
        context.setRewrittenQuery(null);
        context.setResolvedIndexPattern("kb_document_notice");
        context.setTuningConfig(new SysAiTuningConfig());
        context.setFilters(Collections.singletonMap("user_id", "u-1"));
        context.setDocCandidateSources(Collections.singletonList(FieldValue.of("doc-a")));
        Map<String, Double> sparseVector = new HashMap<>();
        sparseVector.put("notice", 1.0);
        context.setQuerySparseVector(sparseVector);

        strategy.recall(context);

        ArgumentCaptor<SearchRequest> requestCaptor = forClass(SearchRequest.class);
        verify(esClient, times(3)).search(requestCaptor.capture(), eq(Object.class));
        List<SearchRequest> requests = requestCaptor.getAllValues();

        for (SearchRequest request : requests) {
            String json = toJson(request);
            assertTrue(json.contains("\"metadata.source\""), json);
            assertTrue(json.contains("\"doc-a\""), json);
        }
    }

    @Test
    void recallAddsShortChinesePrefixFallbackToChunkTextRequest() throws Exception {
        HybridRecallStrategy strategy = newStrategy(true, true, true);
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        AiEngineGateway aiEngineGateway = mock(AiEngineGateway.class);
        EsRecallUtils utils = mock(EsRecallUtils.class);
        SearchResponse<Object> emptyResponse = emptySearchResponse();

        when(esClient.search(any(SearchRequest.class), eq(Object.class))).thenReturn(emptyResponse);
        when(utils.extractCoreTerms(any(), any())).thenReturn(Collections.emptyList());
        when(utils.buildLegacyPermFilter(anyBoolean(), any(), anyList()))
                .thenReturn(q -> q.matchAll(m -> m));
        ReflectionTestUtils.setField(strategy, "esClient", esClient);
        ReflectionTestUtils.setField(strategy, "aiEngineGateway", aiEngineGateway);
        ReflectionTestUtils.setField(strategy, "utils", utils);
        ReflectionTestUtils.setField(strategy, "searchQueryCache", mock(SearchQueryCache.class));

        SearchContext context = newHybridContextWithVector();
        context.setNormalizedQuery("再来一");
        context.setQueryText("再来一");
        context.setRewrittenQuery(null);
        context.setResolvedIndexPattern("kb_document_notice");
        context.setTuningConfig(new SysAiTuningConfig());
        context.setFilters(Collections.singletonMap("user_id", "u-1"));
        context.setDocCandidateSources(Collections.singletonList(FieldValue.of("doc-a")));
        Map<String, Double> sparseVector = new HashMap<>();
        sparseVector.put("再来一", 1.0);
        context.setQuerySparseVector(sparseVector);

        strategy.recall(context);

        ArgumentCaptor<SearchRequest> requestCaptor = forClass(SearchRequest.class);
        verify(esClient, times(3)).search(requestCaptor.capture(), eq(Object.class));
        String chunkTextRequestJson = requestCaptor.getAllValues().stream()
                .map(this::toJson)
                .filter(json -> json.contains("\"track_total_hits\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 chunk BM25 请求"));

        assertTrue(chunkTextRequestJson.contains("\"match_phrase_prefix\""), chunkTextRequestJson);
        assertTrue(chunkTextRequestJson.contains("\"content\""), chunkTextRequestJson);
        assertTrue(chunkTextRequestJson.contains("再来一"), chunkTextRequestJson);
    }

    @Test
    void keywordDocSearchQueryAddsShortChinesePrefixFallback() throws Exception {
        KeywordRecallStrategy strategy = new KeywordRecallStrategy();
        Method method = KeywordRecallStrategy.class.getDeclaredMethod(
                "buildTermMatchQuery",
                co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder.class,
                String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query> builder =
                (co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>) method.invoke(
                        strategy,
                        new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder(),
                        "再来一");

        String json = toJson(builder.build());

        assertTrue(json.contains("\"match_phrase_prefix\""), json);
        assertTrue(json.contains("\"doc_terms\""), json);
        assertTrue(json.contains("\"summary\""), json);
    }

    @Test
    void keywordDocSearchQueryDoesNotAddPrefixFallbackForSingleChineseCharacter() throws Exception {
        KeywordRecallStrategy strategy = new KeywordRecallStrategy();
        Method method = KeywordRecallStrategy.class.getDeclaredMethod(
                "buildTermMatchQuery",
                co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder.class,
                String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query> builder =
                (co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.query_dsl.Query>) method.invoke(
                        strategy,
                        new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder(),
                        "一");

        String json = toJson(builder.build());

        assertFalse(json.contains("\"match_phrase_prefix\""), json);
    }

    /**
     * 构造只包含开关字段的策略对象。
     * 业务功能：隔离 ES 和 AI 依赖，仅验证文档级预过滤启用条件。
     * 关键流程：通过反射写入 Spring 配置字段，模拟生产配置组合。
     */
    private HybridRecallStrategy newStrategy(boolean docSearchEnabled,
                                             boolean homePrefilterEnabled,
                                             boolean hybridPrefilterEnabled) {
        HybridRecallStrategy strategy = new HybridRecallStrategy();
        ReflectionTestUtils.setField(strategy, "docSearchEnabled", docSearchEnabled);
        ReflectionTestUtils.setField(strategy, "docSearchHomePrefilterEnabled", homePrefilterEnabled);
        ReflectionTestUtils.setField(strategy, "docSearchHybridPrefilterEnabled", hybridPrefilterEnabled);
        ReflectionTestUtils.setField(strategy, "hybridGlobalKnnEnabled", false);
        ReflectionTestUtils.setField(strategy, "hybridCandidateKnnMaxSources", 200);
        ReflectionTestUtils.setField(strategy, "hybridGlobalChunkRecallEnabled", false);
        ReflectionTestUtils.setField(strategy, "hybridCandidateChunkMaxSources", 200);
        ReflectionTestUtils.setField(strategy, "hybridPreflightTimeoutMs", "1000");
        ReflectionTestUtils.setField(strategy, "hybridEsTimeoutMs", "2000");
        ReflectionTestUtils.setField(strategy, "hybridSparseTimeoutMs", "2000");
        ReflectionTestUtils.setField(strategy, "hybridSubQueryTimeoutMs", "5000");
        return strategy;
    }

    private SearchContext newHybridContextWithVector() {
        SearchContext context = new SearchContext();
        context.setSearchMode("hybrid");
        context.setHomeLightweightMode(false);
        context.setQueryVector(Arrays.asList(0.1, 0.2, 0.3));
        return context;
    }

    private SearchResponse<Object> emptySearchResponse() {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(Collections.emptyList())));
    }

    /**
     * 业务功能：将 ES Java Client 请求序列化为 JSON，便于验证最终发往 ES 的 DSL。
     * 关键流程：使用与生产客户端一致的 JacksonJsonpMapper，避免依赖对象 toString 的不稳定输出。
     */
    private String toJson(SearchRequest request) {
        StringWriter writer = new StringWriter();
        JsonpMapper mapper = new JacksonJsonpMapper();
        JsonGenerator generator = mapper.jsonProvider().createGenerator(writer);
        request.serialize(generator, mapper);
        generator.close();
        return writer.toString();
    }

    private String toJson(co.elastic.clients.elasticsearch._types.query_dsl.Query query) {
        StringWriter writer = new StringWriter();
        JsonpMapper mapper = new JacksonJsonpMapper();
        JsonGenerator generator = mapper.jsonProvider().createGenerator(writer);
        query.serialize(generator, mapper);
        generator.close();
        return writer.toString();
    }
}
