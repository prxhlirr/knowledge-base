package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordCoarseEvidenceDisplaySizeConfigTest {

    @Test
    void displayCoarseSizeUsesDefaultValueWhenNoConfigProvided() {
        KeywordCoarseEvidenceStep step = newStep();

        assertEquals(3, step.resolveDisplayCoarseSize());
    }

    @Test
    void displayCoarseSizeUsesConfiguredPositiveValue() {
        KeywordCoarseEvidenceStep step = newStep();
        ReflectionTestUtils.setField(step, "displayCoarseSize", "5");

        assertEquals(5, step.resolveDisplayCoarseSize());
    }

    @Test
    void displayCoarseSizeFallbackWhenConfiguredValueIsInvalid() {
        KeywordCoarseEvidenceStep step = newStep();
        ReflectionTestUtils.setField(step, "displayCoarseSize", "abc");
        assertEquals(3, step.resolveDisplayCoarseSize());

        ReflectionTestUtils.setField(step, "displayCoarseSize", "0");
        assertEquals(3, step.resolveDisplayCoarseSize());

        ReflectionTestUtils.setField(step, "displayCoarseSize", "-1");
        assertEquals(3, step.resolveDisplayCoarseSize());
    }

    @Test
    void evidenceIndicesSplitsCommaSeparatedIndexPatternForMsearchHeader() {
        KeywordCoarseEvidenceStep step = newStep();

        List<String> indices = step.evidenceIndices("kb_document_notice_v2,kb_document_law_v2, kb_document_news_v2 ");

        assertEquals(3, indices.size());
        assertEquals("kb_document_notice_v2", indices.get(0));
        assertEquals("kb_document_law_v2", indices.get(1));
        assertEquals("kb_document_news_v2", indices.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void chooseCoverageChunksHonorsConfiguredDisplayLimit() throws Exception {
        KeywordCoarseEvidenceStep step = newStep();
        ReflectionTestUtils.setField(step, "displayCoarseSize", "2");

        Method method = KeywordCoarseEvidenceStep.class.getDeclaredMethod(
                "chooseCoverageChunks",
                List.class,
                List.class,
                java.util.Set.class);
        method.setAccessible(true);

        List<Map<String, Object>> chosen = (List<Map<String, Object>>) method.invoke(
                step,
                candidateChunks(5),
                Collections.singletonList("政策"),
                Collections.emptySet());

        assertEquals(2, chosen.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupedBatchResultFallsBackToChunkIdPrefixWhenMetadataDocIdMissing() throws Exception {
        KeywordCoarseEvidenceStep step = newStep();
        Method method = KeywordCoarseEvidenceStep.class.getDeclaredMethod(
                "processGroupedHits",
                MultiSearchItem.class,
                List.class,
                Set.class,
                Map.class,
                Map.class);
        method.setAccessible(true);

        String docId = "5b963ea917f6423be9599ef33adb9f14_v4";
        Map<String, Object> source = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "稿1_-_副本__2_.docx");
        source.put("metadata", metadata);
        source.put("content", "会议要求补齐短板。Notice中再来一个");
        source.put("display_content", "会议要求补齐短板。Notice中再来一个");

        Hit<Object> hit = new Hit.Builder<Object>()
                .index("kb_document_notice_v2")
                .id(docId + "_chunk_2")
                .score(9.0)
                .source(source)
                .build();
        MultiSearchItem<Object> searchResult = new MultiSearchItem.Builder<Object>()
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hit))
                .build();

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put(docId, new ArrayList<>());

        method.invoke(
                step,
                searchResult,
                Collections.singletonList("再来一个"),
                new LinkedHashSet<>(Collections.singletonList(docId)),
                new HashMap<>(),
                result);

        assertEquals(1, result.get(docId).size());
        assertEquals(docId + "_chunk_2", result.get(docId).get(0).get("_id"));
    }

    /**
     * 业务功能：构造只包含展示条数配置的 keyword coarse 证据步骤。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 ES。
     */
    private KeywordCoarseEvidenceStep newStep() {
        KeywordCoarseEvidenceStep step = new KeywordCoarseEvidenceStep();
        ReflectionTestUtils.setField(step, "displayCoarseSize", "3");
        return step;
    }

    private List<Map<String, Object>> candidateChunks(int count) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("_id", "chunk-" + i);
            chunk.put("_es_score", 10.0 - i);
            chunk.put("matched_terms", Collections.emptyList());
            chunks.add(chunk);
        }
        return chunks;
    }
}
