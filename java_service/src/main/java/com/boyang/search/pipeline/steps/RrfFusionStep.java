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
 * 管线节点 4：多路召回结果融合 (Three-Way RRF)
 *
 * 业务功能：
 *   将 BM25（全文检索）、KNN（稠密向量语义检索）、Sparse（稀疏向量词汇信号检索）
 *   三路召回结果，通过加权倒数排名融合（Weighted RRF）合并为统一候选池。
 *
 * 关键设计：
 *   1. [Batch2 修复] Sparse 通道使用独立权重 wSparse，不再耦合 wKnn：
 *      原因：NAVIGATIONAL 意图时 wKnn 被压低，但 Sparse 恰好在精确词汇场景最有价值，
 *            耦合会导致 Sparse 在最需要它的场景反而被削弱（反直觉）。
 *   2. [Batch2 修复] 每路结果只融合一次，消除原代码中 Sparse 被融合两次的 Bug。
 *   3. [P2-1 修复] 从 SearchContext 专用强类型字段读取，删除 __es_raw_responses 魔法 key。
 *   4. 自适应权重（方案J）：根据 BM25/KNN 实际信号强度动态调整 wText/wKnn。
 *   5. BM25 弱信号抑制：BM25 最高分 < 2.0 时，纯 BM25 命中候选被压制至 5%。
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

        double bm25MaxScore = 0.0;
        if (textResp != null && textResp.hits() != null && !textResp.hits().hits().isEmpty()
                && textResp.hits().hits().get(0).score() != null) {
            bm25MaxScore = textResp.hits().hits().get(0).score();
        }

        List<Map<String, Object>> candidates = rrfMerge(
            textResp, knnResp, sparseResp,
            context.getTopK(), context.getTuningConfig(), context.isNavigationalBypass()
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
     * 三路 Weighted RRF 融合。
     *
     * 业务功能：将 BM25/KNN/Sparse 三路召回按加权倒数排名公式融合为统一排序候选池。
     * 融合公式：total_score = wText*(1/(k+rank_bm25)) + wKnn*(1/(k+rank_knn)) + wSparse*(1/(k+rank_sparse))
     *
     * @param textResp   BM25 全文检索结果（可为 null）
     * @param knnResp    KNN 稠密向量检索结果（可为 null，向量化失败时降级）
     * @param sparseResp 稀疏向量检索结果（可为 null，接口不稳定时降级）
     * @param topK       候选池目标大小
     * @param config     调参配置（bm25Weight、vectorWeight、rrfK 等来自 DB）
     * @param navigational 是否为导航型意图（精确文号/关键词查询，BM25 主导）
     * @return 按融合分倒序排列的候选文档列表，每条 Map 携带 _rrf_score/_max_knn_score
     */
    private List<Map<String, Object>> rrfMerge(
            SearchResponse<Object> textResp,
            SearchResponse<Object> knnResp,
            SearchResponse<Object> sparseResp,
            int topK,
            SysAiTuningConfig config,
            boolean navigational) {

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
            double bm25Confidence = Math.min(bm25RawMax / 10.0, 1.0); // 10分为满信号
            double knnConfidence  = knnRawMax;                          // 余弦相似度已在 [0,1]
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
            for (Hit<Object> hit : textResp.hits().hits()) {
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
}
