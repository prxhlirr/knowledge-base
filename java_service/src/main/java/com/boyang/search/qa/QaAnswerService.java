package com.boyang.search.qa;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.boyang.search.entity.SysTenantPolicy;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.security.JwtVerifier;
import com.boyang.search.service.SearchServiceV2;
import com.boyang.search.service.SysTenantPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QaAnswerService {
    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private SearchServiceV2 searchServiceV2;

    @Autowired
    private SysTenantPolicyService sysTenantPolicyService;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    @Autowired
    private QaPromptPackager promptPackager;

    @Autowired
    private QuestionPlanner questionPlanner;

    @Autowired
    private EvidenceSufficiencyJudge evidenceSufficiencyJudge;

    @Autowired
    private FactExtractor factExtractor;

    @Autowired
    private FactValidator factValidator;

    @Autowired
    private ClaimPlanGenerator claimPlanGenerator;

    @Autowired
    private ClaimPlanValidator claimPlanValidator;

    public QaAnswerPlan prepareAnswer(String appCode,
                                      String queryText,
                                      int topK,
                                      JwtVerifier.UserIdentity identity) throws Exception {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (identity != null) {
            filters.putAll(identity.toFilters());
        }
        int recallTopK = Math.max(20, Math.min(30, topK * 6));
        Map<String, Object> timings = new LinkedHashMap<>();
        long docRecallStart = System.currentTimeMillis();
        SearchContext searchContext = searchServiceV2.hybridSearchContext(
                appCode, queryText, recallTopK, filters, "hybrid");
        List<Map<String, Object>> candidates = searchContext.getFinalResult();
        List<Map<String, Object>> qaHits = searchContext.getAnswerQaHits();
        timings.putAll(searchContext.getTimings());
        timings.put("doc_recall_ms", System.currentTimeMillis() - docRecallStart);
        timings.put("qa_recall_ms", searchContext.getAnswerQaRecallMs());
        timings.put("qa_recall_wait_ms", searchContext.getAnswerQaRecallWaitMs());
        timings.put("qa_recall_completed", searchContext.isAnswerQaRecallCompleted());
        timings.put("qa_recall_skipped_reason", searchContext.getAnswerQaRecallSkippedReason());
        timings.put("qa_recall_parallel", true);
        timings.put("qa_recall_reused_vector", true);
        return buildAnswerPlan(appCode, queryText, topK, candidates, qaHits, timings, false, recallTopK);
    }

    /**
     * 首页聚合搜索已完成文档混合召回时使用，避免问答链路再次执行 hybridSearchV2。
     */
    public QaAnswerPlan prepareAnswerWithCandidates(String appCode,
                                                    String queryText,
                                                    int topK,
                                                    List<Map<String, Object>> candidates) throws Exception {
        int recallTopK = candidates == null ? 0 : candidates.size();
        return buildAnswerPlan(appCode, queryText, topK, candidates, null, new LinkedHashMap<>(), true, recallTopK);
    }

    public QaAnswerPlan prepareAnswerWithCandidates(String appCode,
                                                    String queryText,
                                                    int topK,
                                                    List<Map<String, Object>> candidates,
                                                    List<Map<String, Object>> prefetchedQaHits) throws Exception {
        int recallTopK = candidates == null ? 0 : candidates.size();
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("qa_recall_parallel", prefetchedQaHits != null);
        return buildAnswerPlan(appCode, queryText, topK, candidates, prefetchedQaHits, timings, true, recallTopK);
    }

    public List<Map<String, Object>> prefetchQaHits(String queryText, int topK) {
        return fetchQaHits(queryText, topK);
    }

    private QaAnswerPlan buildAnswerPlan(String appCode,
                                         String queryText,
                                         int topK,
                                         List<Map<String, Object>> candidates,
                                         List<Map<String, Object>> prefetchedQaHits,
                                         Map<String, Object> timings,
                                         boolean reusedDocCandidates,
                                         int recallTopK) throws Exception {
        long startMs = System.currentTimeMillis();
        if (timings == null) {
            timings = new LinkedHashMap<>();
        }
        long questionPlanStart = System.currentTimeMillis();
        QuestionPlan questionPlan = questionPlanner.plan(queryText);
        timings.put("question_plan_ms", System.currentTimeMillis() - questionPlanStart);
        QaIntent intent = questionPlan.getIntent();

        List<Map<String, Object>> qaHits;
        if (prefetchedQaHits != null) {
            qaHits = prefetchedQaHits;
            timings.put("qa_recall_reused", true);
        } else {
            long qaRecallStart = System.currentTimeMillis();
            qaHits = fetchQaHits(queryText, topK);
            timings.put("qa_recall_ms", System.currentTimeMillis() - qaRecallStart);
            timings.put("qa_recall_reused", false);
        }
        timings.put("doc_candidates_reused", reusedDocCandidates);

        long rankStart = System.currentTimeMillis();
        List<DocumentEvidence> groupedDocs = new DocumentEvidenceGrouper().group(candidates);
        int promptMinDocs = promptMinDocs(questionPlan);
        int promptMaxDocs = promptMaxDocs(questionPlan);
        List<DocumentEvidence> rankedDocs = new DocumentEvidenceRanker().selectForPrompt(
                queryText, groupedDocs, promptMinDocs, promptMaxDocs);
        timings.put("group_rank_ms", System.currentTimeMillis() - rankStart);

        long assembleStart = System.currentTimeMillis();
        QaPromptAssembly assembly = assemblePromptEvidence(appCode, queryText, intent, qaHits, rankedDocs);
        timings.put("prompt_assemble_ms", System.currentTimeMillis() - assembleStart);
        long sufficiencyStart = System.currentTimeMillis();
        EvidenceSufficiency sufficiency = evidenceSufficiencyJudge.judge(questionPlan, assembly);
        timings.put("sufficiency_ms", System.currentTimeMillis() - sufficiencyStart);
        boolean promotionInsufficient = sufficiency.missingAspects.contains(
                qaAnswerProperties.getRiskPolicy().getPromotionMissingAspect());
        long structuredStart = System.currentTimeMillis();
        FactExtractionResult factExtraction = null;
        ValidationResult factValidation = null;
        ClaimPlan claimPlan = null;
        ValidationResult claimValidation = null;
        boolean structuredRender = false;
        boolean structuredQaEnabled = false; // [Phase 3] Bypassed for CoT Streaming. Using modern prompt template.
        timings.put("structured_mode", qaAnswerProperties.getStructuredMode());
        timings.put("structured_qa_enabled", structuredQaEnabled);
        if (structuredQaEnabled) {
            long factExtractStart = System.currentTimeMillis();
            factExtraction = factExtractor.extract(queryText, questionPlan, assembly.getLedger());
            timings.put("fact_extract_ms", System.currentTimeMillis() - factExtractStart);
            factValidation = factValidator.validate(questionPlan, factExtraction);
            long claimPlanStart = System.currentTimeMillis();
            claimPlan = claimPlanGenerator.generate(queryText, questionPlan, factExtraction, factValidation);
            timings.put("claim_plan_ms", System.currentTimeMillis() - claimPlanStart);
            claimValidation = claimPlanValidator.validate(claimPlan, factExtraction);
            structuredRender = claimPlan != null
                    && !claimPlan.getClaims().isEmpty()
                    && claimValidation.isPass()
                    && !claimPlan.isFallbackUsed()
                    && claimPlan.isAnswerable();
        }
        timings.put("structured_qa_ms", System.currentTimeMillis() - structuredStart);
        List<Map<String, String>> messages = structuredRender
                ? promptPackager.buildRenderMessages(queryText, claimPlan)
                : promptPackager.buildMessages(
                queryText, intent.name().toLowerCase(), assembly.getKnowledge(),
                sufficiency.answerable, promotionInsufficient);
        List<Map<String, Object>> allCitations = assembly.getCitations();
        List<Map<String, Object>> citations = filterCitationsByClaimPlan(allCitations, claimPlan, structuredRender);
        List<String> selectedDocFiles = rankedDocs.stream()
                .map(DocumentEvidence::getFileName)
                .filter(name -> name != null && !name.trim().isEmpty())
                .collect(Collectors.toList());
        List<String> citationFiles = citations.stream()
                .map(cite -> stringValue(cite.get("file_name")))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("answerable", sufficiency.answerable);
        summary.put("confidence", sufficiency.confidence);
        summary.put("answerable_reason", sufficiency.reason);
        summary.put("intent", intent.name().toLowerCase());
        summary.put("question_plan", questionPlan.toMap());
        summary.put("qa_hits", qaHits.size());
        summary.put("doc_candidates", candidates == null ? 0 : candidates.size());
        summary.put("doc_candidates_reused", reusedDocCandidates);
        summary.put("grouped_docs", groupedDocs.size());
        summary.put("selected_docs", rankedDocs.size());
        summary.put("selected_doc_files", selectedDocFiles);
        summary.put("citation_count", citations.size());
        summary.put("prompt_citation_count", allCitations.size());
        summary.put("citation_files", citationFiles);
        summary.put("recall_top_k", recallTopK);
        summary.put("prompt_min_docs", promptMinDocs);
        summary.put("prompt_max_docs", promptMaxDocs);
        summary.put("prompt_chunks", assembly.getMaterialCount());
        summary.put("qa_prompt_chunks", assembly.getQaMaterialCount());
        summary.put("qa_skipped_hits", assembly.getSkippedQaHits());
        summary.put("prompt_chars", assembly.getPromptChars());
        summary.put("target_prompt_chars", qaAnswerProperties.getTargetPromptChars());
        summary.put("truncated_evidence_count", assembly.getTruncatedEvidenceCount());
        summary.put("prompt_pruned_chars", assembly.getPromptPrunedChars());
        summary.put("prompt_message_chars", messageChars(messages));
        summary.put("max_citations", qaAnswerProperties.getMaxCitations());
        summary.put("max_total_chunks", qaAnswerProperties.getMaxTotalChunks());
        summary.put("max_chunks_per_doc", qaAnswerProperties.getMaxChunksPerDoc());
        summary.put("missing_aspects", sufficiency.missingAspects);
        summary.put("sufficiency", sufficiency.toMap());
        summary.put("evidence_ledger", assembly.getLedger().toMap());
        summary.put("structured_render", structuredRender);
        summary.put("structured_qa_enabled", structuredQaEnabled);
        if (factExtraction != null) {
            summary.put("facts", factExtraction.toMap());
        }
        if (factValidation != null) {
            summary.put("fact_validation", factValidation.toMap());
        }
        if (claimPlan != null) {
            summary.put("claim_plan", claimPlan.toMap());
        }
        if (claimValidation != null) {
            summary.put("claim_validation", claimValidation.toMap());
        }
        summary.put("timings", timings);
        long totalCostMs = System.currentTimeMillis() - startMs;
        summary.put("cost_ms", totalCostMs);

        System.out.printf("[QA Plan] query='%s' intent=%s qaHits=%d candidates=%d grouped=%d selected=%d citations=%d chunks=%d chars=%d answerable=%s files=%s cost=%dms timings=%s%n",
                preview(queryText, 40), intent, qaHits.size(), candidates == null ? 0 : candidates.size(),
                groupedDocs.size(), rankedDocs.size(), citations.size(), assembly.getMaterialCount(),
                assembly.getPromptChars(), sufficiency.answerable, citationFiles, totalCostMs, timings);

        QaAnswerPlan answerPlan = new QaAnswerPlan(messages, citations, summary);
        answerPlan.setClaimPlan(claimPlan);
        answerPlan.setStructuredRender(structuredRender);
        return answerPlan;
    }

    private int messageChars(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, String> message : messages) {
            if (message == null) {
                continue;
            }
            for (String value : message.values()) {
                total += value == null ? 0 : value.length();
            }
        }
        return total;
    }

    private boolean shouldRunStructuredQa(QuestionPlan questionPlan, QaPromptAssembly assembly) {
        if (!qaAnswerProperties.isClaimPlanEnabled()) {
            return false;
        }
        String mode = qaAnswerProperties.getStructuredMode() == null
                ? "auto"
                : qaAnswerProperties.getStructuredMode().trim().toLowerCase();
        if ("off".equals(mode)) {
            return false;
        }
        if ("always".equals(mode)) {
            return true;
        }
        if (questionPlan == null) {
            return false;
        }
        if (questionPlan.isStatusSensitive() || questionPlan.isRequiresMultiDoc()) {
            return true;
        }
        QaIntent intent = questionPlan.getIntent();
        String intentName = intent == null ? "" : intent.name();
        List<String> riskIntents = qaAnswerProperties.getStructuredRiskIntents();
        if (riskIntents != null) {
            for (String riskIntent : riskIntents) {
                if (intentName.equalsIgnoreCase(String.valueOf(riskIntent).trim())) {
                    return true;
                }
            }
        }
        return assembly != null
                && assembly.getLedger() != null
                && assembly.getLedger().selectedRecords().size() > qaAnswerProperties.getStructuredMaxEvidenceRecords();
    }

    private List<Map<String, Object>> filterCitationsByClaimPlan(List<Map<String, Object>> citations,
                                                                 ClaimPlan claimPlan,
                                                                 boolean structuredRender) {
        if (!structuredRender || claimPlan == null || claimPlan.getClaims().isEmpty()) {
            return citations == null ? new ArrayList<>() : citations;
        }
        Set<Integer> usedIndexes = new HashSet<>();
        for (ClaimUnit claim : claimPlan.getClaims()) {
            usedIndexes.addAll(claim.getCitationIndexes());
        }
        if (usedIndexes.isEmpty()) {
            return citations == null ? new ArrayList<>() : citations;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> citation : citations == null ? Collections.<Map<String, Object>>emptyList() : citations) {
            Object rawIndex = citation.get("index");
            Integer index = null;
            if (rawIndex instanceof Number) {
                index = ((Number) rawIndex).intValue();
            } else if (rawIndex != null) {
                try {
                    index = Integer.parseInt(rawIndex.toString().trim());
                } catch (NumberFormatException ignored) {
                    index = null;
                }
            }
            if (index != null && usedIndexes.contains(index)) {
                filtered.add(citation);
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchQaHits(String queryText, int topK) {
        List<Map<String, Object>> qaHits = new ArrayList<>();
        try {
            Map<String, Object> dual = aiEngineGateway.fetchDualVector(queryText);
            List<Double> denseVec = dual != null ? (List<Double>) dual.get("dense") : null;
            if (denseVec != null && !denseVec.isEmpty()) {
                qaHits.addAll(aiEngineGateway.fetchQaResults(denseVec, queryText, null, Math.max(1, topK)));
                qaHits.removeIf(hit -> getQaConfidence(hit) < qaAnswerProperties.getMinQaConfidence());
            } else {
                qaHits.addAll(aiEngineGateway.fetchQaResultsByBm25(queryText, null, Math.max(1, topK)));
                qaHits.removeIf(hit -> getQaConfidence(hit) < qaAnswerProperties.getMinQaConfidence());
            }
        } catch (Exception e) {
            System.err.println("[QA Plan] QA recall failed, continuing with document recall: " + e.getMessage());
        }
        return qaHits;
    }

    private QaPromptAssembly assemblePromptEvidence(String appCode,
                                                    String queryText,
                                                    QaIntent intent,
                                                    List<Map<String, Object>> qaHits,
                                                    List<DocumentEvidence> rankedDocs) {
        QaPromptAssembly assembly = promptPackager.newAssembly();
        Set<String> fingerprints = new HashSet<>();

        List<DocumentEvidence> docs = rankedDocs == null ? Collections.emptyList() : rankedDocs;
        int maxDocs = Math.min(qaAnswerProperties.getMaxDocs(), docs.size());
        for (int i = 0; i < maxDocs; i++) {
            DocumentEvidence doc = docs.get(i);
            List<Map<String, Object>> evidenceChunks = selectEvidenceChunks(appCode, doc, intent);
            int injectedForDoc = 0;
            for (Map<String, Object> chunk : evidenceChunks) {
                if (assembly.getMaterialCount() >= qaAnswerProperties.getMaxTotalChunks()
                        || injectedForDoc >= perDocLimit(intent)
                        || assembly.getPromptChars() >= qaAnswerProperties.getPromptMaxChars()) {
                    break;
                }
                String promptChunk = cleanPromptChunk(chunk.getOrDefault("chunk_text", chunk.get("content")));
                if (promptChunk.isEmpty()) {
                    continue;
                }
                String fingerprint = doc.getFileName() + "::" + promptChunk;
                if (!fingerprints.add(fingerprint)) {
                    continue;
                }
                Object chunkIndex = chunk.getOrDefault("chunk_index", chunk.get("chunk_id"));
                EvidenceUnit evidence = new EvidenceUnit(
                        assembly.getLedger().nextEvidenceId(),
                        doc.getFileName(), doc.getDocId(), chunkIndex, promptChunk,
                        "doc_chunk", "doc_recall", numberValue(chunk.get("_score")), chunk);
                if (promptPackager.appendEvidence(assembly, evidence)) {
                    injectedForDoc++;
                }
            }
        }

        // QA pairs store the original source chunk as answer_content. Treat them as
        // source evidence, not as model-written answers.
        for (Map<String, Object> qaHit : qaHits == null ? Collections.<Map<String, Object>>emptyList() : qaHits) {
            if (assembly.getMaterialCount() >= qaAnswerProperties.getMaxTotalChunks()
                    || assembly.getPromptChars() >= qaAnswerProperties.getPromptMaxChars()) {
                break;
            }
            Map<String, Object> qaSource = sourceOf(qaHit);
            if (!isLatestEvidence(qaSource)) {
                assembly.incrementSkippedQaHits();
                assembly.getLedger().recordSkipped("qa_not_latest", qaSource);
                continue;
            }
            String sourceName = stringValue(qaSource.getOrDefault("source", ""));
            String evidence = cleanPromptChunk(qaSource.getOrDefault("answer_content", qaSource.get("content")));
            if (sourceName.isEmpty() || evidence.isEmpty()) {
                assembly.incrementSkippedQaHits();
                assembly.getLedger().recordSkipped("qa_missing_source_or_content", qaSource);
                continue;
            }
            boolean alreadyCovered = assembly.citationByDoc().containsKey(sourceName);
            if (alreadyCovered) {
                continue;
            }
            String fingerprint = sourceName + "::qa::" + evidence;
            if (!fingerprints.add(fingerprint)) {
                continue;
            }
            EvidenceUnit qaEvidence = new EvidenceUnit(
                    assembly.getLedger().nextEvidenceId(),
                    sourceName,
                    qaSource.getOrDefault("doc_hash", qaSource.get("answer_chunk_id")),
                    qaSource.get("answer_chunk_id"), evidence,
                    "qa_pair", "qa_recall", getQaConfidence(qaHit), qaSource);
            if (promptPackager.appendEvidence(assembly, qaEvidence)) {
                assembly.incrementQaMaterialCount();
            }
        }
        return assembly;
    }

    private List<Map<String, Object>> selectEvidenceChunks(String appCode, DocumentEvidence doc, QaIntent intent) {
        List<Map<String, Object>> seeds = doc.getChunks();
        if (seeds == null || seeds.isEmpty()) {
            return Collections.emptyList();
        }
        if (qaAnswerProperties.isAllowPromptStageEsFetch()) {
            System.err.println("[QaAnswerService] WARN: allowPromptStageEsFetch is true but fetchNeighborhoodChunks was removed. Please rely on RerankStep prefetch.");
        }
        return seeds;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceOf(Map<String, Object> hit) {
        if (hit == null) {
            return new LinkedHashMap<>();
        }
        Object source = hit.get("_source");
        if (source instanceof Map) {
            return (Map<String, Object>) source;
        }
        return hit;
    }

    private double getQaConfidence(Map<String, Object> hit) {
        if (hit == null) {
            return 0.0;
        }
        Object conf = hit.get("_qa_confidence");
        if (conf instanceof Number) {
            return ((Number) conf).doubleValue();
        }
        Object score = hit.get("_score");
        if (score instanceof Number) {
            return Math.min(((Number) score).doubleValue(), 1.0);
        }
        return 0.0;
    }

    private String cleanPromptChunk(Object rawChunk) {
        if (rawChunk == null) {
            return "";
        }
        return rawChunk.toString().replaceAll("</?em[^>]*>", "").trim();
    }

    private Integer numericChunkIndex(Map<String, Object> chunk) {
        if (chunk == null) {
            return null;
        }
        Object value = chunk.getOrDefault("chunk_index", chunk.get("chunk_id"));
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            String text = String.valueOf(value);
            if (text.contains("_chunk_")) {
                text = text.substring(text.lastIndexOf("_chunk_") + 7);
            }
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int perDocLimit(QaIntent intent) {
        if (intent == QaIntent.LIST || intent == QaIntent.PROCEDURE || intent == QaIntent.CONDITION) {
            return qaAnswerProperties.getMaxChunksPerDoc();
        }
        return 4;
    }

    private int promptMinDocs(QuestionPlan questionPlan) {
        QaIntent intent = questionPlan == null ? QaIntent.UNKNOWN : questionPlan.getIntent();
        int configured = Math.max(1, qaAnswerProperties.getMinDocs());
        if (intent == QaIntent.LIST || intent == QaIntent.PROCEDURE || intent == QaIntent.CONDITION) {
            configured = Math.max(configured, 3);
        }
        if (questionPlan != null && (questionPlan.isStatusSensitive() || questionPlan.isRequiresMultiDoc())) {
            configured = Math.max(configured, 3);
        }
        if (intent == QaIntent.COMPARISON) {
            configured = Math.max(configured, 2);
        }
        return Math.min(configured, Math.max(1, qaAnswerProperties.getMaxDocs()));
    }

    private int promptMaxDocs(QuestionPlan questionPlan) {
        QaIntent intent = questionPlan == null ? QaIntent.UNKNOWN : questionPlan.getIntent();
        int configured = Math.max(1, qaAnswerProperties.getMaxDocs());
        if (questionPlan != null && (questionPlan.isStatusSensitive() || questionPlan.isRequiresMultiDoc())) {
            return configured;
        }
        if (intent == QaIntent.FACT || intent == QaIntent.COUNT_OR_TIME) {
            return Math.min(configured, 3);
        }
        return configured;
    }

    private int windowBefore(QaIntent intent) {
        if (intent == QaIntent.LIST || intent == QaIntent.PROCEDURE || intent == QaIntent.CONDITION) {
            return 2;
        }
        return 1;
    }

    private int windowAfter(QaIntent intent) {
        if (intent == QaIntent.LIST || intent == QaIntent.PROCEDURE || intent == QaIntent.CONDITION) {
            return 4;
        }
        return 1;
    }

    private boolean isLatestEvidence(Map<String, Object> source) {
        if (source == null) {
            return false;
        }
        Object latest = source.get("is_latest");
        if (latest == null) {
            return true;
        }
        return Boolean.TRUE.equals(latest)
                || Integer.valueOf(1).equals(latest)
                || "true".equalsIgnoreCase(String.valueOf(latest));
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private Double numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String preview(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen) + "...";
    }

}
