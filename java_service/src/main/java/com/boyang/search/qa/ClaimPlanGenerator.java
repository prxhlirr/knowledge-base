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
public class ClaimPlanGenerator {
    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    @Autowired
    private ObjectMapper objectMapper;

    public ClaimPlan generate(String query,
                              QuestionPlan questionPlan,
                              FactExtractionResult facts,
                              ValidationResult factValidation) {
        ClaimPlan plan = new ClaimPlan();
        if (facts == null || facts.getFacts().isEmpty()) {
            plan.setAnswerable(false);
            plan.getMissingEvidence().add("no_structured_facts");
            plan.setFallbackUsed(true);
            return plan;
        }

        try {
            List<Map<String, String>> messages = buildMessages(query, questionPlan, facts, factValidation);
            String raw = aiEngineGateway.fetchChatCompletion(
                    messages, qaAnswerProperties.getModelKey(), 0.0,
                    Math.max(600, qaAnswerProperties.getClaimPlanMaxTokens()));
            parsePlan(raw, plan);
        } catch (Exception e) {
            plan.getMissingEvidence().add("llm_claim_plan_failed: " + summarize(e.getMessage(), 180));
        }

        if (plan.getClaims().isEmpty()) {
            fallbackPlan(facts, factValidation, plan);
        }
        return plan;
    }

    private List<Map<String, String>> buildMessages(String query,
                                                    QuestionPlan questionPlan,
                                                    FactExtractionResult facts,
                                                    ValidationResult factValidation) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("question_plan", questionPlan == null ? new LinkedHashMap<String, Object>() : questionPlan.toMap());
        payload.put("fact_validation", factValidation == null ? new LinkedHashMap<String, Object>() : factValidation.toMap());
        payload.put("facts", facts.toMap());

        List<Map<String, String>> messages = new ArrayList<>();
        QaAnswerProperties.StructuredPrompt prompt = qaAnswerProperties.getStructuredPrompt();
        String payloadJson = objectMapper.writeValueAsString(payload);
        messages.add(message("system", prompt.getClaimPlanSystem()));
        messages.add(message("user", renderTemplate(prompt.getClaimPlanUserTemplate(), payloadJson)));
        return messages;
    }

    private String renderTemplate(String template, String payloadJson) {
        String rendered = template == null || template.trim().isEmpty()
                ? "{{payload}}"
                : template;
        return rendered.replace("{{payload}}", payloadJson == null ? "{}" : payloadJson);
    }

    @SuppressWarnings("unchecked")
    private void parsePlan(String raw, ClaimPlan plan) throws Exception {
        String json = QaJsonUtils.extractJsonObject(raw);
        if (json.isEmpty()) {
            return;
        }
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        Object answerable = map.get("answerable");
        plan.setAnswerable(Boolean.TRUE.equals(answerable) || "true".equalsIgnoreCase(String.valueOf(answerable)));
        plan.setDirectAnswer(stringValue(map.get("direct_answer")));

        Object claims = map.get("claims");
        if (claims instanceof List<?>) {
            int i = 1;
            for (Object item : (List<?>) claims) {
                if (item instanceof Map<?, ?>) {
                    ClaimUnit claim = ClaimUnit.fromMap((Map<String, Object>) item, i++);
                    if (!claim.getClaimText().trim().isEmpty()) {
                        plan.getClaims().add(claim);
                    }
                }
            }
        }
        copyStringList(map.get("missing_evidence"), plan.getMissingEvidence());
        copyStringList(map.get("conflicts"), plan.getConflicts());
    }

    private void fallbackPlan(FactExtractionResult facts, ValidationResult factValidation, ClaimPlan plan) {
        plan.setFallbackUsed(true);
        boolean pass = factValidation != null && factValidation.isPass();
        plan.setAnswerable(pass);
        if (!pass) {
            plan.getMissingEvidence().addAll(factValidation == null
                    ? java.util.Collections.singletonList("fact_validation_failed")
                    : factValidation.getErrors());
        }
        int i = 1;
        for (FactUnit fact : facts.getFacts()) {
            ClaimUnit claim = new ClaimUnit();
            claim.setClaimId("C" + i++);
            claim.setClaimText(fact.getText());
            claim.getSupportingFactIds().add(fact.getFactId());
            if (fact.getCitationIndex() > 0) {
                claim.getCitationIndexes().add(fact.getCitationIndex());
            }
            claim.setAnswerable(pass);
            plan.getClaims().add(claim);
            if (plan.getClaims().size() >= 5) {
                break;
            }
        }
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String summarize(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }

    private void copyStringList(Object raw, List<String> target) {
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    target.add(item.toString());
                }
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
