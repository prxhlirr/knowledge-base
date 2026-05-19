package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QaPromptAssembly {
    private final StringBuilder knowledge = new StringBuilder();
    private final Map<String, Map<String, Object>> citationByDoc = new LinkedHashMap<>();
    private final EvidenceLedger ledger = new EvidenceLedger();
    private int materialCount;
    private int qaMaterialCount;
    private int skippedQaHits;
    private int promptChars;
    private int truncatedEvidenceCount;
    private int promptPrunedChars;

    public String getKnowledge() {
        return knowledge.toString();
    }

    StringBuilder knowledgeBuilder() {
        return knowledge;
    }

    Map<String, Map<String, Object>> citationByDoc() {
        return citationByDoc;
    }

    public List<Map<String, Object>> getCitations() {
        return new ArrayList<>(citationByDoc.values());
    }

    public EvidenceLedger getLedger() {
        return ledger;
    }

    public int getMaterialCount() {
        return materialCount;
    }

    void incrementMaterialCount() {
        materialCount++;
    }

    public int getQaMaterialCount() {
        return qaMaterialCount;
    }

    public void incrementQaMaterialCount() {
        qaMaterialCount++;
    }

    public int getSkippedQaHits() {
        return skippedQaHits;
    }

    public void incrementSkippedQaHits() {
        skippedQaHits++;
    }

    public int getPromptChars() {
        return promptChars;
    }

    void addPromptChars(int chars) {
        promptChars += Math.max(0, chars);
    }

    public int getTruncatedEvidenceCount() {
        return truncatedEvidenceCount;
    }

    void incrementTruncatedEvidenceCount() {
        truncatedEvidenceCount++;
    }

    public int getPromptPrunedChars() {
        return promptPrunedChars;
    }

    void addPromptPrunedChars(int chars) {
        promptPrunedChars += Math.max(0, chars);
    }
}
