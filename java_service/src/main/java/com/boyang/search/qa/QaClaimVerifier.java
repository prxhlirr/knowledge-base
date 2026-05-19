package com.boyang.search.qa;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QaClaimVerifier {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final String[] STRONG_STATUS_TERMS = {
            "\u5df2\u4efb\u547d", "\u5df2\u4efb\u804c", "\u5df2\u664b\u5347", "\u73b0\u4efb",
            "\u4efb\u547d", "\u51b3\u5b9a\u4efb\u804c", "\u6b63\u5f0f\u4efb\u804c",
            "\u4efb\u804c\u901a\u77e5", "\u4efb\u804c", "\u664b\u5347", "\u5347\u4efb",
            "\u63d0\u62d4", "\u63d0\u4efb", "\u514d\u804c"
    };
    private static final String[] WEAK_STATUS_TERMS = {
            "\u62df\u4efb", "\u62df\u63d0\u62d4", "\u5019\u9009", "\u5019\u9009\u4eba", "\u516c\u793a"
    };
    private static final String[] CAUTIOUS_TERMS = {
            "\u672a\u627e\u5230", "\u6ca1\u6709\u660e\u786e", "\u4e0d\u80fd\u786e\u8ba4",
            "\u65e0\u6cd5\u786e\u8ba4", "\u4ec5\u663e\u793a", "\u4ec5\u63d0\u5230",
            "\u6750\u6599\u663e\u793a", "\u6750\u6599\u63d0\u5230"
    };

    @Autowired(required = false)
    private QaAnswerProperties qaAnswerProperties;

    public Map<String, Object> verify(String answer,
                                      List<Map<String, Object>> citations,
                                      Map<String, Object> evidenceSummary) {
        String answerText = answer == null ? "" : answer;
        String evidenceText = joinEvidenceText(citations);
        boolean statusSensitive = isStatusSensitive(evidenceSummary);
        boolean answerStrong = containsAny(answerText, strongStatusTerms());
        boolean answerWeak = containsAny(answerText, weakStatusTerms());
        boolean cautious = containsAny(answerText, cautiousAnswerTerms());
        boolean evidenceStrong = containsAny(evidenceText, strongStatusTerms());
        boolean evidenceWeak = containsAny(evidenceText, weakStatusTerms());

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> claimChecks = verifyCitedStatusClaims(answerText, citations, statusSensitive);
        if (statusSensitive && answerStrong && !evidenceStrong) {
            warnings.add("status_claim_not_supported_by_evidence");
        }
        if (statusSensitive && answerStrong && evidenceWeak && !cautious) {
            warnings.add("status_claim_may_overstate_weak_evidence");
        }
        for (Map<String, Object> claimCheck : claimChecks) {
            Object claimWarnings = claimCheck.get("warnings");
            if (claimWarnings instanceof List) {
                for (Object warning : (List<?>) claimWarnings) {
                    String value = String.valueOf(warning);
                    if (!warnings.contains(value)) {
                        warnings.add(value);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status_sensitive", statusSensitive);
        result.put("answer_has_strong_status", answerStrong);
        result.put("answer_has_weak_status", answerWeak);
        result.put("answer_is_cautious", cautious);
        result.put("evidence_has_strong_status", evidenceStrong);
        result.put("evidence_has_weak_status", evidenceWeak);
        result.put("claim_checks", claimChecks);
        result.put("warnings", warnings);
        result.put("pass", warnings.isEmpty());
        return result;
    }

    private List<Map<String, Object>> verifyCitedStatusClaims(String answer,
                                                              List<Map<String, Object>> citations,
                                                              boolean statusSensitive) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!statusSensitive || answer == null || answer.trim().isEmpty()) {
            return checks;
        }
        Map<Integer, String> evidenceByIndex = evidenceByIndex(citations);
        String[] sentences = answer.split("(?<=[\\u3002\\uff01\\uff1f!?\\uff1b;])|\\n+");
        for (String sentence : sentences) {
            String claim = sentence == null ? "" : sentence.trim();
            if (claim.isEmpty() || !containsAny(claim, strongStatusTerms())) {
                continue;
            }
            Set<Integer> refs = citationIndexes(claim);
            if (refs.isEmpty()) {
                continue;
            }
            String citedEvidence = joinIndexedEvidence(refs, evidenceByIndex);
            boolean citedStrong = containsAny(citedEvidence, strongStatusTerms());
            boolean citedWeak = containsAny(citedEvidence, weakStatusTerms());
            boolean cautious = containsAny(claim, cautiousAnswerTerms());
            List<String> warnings = new ArrayList<>();
            if (!citedStrong) {
                warnings.add("status_claim_not_supported_by_cited_evidence");
            }
            if (!cautious && citedWeak) {
                warnings.add("status_claim_may_overstate_weak_cited_evidence");
            }

            Map<String, Object> check = new LinkedHashMap<>();
            check.put("sentence", claim);
            check.put("citation_indexes", new ArrayList<>(refs));
            check.put("cited_evidence_has_strong_status", citedStrong);
            check.put("cited_evidence_has_weak_status", citedWeak);
            check.put("answer_is_cautious", cautious);
            check.put("warnings", warnings);
            checks.add(check);
        }
        return checks;
    }

    @SuppressWarnings("unchecked")
    private boolean isStatusSensitive(Map<String, Object> evidenceSummary) {
        if (evidenceSummary == null) {
            return false;
        }
        Object questionPlan = evidenceSummary.get("question_plan");
        if (questionPlan instanceof Map) {
            Object statusSensitive = ((Map<String, Object>) questionPlan).get("status_sensitive");
            return Boolean.TRUE.equals(statusSensitive)
                    || "true".equalsIgnoreCase(String.valueOf(statusSensitive));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String joinEvidenceText(List<Map<String, Object>> citations) {
        StringBuilder builder = new StringBuilder();
        if (citations == null) {
            return "";
        }
        for (Map<String, Object> citation : citations) {
            append(builder, citationText(citation));
        }
        return builder.toString();
    }

    private Map<Integer, String> evidenceByIndex(List<Map<String, Object>> citations) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (citations == null) {
            return out;
        }
        for (Map<String, Object> citation : citations) {
            Integer index = toInt(citation == null ? null : citation.get("index"));
            if (index != null) {
                out.put(index, citationText(citation));
            }
        }
        return out;
    }

    private Set<Integer> citationIndexes(String text) {
        Set<Integer> refs = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            Integer index = toInt(matcher.group(1));
            if (index != null) {
                refs.add(index);
            }
        }
        return refs;
    }

    private String joinIndexedEvidence(Set<Integer> refs, Map<Integer, String> evidenceByIndex) {
        StringBuilder builder = new StringBuilder();
        for (Integer ref : refs) {
            append(builder, evidenceByIndex.get(ref));
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private String citationText(Map<String, Object> citation) {
        StringBuilder builder = new StringBuilder();
        if (citation == null) {
            return "";
        }
        append(builder, citation.get("chunk_text"));
        Object hitTexts = citation.get("hit_texts");
        if (hitTexts instanceof List) {
            for (Object item : (List<Object>) hitTexts) {
                append(builder, item);
            }
        }
        Object chunks = citation.get("chunks");
        if (chunks instanceof List) {
            for (Object chunkObj : (List<Object>) chunks) {
                if (chunkObj instanceof Map) {
                    Map<String, Object> chunk = (Map<String, Object>) chunkObj;
                    append(builder, chunk.get("hit_text"));
                    append(builder, chunk.get("chunk_text"));
                }
            }
        }
        return builder.toString();
    }

    private void append(StringBuilder builder, Object value) {
        if (value != null) {
            builder.append(value).append('\n');
        }
    }

    private boolean containsAny(String text, String[] terms) {
        String value = text == null ? "" : text;
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, Collection<String> terms) {
        String value = text == null ? "" : text;
        if (terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isEmpty() && value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private Collection<String> strongStatusTerms() {
        if (qaAnswerProperties != null && qaAnswerProperties.getRiskPolicy() != null
                && !qaAnswerProperties.getRiskPolicy().getStrongStatusTerms().isEmpty()) {
            return qaAnswerProperties.getRiskPolicy().getStrongStatusTerms();
        }
        return Arrays.asList(STRONG_STATUS_TERMS);
    }

    private Collection<String> weakStatusTerms() {
        if (qaAnswerProperties != null && qaAnswerProperties.getRiskPolicy() != null
                && !qaAnswerProperties.getRiskPolicy().getWeakStatusTerms().isEmpty()) {
            return qaAnswerProperties.getRiskPolicy().getWeakStatusTerms();
        }
        return Arrays.asList(WEAK_STATUS_TERMS);
    }

    private Collection<String> cautiousAnswerTerms() {
        if (qaAnswerProperties != null && qaAnswerProperties.getRiskPolicy() != null
                && !qaAnswerProperties.getRiskPolicy().getCautiousAnswerTerms().isEmpty()) {
            return qaAnswerProperties.getRiskPolicy().getCautiousAnswerTerms();
        }
        return Arrays.asList(CAUTIOUS_TERMS);
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
