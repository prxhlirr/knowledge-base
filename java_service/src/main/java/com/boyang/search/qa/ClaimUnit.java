package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClaimUnit {
    private String claimId;
    private String claimText = "";
    private List<String> supportingFactIds = new ArrayList<>();
    private List<Integer> citationIndexes = new ArrayList<>();
    private String riskLevel = "normal";
    private boolean answerable = true;

    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getClaimText() { return claimText; }
    public void setClaimText(String claimText) { this.claimText = claimText == null ? "" : claimText; }
    public List<String> getSupportingFactIds() { return supportingFactIds; }
    public void setSupportingFactIds(List<String> supportingFactIds) {
        this.supportingFactIds = supportingFactIds == null ? new ArrayList<>() : supportingFactIds;
    }
    public List<Integer> getCitationIndexes() { return citationIndexes; }
    public void setCitationIndexes(List<Integer> citationIndexes) {
        this.citationIndexes = citationIndexes == null ? new ArrayList<>() : citationIndexes;
    }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel == null ? "normal" : riskLevel; }
    public boolean isAnswerable() { return answerable; }
    public void setAnswerable(boolean answerable) { this.answerable = answerable; }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claim_id", claimId);
        out.put("claim_text", claimText);
        out.put("supporting_fact_ids", supportingFactIds);
        out.put("citation_indexes", citationIndexes);
        out.put("risk_level", riskLevel);
        out.put("answerable", answerable);
        return out;
    }

    public static ClaimUnit fromMap(Map<String, Object> map, int fallbackIndex) {
        ClaimUnit claim = new ClaimUnit();
        claim.setClaimId(stringValue(map.getOrDefault("claim_id", "C" + fallbackIndex)));
        claim.setClaimText(stringValue(map.get("claim_text")));
        claim.setRiskLevel(stringValue(map.getOrDefault("risk_level", "normal")));
        Object answerable = map.get("answerable");
        claim.setAnswerable(!(answerable instanceof Boolean) || Boolean.TRUE.equals(answerable));
        claim.setSupportingFactIds(toStringList(map.get("supporting_fact_ids")));
        claim.setCitationIndexes(toIntegerList(map.get("citation_indexes")));
        return claim;
    }

    private static List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }

    private static List<Integer> toIntegerList(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                try {
                    if (item instanceof Number) {
                        out.add(((Number) item).intValue());
                    } else if (item != null) {
                        out.add(Integer.parseInt(item.toString()));
                    }
                } catch (Exception ignored) {
                    // Skip malformed citation index.
                }
            }
        }
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
