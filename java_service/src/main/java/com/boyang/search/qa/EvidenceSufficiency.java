package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvidenceSufficiency {
    boolean answerable;
    double confidence;
    String reason = "";
    List<String> missingAspects = new ArrayList<>();

    public boolean isAnswerable() {
        return answerable;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getMissingAspects() {
        return missingAspects;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answerable", answerable);
        out.put("confidence", confidence);
        out.put("reason", reason);
        out.put("missing_aspects", missingAspects);
        return out;
    }
}
