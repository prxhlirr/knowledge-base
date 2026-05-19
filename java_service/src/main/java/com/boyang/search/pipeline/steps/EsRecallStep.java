package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;
import com.boyang.search.pipeline.SearchPipelineStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 管线节点 3：Elasticsearch 多路召回（薄委托者）
 *
 * 业务功能：
 *   作为 Pipeline 中的固定节点，根据 SearchContext.searchMode 选择对应的召回策略，
 *   将实际执行委托给具体的 RecallStrategy 实现类。
 *
 * 设计模式：Strategy Pattern（委托者 + 策略接口）
 *   - 委托者（本类）：仅负责策略选择，不含任何 ES 查询逻辑
 *   - 策略接口：RecallStrategy.recall(context)
 *   - 具体策略：
 *       KeywordRecallStrategy  -> "keyword"  : BM25 only
 *       SemanticRecallStrategy -> "semantic" : KNN + Sparse
 *       HybridRecallStrategy   -> "hybrid"   : BM25 + KNN + Sparse（默认）
 *
 * 扩展性：
 *   新增检索模式（如 "graph" 知识图谱召回）只需：
 *     1. 新建实现 RecallStrategy 的 @Component 类
 *     2. 在本类 selectStrategy() 中添加一行 case
 *   EsRecallStep 主体逻辑零修改，符合开闭原则。
 *
 * 关键流程：
 *   1. 读取 context.searchMode（由 SearchController 从前端请求中写入）
 *   2. selectStrategy() 返回对应策略 Bean
 *   3. 委托 strategy.recall(context) 执行 ES 查询并写入 context 强类型字段
 */
@Component
public class EsRecallStep implements SearchPipelineStep {

    @Autowired
    private KeywordRecallStrategy  keywordStrategy;

    @Autowired
    private SemanticRecallStrategy semanticStrategy;

    @Autowired
    private HybridRecallStrategy   hybridStrategy;

    /**
     * 选择并委托召回策略执行 ES 查询。
     *
     * @param context 当前搜索上下文（含 searchMode / queryVector / filters 等）
     * @throws Exception ES 查询异常，由 SearchServiceV2 Pipeline 循环统一向上抛出
     */
    @Override
    public void execute(SearchContext context) throws Exception {
        RecallStrategy strategy = selectStrategy(context.getSearchMode());
        System.out.println("[EsRecallStep] Delegating to " + strategy.getClass().getSimpleName()
            + " (mode=" + context.getSearchMode() + ")");
        strategy.recall(context);
    }

    /**
     * 根据 searchMode 字符串选择对应的召回策略 Bean。
     *
     * null 和未知值均降级为 hybridStrategy（生产安全兜底），保持向后兼容。
     *
     * @param searchMode "keyword" | "semantic" | "hybrid"（其他值降级为 hybrid）
     * @return 对应的 RecallStrategy 实现
     */
    private RecallStrategy selectStrategy(String searchMode) {
        if (searchMode == null) return hybridStrategy;
        switch (searchMode) {
            case "keyword":  return keywordStrategy;
            case "semantic": return semanticStrategy;
            default:         return hybridStrategy;
        }
    }
}
