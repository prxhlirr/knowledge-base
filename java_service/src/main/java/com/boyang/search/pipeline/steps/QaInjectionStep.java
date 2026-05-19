package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 管线节点 4.5：QA FastTrack 预检（极严格短路机制）
 *
 * 业务功能：
 *   [架构重构] 本步骤已大幅精简，职责边界重新划定：
 *
 *   - 原职责（已移除）：
 *     1. 独立调用 AiEngineGateway 进行 QA KNN/BM25 召回   ← 已整合到 RecallStrategy 第四路 RRF
 *     2. 中置信（0.6~0.9）QA 手动注入 candidateDocs HEAD  ← 已由 RrfFusionStep 第四路融合替代
 *
 *   - 新职责（保留）：
 *     极严格 FastTrack 预检：conf > 0.97 AND ngram > 0.50
 *     满足条件（高置信 + 高字面重叠的双重保险）时直接 setFastTrackDocs，
 *     跳过 DocExpansion / ColBERT / Reranker，实现 < 200ms 的秒返回体验。
 *
 * 设计原则：
 *   1. 不再独立调用 AiEngineGateway，直接复用 context.getQaHits()（RecallStrategy 已并行取回）
 *   2. FastTrack 触发条件极严格（conf > 0.97 AND ngram > 0.50），防止误触，保障精排质量
 *   3. 非 FastTrack QA 通过第四路 RRF 进入 candidateDocs，与普通文档公平竞争 ColBERT 精排
 *
 * 管线位置：RrfFusionStep（节点4）→ QaInjectionStep（节点4.5）→ DocExpansionStep（节点5）
 */
@Component
public class QaInjectionStep implements SearchPipelineStep {

    /**
     * [阈值调整] FastTrack 置信度阈值（由原 0.97 放宽至 0.92）。
     * 放宽原因：同义改写的问句（"互联网发展要多少钱" vs "互联网发展需要投入多少资金？"）
     * 语义余弦相似度通常在 0.90~0.96 区间，0.97 过严导致系统性漏检。
     * 0.92 的边界仍远高于普通语义相关（0.6~0.8），不会引入低质量 QA 误触发。
     */
    private static final double FAST_TRACK_CONF_THRESHOLD = 0.92;

    /**
     * [阈值调整] FastTrack N-gram 字面重叠阈值（由原 0.50 放宽至 0.30）。
     * 放宽原因：中文 bigram（2字）颗粒极细，同义改写后 bigram 重叠率系统性偏低，
     * 例如 "发展要多" vs "发展需要" 无公共 bigram。0.50 会将大量语义高度相关的 QA 误判为字面不符。
     * 同时新增 unigram 校验取最大值，单字级重叠更能体现中文语义相关性。
     */
    private static final double FAST_TRACK_NGRAM_THRESHOLD = 0.30;

    @Override
    public void execute(SearchContext context) throws Exception {
        // [守卫] keyword 模式用户意图是精确文档检索，QA FastTrack 不应触发
        // 根因：KeywordRecallStrategy 已将 qaHits 置为空列表，此处双重保险防止意外触发
        if ("keyword".equals(context.getSearchMode())) {
            System.out.println("[QaFastTrack] 跳过：keyword 模式禁用 QA 注入");
            return;
        }

        // ── 复用第四路 RRF 已并行取回的 QA 候选，无需重复 API 调用 ────────────
        List<Map<String, Object>> qaHits = context.getQaHits();
        if (qaHits == null || qaHits.isEmpty()) return;

        String queryText = context.getQueryText();

        // ── 找置信度最高的 QA 候选 ──────────────────────────────────────────
        Map<String, Object> bestQa = null;
        double bestConf = 0.0;
        for (Map<String, Object> qa : qaHits) {
            double conf = getConfidence(qa);
            if (conf > bestConf) {
                bestConf = conf;
                bestQa = qa;
            }
        }

        // 置信度不足阈值时不触发 FastTrack（让 QA 正常参与 RRF 候选池竞争）
        if (bestQa == null || bestConf < FAST_TRACK_CONF_THRESHOLD) {
            System.out.printf("[QaFastTrack] 跳过：最高置信度 conf=%.3f < %.2f%n", bestConf, FAST_TRACK_CONF_THRESHOLD);
            return;
        }

        // ── N-gram 字面重叠校验（双重保险）──────────────────────────────────
        // 根因：KNN 向量相似度高但字面差异大（如领域内同义改写）时，用户体验可能不符合预期。
        // [改进] 改为 unigram + bigram 混合校验，取最大值：
        //   - unigram（单字）：更能反映中文语义关联，对同义改写容错性更高
        //   - bigram（双字）：保留精确字面重叠的判断
        //   取 max 后与阈值比较，避免「向量高置信且语义确实相关」但因改写方式不同被误判拒绝。
        @SuppressWarnings("unchecked")
        Map<String, Object> bestSrc = (Map<String, Object>) bestQa.get("_source");
        if (bestSrc == null) {
            System.out.println("[QaFastTrack] 跳过：QA _source 为 null，无法做 N-gram 校验");
            return;
        }
        String bestQuestion = (String) bestSrc.getOrDefault("question", "");
        double unigramScore = ngramOverlapRatio(queryText, bestQuestion, 1); // 单字重叠
        double bigramScore  = ngramOverlapRatio(queryText, bestQuestion, 2); // 双字重叠
        double ngramScore   = Math.max(unigramScore, bigramScore);           // 取最大值
        if (ngramScore < FAST_TRACK_NGRAM_THRESHOLD) {
            System.out.printf("[QaFastTrack] 跳过：N-gram 重叠率 %.3f（unigram=%.3f, bigram=%.3f）< %.2f（字面差异过大）%n",
                    ngramScore, unigramScore, bigramScore, FAST_TRACK_NGRAM_THRESHOLD);
            return;
        }

        // ── 双重条件均满足：设置 FastTrack，后续步骤短路返回 ────────────────
        bestQa.put("_qa_hit", Boolean.TRUE);
        bestQa.put("_qa_confidence", bestConf);
        context.setFastTrackDocs(Collections.singletonList(bestQa));
        System.out.printf("[QaFastTrack] TRIGGERED: conf=%.3f, ngram=%.3f | question='%s'%n",
                bestConf, ngramScore, getQuestion(bestQa, 30));
    }

    /**
     * 从 QA 候选中提取置信度分数。
     * 优先读 _qa_confidence（Python 侧已归一化到 [0,1]），降级从 _rrf_score 反推。
     *
     * @param qa QA 候选 Map
     * @return 置信度分数 [0, 1]
     */
    private double getConfidence(Map<String, Object> qa) {
        Object confObj = qa.get("_qa_confidence");
        if (confObj instanceof Number) return ((Number) confObj).doubleValue();
        // 降级：从 _rrf_score 反推（Python 侧 _rrf_score ≈ cosine_sim * 0.015）
        Object rrfObj = qa.get("_rrf_score");
        if (rrfObj instanceof Number) return Math.min(((Number) rrfObj).doubleValue() / 0.015, 1.0);
        return 0.0; // 无法判断置信度，视为 0（不触发 FastTrack）
    }

    /**
     * 从 QA source 提取 question 字段预览（用于日志输出）。
     *
     * @param qa     QA 候选 Map
     * @param maxLen 最大截取长度
     * @return question 字段的前 maxLen 字符
     */
    @SuppressWarnings("unchecked")
    private String getQuestion(Map<String, Object> qa, int maxLen) {
        Object src = qa.get("_source");
        if (src instanceof Map) {
            Object q = ((Map<String, Object>) src).get("question");
            if (q instanceof String) {
                String qs = (String) q;
                return qs.length() > maxLen ? qs.substring(0, maxLen) + "..." : qs;
            }
        }
        return "";
    }

    /**
     * 计算两个字符串之间的 N-gram 重叠率（[0, 1]）。
     *
     * 业务功能：FastTrack N-gram 双重保险校验。
     * 原理：统计 text1 的所有 n-gram 中，有多少出现在 text2 中，比例为重叠率。
     * FastTrack 版本仅对 question 字段做校验（answer 通常比 query 长很多，容易误判为全覆盖）。
     *
     * @param text1 查询词（用户 query）
     * @param text2 QA question 字段
     * @param n     n-gram 窗口大小（通常为 2，即 bigram）
     * @return 重叠率 [0, 1]
     */
    private double ngramOverlapRatio(String text1, String text2, int n) {
        if (text1 == null || text1.length() < n) return 0.0;
        if (text2 == null || text2.isEmpty()) return 0.0;
        Set<String> grams1 = buildNgrams(text1, n);
        if (grams1.isEmpty()) return 0.0;
        long overlap = grams1.stream().filter(text2::contains).count();
        return (double) overlap / grams1.size();
    }

    /**
     * 构建字符串的 N-gram 集合（相邻 n 字组合）。
     *
     * @param text 原始文本
     * @param n    窗口大小
     * @return N-gram 集合
     */
    private Set<String> buildNgrams(String text, int n) {
        if (text == null || text.length() < n) return Collections.emptySet();
        Set<String> ngrams = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            ngrams.add(text.substring(i, i + n));
        }
        return ngrams;
    }
}
