package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FactUnit {
    private String factId;
    private String type = "general";
    private String subject = "";
    private String predicate = "";
    private String object = "";
    private String status = "UNKNOWN";
    private String time = "";
    private String quantity = "";
    private String text = "";
    private String evidenceId = "";
    private int citationIndex;
    private List<String> riskTags = new ArrayList<>();

    public String getFactId() { return factId; }
    public void setFactId(String factId) { this.factId = factId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = emptyToDefault(type, "general"); }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = emptyToDefault(subject, ""); }
    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = emptyToDefault(predicate, ""); }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = emptyToDefault(object, ""); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = normalizeStatus(status); }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = emptyToDefault(time, ""); }
    public String getQuantity() { return quantity; }
    public void setQuantity(String quantity) { this.quantity = emptyToDefault(quantity, ""); }
    public String getText() { return text; }
    public void setText(String text) { this.text = emptyToDefault(text, ""); }
    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = emptyToDefault(evidenceId, ""); }
    public int getCitationIndex() { return citationIndex; }
    public void setCitationIndex(int citationIndex) { this.citationIndex = citationIndex; }
    public List<String> getRiskTags() { return riskTags; }
    public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags == null ? new ArrayList<>() : riskTags; }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fact_id", factId);
        out.put("type", type);
        out.put("subject", subject);
        out.put("predicate", predicate);
        out.put("object", object);
        out.put("status", status);
        out.put("time", time);
        out.put("quantity", quantity);
        out.put("text", text);
        out.put("evidence_id", evidenceId);
        out.put("citation_index", citationIndex);
        out.put("risk_tags", riskTags);
        return out;
    }

    public static FactUnit fromMap(Map<String, Object> map, int fallbackIndex) {
        FactUnit fact = new FactUnit();
        fact.setFactId(stringValue(map.getOrDefault("fact_id", "F" + fallbackIndex)));
        fact.setType(stringValue(map.get("type")));
        fact.setSubject(stringValue(map.get("subject")));
        fact.setPredicate(stringValue(map.get("predicate")));
        fact.setObject(stringValue(map.get("object")));
        fact.setStatus(stringValue(map.get("status")));
        fact.setTime(stringValue(map.get("time")));
        fact.setQuantity(stringValue(map.get("quantity")));
        fact.setText(stringValue(map.get("text")));
        fact.setEvidenceId(stringValue(map.get("evidence_id")));
        fact.setCitationIndex(intValue(map.get("citation_index")));
        Object tags = map.get("risk_tags");
        if (tags instanceof List<?>) {
            List<String> values = new ArrayList<>();
            for (Object tag : (List<?>) tags) {
                if (tag != null) {
                    values.add(tag.toString());
                }
            }
            fact.setRiskTags(values);
        }
        return fact;
    }

    private static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if ("PROPOSED".equals(value) || "CANDIDATE".equals(value) || "PUBLIC_NOTICE".equals(value)
                || "APPOINTED".equals(value) || "CURRENT".equals(value) || "REMOVED".equals(value)
                || "HISTORICAL".equals(value)) {
            return value;
        }
        return "UNKNOWN";
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
