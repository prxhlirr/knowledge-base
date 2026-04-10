package com.boyang.search.pipeline.steps;

import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管线节点 4.5：Q&A 问答对注入（对齐 V1 SearchService.fetchQaResults 逻辑）
 *
 * 业务功能：
 *   在 RRF 融合之后、DocExpansion 之前，将 QA 索引的精准命中注入候选池 HEAD。
 *   QA 命中的文档标记 _qa_hit=true，在后续 RerankStep 中享有：
 *     1. Veto Gate 豁免（不被低分过滤）
 *     2. LTR 打分直接给满分 1.0（精准问答优先展示）
 *
 * 关键流程（对齐 V1 L1460-1507）：
 *   1. 无向量时跳过（无法做 KNN Q&A 匹配，降级）
 *   2. 调用 AiEngineGateway.fetchQaResults（KNN 检索 QA 索引，top-3）
 *   3. Domain Filter（双路过滤，防止低相关噪声注入）：
 *      - 高分路径（_rrf_score >= 0.012）：直接接受，无需 bigram 校验
 *      - 低分路径：需要 queryText 的 bigram 至少一个与 QA answer 重叠
 *   4. 标记 _qa_hit=true，注入候选池最前端（HEAD 优先级最高）
 *
 * 设计决策：
 *   - Domain Filter 在 Java 侧执行（Python 端不做），保持职责单一
 *   - QA 检索失败/超时（fastRestTemplate 3s 超时）时静默跳过，不阻断主链路
 *   - 无向量时（skipEmbedding=true）直接跳过，避免 fetchQaResults 空向量请求
 */
@Component
public class QaInjectionStep implements SearchPipelineStep {

    @Autowired
    private AiEngineGateway aiEngineGateway;

    @Override
    public void execute(SearchContext context) throws Exception {
        // 无向量时无法做 KNN Q&A 匹配，直接跳过
        List<Double> queryVector = context.getQueryVector();
        if (queryVector == null || queryVector.isEmpty()) {
            System.out.println("[QaInjection] 跳过：无查询向量（skipEmbedding 场景）");
            return;
        }

        String queryText   = context.getQueryText();
        // force_source：从 filters 中取租户数据源（对应 metadata.source），null 时不过滤
        String forceSource = context.getFilters() != null
                ? (String) context.getFilters().get("data_source") : null;

        // Step 1: 检索 QA 索引（top-3，降级返回空列表）
        List<Map<String, Object>> qaCandidates =
                aiEngineGateway.fetchQaResults(queryVector, queryText, forceSource, 3);

        if (qaCandidates == null || qaCandidates.isEmpty()) return;

        // Step 2: 构建 queryText 的 bigram 集合（用于低分 QA 的 Domain Filter）
        final Set<String> queryBigrams = buildBigrams(queryText);

        // Step 3: Domain Filter（对齐 V1 L1469-1498）
        //   - 高分（_rrf_score >= 0.012）: 直通，视为高置信度精准匹配
        //   - 低分: bigram 重叠校验，防止低相关噪声 QA 侵入候选池
        List<Map<String, Object>> accepted = qaCandidates.stream()
                .filter(candidate -> {
                    Object rrfObj = candidate.get("_rrf_score");
                    double qaRrf = (rrfObj instanceof Number) ? ((Number) rrfObj).doubleValue() : 0.0;
                    if (qaRrf >= 0.012) return true;  // 高置信度直通

                    // 低分 QA：bigram 重叠校验（需至少 1 个 bigram 命中 QA 内容）
                    // 取 answer 或 question 字段（降级取 content 字段）
                    Object srcObj = candidate.get("_source");
                    if (!(srcObj instanceof Map)) return false;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) srcObj;
                    String qaContent = (String) src.getOrDefault("answer",
                                       src.getOrDefault("question",
                                       src.getOrDefault("content", "")));
                    if (qaContent == null || qaContent.isEmpty()) return false;
                    final String qaText = qaContent;
                    return queryBigrams.stream().anyMatch(qaText::contains);
                })
                .collect(Collectors.toList());

        if (accepted.isEmpty()) return;

        // Step 4: 标记 _qa_hit=true，供 RerankStep Veto Gate 豁免 + LTR 满分逻辑使用
        accepted.forEach(c -> c.put("_qa_hit", Boolean.TRUE));

        // Step 5: 注入候选池 HEAD（优先级最高）
        List<Map<String, Object>> merged = new ArrayList<>(accepted);
        List<Map<String, Object>> existing = context.getCandidateDocs();
        if (existing != null) merged.addAll(existing);
        context.setCandidateDocs(merged);

        System.out.printf("[QaInjection] Injected %d Q&A hits into candidates HEAD.%n", accepted.size());
    }

    /**
     * 构建字符串的 bigram 集合（相邻两字组合）。
     * 用于低分 QA Domain Filter 的字面重叠校验。
     * 空输入返回空集合（调用方 bigrams.stream().anyMatch 结果为 false，等效拦截低分 QA）。
     *
     * @param text 原始查询词
     * @return bigram 集合（如 "静宁苹果" → {"静宁", "宁苹", "苹果"}）
     */
    private Set<String> buildBigrams(String text) {
        if (text == null || text.length() < 2) return Collections.emptySet();
        Set<String> bigrams = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }
}
