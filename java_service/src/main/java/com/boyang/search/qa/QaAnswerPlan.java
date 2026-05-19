package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QaAnswerPlan {
    private final List<Map<String, String>> messages;
    private final List<Map<String, Object>> citations;
    private final Map<String, Object> evidenceSummary;
    private ClaimPlan claimPlan;
    private boolean structuredRender;

    public QaAnswerPlan(List<Map<String, String>> messages,
                        List<Map<String, Object>> citations,
                        Map<String, Object> evidenceSummary) {
        this.messages = messages == null ? new ArrayList<>() : messages;
        this.citations = citations == null ? new ArrayList<>() : citations;
        this.evidenceSummary = evidenceSummary == null ? new LinkedHashMap<>() : evidenceSummary;
    }

    public List<Map<String, String>> getMessages() {
        return messages;
    }

    public List<Map<String, Object>> getCitations() {
        return citations;
    }

    public Map<String, Object> getEvidenceSummary() {
        return evidenceSummary;
    }

    public ClaimPlan getClaimPlan() {
        return claimPlan;
    }

    public void setClaimPlan(ClaimPlan claimPlan) {
        this.claimPlan = claimPlan;
    }

    public boolean isStructuredRender() {
        return structuredRender;
    }

    public void setStructuredRender(boolean structuredRender) {
        this.structuredRender = structuredRender;
    }
}
