package com.boyang.search.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DocumentEvidenceGrouper {

    public List<DocumentEvidence> group(List<Map<String, Object>> candidates) {
        Map<String, DocumentEvidence> evidenceByFile = new LinkedHashMap<>();
        if (candidates == null) {
            return new ArrayList<>();
        }

        for (Map<String, Object> candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String fileName = stringValue(candidate.getOrDefault("file_name",
                    candidate.getOrDefault("source", "知识库文档")));
            Object docId = candidate.get("doc_id");
            DocumentEvidence evidence = evidenceByFile.computeIfAbsent(fileName,
                    key -> new DocumentEvidence(key, docId));
            evidence.setDocId(docId);

            double score = numberValue(candidate.getOrDefault("score", candidate.get("_score")));
            evidence.setMaxScore(Math.max(evidence.getMaxScore(), score));
            evidence.setSumScore(evidence.getSumScore() + score);

            Object contextChunksObj = candidate.get("evidence_context_chunks");
            if (contextChunksObj instanceof List<?>) {
                for (Object item : (List<?>) contextChunksObj) {
                    if (item instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = new LinkedHashMap<>((Map<String, Object>) item);
                        ensureChunkDefaults(chunk, candidate);
                        evidence.addChunk(chunk);
                    }
                }
                continue;
            }

            Object chunksObj = candidate.get("chunks");
            if (chunksObj instanceof List<?>) {
                for (Object item : (List<?>) chunksObj) {
                    if (item instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = new LinkedHashMap<>((Map<String, Object>) item);
                        ensureChunkDefaults(chunk, candidate);
                        Object nestedContext = chunk.get("evidence_context_chunks");
                        if (nestedContext instanceof List<?>) {
                            for (Object nested : (List<?>) nestedContext) {
                                if (nested instanceof Map<?, ?>) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> contextChunk = new LinkedHashMap<>((Map<String, Object>) nested);
                                    ensureChunkDefaults(contextChunk, candidate);
                                    evidence.addChunk(contextChunk);
                                }
                            }
                        } else {
                            evidence.addChunk(chunk);
                        }
                    }
                }
            } else {
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("chunk_text", candidate.getOrDefault("chunk_text", candidate.get("content")));
                chunk.put("chunk_index", candidate.getOrDefault("chunk_index", "-"));
                ensureChunkDefaults(chunk, candidate);
                evidence.addChunk(chunk);
            }
        }

        for (DocumentEvidence evidence : evidenceByFile.values()) {
            evidence.setHitCount(evidence.getChunks().size());
            evidence.getChunks().sort((a, b) -> Integer.compare(chunkIndex(a), chunkIndex(b)));
        }
        return new ArrayList<>(evidenceByFile.values());
    }

    private void ensureChunkDefaults(Map<String, Object> chunk, Map<String, Object> candidate) {
        if (!chunk.containsKey("chunk_text")) {
            chunk.put("chunk_text", chunk.getOrDefault("content", candidate.getOrDefault("chunk_text", candidate.get("content"))));
        }
        if (!chunk.containsKey("chunk_index")) {
            chunk.put("chunk_index", chunk.getOrDefault("chunk_id", candidate.getOrDefault("chunk_index", "-")));
        }
    }

    private int chunkIndex(Map<String, Object> chunk) {
        Object value = chunk.getOrDefault("chunk_index", chunk.get("chunk_id"));
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private double numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
