package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.boyang.search.pipeline.SearchContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentPermissionProjectionAssembleTest {

    @Test
    @SuppressWarnings("unchecked")
    void literalResultKeepsDocumentPermissionProjectionFields() throws Exception {
        LiteralRecallStep step = new LiteralRecallStep();
        java.lang.reflect.Field utilsField = LiteralRecallStep.class.getDeclaredField("utils");
        utilsField.setAccessible(true);
        utilsField.set(step, new EsRecallUtils());
        Map<String, Object> source = sourceWithPermissionProjection();
        Map<String, Object> metadata = (Map<String, Object>) source.get("metadata");
        Hit<Object> hit = new Hit.Builder<Object>()
                .index("kb_document_official")
                .id("doc-1_chunk_0")
                .score(1.0)
                .source(source)
                .build();

        Method method = LiteralRecallStep.class.getDeclaredMethod(
                "toResult", Hit.class, Map.class, Map.class, String.class, String.class);
        method.setAccessible(true);

        Map<String, Object> result = (Map<String, Object>) method.invoke(
                step, hit, source, metadata, "doc-1", "重点民生实事工程");

        assertPermissionProjection(result);
    }

    @Test
    void keywordResultKeepsDocumentPermissionProjectionFields() {
        KeywordResultAssembleStep step = new KeywordResultAssembleStep();
        SearchContext context = new SearchContext();
        context.setReturnTopK(1);
        context.setKeywordFilterTerms(new ArrayList<>());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("doc_id", "doc-1");
        doc.put("keyword_rank_score", 1.0);
        doc.put("_source", sourceWithPermissionProjection());
        doc.put("chunks", new ArrayList<>());
        context.setKeywordDocumentHits(Arrays.asList(doc));

        step.execute(context);

        assertEquals(1, context.getFinalResult().size());
        assertPermissionProjection(context.getFinalResult().get(0));
    }

    @Test
    void keywordResultKeepsFlattenedPermissionProjectionFieldsWhenSourceIsAbsent() {
        KeywordResultAssembleStep step = new KeywordResultAssembleStep();
        SearchContext context = new SearchContext();
        context.setReturnTopK(1);
        context.setKeywordFilterTerms(new ArrayList<>());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("doc_id", "doc-1");
        doc.put("keyword_rank_score", 1.0);
        doc.put("chunks", new ArrayList<>());
        doc.put("source_index", "kb_document_official");
        doc.put("index_code", "official");
        doc.put("owner_unit_code", "global");
        doc.put("visible_unit_codes", Arrays.asList("global"));
        doc.put("permission_version", 123L);
        context.setKeywordDocumentHits(Arrays.asList(doc));

        step.execute(context);

        assertEquals(1, context.getFinalResult().size());
        assertPermissionProjection(context.getFinalResult().get(0));
    }

    @Test
    void keywordLiteralMergeDoesNotMarkDeptDocumentCacheable() {
        KeywordResultAssembleStep step = new KeywordResultAssembleStep();
        SearchContext context = new SearchContext();
        context.setReturnTopK(5);
        context.setKeywordFilterTerms(new ArrayList<>());

        Map<String, Object> bm25Doc = new LinkedHashMap<>();
        bm25Doc.put("doc_id", "doc-bm25");
        bm25Doc.put("keyword_rank_score", 1.0);
        bm25Doc.put("_source", sourceWithPermissionProjection());
        bm25Doc.put("chunks", new ArrayList<>());
        context.setKeywordDocumentHits(Arrays.asList(bm25Doc));

        Map<String, Object> literalHit = new LinkedHashMap<>();
        literalHit.put("_id", "doc-literal");
        literalHit.put("_source", sourceWithVisibility("DEPT", "dept_notice.docx"));
        context.setFastTrackDocs(Arrays.asList(literalHit));

        step.execute(context);

        Map<String, Object> literalResult = context.getFinalResult().get(0);
        assertEquals("DEPT", literalResult.get("visibility"));
        assertEquals(false, literalResult.get("cacheable"));
    }

    private Map<String, Object> sourceWithPermissionProjection() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "test_notice.docx");
        metadata.put("owner", "静宁县人民政府办公室");
        metadata.put("publish_time", "2025-01-01");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("content", "静宁县2025年度重点民生实事工程");
        source.put("metadata", metadata);
        source.put("source_index", "kb_document_official");
        source.put("index_code", "official");
        source.put("owner_unit_code", "global");
        source.put("visible_unit_codes", Arrays.asList("global"));
        source.put("permission_version", 123L);
        return source;
    }

    private Map<String, Object> sourceWithVisibility(String visibility, String sourceName) {
        Map<String, Object> source = sourceWithPermissionProjection();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) source.get("metadata");
        metadata.put("visibility", visibility);
        metadata.put("source", sourceName);
        return source;
    }

    private void assertPermissionProjection(Map<String, Object> result) {
        assertEquals("kb_document_official", result.get("source_index"));
        assertEquals("official", result.get("index_code"));
        assertEquals("global", result.get("owner_unit_code"));
        assertEquals(Arrays.asList("global"), result.get("visible_unit_codes"));
        assertEquals(123L, result.get("permission_version"));
    }
}
