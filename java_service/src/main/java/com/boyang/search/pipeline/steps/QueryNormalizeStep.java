package com.boyang.search.pipeline.steps;

import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 管线节点 1：意图梳理与文本归一化
 * 负责：
 * - 全角转半角的本地预处理
 * - 访问 NLP 网关进行拼音补充和词法规则
 * - 路由识别长查询的重写策略（精确查询、短词免重写）
 * - 识别 NavigationalBypass 等业务标识
 */
@Component
public class QueryNormalizeStep implements SearchPipelineStep {

    @Autowired
    private AiEngineGateway aiEngineGateway;

    // 意图判断：疑问词/逻辑关系字眼
    private static final Pattern INTENT_PATTERN = Pattern.compile(
        "怎么|如何|为什么|是否|有没有|多少|哪些|哪个|区别|差异|.*关系|应当|不得|禁止|.*外|否则|条件|情形|流程|步骤"
    );

    @Override
    public void execute(SearchContext context) throws Exception {
        String queryText = context.getQueryText();

        // 1. [精确查询短路] 本地预判：精确查询跳过 Python NLP 归一化（避免 3s 延迟折损）
        // [P2-2 修复] isExactQuery 只调用一次，并用 exactQueryPrecheck 标记初始结果。
        //             避免 NLP 后再次 calls isExactQuery(normalizedQuery) 覆盖 skipLlmRewrite。
        // 因 Bug：L40 处 skipLlmRewrite=true，L74 处 normalizedQuery 重新判断再赋值，
        //         若 NLP 服务修改过 query 格式使其不满足精确判断，L74 会错误改回 false。
        boolean exactQueryPrecheck = isExactQuery(queryText.trim());

        String normalizedQuery;
        String queryPinyin;
        // [Step3 优化] 语义问句（含意图且长度大于6）跳过 Python NLP normalize（消减 ~200ms）。
        // 根因：NLP normalize 的价值是拼音补充（如静宁"jingnin"）和错别字纠正，
        //       对含"什么/如何/哪些/条件"等意图词的完整语义问句完全无效——
        //       语义完整，BM25 不依赖拼音，调用 HTTP /api/ai/nlp/normalize 纯属浪费。
        boolean isSemanticQuestion = !exactQueryPrecheck
                && queryText.trim().length() > 6
                && INTENT_PATTERN.matcher(queryText).find();

        if (exactQueryPrecheck || isSemanticQuestion) {
            normalizedQuery = fullWidthToHalf(queryText.trim());
            queryPinyin = "";
            if (exactQueryPrecheck) {
                System.out.println("[QueryNormalizeStep] 精确查询，跳过 Python NLP，使用本地全角→半角预处理");
            } else {
                System.out.println("[QueryNormalizeStep] 语义问句（含意图词），跳过 NLP normalize（消减 200ms）");
            }
        } else {
            CompletableFuture<Map<String, String>> normFuture = com.boyang.search.util.AsyncContextUtil.supplyAsync(() ->
                aiEngineGateway.normalizeQuery(queryText)
            );
            Map<String, String> normData;
            try {
                normData = normFuture.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[QueryNormalizeStep] Timeout/Error in normalization. Using fallback.");
                normData = new java.util.HashMap<>();
                normData.put("normalized", fullWidthToHalf(queryText));
                normData.put("pinyin", "");
            }
            normalizedQuery = normData.getOrDefault("normalized", fullWidthToHalf(queryText));
            queryPinyin = normData.getOrDefault("pinyin", "");
        }

        context.setNormalizedQuery(normalizedQuery);
        context.setQueryPinyin(queryPinyin);

        System.out.println("====== [Pipeline] Node 1: Input & Normalization ======");
        System.out.println("  - Original Query: '" + queryText + "'");
        System.out.println("  - Normalized: '" + normalizedQuery + "'");
        System.out.println("  - Pinyin    : '" + queryPinyin + "'");

        // 2. 意图路由与标志位设置
        // [P2-2 修复] skipLlmRewrite 最终结果：精确查询预检 OR NLP 后再次判断（两者取 OR）
        // 场景说明：精确查询预检为 false 时，NLP 后的 normalizedQuery 若变为精确格式（罕见），
        //           也应标记为 skipLlmRewrite=true，兼顾两种路径。
        boolean skipLlmRewrite = exactQueryPrecheck || isExactQuery(normalizedQuery);
        context.setSkipLlmRewrite(skipLlmRewrite);

        boolean isShortQuery = (normalizedQuery != null && normalizedQuery.trim().length() <= 15);
        context.setShortQuery(isShortQuery);

        // [Navigational Bypass] 如果是小于4字的纯名词，后续跳过重排序，加大BM25权重
        boolean navigationalBypass = queryText != null
            && queryText.trim().length() <= 4
            && !INTENT_PATTERN.matcher(queryText).find();
        context.setNavigationalBypass(navigationalBypass);
        if (navigationalBypass) {
            System.out.println("[IntentRouter] NAVIGATIONAL query ('" + queryText + "'), wBM25 + Reranker bypass.");
        }

        // [短语拦截熔断机制]
        boolean circuitBreakerEnabled = Boolean.TRUE.equals(context.getTuningConfig().getCircuitBreakerEnabled());
        context.setSkipEmbedding(circuitBreakerEnabled);
    }

    /**
     * 判断是否为精确查询：避免被 LLM 改写打散
     */
    private boolean isExactQuery(String query) {
        if (query == null || query.isEmpty()) return false;
        if (query.contains("\"") || query.contains("“") || query.contains("”")
                || query.contains("‘") || query.contains("’")) return true;
        if (query.contains("《") || query.contains("》") || query.contains("第")
                || Pattern.compile("[0-9]{4}[^0-9]*(号|发|函|令|字)").matcher(query).find()) return true;
        if (query.matches("[\\dA-Za-z\\-_/.]+")) return true;
        if (query.contains("/") && query.matches(".*[\\dA-Za-z]/[\\dA-Za-z].*")) return true;
        
        if (query.trim().length() <= 6) {
            String[] interrogativeWords = {"吗", "呢", "啊", "呀", "吧", "嘛", "哪", "谁", "啥", "咋",
                "么", "什", "如何", "为何", "为什", "哪里", "哪些"};
            for (String word : interrogativeWords) {
                if (query.contains(word)) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 全角转半角
     */
    private String fullWidthToHalf(String s) {
        if (s == null || s.isEmpty()) return s;
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u3000') {
                chars[i] = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                chars[i] = (char)(c - 0xFEE0);
            }
        }
        return new String(chars);
    }
}
