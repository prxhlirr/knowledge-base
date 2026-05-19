package com.boyang.search.pipeline.keyword;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the keyword query plan from the user input.
 *
 * Keyword mode is literal: spaces separate required terms, and all terms must be
 * found within the same document.
 */
@Component
public class KeywordQueryPlanner {

    public KeywordQueryPlan plan(String normalizedQuery) {
        KeywordQueryPlan plan = new KeywordQueryPlan();
        String normalized = normalizedQuery == null ? "" : normalizedQuery.trim();
        plan.setNormalizedQuery(normalized);
        plan.setRequiredTerms(splitRequiredTerms(normalized));
        return plan;
    }

    private List<String> splitRequiredTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query != null) {
            String[] parts = query.trim().split("[\\s\\u3000]+");
            for (String part : parts) {
                String term = part == null ? "" : part.trim();
                if (!term.isEmpty()) {
                    terms.add(term);
                }
            }
        }
        return new ArrayList<>(terms);
    }
}
