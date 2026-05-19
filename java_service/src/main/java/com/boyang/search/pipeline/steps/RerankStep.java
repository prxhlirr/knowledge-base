package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.qa.QaAnswerProperties;
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

    // [P1-1 修复] ColBERT 背压信号量：限制同时进入 ColBERT 的并发数（Spring @Component 单例，全局唯一）? //
    // 根因：高并发峰候多请求同时等待 ColBERT GPU 推理，将导致显存溢出 + 排队雪崩? // 基于公式：并?4 保证 GPU VRAM
    // 不溢（平?400MB/?x 4 = 1.6GB < 4B）? // tryAcquire 失败时降级为 RRF 排序，不阻断主链路
    private static final int COLBERT_SEM_MAX = 4;
    private static final Semaphore COLBERT_SEM = new Semaphore(COLBERT_SEM_MAX, true);

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private QaAnswerProperties qaAnswerProperties;

    @Override
    public void execute(SearchContext context) throws Exception {
        // [FastTrack] QaInjectionStep 设置的高置信 QA：需要做 snippet 结构化处理再返回
        // 根因：FastTrackDocs 之前直接 setFinalResult(fastTrackDocs) 返回 Python 原始 Map，
        // 该 Map 没有 chunk_text / file_name / organization 等前端期望的字段，
        // 导致前端完全无法渲染 QA 卡片，用户看到的是普通文档而非 QA 答案。
        // 修复：FastTrack 场景下对 QA 命中执行轻量 snippet 结构化组装，再写入 finalResult。
        if (context.getFastTrackDocs() != null && !context.getFastTrackDocs().isEmpty()) {
            List<Map<String, Object>> fastTrackResult = new ArrayList<>();
            for (Map<String, Object> ftDoc : context.getFastTrackDocs()) {
                if (Boolean.TRUE.equals(ftDoc.get("_qa_hit"))) {
                    // QA 命中：组装前端期望格式
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ftSource = (Map<String, Object>) ftDoc.get("_source");
                    if (ftSource == null) {
                        fastTrackResult.add(ftDoc);
                        continue;
                    }

                    String qaQuestion = (String) ftSource.getOrDefault("question", "");
                    String qaAnswer = (String) ftSource.getOrDefault("answer_content",
                            ftSource.getOrDefault("content", ""));
                    Object confObj = ftDoc.get("_qa_confidence");
                    double qaConf = (confObj instanceof Number) ? ((Number) confObj).doubleValue() : 0.90;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> qaMeta = (Map<String, Object>) ftSource.get("metadata");

                    Map<String, Object> qaResult = new HashMap<>();
                    qaResult.put("_id", ftDoc.get("_id"));
                    qaResult.put("_source", ftSource);
                    qaResult.put("raw_score", 0.70 + Math.min(qaConf, 1.0) * 0.29);
                    qaResult.put("chunk_text", "【问】" + qaQuestion + "\n【答】" + qaAnswer);
                    qaResult.put("is_qa_answer", Boolean.TRUE);
                    qaResult.put("_qa_hit", Boolean.TRUE);
                    qaResult.put("qa_confidence", (int) Math.round(qaConf * 100));
                    // 组装前端依赖的文档元信息字段
                    String fileName = qaMeta != null ? (String) qaMeta.getOrDefault("source", "") : "";
                    qaResult.put("file_name", fileName);
                    qaResult.put("organization", fileName);
                    qaResult.put("extracted_doc_id",
                            qaMeta != null ? (String) qaMeta.getOrDefault("doc_hash", "") : "");
                    System.out.printf("[FastTrack-QA] 高置信 QA 返回: conf=%.3f file='%s'%n",
                            qaConf, fileName);
                    fastTrackResult.add(qaResult);
                } else {
                    // 非 QA 命中（语义缓存 FastTrack）：原样返回
                    fastTrackResult.add(ftDoc);
                }
            }
            context.setFinalResult(fastTrackResult);
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
            // [架构重构] QA 候选豁免：QA 已经通过 RRF 7公平竞争进入候选池，不需要 Veto Gate 拦截
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));
            if (qaHit)
                return false;

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
                // [架构重构] QA 候选送 ColBERT 时用「『问』...『答』...」格式
                // 根因：ColBERT MaxSim 对"query vs 『问』 Q \n 『答』 A" 的相关性判断比透明“叺排 content”好得多，
                // 确保鱼质量差的 QA（馗问不匹）被正确判居，而不是靠特权分强制占党。
                boolean isQaHit = Boolean.TRUE.equals(candidate.get("_qa_hit"));
                if (isQaHit) {
                    // QA 候选：用「『问』/『答』」结构化输入，让 ColBERT 能评估问答对与查询的相关性
                    String qaQ = (String) source.getOrDefault("question", "");
                    String qaA = (String) source.getOrDefault("answer_content",
                            source.getOrDefault("content", ""));
                    String qaText = "『问』" + qaQ + "\n『答』" + qaA;
                    docTexts.add(qaText.length() > maxChars ? qaText.substring(0, maxChars) : qaText);
                    continue; // 跳过普通 content 构建路径
                }
                // [职责分离] Reranker 输入优先读 display_content（DocExpansionStep 写入的父 coarse 块内容）
                // 根因：Small-to-Big 的核心价值在于给 Reranker 更丰富的上下文（父块语义完整）。
                // 注意：DocExpansionStep 已改为写 display_content 而非覆盖 content，
                // 所以这里 getOrDefault("display_content", content) 才能正确读到父块内容。
                String rawContent = (String) source.getOrDefault("display_content",
                        source.getOrDefault("content", ""));
                String docSource = "";
                Map<String, Object> srcMetaForRerank = (Map<String, Object>) source.get("metadata");
                if (srcMetaForRerank != null && srcMetaForRerank.get("source") != null) {
                    docSource = "[Source: " + srcMetaForRerank.get("source") + "] ";
                }
                String rerankText = docSource + (rawContent != null && rawContent.length() > maxChars
                        ? rawContent.substring(0, maxChars)
                        : (rawContent != null ? rawContent : ""));
                docTexts.add(rerankText);
            }
        }

        // [P1-2 补充] BM25 高频词检测：命中?> 50 的短查询（≤4字）属于歧义高频词，
        // 即便?NAVIGATIONAL 意图也需要强制走 Reranker 消歧? // 根因：\"苹果\"、\"通知\" 等词 BM25 命中上百文档，无法通过
        // BM25 分数区分相关性，
        // 只有 ColBERT MaxSim 才能精准识别语义意图
        long bm25TextHits = context.getBm25TextHits();
        final boolean bm25HighFreqTerm = context.isNavigationalBypass()
                && queryText != null
                && queryText.trim().length() <= 4
                && bm25TextHits > 50;
        if (bm25HighFreqTerm) {
            System.out.printf("[HighFreqTerm] 短查?BM25 命中?%d > 50，词汇高频歧义，将强?Reranker 消歧%n", bm25TextHits);
        }

        // [P1-2 修复] BM25 平坦度检测：top1/top5avg ratio < 1.3 = 无明显赢?= 歧义词信? // 根因：原方案通过
        // filters Map 魔法 Key 传递，FastTrack 场景下未写入会静默失效? //
        // 修复：读?context.getBm25FlatnessRatio()（强类型字段，默?99.0），?null 风险
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
        // 1. BM25 最高分 < 1.0（无强词汇信号）
        // 2. BM25 高频词命中数 > 50（高频歧义词，需 ColBERT 消歧）[P1-2 新增]
        // 3. BM25 平坦?ratio < 1.3（分数分布无明显赢家）[P1-2 新增]
        if (context.isNavigationalBypass() && !candidates.isEmpty()) {
            double highestBM25 = candidates.stream().mapToDouble(c -> {
                Object esScoreObj = c.get("_es_score");
                return (esScoreObj instanceof Number) ? ((Number) esScoreObj).doubleValue() : 0.0;
            }).max().orElse(0.0);
            if (highestBM25 < 1.0 || bm25HighFreqTerm || bm25IsFlat)
                forceRerankForSemantics = true;
        }

        // [Layer3] 公示/法规类文档强制走 Cross-Encoder 精排
        // 根因：前言 chunk 语义向量高度靠近查询词（自我描述性强），BM25+RRF 无法区分
        // "前言描述了主题" 和 "这是对查询的实际答案"。只有 Cross-Encoder 能做
        // (query, chunk) 级别的答案性评分。
        // [Layer3] 公示/法规类文档强制 Cross-Encoder精排
        // 前言 和 人员信息 在语义相似度上差异不大，只有 Cross-Encoder 能识别「答案性」。
        // 受限：仅在 GPU(CUDA/DML) 可用时才强制，CPU 模式下 ColBERT 太慢会超出 SLA。
        // Layer1+2 已在展示层解决了大部分问题，Layer3 是增强项。
        String currentAccelMode = aiEngineGateway.getCachedAcceleration();
        boolean gpuAvailable = "CUDA".equals(currentAccelMode) || "DML".equals(currentAccelMode);
        boolean isSemanticMode = "semantic".equals(context.getSearchMode());
        if (!forceRerankForSemantics && isSemanticMode && gpuAvailable) {
            boolean hasPublicNoticeDocs = candidates.stream().anyMatch(c -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> src2 = (java.util.Map<String, Object>) c.get("_source");
                if (src2 == null)
                    return false;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> meta2 = (java.util.Map<String, Object>) src2.get("metadata");
                if (meta2 == null)
                    return false;
                String dt = String.valueOf(meta2.getOrDefault("doc_type", ""));
                return "公示".equals(dt) || "法规".equals(dt);
            });
            if (hasPublicNoticeDocs) {
                forceRerankForSemantics = true;
                System.out.println("[Layer3] GPU可用 + 公示/法规类 + 语义模式，强制开启 Cross-Encoder 精排");
            }
        } else if (!forceRerankForSemantics && isSemanticMode && !gpuAvailable) {
            // CPU 模式：Layer1+2 已处理展示内容选择，跳过 Cross-Encoder 避免 SLA 超时
            System.out.println("[Layer3] CPU 模式，跳过强制精排（依赖 Layer1+2 展示选择）");
        }
        // [T-keyword] keyword 模式：跳过 ColBERT 精排。
        // 根因：用户意图是精确文档检索，BM25 分已能体现相关性；
        // ColBERT fetchColbertScores 是 Python HTTP 调用，超时上限 6~8s，是 keyword 模式最大耗时来源。
        boolean shouldRunReranker = !candidates.isEmpty()
                && (!context.isNavigationalBypass() || forceRerankForSemantics)
                && !"keyword".equals(context.getSearchMode())
                && !Boolean.TRUE.equals(config.getCircuitBreakerEnabled())
                && !docTexts.isEmpty();

        int effectiveLimit = 0;
        if (shouldRunReranker) {
            int rerankLimit = config.getRerankLimit() != null ? config.getRerankLimit() : 15;
            String mode = aiEngineGateway.getCachedAcceleration();

            // [弹性机制] 判断上帝模式，以便在 CPU 调试时打破 12 的枷锁测试功能
            boolean isGodMode = false;
            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder
                    .getIdentity();
            if (identity != null && identity.isSuperAdmin() && identity.getAclTokens().contains("_SUPER_ADMIN")) {
                isGodMode = true;
            }

            if (!"CUDA".equals(mode) && !"DML".equals(mode) && !isGodMode) {
                // 如果是纯 CPU 且非上帝模式，继续启动熔断保护，最多 12 个分片防止死机
                rerankLimit = Math.min(rerankLimit, 12);
            } else {
                // 如果拥有 GPU 算力或者处于上帝模式，动态随需扩大（最大放开到 100 供分页）
                rerankLimit = Math.max(rerankLimit, Math.min(context.getTopK(), 100));
            }

            // Pre-rerank Diversity Selection: Prioritize different organizations/files
            List<Integer> diverseIndices = new ArrayList<>();
            Set<String> seenOrgs = new HashSet<>();
            List<Integer> deferredIndices = new ArrayList<>();
            effectiveLimit = Math.min(docTexts.size(), rerankLimit);
            for (int i = 0; i < docTexts.size(); i++) {
                Map<String, Object> source = (Map<String, Object>) candidates.get(i).get("_source");
                String org = source != null ? (String) source.getOrDefault("organization", "") : "";
                if (seenOrgs.contains(org)) {
                    deferredIndices.add(i);
                } else {
                    seenOrgs.add(org);
                    diverseIndices.add(i);
                }
                if (diverseIndices.size() >= effectiveLimit)
                    break;
            }
            if (diverseIndices.size() < effectiveLimit) {
                for (int i : deferredIndices) {
                    if (diverseIndices.size() >= effectiveLimit)
                        break;
                    diverseIndices.add(i);
                }
            }

            // Reorder candidates and docTexts according to diverseIndices, and keep the
            // rest intact
            List<Map<String, Object>> newCandidates = new ArrayList<>();
            List<String> newDocTexts = new ArrayList<>();
            for (int idx : diverseIndices) {
                newCandidates.add(candidates.get(idx));
                newDocTexts.add(docTexts.get(idx));
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (!diverseIndices.contains(i)) {
                    newCandidates.add(candidates.get(i));
                    newDocTexts.add(docTexts.get(i));
                }
            }
            candidates.clear();
            candidates.addAll(newCandidates);
            docTexts.clear();
            docTexts.addAll(newDocTexts);

            // Limit characters completely
            final int MAX_GLOBAL_CHARS = 3500;
            List<String> limitedDocs = new ArrayList<>();
            int currentChars = 0;

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

            // [P1-1 修复] ColBERT 调用包裹 Semaphore 背压拤控，防止并发峰候爆 GPU
            // 弹性排队优化：赋予 500ms 缓冲池平滑瞬时波峰，避免大面积静默降级 RRF
            boolean acquired = false;
            try {
                acquired = COLBERT_SEM.tryAcquire(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (acquired) {
                try {
                    long colbertTimeoutMs = forceRerankForSemantics ? 8000L : 6000L;
                    rerankScores = CompletableFuture
                            .supplyAsync(() -> aiEngineGateway.fetchColbertScores(queryText, limitedDocs))
                            .get(colbertTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    System.err.printf("[ColBERT] 超时(%dms)，降级 RRF 排序%n", forceRerankForSemantics ? 8000 : 6000);
                } catch (Exception ex) {
                    System.err.println("[ColBERT] 调用异常，降?RRF 排序: " + ex.getMessage());
                } finally {
                    COLBERT_SEM.release(); // finally 保证必然释放，防止信号量泄漏
                }
            } else {
                System.out.printf("[ColBERT Semaphore] 超出并发上限 %d，降?RRF 排序%n", COLBERT_SEM_MAX);
            }

            // Collective Veto Logic
            // [NavigationalBypass 豁免] NAVIGATIONAL 导航型查询（≤4字关键词）不进入集体否决：
            // 根因：CollectiveVeto 设计初衷是「语义问答型查询（如"如何申请..."）时，
            // 若所有候选文档 ColBERT 分都低于阈值，说明知识库无相关答案，宁可返回空」。
            // 但对于导航查询（用户直接输入关键词找文档），BM25 精准命中的结果本就是用户要找的，
            // ColBERT MaxSim 对极短词汇（≤4字）打分普遍偏低是模型特性而非「无答案」信号。
            // 错误触发 CollectiveVeto 会将 BM25 检索到的合法结果全部清空，产生「搜不到」的错误体验。
            boolean skipCollectiveVeto = context.isNavigationalBypass();
            if (skipCollectiveVeto) {
                System.out.println("[CollectiveVeto] NAVIGATIONAL bypass 查询，跳过集体否决（ColBERT短词低分≠无相关文档）");
            }
            if (!skipCollectiveVeto && rerankScores != null && !rerankScores.isEmpty()) {
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
        double baseNormConfig = config.getEsNormBase() != null ? config.getEsNormBase().doubleValue() : 20.0;
        if (baseNormConfig < 0.001)
            baseNormConfig = 20.0;
        final double esNormBase = "hybrid".equals(context.getSearchMode()) ? 150.0 : baseNormConfig;

        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> cand = candidates.get(i);

            Object knnScoreObj = cand.get("_max_knn_score");
            double knnCosineSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : -1.0;
            Object scoreObj = cand.get("_es_score");
            double rawEsScore = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;
            double rrfScore = (double) cand.getOrDefault("_rrf_score", 0.0);

            double normalizedEsScore = rawEsScore / (rawEsScore + esNormBase);
            boolean qaHit = Boolean.TRUE.equals(cand.get("_qa_hit"));
            // [T1-置信度] QA 置信度：由 QaInjectionStep 写入 _qa_confidence，传入 features 供 LTR 使用
            Object qaConfObj = cand.get("_qa_confidence");
            double qaConfidence = (qaConfObj instanceof Number) ? ((Number) qaConfObj).doubleValue() : 0.75;

            Map<String, Object> features = new HashMap<>();
            features.put("qa_hit", qaHit);
            features.put("qa_confidence", qaConfidence);
            features.put("raw_es_score", rawEsScore);
            features.put("normalized_es_score", normalizedEsScore);
            features.put("knn_score", knnCosineSim);
            features.put("rrf_score", rrfScore);

            if (rerankScores != null && !rerankScores.isEmpty()) {
                if (i < rerankScores.size()) {
                    features.put("rerank_score", rerankScores.get(i));
                } else {
                    // [Batch3b 修复] 未被 reranker 处理的文档（超出 effectiveLimit 部分? // 原因：用 0.0 惩罚这些文档，导致它们在
                    // LTR 打分中被严重压制? // 破坏了排名公平性（这些文档并非"无关"，只是未被精排而已）? // 修复：用 RRF 分（×10 归一化到
                    // [0,1]）作为代理分? // 保留其相对召回质量信息，不额外惩罚
                    double rrfProxy = Math.min(rrfScore * 10.0, 1.0);
                    features.put("rerank_score", rrfProxy);
                }
            }
            ltrFeaturesList.add(features);
        }

        // [Step4 优化] LTR 公式内联?Java，消?Python RPC ?~150ms 往返延迟? // 根因：LTR
        // 调用的是线性组合公式，返回内容?AiEngineGateway.fetchLtrScores fallback 外层一致? // 公式：score =
        // max(normalized_es_score, rerank_score, rrf_score × 10)
        List<Double> ltrScores = new ArrayList<>();
        for (Map<String, Object> feat : ltrFeaturesList) {
            double score;
            if (("semantic".equals(context.getSearchMode()) || "hybrid".equals(context.getSearchMode()))
                    && (feat.get("knn_score") instanceof Number)
                    && ((Number) feat.get("knn_score")).doubleValue() > 0) {
                score = Math.max(((Number) feat.get("knn_score")).doubleValue(),
                        (feat.get("normalized_es_score") instanceof Number)
                                ? ((Number) feat.get("normalized_es_score")).doubleValue()
                                : 0.0);
            } else {
                score = (feat.get("normalized_es_score") instanceof Number)
                        ? ((Number) feat.get("normalized_es_score")).doubleValue()
                        : 0.0;
            }
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
            // [架构重构] QA 特权打分已删除：
            // QA 候选已通过第四路 RRF 公平进入候选池，ColBERT 使用「『问』/『答』」格式精排，
            // 真正相关的 QA 会获得高 ColBERT 分，自然排前；不相关的 QA 不再被特权分拉高。
            // score 由 max(knn_score, normalized_es_score, ColBERT_score, rrf_score*10)
            // 决定，与普通文档一视同仁。
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
            if (Boolean.TRUE.equals(cand.get("_qa_hit"))) {
                docMap.put("_qa_hit", Boolean.TRUE);
            }

            // [T1-QA Snippet] QA 命中：构造【问】/【答】结构化展示，完全跳过普通截断逻辑
            // 根因：普通 generateFallbackSnippet 截取前100字，Q/A 结构完全丢失，
            // 用户看到的是 answer 的碎片，而非精准问答，用户体验等同于没有 QA。
            if (Boolean.TRUE.equals(cand.get("_qa_hit")) && source != null) {
                String qaQuestion = (String) source.getOrDefault("question", "");
                String qaAnswer = (String) source.getOrDefault("answer_content",
                        source.getOrDefault("content", ""));
                Object confObj = cand.get("_qa_confidence");
                double qaConf = (confObj instanceof Number) ? ((Number) confObj).doubleValue() : 0.75;

                // 构造结构化 Q/A 文本，前端据 is_qa_answer 字段渲染专用卡片
                String qaSnippet = "【问】" + qaQuestion + "\n【答】" + qaAnswer;
                docMap.put("chunk_text", qaSnippet);
                docMap.put("is_qa_answer", Boolean.TRUE);
                docMap.put("qa_confidence", (int) Math.round(qaConf * 100)); // 百分比，供前端展示
                scoredResults.add(docMap);
                continue; // 跳过后续普通 snippet 生成路径
            }

            String snippetText = null;
            if (cand.containsKey("highlight") && cand.get("highlight") != null) {
                Map<String, List<String>> hlMap = (Map<String, List<String>>) cand.get("highlight");
                List<String> hlContent = hlMap.get("content");
                if (hlContent != null && !hlContent.isEmpty()) {
                    snippetText = String.join(" ... ", hlContent).replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
                    // [单字高亮剥离] ES IK 分词会产生单字 token（如"人"），去除单字符 <em> 标签
                    snippetText = snippetText.replaceAll("<em class='highlight'>([^<])</em>", "$1");
                }
            }
            if (snippetText == null || snippetText.isEmpty()) {
                String content = source != null ? (String) source.getOrDefault("content", "") : "";
                if ("keyword".equals(context.getSearchMode())) {
                    // 关键词模式：按空格分词检索和高亮，不使用 IK 分词
                    List<String> kwTerms = new ArrayList<>();
                    if (queryText != null) {
                        for (String term : queryText.trim().split("[\\s\\u3000]+")) {
                            String trimmed = term.trim();
                            if (!trimmed.isEmpty()) kwTerms.add(trimmed);
                        }
                    }
                    // 关键词模式：如果标题中包含搜索词，将标题前置展示并高亮
                    // 解决：关键词分散在 title 和 content 时，用户看不到标题命中的关键词
                    String title = "";
                    if (source != null) {
                        Object metaObj = source.get("metadata");
                        if (metaObj instanceof Map) {
                            Object tObj = ((Map<String, Object>) metaObj).get("title");
                            title = tObj != null ? tObj.toString() : "";
                        }
                    }
                    boolean titleHasKeyword = false;
                    for (String kw : kwTerms) {
                        if (!content.contains(kw) && title.contains(kw)) {
                            titleHasKeyword = true;
                            break;
                        }
                    }
                    if (titleHasKeyword && !title.isEmpty()) {
                        String highlightedTitle = "【" + highlightText(title, String.join(" ", kwTerms)) + "】";
                        snippetText = highlightedTitle + "\n" + highlightText(content, String.join(" ", kwTerms));
                    } else {
                        snippetText = highlightText(content, String.join(" ", kwTerms));
                    }
                } else {
                    // 语义模式：使用关键词定位摘要，不对用户输入做高亮标注
                    snippetText = generateFallbackSnippet(content, queryText);
                }
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

        // [4] Result Collapsing（按文件名折叠，同一文档保留前3个最优chunks）
        // [优化] 对标 V1 逻辑，在同文档多 chunk 时优先保留「含查询词的 chunk」
        // 同一文档保留前3个评分最高的chunks，其他chunks通过前端抽屉展示
        Map<String, Map<String, Object>> fileAggregatedMap = new LinkedHashMap<>();
        for (Map<String, Object> docMap : scoredResults) {
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = source != null ? (Map<String, Object>) source.get("metadata") : null;

            // [架构修复] dedupeKey 改为 ES _id 的 hash 前缀（文件内容 hash，真正唯一）
            // 原来用 metadata.source（文件名）去重，存在两个致命缺陷：
            // 1. 不同单位上传同名文件 → 相同 dedupeKey → 被错误合并为一条结果
            // 2. 同一文件重命名后重传 → 不同 dedupeKey → 重复展示
            // ES _id 格式：{content_hash}_v{version}_chunk_{index}
            // content_hash 是摄取时对文件内容计算的 MD5/SHA，天然唯一且稳定。
            String esId = (String) docMap.get("_id");
            String docHashId = extractDocHash(esId);
            // dedupeKey = hash（唯一标识文档），不再用文件名
            String dedupeKey = (docHashId != null && !docHashId.isEmpty())
                    ? docHashId
                    : (esId != null ? esId : UUID.randomUUID().toString());

            // [T4-Collapsing] QA 命中优先读 metadata.doc_hash 字段作为 dedupeKey
            // 根因：QA _id 的 hash 与文档 chunk _id 的 hash 理论上相同，
            // 但若 QA 是旧数据（文档重新入库后 hash 变了），两者不一致 → 折叠失效 → 双显。
            // Python 写入 QA 时已显式加入 doc_hash = file_base_hash，此处直接读取，保证对齐。
            if (Boolean.TRUE.equals(docMap.get("_qa_hit")) && source != null) {
                Map<String, Object> qaMeta = (Map<String, Object>) source.get("metadata");
                if (qaMeta != null) {
                    String docHashFromMeta = (String) qaMeta.get("doc_hash");
                    if (docHashFromMeta != null && !docHashFromMeta.isEmpty()) {
                        dedupeKey = docHashFromMeta;
                        System.out.printf("[T4-Collapsing] QA dedupeKey override: %s → %s%n", docHashId,
                                docHashFromMeta);
                    }
                }
            }

            docMap.put("extracted_doc_id", dedupeKey);
            Set<String> currentKeywordMatches = collectKeywordMatches(source, context.getKeywordFilterTerms());

            // 提取 chunk 分片序号（供 keyword 模式按分片顺序排序）
            int chunkIndex = Integer.MAX_VALUE;
            if (esId != null) {
                int ci = esId.lastIndexOf("_chunk_");
                if (ci >= 0) {
                    try { chunkIndex = Integer.parseInt(esId.substring(ci + 7)); } catch (NumberFormatException ignored) {}
                }
            }
            if (metaMap != null && metaMap.get("chunk_id") instanceof Number) {
                chunkIndex = ((Number) metaMap.get("chunk_id")).intValue();
            }

            if (!fileAggregatedMap.containsKey(dedupeKey)) {
                // 首次见到该文档，创建文档对象
                Map<String, Object> documentMap = new HashMap<>();
                // 使用该文档的最高分作为文档评分
                Double docScore = docMap.containsKey("score") ? (Double) docMap.get("score") : 0.0;
                documentMap.put("score", docScore);
                documentMap.put("_source", docMap.get("_source"));
                documentMap.put("_id", docMap.get("_id"));
                documentMap.put("extracted_doc_id", dedupeKey);
                // 创建chunks列表
                List<Map<String, Object>> chunksList = new ArrayList<>();
                Map<String, Object> chunkCopy = new HashMap<>(docMap);
                chunkCopy.remove("_source");
                chunkCopy.remove("_id");
                chunkCopy.remove("score");
                chunkCopy.put("chunk_index", chunkIndex);
                // 传播 chunk_granularity 到顶层，供前端 filterAndDedupeChunks 过滤 fine chunks
                String chunkGran = source != null ? (String) source.get("chunk_granularity") : null;
                if (chunkGran == null && source != null && source.containsKey("display_content")) {
                    chunkGran = "coarse"; // fine→coarse 回溯后的 chunk
                }
                if (chunkGran != null) {
                    chunkCopy.put("chunk_granularity", chunkGran);
                }
                chunksList.add(chunkCopy);
                documentMap.put("chunks", chunksList);
                documentMap.put("_keyword_doc_matched_terms", new HashSet<>(currentKeywordMatches));
                fileAggregatedMap.put(dedupeKey, documentMap);
            } else {
                // 同一文档已有，添加到chunks列表（最多保留5个chunks）
                Map<String, Object> documentMap = fileAggregatedMap.get(dedupeKey);
                List<Map<String, Object>> chunksList = (List<Map<String, Object>>) documentMap.get("chunks");
                Set<String> docMatchedTerms = ensureKeywordMatchedTerms(documentMap);
                docMatchedTerms.addAll(currentKeywordMatches);

                // [去重] 基于 chunk_text 内容去重：coarse chunk 与其 fine 子 chunk（fine→coarse 回溯后内容相同）
                // 会同时出现在候选池中，需跳过重复内容的 chunk
                String newChunkText = docMap.get("chunk_text") instanceof String
                        ? (String) docMap.get("chunk_text") : "";
                boolean isDuplicate = false;
                for (Map<String, Object> existing : chunksList) {
                    String existingText = existing.get("chunk_text") instanceof String
                            ? (String) existing.get("chunk_text") : "";
                    if (!newChunkText.isEmpty() && newChunkText.equals(existingText)) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (isDuplicate) continue;

                // [Fix] keyword 模式：折叠时过滤不含关键词的附属 chunk，避免"附赠"无关内容
                if ("keyword".equals(context.getSearchMode())) {
                    List<String> filterTerms = context.getKeywordFilterTerms();
                    if (filterTerms != null && !filterTerms.isEmpty()) {
                        String chunkText = docMap.get("chunk_text") instanceof String
                                ? (String) docMap.get("chunk_text") : "";
                        // 同时检查原始 content 和 metadata.title
                        String rawContent = "";
                        String rawTitle = "";
                        if (source != null) {
                            Object cObj = source.get("content");
                            rawContent = cObj instanceof String ? (String) cObj : "";
                            Object metaObj = source.get("metadata");
                            if (metaObj instanceof Map) {
                                Object tObj = ((Map<String, Object>) metaObj).get("title");
                                rawTitle = tObj != null ? tObj.toString() : "";
                            }
                        }
                        boolean matchFound = false;
                        for (String term : filterTerms) {
                            if (rawContent.contains(term) || rawTitle.contains(term) || chunkText.contains(term)) {
                                matchFound = true;
                                break;
                            }
                        }
                        if (!matchFound) continue;
                    }
                }

                // 限制最多保留5个chunks（后续按 coarse 优先截断到3）
                if (chunksList.size() >= 5) {
                    continue;
                }

                // 更新文档评分为最高分
                Double currentScore = docMap.containsKey("score") ? (Double) docMap.get("score") : 0.0;
                Double existingScore = documentMap.containsKey("score") ? (Double) documentMap.get("score") : 0.0;
                if (currentScore > existingScore) {
                    documentMap.put("score", currentScore);
                }

                // 添加chunk到列表
                Map<String, Object> chunkCopy = new HashMap<>(docMap);
                chunkCopy.remove("_source");
                chunkCopy.remove("_id");
                chunkCopy.remove("score");
                chunkCopy.put("chunk_index", chunkIndex);
                // 传播 chunk_granularity 到顶层
                String chunkGran2 = source != null ? (String) source.get("chunk_granularity") : null;
                if (chunkGran2 == null && source != null && source.containsKey("display_content")) {
                    chunkGran2 = "coarse";
                }
                if (chunkGran2 != null) {
                    chunkCopy.put("chunk_granularity", chunkGran2);
                }
                chunksList.add(chunkCopy);
            }
        }

        // keyword 模式：同一文档的 chunks 按分片序号排序，保持文档自然顺序
        if ("keyword".equals(context.getSearchMode())) {
            for (Map<String, Object> dm : fileAggregatedMap.values()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cls = (List<Map<String, Object>>) dm.get("chunks");
                if (cls != null && cls.size() > 1) {
                    cls.sort((a, b) -> {
                        int av = a.get("chunk_index") instanceof Number ? ((Number) a.get("chunk_index")).intValue() : Integer.MAX_VALUE;
                        int bv = b.get("chunk_index") instanceof Number ? ((Number) b.get("chunk_index")).intValue() : Integer.MAX_VALUE;
                        return Integer.compare(av, bv);
                    });
                }
            }
            System.out.println("[RerankStep] keyword 模式：chunks 已按分片序号排序");
        }

        // 混合/语义模式：coarse 优先排列，保留更多同文档片段供问答侧完整枚举
        if (!"keyword".equals(context.getSearchMode())) {
            int maxChunksPerDoc = 5;
            for (Map<String, Object> dm : fileAggregatedMap.values()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cls = (List<Map<String, Object>>) dm.get("chunks");
                if (cls != null && cls.size() > maxChunksPerDoc) {
                    cls.sort((a, b) -> {
                        String ga = String.valueOf(a.getOrDefault("chunk_granularity", ""));
                        String gb = String.valueOf(b.getOrDefault("chunk_granularity", ""));
                        if ("fine".equals(ga) && !"fine".equals(gb)) return 1;
                        if (!"fine".equals(ga) && "fine".equals(gb)) return -1;
                        return 0;
                    });
                    while (cls.size() > maxChunksPerDoc) {
                        cls.remove(cls.size() - 1);
                    }
                }
            }
            System.out.println("[RerankStep] 混合模式：chunks 已按 coarse 优先截断到3");
        }

        // 构建最终结果：每个文档作为一个结果项
        List<Map<String, Object>> collapsedResults = new ArrayList<>(fileAggregatedMap.values());
        if ("keyword".equals(context.getSearchMode())) {
            List<String> filterTerms = context.getKeywordFilterTerms();
            if (filterTerms != null && !filterTerms.isEmpty()) {
                collapsedResults.removeIf(docMap -> !ensureKeywordMatchedTerms(docMap).containsAll(filterTerms));
            }
        }

        // 构建最终结果：每个文档作为一个结果项
        prefetchEvidenceContextChunks(collapsedResults, context);
        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(collapsedResults.size(), context.getTopK());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> docMap = collapsedResults.get(i);
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> metaMap = source != null ? (Map<String, Object>) source.get("metadata") : null;

            if (metaMap != null) {
                // doc_id = 内容 hash 前缀（唯一标识，跨单位不冲突）
                String docId = (String) docMap.getOrDefault("extracted_doc_id", "");
                docMap.put("doc_id", docId);

                // organization 优先用 owner（发布单位机构名），缺失时降级到 source（文件名）
                Object owner = metaMap.get("owner");
                docMap.put("organization", (owner != null && !owner.toString().isEmpty())
                        ? owner.toString()
                        : metaMap.getOrDefault("source", "默认组织"));

                docMap.put("publish_time", metaMap.getOrDefault("publish_time", "2024-01-10"));
                docMap.put("tags", metaMap.get("tags"));
                docMap.put("custom_tags", metaMap.get("custom_keywords"));
                // file_name 单独透传文件名（供前端显示 + file/preview 接口使用）
                docMap.put("file_name", metaMap.getOrDefault("source", ""));
                // dept 信息补充（供权限显示和调试）
                docMap.put("dept_code", metaMap.getOrDefault("owner_dept_id", ""));
            }
            // 移除内部字段
            docMap.remove("_source");
            docMap.remove("extracted_doc_id");
            docMap.remove("raw_score");
            docMap.remove("_id");
            docMap.remove("_keyword_doc_matched_terms");
            results.add(docMap);
        }

        context.setFinalResult(results);
        System.out.println("====== [Pipeline] Node 6: Output Ready ======");
    }

    @SuppressWarnings("unchecked")
    private void prefetchEvidenceContextChunks(List<Map<String, Object>> collapsedResults, SearchContext context) {
        if (collapsedResults == null || collapsedResults.isEmpty() || context == null
                || !context.isEvidencePrefetchEnabled()) {
            return;
        }
        long startMs = System.currentTimeMillis();
        final int maxDocs = Math.min(Math.max(1, qaAnswerProperties.getEvidencePrefetchMaxDocs()), collapsedResults.size());
        final int before = Math.max(0, qaAnswerProperties.getEvidencePrefetchWindowBefore());
        final int after = Math.max(0, qaAnswerProperties.getEvidencePrefetchWindowAfter());
        final int maxChunks = Math.max(10, qaAnswerProperties.getEvidencePrefetchMaxChunks());
        Map<String, Set<Integer>> wantedByFile = new LinkedHashMap<>();
        Map<String, Set<Integer>> seedByFile = new LinkedHashMap<>();

        for (int i = 0; i < maxDocs; i++) {
            Map<String, Object> docMap = collapsedResults.get(i);
            Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
            Map<String, Object> meta = source != null ? (Map<String, Object>) source.get("metadata") : null;
            String fileName = meta != null ? String.valueOf(meta.getOrDefault("source", "")) : "";
            if (fileName == null || fileName.trim().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> chunks = docMap.get("chunks") instanceof List
                    ? (List<Map<String, Object>>) docMap.get("chunks")
                    : Collections.emptyList();
            for (Map<String, Object> chunk : chunks) {
                Integer idx = numericChunkIndex(chunk);
                if (idx == null) {
                    continue;
                }
                seedByFile.computeIfAbsent(fileName, k -> new LinkedHashSet<>()).add(idx);
                Set<Integer> wanted = wantedByFile.computeIfAbsent(fileName, k -> new LinkedHashSet<>());
                for (int n = idx - before; n <= idx + after; n++) {
                    if (n >= 0) {
                        wanted.add(n);
                    }
                }
            }
        }

        if (wantedByFile.isEmpty()) {
            return;
        }

        try {
            String indexPattern = context.getResolvedIndexPattern() != null
                    ? context.getResolvedIndexPattern()
                    : (context.getTenantPolicy() != null ? context.getTenantPolicy().getIndexPattern() : "kb_document");
            SearchRequest req = new SearchRequest.Builder()
                    .index(indexPattern)
                    .size(Math.min(maxChunks, wantedByFile.values().stream().mapToInt(Set::size).sum() + 16))
                    .query(q -> q.bool(b -> {
                        for (Map.Entry<String, Set<Integer>> entry : wantedByFile.entrySet()) {
                            final String fileName = entry.getKey();
                            final List<FieldValue> values = entry.getValue().stream()
                                    .map(FieldValue::of)
                                    .collect(Collectors.toList());
                            b.should(s -> s.bool(bb -> bb
                                    .must(m -> m.matchPhrase(mp -> mp.field("metadata.source").query(fileName)))
                                    .filter(f -> f.terms(t -> t.field("metadata.chunk_id").terms(ts -> ts.value(values))))));
                        }
                        b.minimumShouldMatch("1");
                        b.filter(f -> f.bool(boolQuery -> boolQuery
                                .should(s -> s.term(t -> t.field("metadata.is_latest").value(true)))
                                .should(s -> s.bool(mn -> mn.mustNot(ex -> ex.exists(e -> e.field("metadata.is_latest")))))
                                .minimumShouldMatch("1")));
                        return b;
                    }))
                    .source(src -> src.fetch(true))
                    .build();
            SearchResponse<Object> resp = esClient.search(req, Object.class);
            Map<String, List<Map<String, Object>>> contextByFile = new LinkedHashMap<>();
            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : resp.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) {
                    continue;
                }
                Map<String, Object> meta = (Map<String, Object>) src.get("metadata");
                String fileName = meta != null ? String.valueOf(meta.getOrDefault("source", "")) : "";
                if (fileName.trim().isEmpty()) {
                    continue;
                }
                Object idxObj = meta != null ? meta.getOrDefault("chunk_id", 0) : 0;
                Integer idx = numericValue(idxObj);
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("chunk_id", hit.id());
                chunk.put("chunk_index", idxObj);
                chunk.put("section_path", meta != null ? meta.getOrDefault("section_path", "") : "");
                chunk.put("chunk_text", src.getOrDefault("content", ""));
                chunk.put("source_type", seedByFile.getOrDefault(fileName, Collections.emptySet()).contains(idx) ? "hit" : "neighbor");
                contextByFile.computeIfAbsent(fileName, k -> new ArrayList<>()).add(chunk);
            }
            for (List<Map<String, Object>> chunks : contextByFile.values()) {
                chunks.sort((a, b) -> {
                    Integer av = numericValue(a.get("chunk_index"));
                    Integer bv = numericValue(b.get("chunk_index"));
                    return Integer.compare(av == null ? Integer.MAX_VALUE : av, bv == null ? Integer.MAX_VALUE : bv);
                });
            }
            for (int i = 0; i < maxDocs; i++) {
                Map<String, Object> docMap = collapsedResults.get(i);
                Map<String, Object> source = (Map<String, Object>) docMap.get("_source");
                Map<String, Object> meta = source != null ? (Map<String, Object>) source.get("metadata") : null;
                String fileName = meta != null ? String.valueOf(meta.getOrDefault("source", "")) : "";
                List<Map<String, Object>> chunks = contextByFile.get(fileName);
                if (chunks != null && !chunks.isEmpty()) {
                    docMap.put("evidence_context_chunks", chunks);
                }
            }
            System.out.printf("[EvidencePrefetch] docs=%d files=%d chunks=%d cost=%dms%n",
                    maxDocs, wantedByFile.size(), contextByFile.values().stream().mapToInt(List::size).sum(),
                    System.currentTimeMillis() - startMs);
            context.getTimings().put("evidence_prefetch_ms", System.currentTimeMillis() - startMs);
            context.getTimings().put("evidence_prefetched_docs", maxDocs);
            context.getTimings().put("evidence_prefetched_chunks",
                    contextByFile.values().stream().mapToInt(List::size).sum());
        } catch (Exception e) {
            context.getTimings().put("evidence_prefetch_ms", System.currentTimeMillis() - startMs);
            context.getTimings().put("evidence_prefetch_error", e.getMessage());
            System.err.println("[EvidencePrefetch] failed, using existing chunks: " + e.getMessage());
        }
    }

    private Integer numericChunkIndex(Map<String, Object> chunk) {
        if (chunk == null) {
            return null;
        }
        Object value = chunk.getOrDefault("chunk_index", chunk.get("chunk_id"));
        return numericValue(value);
    }

    private Integer numericValue(Object value) {
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

    @SuppressWarnings("unchecked")
    private Set<String> ensureKeywordMatchedTerms(Map<String, Object> documentMap) {
        Object existing = documentMap.get("_keyword_doc_matched_terms");
        if (existing instanceof Set) {
            return (Set<String>) existing;
        }
        Set<String> matchedTerms = new HashSet<>();
        if (existing instanceof Collection) {
            for (Object item : (Collection<?>) existing) {
                if (item != null) {
                    matchedTerms.add(item.toString());
                }
            }
        }
        documentMap.put("_keyword_doc_matched_terms", matchedTerms);
        return matchedTerms;
    }

    @SuppressWarnings("unchecked")
    private Set<String> collectKeywordMatches(Map<String, Object> source, List<String> keywordTerms) {
        Set<String> matches = new HashSet<>();
        if (source == null || keywordTerms == null || keywordTerms.isEmpty()) {
            return matches;
        }

        StringBuilder searchable = new StringBuilder();
        Object contentObj = source.get("content");
        if (contentObj != null) {
            searchable.append(contentObj);
        }
        Object displayContentObj = source.get("display_content");
        if (displayContentObj != null) {
            searchable.append('\n').append(displayContentObj);
        }
        Object docTitleObj = source.get("doc_title");
        if (docTitleObj != null) {
            searchable.append('\n').append(docTitleObj);
        }
        Object metaObj = source.get("metadata");
        if (metaObj instanceof Map) {
            Object titleObj = ((Map<String, Object>) metaObj).get("title");
            if (titleObj != null) {
                searchable.append('\n').append(titleObj);
            }
            Object sourceObj = ((Map<String, Object>) metaObj).get("source");
            if (sourceObj != null) {
                searchable.append('\n').append(sourceObj);
            }
        }

        String text = searchable.toString();
        for (String term : keywordTerms) {
            if (term != null && !term.isEmpty() && text.contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    /**
     * 从文档内容中提取关键词定位摘要（对应 V1 generateFallbackSnippet 逻辑）? *
     * 业务功能? * 1. OOM Guard：将超长 content 截断?2000 字再处理
     * 2. 清除 [标签: 内容] 格式的上下文标注前缀（不应展示给用户? * 3. ?queryText 定位关键词位置，截取前后 40/60
     * 字上下文作为摘要
     * 4. 兜底：无法定位时返回?100 ? *
     * 
     * @param content   文档原始 content 字段
     * @param queryText 原始用户查询词（用于定位摘要起点?
     */
    private String generateFallbackSnippet(String content, String queryText) {
        if (content == null || content.isEmpty())
            return "";
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
        int end = Math.min(cleanContent.length(), idx + queryText.length() + 60);
        String snippet = cleanContent.substring(start, end);
        if (start > 0)
            snippet = "..." + snippet;
        if (end < cleanContent.length())
            snippet = snippet + "...";
        return snippet;
    }

    /**
     * 按空格分词后的关键词列表定位摘要（关键词模式专用）。
     *
     * 与 generateFallbackSnippet 的区别：
     * - generateFallbackSnippet 用 indexOf(queryText) 查找完整查询串，适合语义/混合模式
     * - 本方法逐个查找空格分词后的独立关键词，适合关键词模式
     *
     * 优先定位最长的关键词（信息密度最高），截取其前后上下文作为摘要。
     */
    private String generateFallbackSnippetByTerms(String content, List<String> keywordTerms) {
        if (content == null || content.isEmpty()) return "";
        final int MAX_CONTENT = 2000;
        String safeContent = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        String cleanContent = safeContent.replaceAll("\\[[^\\]]*:[^\\]]*\\]", "").trim();
        if (keywordTerms == null || keywordTerms.isEmpty()) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        // 按词长降序查找，优先定位最长的关键词
        List<String> sorted = new ArrayList<>(keywordTerms);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        int bestIdx = -1;
        String bestTerm = null;
        for (String term : sorted) {
            int idx = cleanContent.indexOf(term);
            if (idx != -1) {
                bestIdx = idx;
                bestTerm = term;
                break;
            }
        }
        if (bestIdx == -1) {
            return cleanContent.substring(0, Math.min(cleanContent.length(), 100));
        }
        int start = Math.max(0, bestIdx - 40);
        int end = Math.min(cleanContent.length(), bestIdx + bestTerm.length() + 60);
        String snippet = cleanContent.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < cleanContent.length()) snippet = snippet + "...";
        return snippet;
    }

    /**
     * Java 层高亮：?snippet 中命中的查询词包?HTML 高亮标签? *
     * 业务功能：当 ES highlight 字段为空（KNN 检索或 highlight 未触发）时，
     * ?Java 层对 generateFallbackSnippet 返回的摘要手动添?<em class='highlight'> 标记? *
     * 保证前端高亮显示的一致性? *
     * 
     * @param text  待高亮的摘要文本
     * @param query 查询词（空格分隔多词?
     */
    private String highlightText(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty())
            return text;
        // OOM Guard：限定高亮操作的文本长度上界，防?replaceAll StringBuffer 溢出
        final int MAX_HIGHLIGHT_LEN = 1500;
        String safeText = text.length() > MAX_HIGHLIGHT_LEN ? text.substring(0, MAX_HIGHLIGHT_LEN) : text;
        // 按词分割后按长度降序排序，长词优先匹配（防止短词提前替换破坏长词标记
        Set<String> kwSet = new HashSet<>(Arrays.asList(query.split("\\s+")));
        List<String> sortedKws = new ArrayList<>(kwSet);
        sortedKws.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String result = safeText;
        for (String kw : sortedKws) {
            // 过滤单字噪词（的/了/是/在等），避免虚词被高亮
            if (kw.trim().length() < 2)
                continue;
            try {
                String patternStr = "(?i)(" + Pattern.quote(kw) + ")";
                result = result.replaceAll(patternStr, "<em class='highlight'>$1</em>");
            } catch (Exception e) {
                // 正则异常时跳过该词，不影响其他词的高
            }
        }
        return result;
    }

    /**
     * 从 ES _id 中提取文档 hash 前缀（内容唯一标识）。
     *
     * 业务功能：ES 文档 _id 格式为 `{content_hash}_v{n}_chunk_{m}`，其中 content_hash
     * 是文件摄取时对内容计算的唯一 hash，是同名不同内容文件的天然区分符。
     * 提取出 hash 后，可用于：
     * 1. Result Collapsing dedupeKey（正确区分不同单位同名文件）
     * 2. chunks 接口 _id 前缀匹配（定位该文档的全部分片）
     *
     * @param esId ES 文档 _id，格式 `{hash}_v{n}_chunk_{m}`
     * @return hash 前缀（如 `d4738c303b9bc33887561c1bb9535f00`），无法解析时返回 esId 本身
     */
    private String extractDocHash(String esId) {
        if (esId == null || esId.isEmpty())
            return esId;

        // [Fix] QA docID format: {hash}_qa_{idx}_{q_idx}
        int qaIdx = esId.indexOf("_qa_");
        if (qaIdx > 0)
            return esId.substring(0, qaIdx);

        // _id 格式：{hash}_v{n}_chunk_{m}，hash 是纯十六进制，不含下划线
        // 找第一个 _v 分隔符（hash 后跟 _v + 版本号）
        int vMarkerIdx = esId.indexOf("_v");
        if (vMarkerIdx > 0) {
            // 验证：_v 后面紧跟数字（排除 hash 中碰巧出现 _v 的极小概率情况）
            if (vMarkerIdx + 2 < esId.length() && Character.isDigit(esId.charAt(vMarkerIdx + 2))) {
                return esId.substring(0, vMarkerIdx);
            }
        }
        // 兜底：直接去掉 _chunk_{n} 后缀
        int chunkIdx = esId.lastIndexOf("_chunk_");
        if (chunkIdx > 0)
            return esId.substring(0, chunkIdx);
        return esId;
    }

    /**
     * [Layer1] 判断当前 chunk 是否比已存储 chunk 更适合展示给用户。
     *
     * 业务功能：Result Collapsing 阶段，同一文档有多个 chunk 参与竞争时，
     * 此方法决定「哪个 chunk 代表这篇文档展示给用户」。
     * 设计原则：不同文档类型有不同的「最优展示粒度」：
     * - 公示类：人员信息块（section_path 含层级）远比前言（section_path 空）有价值
     * - 法规类：条文块（section_path 含"第X条"）远比总则前言有价值
     * - 其他类型：沿用原逻辑（含查询词 > 不含查询词）
     *
     * @param existingMap 当前已选中 chunk 的 docMap
     * @param currentMap  待比较的当前 chunk 的 docMap
     * @param currentSrc  当前 chunk 的 _source
     * @param queryText   用户查询词
     * @param context     SearchContext（提供 normalizedQuery 等辅助信息）
     * @return true = 当前 chunk 更优，应替换；false = 保持已选 chunk 不变
     */
    @SuppressWarnings("unchecked")
    private boolean selectBestChunk(Map<String, Object> existingMap, Map<String, Object> currentMap,
            Map<String, Object> currentSrc, String queryText, SearchContext context) {
        // [Layer1] QA 优先原则：QA 命中的 chunk 总是最优先展示
        boolean existingIsQa = Boolean.TRUE.equals(existingMap.get("_qa_hit"));
        boolean currentIsQa = Boolean.TRUE.equals(currentMap.get("_qa_hit"));
        if (currentIsQa && !existingIsQa)
            return true; // 替换成 QA
        if (!currentIsQa && existingIsQa)
            return false; // 保留 QA

        // 提取已选 chunk 的 source
        Map<String, Object> existingSrc = (Map<String, Object>) existingMap.get("_source");
        if (existingSrc == null)
            return false; // 无法比较，保持现有

        // 提取 doc_type（以已选块为准，同文档应相同）
        Map<String, Object> existingMeta = (Map<String, Object>) existingSrc.get("metadata");
        String docType = existingMeta != null ? String.valueOf(existingMeta.getOrDefault("doc_type", "")) : "";

        // 公示 / 法规 类型：用信息密度打分决定替换
        if ("公示".equals(docType) || "法规".equals(docType)) {
            int existingScore = calcChunkInfoScore(existingSrc, docType);
            int currentScore = calcChunkInfoScore(currentSrc, docType);
            System.out.printf("[Layer1] docType=%s, existing=%d, current=%d%n", docType, existingScore, currentScore);
            return currentScore > existingScore;
        }

        // 其他类型：沿用原"含查询词优先"逻辑
        String existingText = (String) existingMap.getOrDefault("chunk_text", "");
        String currentText = (String) currentMap.getOrDefault("chunk_text", "");
        String qt = queryText != null ? queryText : "";
        String nq = context.getNormalizedQuery() != null ? context.getNormalizedQuery() : "";
        boolean existingHits = existingText != null && (existingText.contains(qt) || existingText.contains(nq));
        boolean currentHits = currentText != null && (currentText.contains(qt) || currentText.contains(nq));
        return currentHits && !existingHits;
    }

    /**
     * [Layer1] 对 chunk 内容计算信息密度分数（纯规则，无需模型调用）。
     *
     * 业务功能：区分「有效信息块」和「前言/引言块」。
     * 评分规则：
     * +3 section_path 含层级路径（"含/"或"含第X条"）= 正文内容
     * +2 内容含人员信息词（姓名/拟任/现任/人员/候选）
     * +1 内容长度在 30~300 字之间（信息密度适中）
     * -2 内容含套话词（根据/按照/依据/特此/经XXX研究决定）
     * -2 section_path 为空（文档开头，疑似前言）
     *
     * @param source  chunk 的 _source Map
     * @param docType 文档类型（调用方已判断为 公示/法规）
     * @return 信息密度分数，越高越适合展示
     */
    @SuppressWarnings("unchecked")
    private int calcChunkInfoScore(Map<String, Object> source, String docType) {
        if (source == null)
            return 0;
        int score = 0;

        // 提取 section_path（可能在 source 直接或在 metadata 内）
        String sectionPath = (String) source.getOrDefault("section_path", "");
        if (sectionPath == null || sectionPath.isEmpty() || "null".equals(sectionPath)) {
            Map<String, Object> meta = (Map<String, Object>) source.get("metadata");
            if (meta != null)
                sectionPath = String.valueOf(meta.getOrDefault("section_path", ""));
        }

        // section_path 层级判断
        if (sectionPath != null && (sectionPath.contains("/") || sectionPath.matches(".*第.{1,4}条.*"))) {
            score += 3; // 有层级路径，属于正文内容
        } else if (sectionPath == null || sectionPath.isEmpty() || "null".equals(sectionPath)) {
            score -= 2; // 空路径，疑似前言/引言
        }

        // 内容分析
        String content = (String) source.getOrDefault("content", "");
        if (content == null)
            content = "";
        String snippet = content.length() > 300 ? content.substring(0, 300) : content;

        // 公示类：人员信息词加分
        if ("公示".equals(docType)) {
            if (snippet.contains("姓名") || snippet.contains("拟任") || snippet.contains("现任")
                    || snippet.contains("人员") || snippet.contains("候选") || snippet.contains("拟提拔")) {
                score += 2; // 含人员信息词
            }
            // 数字编号开头的人员条目（"1. 张三"格式）额外加分
            if (content.matches("[\\s\\S]*\\d+\\.\\s*[\\u4e00-\\u9fff]{2,4}[，：,；\\s][\\s\\S]*")) {
                score += 2; // 数字编号+中文姓名，属于具体人员条目
            }
            // 章节引言句负分：内容以"如下："/"以下："结尾 = 行文引入句，不是实际数据
            String trimmed = content.trim();
            if (trimmed.endsWith("如下：") || trimmed.endsWith("以下：") || trimmed.endsWith("如下")
                    || trimmed.endsWith("如下。")) {
                score -= 3; // 引言句，实际价值低于具体人员信息
            }
        }
        // 法规类：条文词加分
        if ("法规".equals(docType)) {
            if (snippet.matches("第[一二三四五六七八九十\\d]+条.*") || snippet.contains("本条") || snippet.contains("本款")) {
                score += 2;
            }
        }

        // 套话负分（前言特征）
        if ((snippet.contains("根据") || snippet.contains("按照") || snippet.contains("依据"))
                && (snippet.contains("规定") || snippet.contains("条例") || snippet.contains("细则"))) {
            score -= 2;
        }
        if (snippet.contains("特此通知") || snippet.contains("特此公示") || snippet.contains("研究决定")) {
            score -= 2;
        }

        // 长度适中加分
        int len = content.length();
        if (len >= 30 && len <= 300)
            score += 1;

        return score;
    }
}
