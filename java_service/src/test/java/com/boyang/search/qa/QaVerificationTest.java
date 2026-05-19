package com.boyang.search.qa;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaVerificationTest {
    private final QaCitationVerifier citationVerifier = new QaCitationVerifier();
    private final QaClaimVerifier claimVerifier = new QaClaimVerifier();

    @Test
    void citationVerifierRequiresAnswerCitationWhenEvidenceExists() {
        Map<String, Object> citation = citation(1, "\u6d4b\u8bd5\u6750\u6599");

        Map<String, Object> result = citationVerifier.verify(
                "\u7b54\u6848\u672a\u5e26\u5f15\u7528",
                Collections.singletonList(citation));

        assertFalse((Boolean) result.get("pass"));
        assertFalse((Boolean) result.get("has_answer_citation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimVerifierFlagsStrongStatusClaimWithOnlyWeakCitedEvidence() {
        Map<String, Object> citation = citation(1,
                "\u5f20\u4e09\u62df\u4efb\u67d0\u90e8\u95e8\u8d1f\u8d23\u4eba\uff0c\u6b63\u5728\u516c\u793a\u3002");

        Map<String, Object> result = claimVerifier.verify(
                "\u5f20\u4e09\u5df2\u4efb\u804c\u67d0\u90e8\u95e8\u8d1f\u8d23\u4eba[1]\u3002",
                Collections.singletonList(citation),
                statusSensitiveSummary());

        List<String> warnings = (List<String>) result.get("warnings");
        assertFalse((Boolean) result.get("pass"));
        assertTrue(warnings.contains("status_claim_not_supported_by_evidence"));
        assertTrue(warnings.contains("status_claim_not_supported_by_cited_evidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimVerifierPassesStrongStatusClaimWithStrongCitedEvidence() {
        Map<String, Object> citation = citation(1,
                "\u4efb\u804c\u901a\u77e5\u660e\u786e\uff1a\u5f20\u4e09\u5df2\u4efb\u804c\u67d0\u90e8\u95e8\u8d1f\u8d23\u4eba\u3002");

        Map<String, Object> result = claimVerifier.verify(
                "\u5f20\u4e09\u5df2\u4efb\u804c\u67d0\u90e8\u95e8\u8d1f\u8d23\u4eba[1]\u3002",
                Collections.singletonList(citation),
                statusSensitiveSummary());

        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue((Boolean) result.get("pass"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void questionPlannerMarksStatusQueryAsMultiDoc() {
        QuestionPlan plan = new QuestionPlanner().plan("test \u4efb\u804c");

        assertTrue(plan.isStatusSensitive());
        assertTrue(plan.isRequiresMultiDoc());
        assertTrue(plan.getEntities().contains("test"));
    }

    private Map<String, Object> citation(int index, String text) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("index", index);
        citation.put("file_name", "doc-" + index + ".txt");
        citation.put("chunk_text", text);
        citation.put("hit_texts", Arrays.asList(text));
        return citation;
    }

    private Map<String, Object> statusSensitiveSummary() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("status_sensitive", true);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("question_plan", plan);
        return summary;
    }
}
