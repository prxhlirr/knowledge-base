package com.boyang.search.pipeline.steps;

import com.boyang.search.pipeline.SearchContext;

/**
 * 召回策略接口（Strategy Pattern）
 *
 * 业务功能：定义 EsRecallStep 委托执行的召回行为契约。
 * 每个具体策略对应一种检索模式，负责构建并执行对应的 ES 查询，
 * 将原始响应写入 SearchContext 的强类型字段（bm25Response/knnResponse/sparseResponse）。
 *
 * 设计原则：
 *   - 开闭原则：新增检索模式只需新增策略类，不修改 EsRecallStep 主体
 *   - 单一职责：每个策略只负责自己模式的 ES 查询组装与执行
 *   - 依赖倒置：EsRecallStep 依赖此接口而非具体实现
 */
public interface RecallStrategy {

    /**
     * 执行召回查询，将 ES 原始响应写入 context。
     *
     * @param context 贯穿 Pipeline 的搜索上下文，写入 bm25Response/knnResponse/sparseResponse
     * @throws Exception ES 查询异常（由 EsRecallStep 统一捕获做降级处理）
     */
    void recall(SearchContext context) throws Exception;
}
