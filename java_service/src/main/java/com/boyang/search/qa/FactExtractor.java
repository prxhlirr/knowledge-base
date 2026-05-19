package com.boyang.search.qa;

import com.boyang.search.gateway.AiEngineGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FactExtractor {
    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    @Autowired
    private ObjectMapper objectMapper;

    public FactExtractionResult extract(String query,
                                        QuestionPlan questionPlan,
                                        EvidenceLedger ledger) {
        FactExtractionResult result = new FactExtractionResult();
        List<Map<String, Object>> selected = ledger == null ? new ArrayList<>() : ledger.selectedRecords();
        if (selected.isEmpty()) {
            result.getWarnings().add("no_selected_evidence");
            return result;
        }

        try {
            List<Map<String, String>> messages = buildMessages(query, questionPlan, selected);
            String raw = aiEngineGateway.fetchChatCompletion(
                    messages, qaAnswerProperties.getModelKey(), 0.0,
                    Math.max(600, qaAnswerProperties.getClaimPlanMaxTokens()));
            parseFacts(raw, result);
        } catch (Exception e) {
            result.getWarnings().add("llm_fact_extraction_failed: " + e.getMessage());
        }

        if (result.getFacts().isEmpty()) {
            fallbackFacts(selected, result);
        }
        return result;
    }

    private List<Map<String, String>> buildMessages(String query,
                                                    QuestionPlan questionPlan,
                                                    List<Map<String, Object>> selected) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("question_plan", questionPlan == null ? new LinkedHashMap<String, Object>() : questionPlan.toMap());
        payload.put("evidence", selected);

        List<Map<String, String>> messages = new ArrayList<>();
        QaAnswerProperties.StructuredPrompt prompt = qaAnswerProperties.getStructuredPrompt();
        String payloadJson = objectMapper.writeValueAsString(payload);
        messages.add(message("system", prompt.getFactExtractorSystem()));
        messages.add(message("user", renderTemplate(prompt.getFactExtractorUserTemplate(), payloadJson)));
        return messages;
    }

    private String renderTemplate(String template, String payloadJson) {
        String rendered = template == null || template.trim().isEmpty()
                ? "{{payload}}"
                : template;
        return rendered.replace("{{payload}}", payloadJson == null ? "{}" : payloadJson);
    }

    @SuppressWarnings("unchecked")
    private void parseFacts(String raw, FactExtractionResult result) throws Exception {
        String json = QaJsonUtils.extractJsonObject(raw);
        if (json.isEmpty()) {
            return;
        }
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        Object facts = map.get("facts");
        if (facts instanceof List<?>) {
            int i = 1;
            for (Object item : (List<?>) facts) {
                if (item instanceof Map<?, ?>) {
                    FactUnit fact = FactUnit.fromMap((Map<String, Object>) item, i++);
                    if (!fact.getText().trim().isEmpty()) {
                        result.getFacts().add(fact);
                    }
                }
            }
        }
        Object warnings = map.get("warnings");
        if (warnings instanceof List<?>) {
            for (Object warning : (List<?>) warnings) {
                if (warning != null) {
                    result.getWarnings().add(warning.toString());
                }
            }
        }
    }

    private void fallbackFacts(List<Map<String, Object>> selected, FactExtractionResult result) {
        result.setFallbackUsed(true);
        result.getWarnings().add("fact_extraction_fallback_used");
        int i = 1;
        for (Map<String, Object> record : selected) {
            FactUnit fact = new FactUnit();
            fact.setFactId("F" + i++);
            fact.setType("general");
            fact.setStatus("UNKNOWN");
            fact.setText(limit(stringValue(record.get("content")), 260));
            fact.setEvidenceId(stringValue(record.get("evidence_id")));
            fact.setCitationIndex(intValue(record.get("citation_index")));
            if (!fact.getText().isEmpty()) {
                result.getFacts().add(fact);
            }
        }
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String limit(String text, int maxLen) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private int intValue(Object value) {
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
