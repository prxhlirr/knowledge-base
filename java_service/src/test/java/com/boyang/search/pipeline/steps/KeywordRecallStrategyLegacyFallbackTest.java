package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.pipeline.SearchContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRecallStrategyLegacyFallbackTest {

    @Test
    void docSearchEmptyDoesNotCallLegacyFallbackByDefault() throws Exception {
        TestableKeywordRecallStrategy strategy = newStrategy(false);
        SearchContext context = newContext();

        strategy.recall(context);

        assertEquals(1, strategy.docSearchCalls);
        assertEquals(0, strategy.legacyCalls);
        assertEquals(0, context.getKeywordDocSourcesById().size());
        assertEquals(0, context.getTimings().get("keyword_legacy_fallback"));
        assertEquals(1, context.getTimings().get("doc_search_enabled"));
    }

    @Test
    void docSearchEmptyCallsLegacyOnlyWhenFallbackIsEnabled() throws Exception {
        TestableKeywordRecallStrategy strategy = newStrategy(true);
        SearchContext context = newContext();

        strategy.recall(context);

        assertEquals(1, strategy.docSearchCalls);
        assertEquals(1, strategy.legacyCalls);
        assertTrue(context.getKeywordDocSourcesById().containsKey("legacy-doc"));
        assertEquals(1, context.getTimings().get("keyword_legacy_fallback"));
        assertEquals(0, context.getTimings().get("doc_search_enabled"));
    }

    private TestableKeywordRecallStrategy newStrategy(boolean legacyFallbackEnabled) {
        TestableKeywordRecallStrategy strategy = new TestableKeywordRecallStrategy();
        ReflectionTestUtils.setField(strategy, "utils", new EsRecallUtils());
        ReflectionTestUtils.setField(strategy, "docSearchIndex", "kb_doc_search");
        ReflectionTestUtils.setField(strategy, "docSearchEnabled", true);
        ReflectionTestUtils.setField(strategy, "docSearchKeywordEnabled", true);
        ReflectionTestUtils.setField(strategy, "legacyFallbackEnabled", legacyFallbackEnabled);
        return strategy;
    }

    private SearchContext newContext() {
        SysTenantPolicy policy = new SysTenantPolicy();
        policy.setAllowedIndices("kb_document_law");

        SearchContext context = new SearchContext();
        context.setNormalizedQuery("任职 公示");
        context.setTenantPolicy(policy);
        context.setTuningConfig(new SysAiTuningConfig());
        context.setFilters(new HashMap<>());
        context.setRecallTopK(10);
        return context;
    }

    private static final class TestableKeywordRecallStrategy extends KeywordRecallStrategy {
        int docSearchCalls;
        int legacyCalls;

        @Override
        Map<String, Set<String>> searchCandidateDocsByTerm(String indexPattern,
                                                           java.util.List<String> requiredTerms,
                                                           int timeoutMs,
                                                           boolean isAnonymous,
                                                           String finalUserId,
                                                           java.util.List<FieldValue> deptValues,
                                                           String forceSource,
                                                           String readableSourceIndexPattern,
                                                           Map<String, Map<String, Object>> docSourcesById,
                                                           Map<String, Double> docScoresById,
                                                           int topK) {
            docSearchCalls++;
            return Collections.emptyMap();
        }

        @Override
        Map<String, Set<String>> searchLegacyCandidateDocsByTerm(String indexPattern,
                                                                 java.util.List<String> requiredTerms,
                                                                 int timeoutMs,
                                                                 boolean isAnonymous,
                                                                 String finalUserId,
                                                                 java.util.List<FieldValue> deptValues,
                                                                 String forceSource,
                                                                 Map<String, Map<String, Object>> docSourcesById,
                                                                 Map<String, Double> docScoresById,
                                                                 int topK) {
            legacyCalls++;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source", "legacy-doc");
            docSourcesById.put("legacy-doc", source);
            docScoresById.put("legacy-doc", 1.0);

            Set<String> docs = new LinkedHashSet<>();
            docs.add("legacy-doc");
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String term : requiredTerms) {
                result.put(term, docs);
            }
            return result;
        }
    }
}
