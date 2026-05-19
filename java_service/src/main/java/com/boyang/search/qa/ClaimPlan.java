package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClaimPlan {
    private boolean answerable;
    private String directAnswer = "";
    private final List<ClaimUnit> claims = new ArrayList<>();
    private final List<String> missingEvidence = new ArrayList<>();
    private final List<String> conflicts = new ArrayList<>();
    private boolean fallbackUsed;

    public boolean isAnswerable() { return answerable; }
    public void setAnswerable(boolean answerable) { this.answerable = answerable; }
    public String getDirectAnswer() { return directAnswer; }
    public void setDirectAnswer(String directAnswer) { this.directAnswer = directAnswer == null ? "" : directAnswer; }
    public List<ClaimUnit> getClaims() { return claims; }
    public List<String> getMissingEvidence() { return missingEvidence; }
    public List<String> getConflicts() { return conflicts; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> claimMaps = new ArrayList<>();
        for (ClaimUnit claim : claims) {
            claimMaps.add(claim.toMap());
        }
        out.put("answerable", answerable);
        out.put("direct_answer", directAnswer);
        out.put("claims", claimMaps);
        out.put("missing_evidence", missingEvidence);
        out.put("conflicts", conflicts);
        out.put("fallback_used", fallbackUsed);
        return out;
    }
}
