package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管线节点 4：多路召回结果融合 (Four-Way RRF)
 *
 * 业务功能：
 *   将 BM25（全文检索）、KNN（稠密向量语义检索）、Sparse（稀疏向量词汇信号检索）、
 *   QA（Q2Q 向量/BM25 召回）四路召回结果，通过加权倒数排名融合（Weighted RRF）合并为统一候选池。
 *
 * 关键设计：
 *   1. [Batch2 修复] Sparse 通道使用独立权重 wSparse，不再耦合 wKnn：
 *      原因：NAVIGATIONAL 意图时 wKnn 被压低，但 Sparse 恰好在精确词汇场景最有价值，
 *            耦合会导致 Sparse 在最需要它的场景反而被削弱（反直觉）。
 *   2. [Batch2 修复] 每路结果只融合一次，消除原代码中 Sparse 被融合两次的 Bug。
 *   3. [P2-1 修复] 从 SearchContext 专用强类型字段读取，删除 __es_raw_responses 魔法 key。
 *   4. 自适应权重（方案J）：根据 BM25/KNN 实际信号强度动态调整 wText/wKnn。
 *   5. BM25 弱信号抑制：BM25 最高分 < 2.0 时，纯 BM25 命中候选被压制至 5%。
 *   6. [架构重构] QA 第四路融合：从 context.qaHits 读取 QA 候选，用 wQa 权重加入 RRF，
 *      标记 _qa_hit/\_qa_confidence，供 RerankStep 识别并组装展示。
 *      对比原实现：QA 不再以特权分（score=0.999）强行插随，而是与普通文档公平竞争。
 */
@Component
public class RrfFusionStep implements SearchPipelineStep {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SearchContext context) throws Exception {
        if (context.getFastTrackDocs() != null && !context.getFastTrackDocs().isEmpty()) {
            return; // Pre-flight 捷径命中，跳过后续所有打分逻辑
        }

        // [Batch2] 从 SearchContext 专用字段读取三路 ES 响应（不再用魔法 key）
        SearchResponse<Object> textResp   = context.getBm25Response();
        SearchResponse<Object> knnResp    = context.getKnnResponse();
        SearchResponse<Object> sparseResp = context.getSparseResponse();
        // [架构重构] 从 SearchContext 读取 QA 第四路召回结果
        List<Map<String, Object>> qaHits = context.getQaHits();

        double bm25MaxScore = 0.0;
        if (textResp != null && textResp.hits() != null && !textResp.hits().hits().isEmpty()
                && textResp.hits().hits().get(0).score() != null) {
            bm25MaxScore = textResp.hits().hits().get(0).score();
        }

        List<Map<String, Object>> candidates = rrfMerge(
            textResp, knnResp, sparseResp, qaHits,
            context.getTopK(), context.getTuningConfig(), context.isNavigationalBypass(),
            context.getBm25FlatnessRatio(), context.getBm25TextHits(), context.getQueryIntent(),
            context
        );

        // [方案C 续] BM25 弱信号 → 压制纯 BM25 候选的 RRF 分，让 KNN 语义信号主导
        // 触发条件：BM25 最高分 < 2.0（说明没有强词汇匹配）且存在向量信号
        if (bm25MaxScore < 2.0 && context.getQueryVector() != null && !candidates.isEmpty()) {
            int suppressedCount = 0;
            for (Map<String, Object> cand : candidates) {
                Object knnScoreObj = cand.get("_max_knn_score");
                double knnSim = (knnScoreObj instanceof Number) ? ((Number) knnScoreObj).doubleValue() : 0.0;
                if (knnSim < 0.15) {
                    double rrf = (double) cand.getOrDefault("_rrf_score", 0.0);
                    cand.put("_rrf_score", rrf * 0.05); // 压制至原来 5%，消除伪高排名
                    suppressedCount++;
                }
            }
            if (suppressedCount > 0) {
                candidates.sort((a, b) -> Double.compare(
                    (double) b.getOrDefault("_rrf_score", 0.0),
                    (double) a.getOrDefault("_rrf_score", 0.0)));
            }
        }

        System.out.println("====== [Pipeline] Node 4: Three-Way RRF Fusion ======");
        System.out.println("  - Merged Candidates Pool Size: " + candidates.size());

        context.setCandidateDocs(candidates);
    }

    /**
     * 四路 Weighted RRF 融合。
     *
     * 业务功能：将 BM25/KNN/Sparse/QA 四路召回按加权倒数排名公式融合为统一排序候选池。
     * QA 作为厳格平等的第四路将 w_qa 分配入候选池，并与景通文档一同经过 ColBERT 精排。
     * 融合公式：total_score = wText*(1/(k+r_bm25)) + wKnn*(1/(k+r_knn)) + wSparse*(1/(k+r_sparse)) + wQa*(1/(k+r_qa))
     *
     * @param textResp   BM25 全文检索结果（可为 null）
     * @param knnResp    KNN 稠密向量检索结果（可为 null，向量化失败时降级）
     * @param sparseResp 稀疏向量检索结果（可为 null，接口不稳定时降级）
     * @param qaHits     QA 第四路召回候选（由 RecallStrategy 写入 context，可为 null/空列表）
     * @param topK       候选池目标大小
     * @param config     调参配置（bm25Weight、vectorWeight、rrfK 等来自 DB）
     * @param navigational 是否为导航型意图（精确文号/关键词查询，BM25 主导）
     * @return 按融合分倒序排列的候选文档列表，每条 Map 携带 _rrf_score/_max_knn_score/_qa_hit
     */
    private List<Map<String, Object>> rrfMerge(
            SearchResponse<Object> textResp,
            SearchResponse<Object> knnResp,
            SearchResponse<Object> sparseResp,
            List<Map<String, Object>> qaHits,
            int topK,
            SysAiTuningConfig config,
            boolean navigational,
            double bm25FlatnessRatio,
            long bm25TextHits,
            SearchContext.QueryIntent queryIntent,
            SearchContext context) {

        Map<String, Double> rrfScores  = new HashMap<>();
        Map<String, Map<String, Object>> docRegistry = new HashMap<>();

        // RRF 平滑因子 k（来自 DB，默认 60）：防止 rank=1 时贡献分过大
        int k = 60;

        // ── 动态权重计算（方案J - 自适应信号强度）────────────────────────────────
        double bm25RawMax = (textResp != null && !textResp.hits().hits().isEmpty()
                && textResp.hits().hits().get(0).score() != null)
                ? textResp.hits().hits().get(0).score() : 0.0;
        double knnRawMax  = (knnResp != null && !knnResp.hits().hits().isEmpty()
                && knnResp.hits().hits().get(0).score() != null)
                ? knnResp.hits().hits().get(0).score() : 0.0;

        double configBm25 = config.getBm25Weight()    != null ? config.getBm25Weight().doubleValue()    : 1.0;
        double configKnn  = config.getVectorWeight()  != null ? config.getVectorWeight().doubleValue()  : 1.0;

        double wText, wKnn;
        if (navigational) {
            // NAVIGATIONAL（精确关键词）：BM25 权重加大，保持原有精确匹配优势
            wText = Math.max(configBm25, 0.70);
            wKnn  = Math.min(configKnn,  0.30);
        } else {
            // INFORMATIONAL（语义问句）：按实际信号强度自适应（方案J核心）
            //
            // [P2 修复] 原来 bm25Confidence = bm25RawMax / 10.0，"10.0"是拍脑袋的绝对基准值：
            //   - 短词查询（"政务"）BM25 max score ≈ 2.5 → confidence = 0.25（被系统性低估）
            //   - 长词查询 BM25 max score ≈ 18.0 → confidence = 1.0（正常）
            // 改为：基于 bm25FlatnessRatio（Top1 / Top5 均值），反映命中的"突出程度"：
            //   - 完全命中（Top1 远超 Top5 均值）→ ratio 高 → confidence 高
            //   - 全部相当（完全平坦）→ ratio ≈ 1.0 → confidence 低
            //   - 无命中 → hits=0 → confidence = 0
            // bm25FlatnessRatio 已通过参数传入（由 EsRecallStep 写入 context）
            double bm25Confidence;
            if (bm25TextHits == 0) {
                bm25Confidence = 0.0;
            } else {
                // ratio in [1, +∞), 映射到 [0, 1]
                // ratio=1 (平坦) → 0.2（低置信），ratio=5 → 0.9（高突出），ratio>=8 → ~1.0
                bm25Confidence = Math.min(0.2 + (bm25FlatnessRatio - 1.0) / 8.0 * 0.8, 1.0);
            }
            double knnConfidence  = knnRawMax;  // 余弦相似度已在 [0,1]
            double total = bm25Confidence + knnConfidence;
            if (total < 0.2) {
                // 两者都弱（词汇鸿沟+语义漂移）：KNN 稍主导（泛化能力更强）
                wText = configBm25 * 0.4;
                wKnn  = configKnn  * 0.6;
            } else {
                // 按比例分配，权重下界 0.2 防止任一通道完全失权
                wText = configBm25 * (bm25Confidence / total * 0.6 + 0.2);
                wKnn  = configKnn  * (knnConfidence  / total * 0.6 + 0.2);
            }
        }

        // [Batch2 修复] Sparse 权重独立于 wKnn，不随 NAVIGATIONAL 联动压制。
        // 根因：精确关键词查询（NAVIGATIONAL）恰好是稀疏向量最擅长的场景（词汇精确匹配），
        //       若用 wKnn*0.30 则 wKnn 被压低到 0.30，wSparse 也跟着降为 0.09，逻辑反直觉。
        // 独立基准：取 configBm25 的 20%，在 BM25 强信号时适当加权稀疏信号。
        double wSparse = configBm25 * 0.20;

        System.out.printf("[RRF] intent=%s | wBM25=%.2f | wKNN=%.2f | wSparse=%.2f%n",
            navigational ? "NAVIGATIONAL" : "INFORMATIONAL", wText, wKnn, wSparse);

        // ── 通道1：BM25（全文检索）────────────────────────────────────────────
        if (textResp != null) {
            int rank = 1;
            // 关键词模式精确过滤：ES match 查询因 IK 分词可能召回不含原文关键词的 chunk，
            // 这里用 content.contains() 对每个 requiredTerm 做二次过滤
            // 同时检查 content 和 metadata.title，任一命中即通过
            List<String> keywordFilterTerms = (context != null) ? context.getKeywordFilterTerms() : null;
            for (Hit<Object> hit : textResp.hits().hits()) {
                // keyword mode: Java 层精确过滤，确保 content 或 title 包含所有搜索词
                if (keywordFilterTerms != null && !keywordFilterTerms.isEmpty()) {
                    Object sourceObj = hit.source();
                    String content = "";
                    String title = "";
                    String sourceTitle = "";
                    String docTitle = "";
                    if (sourceObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> srcMap = (Map<String, Object>) sourceObj;
                        Object cObj = srcMap.get("content");
                        content = cObj != null ? cObj.toString() : "";
                        Object dtObj = srcMap.get("doc_title");
                        docTitle = dtObj != null ? dtObj.toString() : "";
                        Object metaObj = srcMap.get("metadata");
                        if (metaObj instanceof Map) {
                            Object tObj = ((Map<String, Object>) metaObj).get("title");
                            title = tObj != null ? tObj.toString() : "";
                            Object sObj = ((Map<String, Object>) metaObj).get("source");
                            sourceTitle = sObj != null ? sObj.toString() : "";
                        }
                    }
                    String finalContent = content;
                    String finalTitle = title;
                    String finalSourceTitle = sourceTitle;
                    String finalDocTitle = docTitle;
                    boolean anyTermMatch = false;
                    for (String term : keywordFilterTerms) {
                        if (finalContent.contains(term) || finalTitle.contains(term)
                                || finalSourceTitle.contains(term) || finalDocTitle.contains(term)) {
                            anyTermMatch = true;
                            break;
                        }
                    }
                    if (!anyTermMatch) {
                        rank++;
                        continue; // 跳过 content 和 title 均不含关键词的 chunk
                    }
                }

                String id    = hit.id();
                double score = wText * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                docRegistry.put(id, convertHitToMap(hit));
                rank++;
            }
        }

        // ── 通道2：KNN 稠密向量（coarse 粒度）───────────────────────────────
        // 最低相似度过滤：低于阈值的 KNN 命中说明语义漂移，不进入候选池
        final double knnMinSimilarity = 0.15;
        if (knnResp != null) {
            int rank = 1;
            for (Hit<Object> hit : knnResp.hits().hits()) {
                double knnScore = hit.score() != null ? hit.score() : 0.0;
                if (knnScore < knnMinSimilarity) continue;
                String id    = hit.id();
                double score = wKnn * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docRegistry.containsKey(id)) {
                    Map<String, Object> map = convertHitToMap(hit);
                    map.put("_max_knn_score", knnScore);
                    docRegistry.put(id, map);
                } else {
                    Map<String, Object> map = docRegistry.get(id);
                    double ex = map.containsKey("_max_knn_score") ? ((Number) map.get("_max_knn_score")).doubleValue() : 0.0;
                    map.put("_max_knn_score", Math.max(ex, knnScore));
                }
                rank++;
            }
        }

        // ── 通道3：Sparse 稀疏向量（rank_features 词汇权重信号）──────────────
        // [Batch2 修复] Sparse 独立权重，只融合一次（原代码中此段逻辑被执行了两次）
        // 最低分阈值 0.1：rank_features 分 < 0.1 说明 saturation 命中几乎空白，忽略
        if (sparseResp != null) {
            int rank = 1;
            for (Hit<Object> hit : sparseResp.hits().hits()) {
                double sparseScore = hit.score() != null ? hit.score() : 0.0;
                if (sparseScore < 0.1) continue; // 低于阈值的稀疏命中贡献接近零，直接跳过
                String id    = hit.id();
                double score = wSparse * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docRegistry.containsKey(id)) {
                    docRegistry.put(id, convertHitToMap(hit));
                }
                rank++;
            }
            System.out.printf("[RRF] Sparse channel: %d hits effective (wSparse=%.3f)%n",
                sparseResp.hits().hits().size(), wSparse);
        } else {
            System.out.println("[RRF] Sparse channel: degraded (null response, skipped)");
        }

        // ── [架构重构] 通道4：QA 第四路（公平竞争，不再以特权分强行插随）─────────────────────
        // [意图感知] wQa 根据 QueryIntent 动态调整：
        //   INFORMATIONAL：问句型查询（"如何/多少/是否"）→用户找"答案"，QA 路应优先（提升到 1.5x）
        //   NAVIGATIONAL：导航型查询（≤ 4字关键词）→用户找"文档"，QA 很可能干扪（压低到 0.3x）
        //   UNKNOWN：其余情况→保持原 0.8x 默认不变，防止回归
        double wQa;
        if (queryIntent == SearchContext.QueryIntent.INFORMATIONAL) {
            wQa = configKnn * 1.50; // 问答意图：QA 权重大幅提升，让 QA 候选稳定进入候选池
            System.out.printf("[RRF] INFORMATIONAL intent → wQa boosted: %.2f%n", wQa);
        } else if (queryIntent == SearchContext.QueryIntent.NAVIGATIONAL) {
            wQa = configKnn * 0.30; // 导航意图：QA 干扪文档检索，显著压低
            System.out.printf("[RRF] NAVIGATIONAL intent → wQa suppressed: %.2f%n", wQa);
        } else {
            wQa = configKnn * 0.80; // UNKNOWN：保持原默认不变
        }
        if (qaHits != null && !qaHits.isEmpty()) {
            int rank = 1;
            for (Map<String, Object> qaHit : qaHits) {
                // 从 QA hit 提取置信度（Python 已计算的 cosine similarity）
                Object confObj = qaHit.get("_qa_confidence");
                double qaConf = confObj instanceof Number ? ((Number) confObj).doubleValue() : 0.0;
                if (qaConf == 0.0) {
                    // 降级：从 _rrf_score 反推（_rrf_score = cosine_sim * 0.015）
                    Object rrfObj = qaHit.get("_rrf_score");
                    qaConf = rrfObj instanceof Number ? Math.min(((Number) rrfObj).doubleValue() / 0.015, 1.0) : 0.5;
                }
                // 只融合置信度 ≥ 0.60 的 QA（过低的 QA 质量差，不应进入候选池）
                if (qaConf < 0.60) { rank++; continue; }

                String id = (String) qaHit.get("_id");
                if (id == null) { rank++; continue; }

                double score = wQa * (1.0 / (k + rank));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);

                if (!docRegistry.containsKey(id)) {
                    // 将 QA hit 转为与 ES hit 兼容的 docRegistry 格式
                    Map<String, Object> docMap = convertQaHitToDoc(qaHit);
                    docMap.put("_qa_hit",        Boolean.TRUE);
                    docMap.put("_qa_confidence", qaConf);
                    docRegistry.put(id, docMap);
                } else {
                    // 已存在于其他通道：只添加 QA 标记和置信度，不覆盖 source
                    Map<String, Object> existing = docRegistry.get(id);
                    existing.put("_qa_hit",        Boolean.TRUE);
                    existing.put("_qa_confidence", qaConf);
                }
                rank++;
            }
            System.out.printf("[RRF] QA channel: %d hits (wQa=%.3f)%n", qaHits.size(), wQa);
        } else {
            System.out.println("[RRF] QA channel: empty (skipped)");
        }

        // ── [Phase2 v2] 前言精准降权（替代原 coarse/fine 粒度区分策略）──────────────────
        // 设计重思：原方案对公示类文档做"fine +40% / coarse -30%"调权，但实地验证发现：
        //   - PublicNoticeDocStrategy 使用 person_boundary_words 将每人信息切为独立 chunk
        //   - 这些人员信息 chunk 粒度标签是 "coarse"（不是 fine！）
        //   - 原方案的 coarse×0.7 会同时降权人员信息，恰好打错了目标
        //
        // 正确方向：不依赖 coarse/fine 粒度标签，而是精准识别"前言 chunk"并降权：
        //   1. is_preamble=true（Phase3 打标，最精准，新入库文档生效）
        //   2. section_path 为空 且 chunk 内容含前言套语（现存文档兜底方案）
        //   3. doc_type 感知：公示类对前言的降权力度更大（-70% vs 普通 -50%）
        //
        // [Bug Fix] 先收集 adjustments 到独立 Map，循环结束后统一 put，避免 ConcurrentModificationException
        Map<String, Double> adjustments = new HashMap<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            String docId = entry.getKey();
            Map<String, Object> doc = docRegistry.get(docId);
            if (doc == null) continue;

            Object sourceObj = doc.get("_source");
            if (!(sourceObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) sourceObj;

            Object metaObj = source.get("metadata");
            String docType = "";
            boolean isPreamble = false;
            String sectionPath = "";
            if (metaObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) metaObj;
                docType     = String.valueOf(metadata.getOrDefault("doc_type", ""));
                isPreamble  = Boolean.TRUE.equals(metadata.getOrDefault("is_preamble", false));
                sectionPath = String.valueOf(metadata.getOrDefault("section_path", ""));
            }

            // 兜底前言识别：section_path 为空（文档开头，未进入任何章节）
            // 且内容包含典型前言套语（针对存量未重新入库、is_preamble 尚未写入的文档）
            boolean contentLooksLikePreamble = false;
            if (!isPreamble && (sectionPath == null || sectionPath.isEmpty() || "null".equals(sectionPath))) {
                Object contentObj = source.get("content");
                if (contentObj instanceof String) {
                    String snippet = ((String) contentObj);
                    snippet = snippet.length() > 200 ? snippet.substring(0, 200) : snippet;
                    contentLooksLikePreamble = snippet.contains("根据") && (snippet.contains("规定") || snippet.contains("条例"))
                        || snippet.contains("经") && snippet.contains("研究决定")
                        || snippet.contains("特此通知") || snippet.contains("特此公示")
                        || snippet.contains("现将") && snippet.contains("公示");
                }
            }

            boolean shouldDemote = isPreamble || contentLooksLikePreamble;
            if (!shouldDemote) continue;

            // 前言降权系数：公示类文档降更多（前言与用户意图差距最大）
            double demoteMultiplier;
            if ("公示".equals(docType)) {
                demoteMultiplier = 0.25; // 公示前言：降至原来 25%（强降权）
            } else if ("通知".equals(docType) || "通告".equals(docType)) {
                demoteMultiplier = 0.40; // 通知/通告：降至 40%
            } else {
                demoteMultiplier = 0.55; // 其他类型：降至 55%（温和降权）
            }
            adjustments.put(docId, entry.getValue() * demoteMultiplier);
        }
        // 统一应用调权（循环外 put，避免 ConcurrentModificationException）
        adjustments.forEach(rrfScores::put);
        System.out.printf("[RRF][Phase2v2] Preamble demoting applied to %d chunks.%n", adjustments.size());

        // ── 排序并返回候选池 ──────────────────────────────────────────────────
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(entry -> {
                    Map<String, Object> doc = docRegistry.get(entry.getKey());
                    doc.put("_rrf_score", entry.getValue());
                    return doc;
                })
                .collect(Collectors.toList());
    }

    /**
     * 将 ES Hit 对象转换为 Map，提取 _id/_score/_es_score/_source/highlight 等字段。
     */
    private Map<String, Object> convertHitToMap(Hit<Object> hit) {
        Map<String, Object> map = new HashMap<>();
        map.put("_id",       hit.id());
        map.put("_score",    hit.score());
        map.put("_es_score", hit.score()); // 防止下游 NPE，保留冗余字段
        map.put("_source",   hit.source());
        if (hit.highlight() != null) {
            map.put("highlight", hit.highlight());
        }
        return map;
    }

    /**
     * [架构重构] 将 Python QA 层返回的 Map 转换为与 ES Hit 兼容的 docRegistry 格式。
     *
     * 业务功能：
     *   QA 候选不是 ES Hit 对象，而是 Python 层返回的 Map（模拟 Python  AiEngineGateway.fetchQaResults）。
     *   此方法将其转为与 convertHitToMap 相同的结构，下游 RerankStep 可以统一处理：
     *   - _id     : QA 文档 _id
     *   - _source : QA 数据源 Map（含 question/answer_content/metadata 等）
     *   - _es_score : QA 置信度分（供下游进一步使用）
     *
     * @param qaHit Python 层返回的单条 QA 候选 Map
     * @return 与 ES Hit 兼容的 docRegistry 格式 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertQaHitToDoc(Map<String, Object> qaHit) {
        Map<String, Object> doc = new HashMap<>();
        // _id：直接使用 QA 文档的 _id
        doc.put("_id", qaHit.get("_id"));
        // _source：优先读 _source，降级用整个 qaHit 作为源
        Object srcObj = qaHit.get("_source");
        doc.put("_source", srcObj != null ? srcObj : qaHit);
        // _es_score：用 QA 置信度作为代理分（供 LTR 打分扩展使用）
        Object confObj = qaHit.get("_qa_confidence");
        double conf = confObj instanceof Number ? ((Number) confObj).doubleValue() : 0.75;
        doc.put("_score",    conf);
        doc.put("_es_score", conf);
        return doc;
    }
}
