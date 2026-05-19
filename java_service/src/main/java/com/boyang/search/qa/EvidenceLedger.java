package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvidenceLedger {
    private final List<Map<String, Object>> records = new ArrayList<>();
    private int sequence = 1;

    public String nextEvidenceId() {
        return "E" + sequence++;
    }

    public void recordSelected(EvidenceUnit evidence, int citationIndex, String reason) {
        if (evidence == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>(evidence.toMap());
        record.put("citation_index", citationIndex);
        record.put("selected", true);
        record.put("reason", reason == null ? "selected_for_prompt" : reason);
        records.add(record);
    }

    public void recordSkipped(String reason, Map<String, Object> raw) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("evidence_id", nextEvidenceId());
        record.put("selected", false);
        record.put("reason", reason == null ? "skipped" : reason);
        record.put("raw", raw == null ? new LinkedHashMap<String, Object>() : raw);
        records.add(record);
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }

    public List<Map<String, Object>> selectedRecords() {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (Boolean.TRUE.equals(record.get("selected"))) {
                selected.add(record);
            }
        }
        return selected;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("selected_count", selectedRecords().size());
        out.put("total_count", records.size());
        return out;
    }
}
