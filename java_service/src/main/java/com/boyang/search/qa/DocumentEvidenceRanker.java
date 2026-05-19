package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DocumentEvidenceRanker {
    private static final Pattern LIST_MARKER = Pattern.compile("(^|\\n|。|；|;)\\s*(\\d+|[一二三四五六七八九十]+)[\\.、．)]");
    private static final Pattern PERSON_PROFILE = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}[，,]\\s*[男女][，,]");

    public List<DocumentEvidence> rank(String query, List<DocumentEvidence> docs) {
        List<DocumentEvidence> ranked = new ArrayList<>(docs == null ? new ArrayList<>() : docs);
        boolean listQuery = isListQuery(query);
        for (DocumentEvidence doc : ranked) {
            String text = joinedText(doc);
            double coverage = coverageScore(query, text);
            double listScore = listStructureScore(text);
            double entityScore = entityDensityScore(text);
            double sameSection = sameSectionScore(doc);
            double finalScore = doc.getMaxScore() * 1.5
                    + Math.log1p(Math.max(doc.getSumScore(), 0.0))
                    + doc.getHitCount() * 0.4
                    + coverage * 2.0
                    + listScore * (listQuery ? 2.8 : 1.2)
                    + entityScore * (listQuery ? 2.2 : 1.0)
                    + sameSection * 0.8;
            doc.setCoverageScore(coverage);
            doc.setListStructureScore(listScore);
            doc.setEntityDensityScore(entityScore);
            doc.setSameSectionScore(sameSection);
            doc.setFinalScore(finalScore);
            doc.setSufficient(isSufficient(doc, listQuery));
        }
        ranked.sort(Comparator.comparingDouble(DocumentEvidence::getFinalScore).reversed());
        return ranked;
    }

    public List<DocumentEvidence> selectForPrompt(String query, List<DocumentEvidence> docs, int minLimit, int fallbackLimit) {
        List<DocumentEvidence> ranked = rank(query, docs);
        if (ranked.isEmpty()) {
            return ranked;
        }
        List<String> coreTokens = coreQueryTokens(query);
        if (!coreTokens.isEmpty()) {
            List<DocumentEvidence> topical = ranked.stream()
                    .filter(doc -> containsAnyCoreToken(joinedText(doc), coreTokens))
                    .collect(Collectors.toList());
            if (!topical.isEmpty()) {
                ranked = topical;
            }
        }
        DocumentEvidence primary = ranked.get(0);
        logRank(query, ranked);
        int safeMin = Math.max(1, Math.min(Math.max(1, minLimit), ranked.size()));
        int safeMax = Math.max(safeMin, Math.min(Math.max(1, fallbackLimit), ranked.size()));
        if (primary.isSufficient()) {
            return ranked.subList(0, safeMin);
        }
        return ranked.stream().limit(safeMax).collect(Collectors.toList());
    }

    private boolean isSufficient(DocumentEvidence doc, boolean listQuery) {
        if (listQuery) {
            return doc.getHitCount() >= 3
                    && doc.getListStructureScore() >= 0.35
                    && doc.getEntityDensityScore() >= 0.25;
        }
        return doc.getFinalScore() >= 2.0 && doc.getCoverageScore() >= 0.15;
    }

    private boolean isListQuery(String query) {
        String q = query == null ? "" : query;
        return q.contains("哪些") || q.contains("名单") || q.contains("人员")
                || q.contains("谁") || q.contains("晋升") || q.contains("提拔")
                || q.contains("任命") || q.contains("候选人") || q.contains("多少人");
    }

    private double coverageScore(String query, String text) {
        if (query == null || query.trim().isEmpty() || text == null || text.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        int total = 0;
        for (String token : query.replaceAll("[，。！？、；：,.!?;:\\s]", " ").split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            total++;
            if (text.contains(token)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : Math.min(1.0, matched * 1.0 / total);
    }

    private List<String> coreQueryTokens(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null) {
            return tokens;
        }
        for (String token : query.replaceAll("[锛屻€傦紒锛熴€侊紱锛?.!?;:\\s]", " ").split("\\s+")) {
            String trimmed = token.trim();
            if (trimmed.length() < 2 || isGenericQueryToken(trimmed)) {
                continue;
            }
            tokens.add(trimmed);
        }
        return tokens;
    }

    private boolean isGenericQueryToken(String token) {
        return "要求".equals(token) || "规定".equals(token) || "内容".equals(token)
                || "情况".equals(token) || "信息".equals(token) || "材料".equals(token)
                || "流程".equals(token) || "条件".equals(token);
    }

    private boolean containsAnyCoreToken(String text, List<String> coreTokens) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String token : coreTokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private double listStructureScore(String text) {
        Matcher matcher = LIST_MARKER.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return Math.min(1.0, count / 5.0);
    }

    private double entityDensityScore(String text) {
        Matcher matcher = PERSON_PROFILE.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return Math.min(1.0, count / 5.0);
    }

    private double sameSectionScore(DocumentEvidence doc) {
        if (doc.getChunks().size() <= 1) {
            return 0.0;
        }
        int adjacent = 0;
        Integer prev = null;
        for (Map<String, Object> chunk : doc.getChunks()) {
            Integer current = numericChunkIndex(chunk);
            if (prev != null && current != null && current - prev == 1) {
                adjacent++;
            }
            if (current != null) {
                prev = current;
            }
        }
        return Math.min(1.0, adjacent * 1.0 / Math.max(1, doc.getChunks().size() - 1));
    }

    private Integer numericChunkIndex(Map<String, Object> chunk) {
        Object value = chunk.getOrDefault("chunk_index", chunk.get("chunk_id"));
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String joinedText(DocumentEvidence doc) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> chunk : doc.getChunks()) {
            Object text = chunk.getOrDefault("chunk_text", chunk.get("content"));
            if (text != null) {
                builder.append(text).append('\n');
            }
        }
        return builder.toString();
    }

    private void logRank(String query, List<DocumentEvidence> ranked) {
        int limit = Math.min(ranked.size(), 5);
        for (int i = 0; i < limit; i++) {
            DocumentEvidence doc = ranked.get(i);
            System.out.printf("[QA DocRank] query='%s' rank=%d file='%s' final=%.3f hit=%d coverage=%.2f list=%.2f entity=%.2f sufficient=%s%n",
                    query,
                    i + 1,
                    doc.getFileName(),
                    doc.getFinalScore(),
                    doc.getHitCount(),
                    doc.getCoverageScore(),
                    doc.getListStructureScore(),
                    doc.getEntityDensityScore(),
                    doc.isSufficient());
        }
    }
}
