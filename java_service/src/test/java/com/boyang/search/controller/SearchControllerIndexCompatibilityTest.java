package com.boyang.search.controller;

import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchControllerIndexCompatibilityTest {

    @Test
    void analyzeRequestDoesNotBindLegacyPhysicalIndex() {
        SearchController controller = new SearchController();

        AnalyzeRequest request = controller.buildAnalyzeRequest("检验检测工作", "ik_smart");

        assertNull(request.index());
        assertTrue(request.text().contains("检验检测工作"));
    }

    @Test
    void chunkDocIdBranchIsNullSafeForMissingFileName() {
        SearchController controller = new SearchController();

        assertTrue(controller.shouldQueryChunksByDocId("doc-hash-1", null));
        assertTrue(controller.shouldQueryChunksByDocId("doc-hash-1", " "));
        assertFalse(controller.shouldQueryChunksByDocId("doc-hash-1", "稿.docx"));
        assertFalse(controller.shouldQueryChunksByDocId("by-file-name", null));
        assertFalse(controller.shouldQueryChunksByDocId(null, null));
    }
}
