package com.boyang.search.pipeline;

/**
 * Pipeline 执行步骤的标准接口，遵循职责链模式。
 * 每一个环节只负责读取自身所需 Context 并把结果写回 Context。
 */
public interface SearchPipelineStep {
    
    /**
     * 执行具体的搜索阶段逻辑。
     * @param context 贯穿整个请求生命周期的上下文
     */
    void execute(SearchContext context) throws Exception;
}
