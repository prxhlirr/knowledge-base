package com.boyang.search.controller;

import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import com.boyang.search.service.SimilarityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/editor")
@CrossOrigin(origins = "*")
public class EditorSimilarityController {

    @Autowired
    private SimilarityService similarityService;

    @Value("${editor.similarity.min-input-chars:5}")
    private int minInputChars;

    @Value("${editor.similarity.max-input-chars:4000}")
    private int maxInputChars;

    @PostMapping("/similar-docs")
    public Map<String, Object> similarDocs(@RequestBody Map<String, Object> body) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String text = stringValue(body.get("text"));
            if (text.isEmpty()) {
                response.put("code", 400);
                response.put("msg", "text cannot be empty");
                return response;
            }

            String title = stringValue(body.get("title"));
            String combinedText = title.isEmpty() ? text : title + "\n" + text;
            combinedText = combinedText.replaceAll("\\s+", " ").trim();
            if (combinedText.length() < Math.max(1, minInputChars)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", Collections.emptyList());
                data.put("total", 0);
                data.put("queryChars", combinedText.length());
                data.put("skipped", true);
                data.put("skipReason", "input_too_short");
                data.put("costMs", System.currentTimeMillis() - startMs);
                response.put("code", 200);
                response.put("data", data);
                return response;
            }
            int maxChars = Math.max(200, maxInputChars);
            if (combinedText.length() > maxChars) {
                combinedText = combinedText.substring(combinedText.length() - maxChars);
            }

            int topK = intValue(body.get("topK"), 5);
            topK = Math.max(1, Math.min(topK, 10));
            String excludeDocId = stringValue(body.get("excludeDocId"));
            String excludeSource = stringValue(body.get("excludeSource"));
            JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
            String appCode = identity != null ? identity.getAppCode() : "";

            return similarityService.findSimilarDocsForEditor(
                    appCode, combinedText, topK, excludeDocId, excludeSource, identity);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "editor similar docs failed: " + e.getMessage());
            return response;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
