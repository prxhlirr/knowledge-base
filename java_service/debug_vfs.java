package com.boyang.search.pipeline.steps;

import com.boyang.search.entity.SysAiTuningConfig;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import com.boyang.search.service.SysAiTuningConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 管线节点 2：向量提取与质量门控
 * 负责�? * - 发送合并的 Rewrite+HyDE 请求（应对长尾问题）
 * - 直接申请 BGE 向量（应对精确查�?短尾问题�? * - 质量门控（防�?LLM 幻觉生成严重偏移事实�?HyDE 向量�? */
@Component
public class VectorFetchStep implements SearchPipelineStep {

    @Autowired
    private AiEngineGateway gateway;

    // [P0-4 补充] 注入调参配置，让 HyDE Gate 阈值从 DB 热配置读取，不再硬编�?0.75
    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    @Override
    public void execute(SearchContext context) throws Exception {
        boolean skipLlmRewrite = context.isSkipLlmRewrite();
        boolean isShortQuery   = context.isShortQuery();
        boolean mightNeedVector = !context.isSkipEmbedding();
        String queryForMerged  = context.getNormalizedQuery();

        List<Double> queryVector         = null;
        List<Double> originalQueryVector = null;
        String rewrittenQuery            = queryForMerged;

        if (skipLlmRewrite || isShortQuery) {
            // ── 精确/短查询路径：跳过 LLM 扩写 ──────────────────────────────────
            // [Step2 优化] 改为调用 fetchDualVector，单�?HTTP 同时获取 dense+sparse�?            //   原链路：VectorFetchStep fetchQueryVector(600ms) + EsRecallStep fetchSparseVector(600ms) = 1200ms 串行
            //   优化后：fetchDualVector(600ms) �?dense/sparse 同时就绪，EsRecallStep 直接�?context �?节省 ~500ms
            //   降级：dual 接口失败�?fallback �?fetchQueryVector，sparse=null(EsRecallStep 自行调用)
            if (mightNeedVector) {
                Map<String, Object> dual = gateway.fetchDualVector(queryForMerged);
                if (dual != null) {
                    queryVector = (java.util.List<Double>) dual.get("dense");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Double> sparseVec = (java.util.Map<String, Double>) dual.get("sparse");
                    context.setQuerySparseVector(sparseVec); // EsRecallStep 优先读此值跳过重复调�?                } else {
                    // dual 接口不可用（ai-service 未更�?网络问题），降级到原有单独调�?                    System.err.println("[VectorFetchStep] fetchDualVector failed, fallback to fetchQueryVector");
                    queryVector = gateway.fetchQueryVector(queryForMerged);
                }
                originalQueryVector = queryVector;
            }
            System.out.println("[HyDE Gate] SKIP (short/exact query) -> Using native BGE Vector");
        } else {
            // ── 长查询路径：并发执行 HyDE + 原始向量�?──────────────────────────
            // [Batch3a] 原逻辑：先�?fetchRewriteAndHyde�?s），再串行等 fetchQueryVector�?s�?            //           修复：两个请求并发发出，总耗时 �?max(t_hyde, t_orig_vec)，节省约 2-3s
            CompletableFuture<Map<String, Object>> hydeFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() ->
                gateway.fetchRewriteAndHyde(queryForMerged, mightNeedVector)
            );

            // 并发获取原始查询向量（用�?HyDE Gate 相似度校验）
            // 根因：HyDE Gate 需要对�?HyDE 向量与原始向量的余弦相似度，
            //       若等 HyDE 返回后再取原始向量则两次请求串行，浪�?BGE 推理时间
            CompletableFuture<List<Double>> origVecFuture = mightNeedVector
                ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                    try { return gateway.fetchQueryVector(queryForMerged); } catch (Exception e) { return null; }
                })
                : CompletableFuture.completedFuture(null);

            Map<String, Object> mergedResult;
            try {
                mergedResult = hydeFuture.get(5_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.err.println("[VectorFetchStep] HyDE call timed out, using fallback.");
                hydeFuture.cancel(true);
                mergedResult = new HashMap<>();
                mergedResult.put("rewritten_query", queryForMerged);
                mergedResult.put("vector", null);
            }

            rewrittenQuery = (String) mergedResult.getOrDefault("rewritten_query", queryForMerged);
            @SuppressWarnings("unchecked")
            List<Double> hydeVector = (List<Double>) mergedResult.get("vector");

            // 等待原始向量（若并发任务仍在运行，剩余时间内等完；失败则继续�?            List<Double> origVector = null;
            try {
                origVector = origVecFuture.get(3_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.err.println("[VectorFetchStep] Original vector timed out, HyDE Gate skipped.");
                origVecFuture.cancel(true);
            }
            originalQueryVector = origVector;

            System.out.println("  - Rewritten : '" + rewrittenQuery + "'");

            // ── HyDE Gate 质量门控 ────────────────────────────────────────────
            // 目标：防�?LLM 幻觉生成�?HyDE 向量与原始语义严重偏�?            if (mightNeedVector && hydeVector != null && !hydeVector.isEmpty()) {
                double hydeSim = (origVector != null) ? cosineSimilarity(hydeVector, origVector) : 1.0;
                System.out.printf("[HyDE Gate] cos_sim(HyDE, original)=%.4f%n", hydeSim);
                // [P0-4 修复] 使用 DB 可配置阈值，而非硬编�?0.75
                // 根因：硬编码违反「热配置」原则，运营人员无法通过管理界面调整该参�?                double hydeMinSim = tuningConfigService.getGlobalConfig().getHydeMinSim();
                if (hydeSim >= hydeMinSim) {
                    queryVector = hydeVector;
                    System.out.println("[HyDE Gate] PASS -> Using HyDE Vector (threshold=" + hydeMinSim + ")");
                } else {
                    queryVector = origVector; // 降级使用原始向量，已并发获取无额外开销
                    System.out.println("[HyDE Gate] FAIL (Drifted cos=" + String.format("%.3f", hydeSim) + " < " + hydeMinSim + ") -> fallback to original");
                }
            } else if (mightNeedVector) {
                // HyDE 失败降级：使用已并发获取的原始向�?                queryVector = origVector;
                System.out.println("[HyDE Gate] HyDE vector null, using original vector as fallback");
            }
        }

        context.setRewrittenQuery(rewrittenQuery);
        context.setQueryVector(queryVector);
        context.setOriginalQueryVector(originalQueryVector);

        System.out.println("====== [Pipeline] Node 2: Vectorization ======");
        if (queryVector != null && !queryVector.isEmpty()) {
            System.out.println("  - Vector Generated with Size: " + queryVector.size());
        } else {
            System.out.println("  - Vector is NULL/Empty (Skipped or Error)");
        }
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i), bi = b.get(i);
            dot   += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-12 ? 0.0 : dot / denom;
    }
}

