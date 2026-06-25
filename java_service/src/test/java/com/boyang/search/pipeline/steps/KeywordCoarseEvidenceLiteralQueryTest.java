package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;

class KeywordCoarseEvidenceLiteralQueryTest {

    @Test
    void keywordCoarseEvidenceQueryDoesNotUseAnalyzedMatch() throws Exception {
        KeywordCoarseEvidenceStep step = new KeywordCoarseEvidenceStep();
        Method method = KeywordCoarseEvidenceStep.class.getDeclaredMethod(
                "buildMatchingCoarseRequestItem",
                String.class,
                String.class,
                java.util.List.class,
                String.class);
        method.setAccessible(true);

        RequestItem item = (RequestItem) method.invoke(
                step,
                "kb_document",
                "稿.docx",
                Arrays.asList("检验检测工作"),
                "稿.docx");

        assertFalse(containsMatchQuery(item.body().query()),
                "keyword coarse evidence recall must not use ES analyzed match");
    }

    @Test
    void keywordBatchedCoarseEvidenceQueryDoesNotUseAnalyzedMatch() throws Exception {
        KeywordCoarseEvidenceStep step = new KeywordCoarseEvidenceStep();
        Method method = KeywordCoarseEvidenceStep.class.getDeclaredMethod(
                "buildBatchHashCoarseRequestItem",
                String.class,
                java.util.List.class,
                java.util.List.class,
                java.util.Map.class);
        method.setAccessible(true);

        RequestItem item = (RequestItem) method.invoke(
                step,
                "kb_document",
                Arrays.asList("abc123"),
                Arrays.asList("检验检测工作"),
                Collections.singletonMap("abc123", "稿.docx"));

        assertFalse(containsMatchQuery(item.body().query()),
                "keyword batched coarse evidence recall must not use ES analyzed match");
    }

    private boolean containsMatchQuery(Query query) {
        if (query == null) {
            return false;
        }
        if (query.isMatch()) {
            return true;
        }
        if (query.isBool()) {
            for (Query child : query.bool().must()) {
                if (containsMatchQuery(child)) {
                    return true;
                }
            }
            for (Query child : query.bool().should()) {
                if (containsMatchQuery(child)) {
                    return true;
                }
            }
            for (Query child : query.bool().filter()) {
                if (containsMatchQuery(child)) {
                    return true;
                }
            }
            for (Query child : query.bool().mustNot()) {
                if (containsMatchQuery(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
