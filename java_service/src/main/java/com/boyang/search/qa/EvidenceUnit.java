package com.boyang.search.qa;

import java.util.LinkedHashMap;
import java.util.Map;

public class EvidenceUnit {
    private final String evidenceId;
    private final String fileName;
    private final Object docId;
    private final Object chunkIndex;
    private final String content;
    private final String sourceType;
    private final String retrievalChannel;
    private final Double score;
    private final Map<String, Object> rawChunk;

    public EvidenceUnit(String fileName,
                        Object docId,
                        Object chunkIndex,
                        String content,
                        String sourceType,
                        Map<String, Object> rawChunk) {
        this(null, fileName, docId, chunkIndex, content, sourceType, sourceType, null, rawChunk);
    }

    public EvidenceUnit(String evidenceId,
                        String fileName,
                        Object docId,
                        Object chunkIndex,
                        String content,
                        String sourceType,
                        String retrievalChannel,
                        Double score,
                        Map<String, Object> rawChunk) {
        this.evidenceId = evidenceId;
        this.fileName = fileName;
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.sourceType = sourceType == null ? "doc_chunk" : sourceType;
        this.retrievalChannel = retrievalChannel == null ? this.sourceType : retrievalChannel;
        this.score = score;
        this.rawChunk = rawChunk == null ? new LinkedHashMap<>() : rawChunk;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getFileName() {
        return fileName;
    }

    public Object getDocId() {
        return docId;
    }

    public Object getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getRetrievalChannel() {
        return retrievalChannel;
    }

    public Double getScore() {
        return score;
    }

    public Map<String, Object> getRawChunk() {
        return rawChunk;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidence_id", evidenceId);
        out.put("file_name", fileName);
        out.put("doc_id", docId);
        out.put("chunk_index", chunkIndex);
        out.put("content", content);
        out.put("source_type", sourceType);
        out.put("retrieval_channel", retrievalChannel);
        out.put("score", score);
        out.put("raw", rawChunk);
        return out;
    }
}
