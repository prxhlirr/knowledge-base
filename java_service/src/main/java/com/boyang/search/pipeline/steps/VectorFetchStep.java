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
 * 负责：
 * - 发送合并的 Rewrite+HyDE 请求（应对长尾问题）
 * - 直接申请 BGE 向量（应对精确查询、短尾问题）
 * - 质量门控（防止 LLM 幻觉生成严重偏移事实的 HyDE 向量）
 */
@Component
public class VectorFetchStep implements SearchPipelineStep {

    @Autowired
    private AiEngineGateway gateway;

    // [P0-4 补充] 注入调参配置，让 HyDE Gate 阈值从 DB 热配置读取，不再硬编码为 0.75
    @Autowired
    private SysAiTuningConfigService tuningConfigService;

    @Override
    public void execute(SearchContext context) throws Exception {
        boolean skipLlmRewrite = context.isSkipLlmRewrite();
        boolean isShortQuery = context.isShortQuery();
        boolean mightNeedVector = !context.isSkipEmbedding();
        String queryForMerged = context.getNormalizedQuery();

        List<Double> queryVector = null;
        List<Double> originalQueryVector = null;
        String rewrittenQuery = queryForMerged;

        // [HyDE增强] 文档类型型查询优先走专属 HyDE，绕过 isShortQuery 拦截
        // 根因：isShortQuery 拦截会让"2024年任职公示"直接走 BGE 原始向量，
        // 导致向量落入「文档类型/通知」语义域，远离「人员信息」语义域，KNN 召回严重偏移。
        // 修复：isDocTypeQuery=true 时，跳过短查询分支，强制进入 HyDE 路径，
        // Python 侧根据 queryType 切换专属 Prompt（公示→生成人员列表，法规→生成条文）。
        boolean isDocTypeQuery = context.isDocTypeQuery();
        if (isDocTypeQuery && mightNeedVector) {
            System.out.println("[VectorFetchStep] DOC_TYPE query: 绕过 isShortQuery，走专属 HyDE 路径");
            // 并发：HyDE（专属 Prompt）+ 原始向量（用于 Gate 校验）
            String docQueryType = inferDocQueryType(queryForMerged); // 推断子类型 "公示"/"法规"/...
            final String finalDocQueryType = docQueryType;
            CompletableFuture<Map<String, Object>> hydeFuture = com.boyang.search.util.AsyncContextUtil
                    .supplyAsync(() -> gateway.fetchRewriteAndHyde(queryForMerged, true, finalDocQueryType));

            CompletableFuture<List<Double>> origVecFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                try {
                    return gateway.fetchQueryVector(queryForMerged);
                } catch (Exception e) {
                    return null;
                }
            });

            Map<String, Object> mergedResult;
            try {
                // HyDE timeout：从 2s 延长到 4000ms。
                // 接入云模型（如 SiliconFlow）时生成完整的假设文档通常耗时更长，
                // 2000ms 极易因为网络抖动导致首抽出现 TimeoutException 从而跳过 HyDE。
                mergedResult = hydeFuture.get(4_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.err.println("[VectorFetchStep] DocType HyDE 4000ms 超时，降级使用 BGE 原始向量");
                hydeFuture.cancel(true);
                mergedResult = new java.util.HashMap<>();
                mergedResult.put("rewritten_query", queryForMerged);
                mergedResult.put("vector", null);
            }

            rewrittenQuery = (String) mergedResult.getOrDefault("rewritten_query", queryForMerged);
            @SuppressWarnings("unchecked")
            List<Double> hydeVector = (List<Double>) mergedResult.get("vector");

            List<Double> origVector = null;
            try {
                origVector = origVecFuture.get(3_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                origVecFuture.cancel(true);
            }
            originalQueryVector = origVector;

            // ⚠️ [机制修复：去门控化]
            // 原逻辑强行使用 <= 0.60 的余弦相似度拦截，而实体清单推演与概念长句之间的夹角通常很大
            // 这里无条件放行专属 HyDE 生成的高维人事/法规清单，彻底根治被强制退化及“跳过 HyDE”的问题。
            if (hydeVector != null && !hydeVector.isEmpty()) {
                double hydeSim = (origVector != null) ? cosineSimilarity(hydeVector, origVector) : 1.0;
                System.out.printf("[HyDE Gate DocType] 检测到专属 HyDE 向量注入，绕过门控强制放行！参考 cos_sim=%.4f%n", hydeSim);
                queryVector = hydeVector;
            } else {
                queryVector = origVector;
                System.out.println("[HyDE Gate DocType] HyDE vector 实在因故未返回(null)，退求其次依赖 BGE 原词");
            }

            context.setRewrittenQuery(rewrittenQuery);
            context.setQueryVector(queryVector);
            context.setOriginalQueryVector(originalQueryVector);
            System.out.println("====== [Pipeline] Node 2: Vectorization (DocType HyDE) ======");
            System.out.println("  - QueryType: " + docQueryType);
            System.out.println("  - Vector size: " + (queryVector != null ? queryVector.size() : "NULL"));
            return; // 走专属路径，跳过下面的通用逻辑
        }

        if (skipLlmRewrite || isShortQuery) {
            // ── 精确/短查询路径：跳过 LLM 扩写 ──────────────────────────────────
            // [Step2 优化] 改为调用 fetchDualVector，单次 HTTP 同时获取 dense+sparse
            // 原链路：VectorFetchStep fetchQueryVector(600ms) + EsRecallStep
            // fetchSparseVector(600ms) = 1200ms 串行
            // 优化后：fetchDualVector(600ms) 是 dense/sparse 同时就绪，EsRecallStep 直接从 context 取，节省
            // ~500ms
            // 降级：dual 接口失败则 fallback 调用 fetchQueryVector，sparse=null(EsRecallStep 自行调用)
            if (mightNeedVector) {
                Map<String, Object> dual = gateway.fetchDualVector(queryForMerged);
                if (dual != null) {
                    queryVector = (java.util.List<Double>) dual.get("dense");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Double> sparseVec = (java.util.Map<String, Double>) dual.get("sparse");
                    context.setQuerySparseVector(sparseVec); // EsRecallStep 优先读此值跳过重复调用
                } else {
                    // dual 接口不可用（ai-service 未更新或网络问题），降级到原有单独调用
                    System.err.println("[VectorFetchStep] fetchDualVector failed, fallback to fetchQueryVector");
                    queryVector = gateway.fetchQueryVector(queryForMerged);
                }
                originalQueryVector = queryVector;
            }
            System.out.println("[HyDE Gate] SKIP (short/exact query) -> Using native BGE Vector");
        } else {
            // ── 长查询路径：并发执行 HyDE + 原始向量 ───────────────────────────
            // [Batch3a] 原逻辑：先调 fetchRewriteAndHyde(4s），再串行等 fetchQueryVector(2s)
            // 修复：两个请求并发发出，总耗时为 max(t_hyde, t_orig_vec)，节省约 2-3s
            CompletableFuture<Map<String, Object>> hydeFuture = com.boyang.search.util.AsyncContextUtil
                    .supplyAsync(() -> gateway.fetchRewriteAndHyde(queryForMerged, mightNeedVector));

            // 并发获取原始查询向量（用于 HyDE Gate 相似度校验）
            // 根因：HyDE Gate 需要对比 HyDE 向量与原始向量的余弦相似度，
            // 若等 HyDE 返回后再取原始向量则两次请求串行，浪费 BGE 推理时间
            CompletableFuture<List<Double>> origVecFuture = mightNeedVector
                    ? com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                        try {
                            return gateway.fetchQueryVector(queryForMerged);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    : CompletableFuture.completedFuture(null);

            Map<String, Object> mergedResult;
            try {
                // [性能优化] hydeFuture timeout 5000ms→1000ms
                // 根因：Qwen 7B GPU 推理需 3~8s，5s 等待对用户而言几乎必然是白等。
                // 策略：1s 内未返回则用 BGE 直接向量兜底（origVecFuture 已并发跑完）。
                // Python 侧 rewrite_and_hyde 已改为后台线程继续跑 Qwen + 写缓存，
                // 相同查询第二次请求命中缓存，0ms 获取 HyDE 优化向量，质量不损失。
                mergedResult = hydeFuture.get(1_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.err.println("[VectorFetchStep] HyDE 1s 超时，降级使用 BGE 原始向量（Python 后台继续预热缓存）。");
                hydeFuture.cancel(true);
                mergedResult = new HashMap<>();
                mergedResult.put("rewritten_query", queryForMerged);
                mergedResult.put("vector", null);
            }

            rewrittenQuery = (String) mergedResult.getOrDefault("rewritten_query", queryForMerged);
            @SuppressWarnings("unchecked")
            List<Double> hydeVector = (List<Double>) mergedResult.get("vector");

            // 等待原始向量（若并发任务仍在运行，剩余时间内等完；失败则继续）
            List<Double> origVector = null;
            try {
                origVector = origVecFuture.get(3_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                System.err.println("[VectorFetchStep] Original vector timed out, HyDE Gate skipped.");
                origVecFuture.cancel(true);
            }
            originalQueryVector = origVector;

            System.out.println("  - Rewritten : '" + rewrittenQuery + "'");

            // ── HyDE Gate 质量门控 ────────────────────────────────────────────
            // 目标：防止 LLM 幻觉生成，HyDE 向量与原始语义严重偏离
            if (mightNeedVector && hydeVector != null && !hydeVector.isEmpty()) {
                double hydeSim = (origVector != null) ? cosineSimilarity(hydeVector, origVector) : 1.0;
                System.out.printf("[HyDE Gate] cos_sim(HyDE, original)=%.4f%n", hydeSim);
                // [P0-4 修复] 使用 DB 可配置阈值，而非硬编码为 0.75
                // 根因：硬编码违反「热配置」原则，运营人员无法通过管理界面调整该参数
                double hydeMinSim = tuningConfigService.getGlobalConfig().getHydeMinSim();
                if (hydeSim >= hydeMinSim) {
                    queryVector = hydeVector;
                    System.out.println("[HyDE Gate] PASS -> Using HyDE Vector (threshold=" + hydeMinSim + ")");
                } else {
                    queryVector = origVector; // 降级使用原始向量，已并发获取无额外开销
                    System.out.println("[HyDE Gate] FAIL (Drifted cos=" + String.format("%.3f", hydeSim) + " < "
                            + hydeMinSim + ") -> fallback to original");
                }
            } else if (mightNeedVector) {
                // HyDE 失败降级：使用已并发获取的原始向量
                queryVector = origVector;
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
        if (a == null || b == null || a.size() != b.size() || a.isEmpty())
            return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double ai = a.get(i), bi = b.get(i);
            dot += ai * bi;
            normA += ai * ai;
            normB += bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-12 ? 0.0 : dot / denom;
    }

    /**
     * 业务功能：推断文档类型型查询的子类型，供 Python 侧选择专属 HyDE Prompt。
     * 规则：按关键词匹配，优先级从高到低。
     *
     * @param query 归一化后的查询文本
     * @return 子类型字符串："公示" / "法规" / "通知" / "方案" / "general_doc"
     */
    private String inferDocQueryType(String query) {
        if (query == null)
            return "general_doc";
        // 公示/任命/提拔/干部/人事 → 人员名单型
        if (query.contains("公示") || query.contains("任职") || query.contains("任命")
                || query.contains("提拔") || query.contains("干部") || query.contains("人事")
                || query.contains("调整")) {
            return "公示";
        }
        // 法规/条例/办法/规定/细则 → 条文型
        if (query.contains("法规") || query.contains("条例") || query.contains("办法")
                || query.contains("规定") || query.contains("细则") || query.contains("章程")) {
            return "法规";
        }
        // 通知/意见/方案 → 政务通知型
        if (query.contains("通知") || query.contains("意见") || query.contains("方案")
                || query.contains("制度")) {
            return "通知";
        }
        return "general_doc";
    }
}
