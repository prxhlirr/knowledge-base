package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRecallStrategySourceFilterTest {

    @Test
    void docSearchCandidateSourceFilterKeepsPermissionProjectionFields() throws Exception {
        KeywordRecallStrategy strategy = newStrategy();
        Method method = KeywordRecallStrategy.class.getDeclaredMethod(
                "buildCandidateDocsRequestItem",
                String.class, String.class, int.class, boolean.class, String.class,
                java.util.List.class, String.class, String.class, int.class);
        method.setAccessible(true);

        RequestItem item = (RequestItem) method.invoke(strategy,
                "kb_doc_search", "keyword", 1000, false, "u-1",
                Collections.<FieldValue>emptyList(), null, "kb_document_official", 10);

        assertPermissionFieldsIncluded(item.toString());
    }

    @Test
    void legacyCandidateSourceFilterKeepsPermissionProjectionFields() throws Exception {
        KeywordRecallStrategy strategy = newStrategy();
        Method method = KeywordRecallStrategy.class.getDeclaredMethod(
                "buildLegacyCandidateDocsRequestItem",
                String.class, String.class, int.class, boolean.class, String.class,
                java.util.List.class, String.class, int.class);
        method.setAccessible(true);

        RequestItem item = (RequestItem) method.invoke(strategy,
                "kb_document_official", "keyword", 1000, false, "u-1",
                Collections.<FieldValue>emptyList(), null, 10);

        assertPermissionFieldsIncluded(item.toString());
        assertTrue(item.toString().contains("metadata.visible_unit_codes"));
    }

    @Test
    void msearchIndicesSplitsCommaSeparatedLegacyIndexPattern() throws Exception {
        KeywordRecallStrategy strategy = newStrategy();

        List<String> indices = strategy.msearchIndices("kb_document_notice_v2,kb_document_law_v2, kb_document_news_v2 ");

        assertEquals(3, indices.size());
        assertEquals("kb_document_notice_v2", indices.get(0));
        assertEquals("kb_document_law_v2", indices.get(1));
        assertEquals("kb_document_news_v2", indices.get(2));
    }

    private KeywordRecallStrategy newStrategy() throws Exception {
        KeywordRecallStrategy strategy = new KeywordRecallStrategy();
        Field utils = KeywordRecallStrategy.class.getDeclaredField("utils");
        utils.setAccessible(true);
        utils.set(strategy, new EsRecallUtils());
        return strategy;
    }

    private void assertPermissionFieldsIncluded(String request) {
        assertTrue(request.contains("source_index"));
        assertTrue(request.contains("index_code"));
        assertTrue(request.contains("owner_unit_code"));
        assertTrue(request.contains("visible_unit_codes"));
        assertTrue(request.contains("permission_version"));
    }
}
