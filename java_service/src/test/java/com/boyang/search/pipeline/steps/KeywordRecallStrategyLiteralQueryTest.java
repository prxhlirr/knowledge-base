package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

class KeywordRecallStrategyLiteralQueryTest {

    @Test
    void keywordDocSearchQueryDoesNotUseAnalyzedMatch() throws Exception {
        Query query = invokeTermQuery("buildTermMatchQuery");

        assertFalse(containsMatchQuery(query), "keyword doc-search recall must not use ES analyzed match");
    }

    @Test
    void keywordLegacyQueryDoesNotUseAnalyzedMatch() throws Exception {
        Query query = invokeTermQuery("buildLegacyTermMatchQuery");

        assertFalse(containsMatchQuery(query), "keyword legacy recall must not use ES analyzed match");
    }

    @SuppressWarnings("unchecked")
    private Query invokeTermQuery(String methodName) throws Exception {
        KeywordRecallStrategy strategy = new KeywordRecallStrategy();
        Method method = KeywordRecallStrategy.class.getDeclaredMethod(
                methodName,
                Query.Builder.class,
                String.class);
        method.setAccessible(true);
        ObjectBuilder<Query> builder = (ObjectBuilder<Query>) method.invoke(
                strategy,
                new Query.Builder(),
                "检验检测工作");
        return builder.build();
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
