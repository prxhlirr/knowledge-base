package com.boyang.search.pipeline.steps;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 管线节点 6：精排与结果装配 (Rerank & Collapse)
 * 负责? * - Layered Veto 过滤掉劣质召? * - ColBERT / LLM 重排
 * - LTR 最终打分计? * - 同文?Chunk 折叠去重 (Result Collapsing)
 */
@Component
public class RerankStep implements SearchPipelineStep {

    // [P1-1 修复] ColBERT 背压信号量：限制同时进入 ColBERT 的并发数（Spring @Component 单例，全局唯一）?    // 根因：高并发峰候多请求同时等待 ColBERT GPU 推理，将导致显存溢出 + 排队雪崩?    // 基于公式：并?4 保证 GPU VRAM 不溢（平?400MB/?x 4 = 1.6GB < 4B）?    // tryAcquire 失败时降级为 RRF 排序，不阻断主链路
    private static final int COLBERT_SEM_MAX = 4;
    private static final Semaphore COLBERT_SEM = new Semaphore(COLBERT_SEM_MAX, true);

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Override
    public void execute(SearchContext context) throws Exception {
        if (context.getFastTrackDocs() != null && !context.getFastTrackDocs().isEmpty()) {
            context.setFinalResult(context.getFastTrackDocs());
            return;
        }

        List<Map<String, Object>> candidates = context.getCandidateDocs();
        if (candidates == null || candidates.isEmpty()) {
            context.setFinalResult(Collections.emptyList());
            return;
        }

        SysAiTuningConfig config = context.getTuningConfig();
        String queryText = context.getQueryText();

        // [1] Layered Veto Gates: 过滤无望垃圾
        int preVetoSize = candidates.size();
        candidates.removeIf(cand -> {
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));
            if (qaHit) return false;
            
            Object knnScObj = cand.get("_max_knn_score");
            double knnSim = (knnScObj instanceof Number) ? ((Number) knnScObj).doubleValue() : -1.0;
            
            Object rrfObj = cand.get("_rrf_score");
            double rrfScore = (rrfObj instanceof Number) ? ((Number) rrfObj).doubleValue() : 0.0;
            
            return (knnSim >= 0 && knnSim < 0.15 && rrfScore < 0.005);
        });

        // 取出要送重排的文本
        List<String> docTexts = new ArrayList<>();
        int maxChars = config.getRerankMaxChars() != null ? config.getRerankMaxChars() : 500;
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> source = (Map<String, Object>) candidate.get("_source");
            if (source != null) {
                String rawContent = (String) source.get("content");
                String docSource = "";
                Map<String, Object> srcMetaForRerank = (Map<String, Object>) source.get("metadata");
                if (srcMetaForRerank != null && srcMetaForRerank.get("source") != null) {
                    docSource = "[Source: " + srcMetaForRerank.get("source") + "] ";
                }
                String rerankText = docSource + (rawContent != null && rawContent.length() > maxChars 
                        ? rawContent.substring(0, maxChars) : (rawContent != null ? rawContent : ""));
                docTexts.add(rerankText);
            }
        }

        // [P1-2 补充] BM25 高频词检测：命中?> 50 的短查询（≤4字）属于歧义高频词，
        // 即便?NAVIGATIONAL 意图也需要强制走 Reranker 消歧?        // 根因：\"苹果\"、\"通知\" 等词 BM25 命中上百文档，无法通过 BM25 分数区分相关性，
        //       只有 ColBERT MaxSim 才能精准识别语义意图
        long bm25TextHits = context.getBm25TextHits();
        final boolean bm25HighFreqTerm = context.isNavigationalBypass()
                && queryText != null
                && queryText.trim().length() <= 4
                && bm25TextHits > 50;
        if (bm25HighFreqTerm) {
            System.out.printf("[HighFreqTerm] 短查?BM25 命中?%d > 50，词汇高频歧义，将强?Reranker 消歧%n", bm25TextHits);
        }

        // [P1-2 修复] BM25 平坦度检测：top1/top5avg ratio < 1.3 = 无明显赢?= 歧义词信?        // 根因：原方案通过 filters Map 魔法 Key 传递，FastTrack 场景下未写入会静默失效?        // 修复：读?context.getBm25FlatnessRatio()（强类型字段，默?99.0），?null 风险
        boolean bm25IsFlat = false;
        double bm25FlatnessRatio = context.getBm25FlatnessRatio();
        if (context.isNavigationalBypass() && bm25FlatnessRatio < 1.3) {
            bm25IsFlat = true;
            System.out.printf("[BM25Flatness] ratio=%.2f < 1.3，分数无赢家，将强制 Reranker 消歧%n", bm25FlatnessRatio);
        }

        // [2] 决定并执行候选的二次重排 (ColBERT)
        List<Double> rerankScores = null;
        boolean forceRerankForSemantics = false;
        // NAVIGATIONAL 查询通常跳过 Reranker，但以下情况强制走：
        //   1. BM25 最高分 < 1.0（无强词汇信号）
        //   2. BM25 高频词命中数 > 50（高频歧义词，需 ColBERT 消歧）[P1-2 新增]
        //   3. BM25 平坦?ratio < 1.3（分数分布无明显赢家）[P1-2 新增]
        if (context.isNavigationalBypass() && !candidates.isEmpty()) {
            double highestBM25 = candidates.stream().mapToDouble(c -> {
                Object esScoreObj = c.get("_es_score");
                return (esScoreObj instanceof Number) ? ((Number) esScoreObj).doubleValue() : 0.0;
            }).max().orElse(0.0);
            if (highestBM25 < 1.0 || bm25HighFreqTerm || bm25IsFlat) forceRerankForSemantics = true;
        }


        boolean shouldRunReranker = !candidates.isEmpty() 
            && (!context.isNavigationalBypass() || forceRerankForSemantics) 
            && !Boolean.TRUE.equals(config.getCircuitBreakerEnabled()) 
            && !docTexts.isEmpty();

        int effectiveLimit = 0;
        if (shouldRunReranker) {
            int rerankLimit = config.getRerankLimit() != null ? config.getRerankLimit() : 15;
            String mode = aiEngineGateway.getCachedAcceleration();
            if (!"CUDA".equals(mode) && !"DML".equals(mode)) {
                // [Step5 优化] CPU 模式候选数 5??                // 根因：CPU CrossEncoder 每个文档对管理能 ~400ms?个文?2000ms?                // 3个文?1200ms，在場景典型召回 Top3 足够识别相关性，
                // 高相关语义第4/5个让 RRF+Es 得分?将其摈除
                rerankLimit = Math.min(rerankLimit, 3);
            }
            
            // Limit characters completely
            final int MAX_GLOBAL_CHARS = 3500;
            List<String> limitedDocs = new ArrayList<>();
            int currentChars = 0;
            effectiveLimit = Math.min(docTexts.size(), rerankLimit);
            
            for (int i = 0; i < effectiveLimit; i++) {
                String d = docTexts.get(i);
                if (currentChars + d.length() > MAX_GLOBAL_CHARS) {
                    int rem = Math.max(0, MAX_GLOBAL_CHARS - currentChars);
                    if (rem > 50) {
                        limitedDocs.add(d.substring(0, rem));
                        currentChars += rem;
                    }
                    break;
                }
                limitedDocs.add(d);
                currentChars += d.length();
            }

            // [P1-1 修复] ColBERT 调用包裹 Semaphore 背压拤控，防止并发峰候爆 GPU?            // 动态超时策略：forceRerankForSemantics=true 时给 4000ms（CPU 模式插健需要）?            //              普通逐次 800ms
            if (COLBERT_SEM.tryAcquire()) {
                try {
                    long colbertTimeoutMs = forceRerankForSemantics ? 2000L : 500L;
                    rerankScores = CompletableFuture
                        .supplyAsync(() -> aiEngineGateway.fetchColbertScores(queryText, limitedDocs))
                        .get(colbertTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    System.err.printf("[ColBERT] 超时(%dms)，降级 RRF 排序%n", forceRerankForSemantics ? 2000 : 500);
                } catch (Exception ex) {
                    System.err.println("[ColBERT] 调用异常，降?RRF 排序: " + ex.getMessage());
                } finally {
                    COLBERT_SEM.release();  // finally 保证必然释放，防止信号量泄漏
                }
            } else {
                System.out.printf("[ColBERT Semaphore] 超出并发上限 %d，降?RRF 排序%n", COLBERT_SEM_MAX);
            }

            // Collective Veto Logic
            if (rerankScores != null && !rerankScores.isEmpty()) {
                double vetoThresh = config.getColbertVetoThreshold();
                long vetoCount = rerankScores.stream().filter(s -> s != null && s < vetoThresh).count();
                double vetoRatio = (double) vetoCount / rerankScores.size();
                boolean hasWinner = rerankScores.stream().anyMatch(s -> s != null && s >= vetoThresh);

                if (vetoRatio > 0.70 && rerankScores.size() >= 5 && !hasWinner) {
                    System.out.println(" [CollectiveVeto] Triggered. Returning null.");
                    context.setFinalResult(Collections.emptyList());
                    return;
                }
            }
        }

        // [3] LTR 打分构建
        List<Map<String, Object>> ltrFeaturesList = new ArrayList<>();
        double esNormBase = config.getEsNormBase() != null ? config.getEsNormBase().doubleValue() : 20.0;
        if (esNormBase < 0.001) esNormBase = 20.0;

        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> cand = candidates.get(i);
            
            Object knnScoreObj = cand.get("_max_knn_score");
            double knnCosineSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : -1.0;
            Object scoreObj = cand.get("_es_score");
            double rawEsScore = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;
            double rrfScore = (double) cand.getOrDefault("_rrf_score", 0.0);
            
            double normalizedEsScore = rawEsScore / (rawEsScore + esNormBase);
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));
            
            Map<String, Object> features = new HashMap<>();
            features.put("qa_hit", qaHit);
            features.put("raw_es_score", rawEsScore);
            features.put("normalized_es_score", normalizedEsScore);
            features.put("knn_score", knnCosineSim);
            features.put("rrf_score", rrfScore);
            
            if (rerankScores != null && !rerankScores.isEmpty()) {
                if (i < rerankScores.size()) {
                    features.put("rerank_score", rerankScores.get(i));
                } else {
                    // [Batch3b 修复] 未被 reranker 处理的文档（超出 effectiveLimit 部分?                    // 原因：用 0.0 惩罚这些文档，导致它们在 LTR 打分中被严重压制?                    //       破坏了排名公平性（这些文档并非"无关"，只是未被精排而已）?                    // 修复：用 RRF 分（×10 归一化到 [0,1]）作为代理分?                    //       保留其相对召回质量信息，不额外惩罚
                    double rrfProxy = Math.min(rrfScore * 10.0, 1.0);
                    features.put("rerank_score", rrfProxy);
                }
            }
            ltrFeaturesList.add(features);
        }

        // [Step4 优化] LTR 公式内联?Java，消?Python RPC ?~150ms 往返延迟?        // 根因：LTR 调用的是线性组合公式，返回内容?AiEngineGateway.fetchLtrScores fallback 外层一致?        // 公式：score = max(normalized_es_score, rerank_score, rrf_score × 10)
        List<Double> ltrScores = new ArrayList<>();
        for (Map<String, Object> feat : ltrFeaturesList) {
            double score = (feat.get("normalized_es_score") instanceof Number)
                    ? ((Number) feat.get("normalized_es_score")).doubleValue() : 0.0;
            // ColBERT rerank 分（若已运行）优
            if (feat.get("rerank_score") instanceof Number) {
                double rerankScore = ((Number) feat.get("rerank_score")).doubleValue();
                score = Math.max(score, rerankScore);
            }
            // RRF 分与 ES 归一化分取最大
            Object rrfObj = feat.get("rrf_score");
            if (rrfObj instanceof Number && ((Number) rrfObj).doubleValue() > 0) {
                score = Math.max(score, ((Number) rrfObj).doubleValue() * 10.0);
            }
            // QA 命中直接赋满
            if (Boolean.TRUE.equals(feat.get("qa_hit"))) score = 1.0;
            ltrScores.add(score);
        }
        List<Map<String, Object>> scoredResults = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> cand = candidates.get(i);
            Map<String, Object> source = (Map<String, Object>) cand.get("_source");
            double finalScore = (ltrScores != null && ltrScores.size() > i) ? ltrScores.get(i) : 0.0001;

            Map<String, Object> docMap = new HashMap<>();
            docMap.put("_source", source);
            docMap.put("_id", cand.get("_id"));
            docMap.put("raw_score", finalScore);

            String snippetText = null;
            if (cand.containsKey("highlight") && cand.get("highlight") != null) {
                Map<String, List<String>> hlMap = (Map<String, List<String>>) cand.get("highlight");
                List<String> hlContent = hlMap.get("content");
                if (hlContent != null && !hlContent.isEmpty()) {
                    snippetText = String.join(" ... ", hlContent).replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
                }
            }
            if (snippetText == null || snippetText.isEmpty()) {
                // [P1-3 修复] ?V1 对齐：ES highlight 为空时使用关键词定位摘要 + Java 层高亮，
                // 原代码直接截?150 字，无关键词定位且无高亮，用户体验与 V1 不一致
                String content = source != null ? (String) source.getOrDefault("content", "") : "";
                String snippet = generateFallbackSnippet(content, queryText);
                String highlightSource = (queryText + " " + (context.getRewrittenQuery() != null ? context.getRewrittenQuery() : "")).trim();
                snippetText = highlightText(snippet, highlightSource);
            }

            docMap.put("chunk_text", snippetText);
            scoredResults.add(docMap);
        }

        scoredResults.sort((a, b) -> Double.compare((Double) b.get("raw_score"), (Double) a.get("raw_score")));

        // Quality Breaker checking...
        double topScore = scoredResults.isEmpty() ? 0 : (Double) scoredResults.get(0).get("raw_score");
        boolean rerankerRan = (rerankScores != null && !rerankScores.isEmpty());
        double corpusThresh = rerankerRan ? config.getOutOfCorpusThreshold() : config.getOutOfCorpusThresholdNoRerank();
        if (topScore < corpusThresh) {
            context.setFinalResult(Collections.emptyList());
            return;
        }

        double relativeThreshold = Math.max(topScore * 0.20, config.getQualityBreakerFloor());
        scoredResults.removeIf(docMap -> {
            Double raw = (Double) docMap.get("raw_score");
            return raw != null && raw < relativeThreshold && raw < 0.99;
        });

        // Compute scores and fold
        for (Map<String, Object> docMap : scoredResults) {
            if (docMap.containsKey("raw_score")) {
                double raw = (Double) docMap.get("raw_score");
                double compressed = Math.pow(raw, 1.8);
                docMap.put("score", Math.round(Math.max(0.05, Math.min(0.99, compressed)) * 10000.0) / 10000.0);
            }
        }

        // [4] Result Collapsing（按文件名折叠，避免同文档多切片霸屏?        // [P0-3 修复] 对标 V1 逻辑，在同文档多 chunk 时优先保留「含查询词的 chunk」，
        // 即便其得分略低于最高分 chunk，也应优先展示（内容相关?> 纯分数排名）?        // 根因：若最高分 chunk 不含查询词，用户看到的是「文档存在但内容空白」的体验
        Map<String, Map<String, Object>> fileAggregatedMap = new LinkedHashMap<>();
        for (Map<String, Object> docMap : scoredResults) {
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = source != null ? (Map<String, Object>) source.get("metadata") : null;

            // 首选：使用物理文件名（metadata.source）去
            String dedupeKey = null;
            if (metaMap != null && metaMap.containsKey("source")) {
                dedupeKey = (String) metaMap.get("source");
            }
            // 兜底：无文件名时?ES ID 去掉 _chunk_ 后缀
            if (dedupeKey == null || dedupeKey.isEmpty()) {
                String esId = (String) docMap.get("_id");
                if (esId != null && esId.contains("_chunk_")) {
                    dedupeKey = esId.substring(0, esId.lastIndexOf("_chunk_"));
                } else {
                    dedupeKey = esId;
                }
            }
            if (dedupeKey == null || dedupeKey.isEmpty()) {
                dedupeKey = UUID.randomUUID().toString();
            }

            docMap.put("extracted_doc_id", dedupeKey);

            if (!fileAggregatedMap.containsKey(dedupeKey)) {
                // 首次见到该文档，直接存入（当前即为最高分 chunk
                fileAggregatedMap.put(dedupeKey, docMap);
            } else {
                // [P0-3 修复核心] 优先选「含查询词的 chunk」，?V1 Result Collapsing 逻辑完全对齐?                // - 若当?chunk ?queryText、而已存储?chunk 不含，则替换（保留两者较大分数）
                // - 否则保持原有高分 chunk 不变，不做替
                Map<String, Object> existingMap = fileAggregatedMap.get(dedupeKey);
                String existingChunkText = (String) existingMap.getOrDefault("chunk_text", "");
                String currentChunkText  = (String) docMap.getOrDefault("chunk_text", "");
                final String qt = queryText != null ? queryText : "";
                final String nq = context.getNormalizedQuery() != null ? context.getNormalizedQuery() : "";
                boolean existingHits = existingChunkText != null &&
                    (existingChunkText.contains(qt) || existingChunkText.contains(nq));
                boolean currentHits  = currentChunkText != null &&
                    (currentChunkText.contains(qt) || currentChunkText.contains(nq));
                if (currentHits && !existingHits) {
                    // 内容替换，但分数保留两者中较大值（不降低文档在结果列表中的排名
                    double existingScore = existingMap.containsKey("score") ? (Double) existingMap.get("score") : 0.0;
                    double currentScore  = docMap.containsKey("score") ? (Double) docMap.get("score") : 0.0;
                    docMap.put("score", Math.max(existingScore, currentScore));
                    fileAggregatedMap.put(dedupeKey, docMap);
                    System.out.println("[Collapsing] 用含查询词的 chunk 替换: key=" + dedupeKey);
                }
                // 否则：保持原有高?chunk 不变
            }
        }

        List<Map<String, Object>> collapsedResults = new ArrayList<>(fileAggregatedMap.values());

        // Construct final data format for front-end
        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(collapsedResults.size(), context.getTopK());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> docMap = collapsedResults.get(i);
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = source != null ? (Map<String, Object>) source.get("metadata") : null;
            
            if (metaMap != null) {
                String docId = (String) docMap.getOrDefault("extracted_doc_id", metaMap.get("doc_id"));
                docMap.put("organization", metaMap.getOrDefault("source", "默认组织"));
                docMap.put("publish_time", metaMap.getOrDefault("publish_time", "2024-01-10"));
                docMap.put("tags", metaMap.get("tags"));
                docMap.put("custom_tags", metaMap.get("custom_keywords"));
                docMap.put("doc_id", docId);
                // [文件跳转] 透传原始文件名，前端用来调用 /api/v1/file/preview?sourceName=xxx
                docMap.put("file_name", metaMap.getOrDefault("source", ""));
            }
            docMap.remove("_source");
            docMap.remove("extracted_doc_id");
            docMap.remove("raw_score");
            results.add(docMap);
        }

        context.setFinalResult(results);
        System.out.println("====== [Pipeline] Node 6: Output Ready ======");
    }

    /**
     * 从文档内容中提取关键词定位摘要（对应 V1 generateFallbackSnippet 逻辑）?     *
     * 业务功能?     *   1. OOM Guard：将超长 content 截断?2000 字再处理
     *   2. 清除 [标签: 内容] 格式的上下文标注前缀（不应展示给用户?     *   3. ?queryText 定位关键词位置，截取前后 40/60 字上下文作为摘要
     *   4. 兜底：无法定位时返回?100 ?     *
     * @param content   文档原始 content 字段
     * @param queryText 原始用户查询词（用于定位摘要起点?     */
    private String generateFallbackSnippet(String content, String queryText) {
        if (content == null || content.isEmpty()) return "";
        // OOM Guard：replaceAll 对超长字符串会构建等?StringBuffer，需先截
        final int MAX_CONTENT = 2000;
        String safeContent = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        // 清除形如 [语篇语境: 乡道] 的上下文标注前缀，不应展示给用户
        String cleanContent = safeContent.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
        if (queryText == null || queryText.isEmpty()) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int idx = cleanContent.indexOf(queryText);
        if (idx == -1) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int start = Math.max(0, idx - 40);
        int end   = Math.min(cleanContent.length(), idx + queryText.length() + 60);
        String snippet = cleanContent.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < cleanContent.length()) snippet = snippet + "...";
        return snippet;
    }

    /**
     * Java 层高亮：?snippet 中命中的查询词包?HTML 高亮标签?     *
     * 业务功能：当 ES highlight 字段为空（KNN 检索或 highlight 未触发）时，
     * ?Java 层对 generateFallbackSnippet 返回的摘要手动添?<em class='highlight'> 标记?     * 保证前端高亮显示的一致性?     *
     * @param text  待高亮的摘要文本
     * @param query 查询词（空格分隔多词?     */
    private String highlightText(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty()) return text;
        // OOM Guard：限定高亮操作的文本长度上界，防?replaceAll StringBuffer 溢出
        final int MAX_HIGHLIGHT_LEN = 1500;
        String safeText = text.length() > MAX_HIGHLIGHT_LEN ? text.substring(0, MAX_HIGHLIGHT_LEN) : text;
        // 按词分割后按长度降序排序，长词优先匹配（防止短词提前替换破坏长词标记
        Set<String> kwSet = new HashSet<>(Arrays.asList(query.split("\\s+")));
        List<String> sortedKws = new ArrayList<>(kwSet);
        sortedKws.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = safeText;
        for (String kw : sortedKws) {
            if (kw.trim().length() < 1) continue;
            try {
                String patternStr = "(?i)(" + Pattern.quote(kw) + ")";
                result = result.replaceAll(patternStr, "<em class='highlight'>$1</em>");
            } catch (Exception e) {
                // 正则异常时跳过该词，不影响其他词的高
            }
        }
        return result;
    }
}

