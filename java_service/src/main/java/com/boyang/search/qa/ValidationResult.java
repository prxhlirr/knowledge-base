package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ValidationResult {
    private boolean pass;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Object> details = new LinkedHashMap<>();

    public boolean isPass() { return pass; }
    public void setPass(boolean pass) { this.pass = pass; }
    public List<String> getWarnings() { return warnings; }
    public List<String> getErrors() { return errors; }
    public Map<String, Object> getDetails() { return details; }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", pass);
        out.put("warnings", warnings);
        out.put("errors", errors);
        out.put("details", details);
        return out;
    }
}
