package com.boyang.search.pipeline.keyword;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keyword search contract.
 *
 * DOCUMENT_ALL_TERMS means every space-delimited term must appear somewhere in
 * the same document. The terms do not need to appear in the same chunk.
 */
@Data
public class KeywordQueryPlan {

    public enum MatchMode {
        DOCUMENT_ALL_TERMS
    }

    private String normalizedQuery;
    private List<String> requiredTerms = new ArrayList<>();
    private MatchMode matchMode = MatchMode.DOCUMENT_ALL_TERMS;

    public List<String> safeRequiredTerms() {
        return requiredTerms == null ? Collections.emptyList() : requiredTerms;
    }
}
