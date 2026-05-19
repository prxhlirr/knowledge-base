package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QuestionPlan {
    private final String rawQuery;
    private final QaIntent intent;
    private final List<String> entities;
    private final List<String> statusTerms;
    private final List<String> timeTerms;
    private final boolean requiresMultiDoc;
    private final boolean statusSensitive;

    public QuestionPlan(String rawQuery,
                        QaIntent intent,
                        List<String> entities,
                        List<String> statusTerms,
                        List<String> timeTerms,
                        boolean requiresMultiDoc,
                        boolean statusSensitive) {
        this.rawQuery = rawQuery == null ? "" : rawQuery;
        this.intent = intent == null ? QaIntent.UNKNOWN : intent;
        this.entities = entities == null ? new ArrayList<>() : entities;
        this.statusTerms = statusTerms == null ? new ArrayList<>() : statusTerms;
        this.timeTerms = timeTerms == null ? new ArrayList<>() : timeTerms;
        this.requiresMultiDoc = requiresMultiDoc;
        this.statusSensitive = statusSensitive;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public QaIntent getIntent() {
        return intent;
    }

    public List<String> getEntities() {
        return entities;
    }

    public List<String> getStatusTerms() {
        return statusTerms;
    }

    public List<String> getTimeTerms() {
        return timeTerms;
    }

    public boolean isRequiresMultiDoc() {
        return requiresMultiDoc;
    }

    public boolean isStatusSensitive() {
        return statusSensitive;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw_query", rawQuery);
        out.put("intent", intent.name().toLowerCase());
        out.put("entities", entities);
        out.put("status_terms", statusTerms);
        out.put("time_terms", timeTerms);
        out.put("requires_multi_doc", requiresMultiDoc);
        out.put("status_sensitive", statusSensitive);
        return out;
    }
}
