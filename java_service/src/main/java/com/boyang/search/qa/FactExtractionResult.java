package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FactExtractionResult {
    private final List<FactUnit> facts = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean fallbackUsed;

    public List<FactUnit> getFacts() {
        return facts;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public void setFallbackUsed(boolean fallbackUsed) {
        this.fallbackUsed = fallbackUsed;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> factMaps = new ArrayList<>();
        for (FactUnit fact : facts) {
            factMaps.add(fact.toMap());
        }
        out.put("facts", factMaps);
        out.put("warnings", warnings);
        out.put("fallback_used", fallbackUsed);
        out.put("fact_count", facts.size());
        return out;
    }
}
