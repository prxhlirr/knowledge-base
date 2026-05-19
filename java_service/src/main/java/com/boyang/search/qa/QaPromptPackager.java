package com.boyang.search.qa;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QaPromptPackager {
    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    @Autowired
    private ObjectMapper objectMapper;

    public QaPromptAssembly newAssembly() {
        return new QaPromptAssembly();
    }

    @SuppressWarnings("unchecked")
    public boolean appendEvidence(QaPromptAssembly assembly, EvidenceUnit evidence) {
        if (assembly == null || evidence == null
                || evidence.getContent() == null || evidence.getContent().trim().isEmpty()) {
            return false;
        }
        Map<String, Object> cite = getOrCreateCitation(assembly,
                evidence.getFileName(), evidence.getDocId());
        if (cite == null) {
            return false;
        }

        int ref = ((Number) cite.get("index")).intValue();
        Object chunkIndex = evidence.getChunkIndex();
        String originalText = normalizeEvidenceText(evidence.getContent());
        String chunkText = pruneEvidenceText(originalText, evidence.getSourceType());
        if (chunkText.length() < originalText.length()) {
            assembly.incrementTruncatedEvidenceCount();
            assembly.addPromptPrunedChars(originalText.length() - chunkText.length());
        }
        String facts = compressEvidence(chunkText, 4);
        String header = "资料[" + ref + "] 来源：" + safeFileName(evidence.getFileName())
                + (chunkIndex == null ? "" : "；片段：" + chunkIndex)
                + "\n";
        String promptBlock = header
                + "可用事实：\n" + facts + "\n"
                + "原文摘录：\n" + chunkText + "\n\n";
        int nextChars = promptBlock.length();
        int promptLimit = effectivePromptLimit();
        if (assembly.getPromptChars() + nextChars > promptLimit) {
            int remain = promptLimit - assembly.getPromptChars()
                    - header.length() - facts.length() - 16;
            if (remain < 120) {
                return false;
            }
            int beforeLimit = chunkText.length();
            chunkText = chunkText.substring(0, Math.min(chunkText.length(), remain));
            if (chunkText.length() < beforeLimit) {
                assembly.incrementTruncatedEvidenceCount();
                assembly.addPromptPrunedChars(beforeLimit - chunkText.length());
            }
            promptBlock = header
                    + "可用事实：\n" + facts + "\n"
                    + "原文摘录：\n" + chunkText + "\n\n";
            nextChars = promptBlock.length();
        }

        assembly.knowledgeBuilder().append(promptBlock);
        assembly.addPromptChars(nextChars);
        assembly.incrementMaterialCount();

        List<String> hitTexts = cite.get("hit_texts") instanceof List
                ? (List<String>) cite.get("hit_texts")
                : new ArrayList<>();
        hitTexts.add(chunkText);
        cite.put("hit_texts", hitTexts);
        if (cite.get("chunk_text") == null || cite.get("chunk_text").toString().trim().isEmpty()) {
            cite.put("chunk_text", chunkText);
        }
        if (chunkIndex != null) {
            List<Object> chunkIds = cite.get("chunk_ids") instanceof List
                    ? (List<Object>) cite.get("chunk_ids")
                    : new ArrayList<>();
            chunkIds.add(chunkIndex);
            cite.put("chunk_ids", chunkIds);
        }

        List<Map<String, Object>> chunks = cite.get("chunks") instanceof List
                ? (List<Map<String, Object>>) cite.get("chunks")
                : new ArrayList<>();
        Map<String, Object> rawChunk = evidence.getRawChunk() == null
                ? new LinkedHashMap<>()
                : evidence.getRawChunk();
        Map<String, Object> chunkInfo = new LinkedHashMap<>();
        chunkInfo.put("chunk_id", rawChunk.getOrDefault("chunk_id", chunkIndex));
        chunkInfo.put("chunk_index", chunkIndex);
        chunkInfo.put("section_path", rawChunk.getOrDefault("section_path", ""));
        chunkInfo.put("source_type", evidence.getSourceType());
        chunkInfo.put("hit_text", chunkText);
        chunks.add(chunkInfo);
        cite.put("chunks", chunks);
        assembly.getLedger().recordSelected(evidence, ref, "selected_for_prompt");
        return true;
    }

    public List<Map<String, String>> buildMessages(String query,
                                                   String intentName,
                                                   String knowledge,
                                                   boolean answerable,
                                                   boolean promotionInsufficient) {
        QaAnswerProperties.Prompt prompt = qaAnswerProperties.getPrompt();
        String systemPrompt = prompt.getSystem() + "\n- " + String.join("\n- ", prompt.getSystemRules());
        String factDigest = qaAnswerProperties.isIncludeFactDigest()
                ? limitText(compressEvidence(knowledge == null ? "" : knowledge, 10),
                        Math.max(0, qaAnswerProperties.getFactDigestMaxChars()))
                : "";

        String taskInstruction = answerable
                ? prompt.getSufficientInstruction()
                : (promotionInsufficient
                ? qaAnswerProperties.getRiskPolicy().getPromotionInsufficientInstruction()
                : prompt.getInsufficientInstruction());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("intent", intentName == null ? "unknown" : intentName);
        values.put("answerable", answerable ? "sufficient" : "insufficient");
        values.put("fact_digest", factDigest);
        values.put("knowledge", knowledge == null ? "" : knowledge);
        values.put("query", query == null ? "" : query);
        values.put("task_instruction", taskInstruction == null ? "" : taskInstruction);
        String userPrompt = renderTemplate(prompt.getUserTemplate(), values);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        return messages;
    }

    public List<Map<String, String>> buildRenderMessages(String query, ClaimPlan claimPlan) {
        List<Map<String, String>> messages = new ArrayList<>();
        QaAnswerProperties.StructuredPrompt prompt = qaAnswerProperties.getStructuredPrompt();
        Map<String, String> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", prompt.getRenderSystem());
        messages.add(sysMsg);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query == null ? "" : query);
        payload.put("claim_plan", claimPlan == null ? new LinkedHashMap<String, Object>() : claimPlan.toMap());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            payloadJson = payload.toString();
        }
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("payload", payloadJson);
        userMsg.put("content", renderTemplate(prompt.getRenderUserTemplate(), values));
        messages.add(userMsg);
        return messages;
    }

    private String renderTemplate(String template, Map<String, String> values) {
        String rendered = template == null ? "" : template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String compressEvidence(String text, int maxFacts) {
        if (text == null || text.trim().isEmpty()) {
            return "未抽取到可用事实。";
        }
        String cleaned = text.replaceAll("</?em[^>]*>", "")
                .replaceAll("\\s+", " ")
                .trim();
        String[] sentences = cleaned.split("(?<=[。；;！？!?])");
        List<String> facts = new ArrayList<>();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty() || isLowValueSentence(s)) {
                continue;
            }
            if (isHighValueSentence(s) || facts.size() < 3) {
                facts.add(limitText(s, 160));
            }
            if (facts.size() >= Math.max(1, maxFacts)) {
                break;
            }
        }
        if (facts.isEmpty()) {
            facts.add(limitText(cleaned, 220));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            out.append(i + 1).append(". ").append(facts.get(i)).append("\n");
        }
        return out.toString().trim();
    }

    private boolean isHighValueSentence(String text) {
        String[] terms = {"任职", "任命", "现任", "拟任", "候选", "公示", "提拔", "晋升",
                "时间", "日期", "期限", "条件", "要求", "禁止", "不得", "范围", "名单",
                "决定", "公告", "通知", "申请", "办理"};
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return text.matches(".*\\d{4}年.*") || text.matches(".*\\d+.*");
    }

    private boolean isLowValueSentence(String text) {
        String s = text == null ? "" : text.trim();
        return s.startsWith("抄送")
                || s.startsWith("附件")
                || s.startsWith("联系人")
                || s.startsWith("联系电话")
                || s.startsWith("地址")
                || s.length() < 8;
    }

    private String limitText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (maxLen <= 0) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String normalizeEvidenceText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("</?em[^>]*>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String pruneEvidenceText(String text, String sourceType) {
        int maxChars = qaAnswerProperties.getEvidenceMaxChars();
        String type = sourceType == null ? "" : sourceType.toLowerCase();
        if (type.contains("neighbor")) {
            maxChars = qaAnswerProperties.getNeighborEvidenceMaxChars();
        } else if (type.contains("qa")) {
            maxChars = qaAnswerProperties.getQaEvidenceMaxChars();
        }
        return limitText(text, Math.max(120, maxChars));
    }

    private int effectivePromptLimit() {
        int hardLimit = Math.max(1200, qaAnswerProperties.getPromptMaxChars());
        int targetLimit = qaAnswerProperties.getTargetPromptChars();
        if (targetLimit <= 0) {
            return hardLimit;
        }
        return Math.max(1200, Math.min(hardLimit, targetLimit));
    }

    private Map<String, Object> getOrCreateCitation(QaPromptAssembly assembly,
                                                    String fileName,
                                                    Object docId) {
        String safeFileName = safeFileName(fileName);
        Map<String, Map<String, Object>> citationByDoc = assembly.citationByDoc();
        Map<String, Object> existing = citationByDoc.get(safeFileName);
        if (existing != null) {
            if ((existing.get("doc_id") == null || existing.get("doc_id").toString().trim().isEmpty())
                    && docId != null && !docId.toString().trim().isEmpty()) {
                existing.put("doc_id", docId);
            }
            return existing;
        }
        if (citationByDoc.size() >= qaAnswerProperties.getMaxCitations()) {
            return null;
        }
        Map<String, Object> cite = new LinkedHashMap<>();
        cite.put("index", citationByDoc.size() + 1);
        cite.put("file_name", safeFileName);
        cite.put("doc_id", docId == null ? "" : docId);
        cite.put("chunk_text", "");
        cite.put("hit_texts", new ArrayList<String>());
        cite.put("chunk_ids", new ArrayList<Object>());
        cite.put("chunks", new ArrayList<Map<String, Object>>());
        citationByDoc.put(safeFileName, cite);
        return cite;
    }

    private String safeFileName(String fileName) {
        return fileName == null || fileName.trim().isEmpty() ? "知识库文档" : fileName;
    }
}
