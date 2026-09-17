package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryNormalizeStepTimeoutConfigTest {

    @Test
    void queryNormalizeTimeoutUsesDefaultValueWhenNoConfigProvided() {
        QueryNormalizeStep step = newStep();

        assertEquals(3000, step.resolveQueryNormalizeTimeoutMs());
    }

    @Test
    void queryNormalizeTimeoutUsesConfiguredPositiveValue() {
        QueryNormalizeStep step = newStep();
        ReflectionTestUtils.setField(step, "queryNormalizeTimeoutMs", "1200");

        assertEquals(1200, step.resolveQueryNormalizeTimeoutMs());
    }

    @Test
    void queryNormalizeTimeoutFallbackWhenConfiguredValueIsInvalid() {
        QueryNormalizeStep step = newStep();
        ReflectionTestUtils.setField(step, "queryNormalizeTimeoutMs", "abc");
        assertEquals(3000, step.resolveQueryNormalizeTimeoutMs());

        ReflectionTestUtils.setField(step, "queryNormalizeTimeoutMs", "0");
        assertEquals(3000, step.resolveQueryNormalizeTimeoutMs());

        ReflectionTestUtils.setField(step, "queryNormalizeTimeoutMs", "-1");
        assertEquals(3000, step.resolveQueryNormalizeTimeoutMs());
    }

    /**
     * 业务功能：构造只包含配置字段的查询归一化步骤。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 Python NLP 服务。
     */
    private QueryNormalizeStep newStep() {
        QueryNormalizeStep step = new QueryNormalizeStep();
        ReflectionTestUtils.setField(step, "queryNormalizeTimeoutMs", "3000");
        return step;
    }
}
