package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocExpansionStepTimeoutConfigTest {

    @Test
    void docExpansionTimeoutUsesDefaultValueWhenNoConfigProvided() {
        DocExpansionStep step = newStep();

        assertEquals(3000, step.resolveDocExpansionTimeoutMs());
    }

    @Test
    void docExpansionTimeoutUsesConfiguredPositiveValue() {
        DocExpansionStep step = newStep();
        ReflectionTestUtils.setField(step, "docExpansionTimeoutMs", "1800");

        assertEquals(1800, step.resolveDocExpansionTimeoutMs());
    }

    @Test
    void docExpansionTimeoutFallbackWhenConfiguredValueIsInvalid() {
        DocExpansionStep step = newStep();
        ReflectionTestUtils.setField(step, "docExpansionTimeoutMs", "abc");
        assertEquals(3000, step.resolveDocExpansionTimeoutMs());

        ReflectionTestUtils.setField(step, "docExpansionTimeoutMs", "0");
        assertEquals(3000, step.resolveDocExpansionTimeoutMs());

        ReflectionTestUtils.setField(step, "docExpansionTimeoutMs", "-1");
        assertEquals(3000, step.resolveDocExpansionTimeoutMs());
    }

    /**
     * 业务功能：构造只包含配置字段的文档扩展步骤。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 ES。
     */
    private DocExpansionStep newStep() {
        DocExpansionStep step = new DocExpansionStep();
        ReflectionTestUtils.setField(step, "docExpansionTimeoutMs", "3000");
        return step;
    }
}
