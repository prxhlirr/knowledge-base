package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DocumentEvidence {
    private final String fileName;
    private Object docId;
    private final List<Map<String, Object>> chunks = new ArrayList<>();
    private int hitCount;
    private double maxScore;
    private double sumScore;
    private double coverageScore;
    private double listStructureScore;
    private double entityDensityScore;
    private double sameSectionScore;
    private double finalScore;
    private boolean sufficient;

    public DocumentEvidence(String fileName, Object docId) {
        this.fileName = (fileName == null || fileName.trim().isEmpty()) ? "知识库文档" : fileName;
        this.docId = docId == null ? "" : docId;
    }

    public String getFileName() {
        return fileName;
    }

    public Object getDocId() {
        return docId;
    }

    public void setDocId(Object docId) {
        if ((this.docId == null || this.docId.toString().trim().isEmpty())
                && docId != null && !docId.toString().trim().isEmpty()) {
            this.docId = docId;
        }
    }

    public List<Map<String, Object>> getChunks() {
        return chunks;
    }

    public void addChunk(Map<String, Object> chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
    }

    public int getHitCount() {
        return hitCount;
    }

    public void setHitCount(int hitCount) {
        this.hitCount = hitCount;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(double maxScore) {
        this.maxScore = maxScore;
    }

    public double getSumScore() {
        return sumScore;
    }

    public void setSumScore(double sumScore) {
        this.sumScore = sumScore;
    }

    public double getCoverageScore() {
        return coverageScore;
    }

    public void setCoverageScore(double coverageScore) {
        this.coverageScore = coverageScore;
    }

    public double getListStructureScore() {
        return listStructureScore;
    }

    public void setListStructureScore(double listStructureScore) {
        this.listStructureScore = listStructureScore;
    }

    public double getEntityDensityScore() {
        return entityDensityScore;
    }

    public void setEntityDensityScore(double entityDensityScore) {
        this.entityDensityScore = entityDensityScore;
    }

    public double getSameSectionScore() {
        return sameSectionScore;
    }

    public void setSameSectionScore(double sameSectionScore) {
        this.sameSectionScore = sameSectionScore;
    }

    public double getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(double finalScore) {
        this.finalScore = finalScore;
    }

    public boolean isSufficient() {
        return sufficient;
    }

    public void setSufficient(boolean sufficient) {
        this.sufficient = sufficient;
    }
}
