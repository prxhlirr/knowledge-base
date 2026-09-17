package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordRecallStrategyCandidateSizeConfigTest {

    @Test
    void keywordCandidateSizeKeepsHistoricalDefaultWindow() {
        KeywordRecallStrategy strategy = newStrategy();

        assertEquals(120, strategy.resolveKeywordCandidateSize(5));
        assertEquals(240, strategy.resolveKeywordCandidateSize(20));
        assertEquals(500, strategy.resolveKeywordCandidateSize(100));
    }

    @Test
    void keywordCandidateSizeUsesConfiguredWindow() {
        KeywordRecallStrategy strategy = newStrategy();
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMax", "300");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMin", "80");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMultiplier", "8");

        assertEquals(80, strategy.resolveKeywordCandidateSize(5));
        assertEquals(160, strategy.resolveKeywordCandidateSize(20));
        assertEquals(300, strategy.resolveKeywordCandidateSize(100));
    }

    @Test
    void keywordCandidateSizeFallbackWhenConfiguredValuesAreInvalid() {
        KeywordRecallStrategy strategy = newStrategy();
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMax", "abc");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMin", "0");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMultiplier", "-1");

        assertEquals(120, strategy.resolveKeywordCandidateSize(5));
        assertEquals(500, strategy.resolveKeywordCandidateSize(100));
    }

    @Test
    void keywordCandidateSizeTreatsNonPositiveTopKAsOne() {
        KeywordRecallStrategy strategy = newStrategy();

        assertEquals(120, strategy.resolveKeywordCandidateSize(0));
        assertEquals(120, strategy.resolveKeywordCandidateSize(-10));
    }

    /**
     * 业务功能：构造只包含候选窗口配置的 keyword 召回策略。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 ES。
     */
    private KeywordRecallStrategy newStrategy() {
        KeywordRecallStrategy strategy = new KeywordRecallStrategy();
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMax", "500");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMin", "120");
        ReflectionTestUtils.setField(strategy, "keywordDocCandidateMultiplier", "12");
        return strategy;
    }
}
