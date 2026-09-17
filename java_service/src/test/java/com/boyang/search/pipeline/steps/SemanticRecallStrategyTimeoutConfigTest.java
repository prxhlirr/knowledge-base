package com.boyang.search.pipeline.steps;

import com.boyang.search.entity.SysAiTuningConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticRecallStrategyTimeoutConfigTest {

    @Test
    void semanticTimeoutsUseDefaultValuesWhenNoConfigProvided() {
        SemanticRecallStrategy strategy = newStrategy();

        assertEquals(2000, strategy.resolveSparseTimeoutMs());
        assertEquals(2000, strategy.resolveBm25TimeoutMs());
        assertEquals(2000, strategy.resolveEsTimeoutMs(null));
    }

    @Test
    void semanticTimeoutsUseConfiguredPositiveValues() {
        SemanticRecallStrategy strategy = newStrategy();
        ReflectionTestUtils.setField(strategy, "semanticSparseTimeoutMs", "2600");
        ReflectionTestUtils.setField(strategy, "semanticBm25TimeoutMs", "2400");
        ReflectionTestUtils.setField(strategy, "semanticEsTimeoutMs", "3600");

        assertEquals(2600, strategy.resolveSparseTimeoutMs());
        assertEquals(2400, strategy.resolveBm25TimeoutMs());
        assertEquals(3600, strategy.resolveEsTimeoutMs(null));
    }

    @Test
    void semanticTimeoutsFallbackWhenConfiguredValuesAreInvalid() {
        SemanticRecallStrategy strategy = newStrategy();
        ReflectionTestUtils.setField(strategy, "semanticSparseTimeoutMs", "0");
        ReflectionTestUtils.setField(strategy, "semanticBm25TimeoutMs", "-1");
        ReflectionTestUtils.setField(strategy, "semanticEsTimeoutMs", "abc");

        assertEquals(2000, strategy.resolveSparseTimeoutMs());
        assertEquals(2000, strategy.resolveBm25TimeoutMs());
        assertEquals(2000, strategy.resolveEsTimeoutMs(null));
    }

    @Test
    void semanticEsTimeoutPrefersDatabaseTuningConfig() {
        SemanticRecallStrategy strategy = newStrategy();
        ReflectionTestUtils.setField(strategy, "semanticEsTimeoutMs", "3600");
        SysAiTuningConfig config = new SysAiTuningConfig();
        config.setEsQueryTimeout(4800);

        assertEquals(4800, strategy.resolveEsTimeoutMs(config));
    }

    /**
     * 业务功能：构造只包含配置字段的语义召回策略。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 ES 或 AI 服务。
     */
    private SemanticRecallStrategy newStrategy() {
        SemanticRecallStrategy strategy = new SemanticRecallStrategy();
        ReflectionTestUtils.setField(strategy, "semanticSparseTimeoutMs", "2000");
        ReflectionTestUtils.setField(strategy, "semanticBm25TimeoutMs", "2000");
        ReflectionTestUtils.setField(strategy, "semanticEsTimeoutMs", "2000");
        return strategy;
    }
}
