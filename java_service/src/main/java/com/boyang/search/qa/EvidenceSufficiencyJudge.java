package com.boyang.search.qa;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class EvidenceSufficiencyJudge {
    private static final Pattern LIST_SIGNAL = Pattern.compile("(^|\\n)\\s*(\\d+|[一二三四五六七八九十]+)[\\.、)]");
    private static final Pattern NUMBER_OR_TIME = Pattern.compile("\\d|年|月|日|期限|工作日");
    private static final String[] WEAK_STATUS_TERMS = {"拟任", "拟提拔", "候选", "候选人", "公示"};
    private static final String[] STRONG_STATUS_TERMS = {"现任", "已任命", "任命", "决定任职", "正式任职", "任职通知", "任职", "晋升", "升任", "提拔", "提任", "免职"};

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    public EvidenceSufficiency judge(QuestionPlan questionPlan, QaPromptAssembly assembly) {
        EvidenceSufficiency s = new EvidenceSufficiency();
        int materialCount = assembly == null ? 0 : assembly.getMaterialCount();
        s.confidence = Math.min(1.0, materialCount / qaAnswerProperties.getMaterialConfidenceDivisor());
        if (materialCount == 0) {
            s.answerable = false;
            s.reason = "no evidence selected";
            s.missingAspects.add("no_evidence");
            s.confidence = 0.0;
            return s;
        }

        String query = questionPlan == null ? "" : questionPlan.getRawQuery();
        QaIntent intent = questionPlan == null ? QaIntent.UNKNOWN : questionPlan.getIntent();
        String evidenceText = assembly.getKnowledge();
        double coverage = coverageScore(query, evidenceText);
        s.confidence = Math.max(s.confidence, coverage);
        if (coverage < qaAnswerProperties.getMinCoverage() && intent != QaIntent.UNKNOWN) {
            s.answerable = false;
            s.reason = "query terms are not sufficiently covered by selected evidence";
            s.missingAspects.add("low_query_coverage");
            return s;
        }

        if (questionPlan != null && questionPlan.isStatusSensitive()) {
            StatusSignal signal = statusSignal(evidenceText);
            if (!signal.strong) {
                s.answerable = false;
                s.reason = signal.weak
                        ? "only weak status evidence was found"
                        : "no explicit status evidence was found";
                s.missingAspects.add(qaAnswerProperties.getRiskPolicy().getPromotionMissingAspect());
                s.missingAspects.add(signal.weak ? "status_only_weak_signal" : "status_not_explicit");
                return s;
            }
        } else if (asksPromotion(query) && !hasExplicitPromotionSignal(evidenceText)) {
            s.answerable = false;
            s.reason = "promotion-related query lacks explicit evidence";
            s.missingAspects.add(qaAnswerProperties.getRiskPolicy().getPromotionMissingAspect());
            return s;
        }

        if (intent == QaIntent.LIST && !hasListSignal(evidenceText)) {
            s.answerable = false;
            s.reason = "list query lacks list-like evidence";
            s.missingAspects.add("list_structure_not_found");
            return s;
        }
        if (intent == QaIntent.COUNT_OR_TIME && !NUMBER_OR_TIME.matcher(evidenceText == null ? "" : evidenceText).find()) {
            s.answerable = false;
            s.reason = "count/time query lacks number or time evidence";
            s.missingAspects.add("number_or_time_not_found");
            return s;
        }
        s.answerable = true;
        s.reason = "selected evidence is sufficient by rule checks";
        return s;
    }

    private double coverageScore(String query, String text) {
        if (query == null || query.trim().isEmpty() || text == null || text.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        int total = 0;
        for (String token : queryTokens(query)) {
            if (token.length() < 2) {
                continue;
            }
            total++;
            if (text.contains(token)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : Math.min(1.0, matched * 1.0 / total);
    }

    private List<String> queryTokens(String query) {
        if (query == null) {
            return Collections.emptyList();
        }
        Set<String> tokens = new HashSet<>();
        String normalized = query.replaceAll("[，。！？、；,.!?;:\\s]", " ").trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
            String cjk = part.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
            if (cjk.length() >= 4) {
                for (int i = 0; i <= cjk.length() - 2; i++) {
                    tokens.add(cjk.substring(i, i + 2));
                }
                for (int i = 0; i <= cjk.length() - 3; i++) {
                    tokens.add(cjk.substring(i, i + 3));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    private boolean hasListSignal(String text) {
        if (text == null) {
            return false;
        }
        return LIST_SIGNAL.matcher(text).find()
                || text.contains("名单")
                || text.contains("包括")
                || text.contains("如下");
    }

    private boolean asksPromotion(String query) {
        return query != null && containsAny(query, qaAnswerProperties.getRiskPolicy().getPromotionQueryTerms());
    }

    private boolean hasExplicitPromotionSignal(String text) {
        return text != null && containsAny(text, qaAnswerProperties.getRiskPolicy().getPromotionEvidenceTerms());
    }

    private StatusSignal statusSignal(String text) {
        String value = text == null ? "" : text;
        StatusSignal signal = new StatusSignal();
        signal.strong = containsAny(value, qaAnswerProperties.getRiskPolicy().getStrongStatusTerms());
        signal.weak = containsAny(value, qaAnswerProperties.getRiskPolicy().getWeakStatusTerms());
        return signal;
    }

    private boolean containsAny(String text, Collection<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static class StatusSignal {
        boolean strong;
        boolean weak;
    }
}
