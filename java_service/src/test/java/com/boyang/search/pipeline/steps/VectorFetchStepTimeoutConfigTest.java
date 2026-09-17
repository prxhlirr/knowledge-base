package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VectorFetchStepTimeoutConfigTest {

    @Test
    void vectorTimeoutsUseDefaultValuesWhenNoConfigProvided() {
        VectorFetchStep step = newStep();

        assertEquals(4000, step.resolveDocTypeHydeTimeoutMs());
        assertEquals(3000, step.resolveDocTypeDualTimeoutMs());
        assertEquals(1000, step.resolveHydeTimeoutMs());
        assertEquals(3000, step.resolveDualTimeoutMs());
    }

    @Test
    void vectorTimeoutsUseConfiguredPositiveValues() {
        VectorFetchStep step = newStep();
        ReflectionTestUtils.setField(step, "docTypeHydeTimeoutMs", "4500");
        ReflectionTestUtils.setField(step, "docTypeDualTimeoutMs", "3200");
        ReflectionTestUtils.setField(step, "hydeTimeoutMs", "1500");
        ReflectionTestUtils.setField(step, "dualTimeoutMs", "2800");

        assertEquals(4500, step.resolveDocTypeHydeTimeoutMs());
        assertEquals(3200, step.resolveDocTypeDualTimeoutMs());
        assertEquals(1500, step.resolveHydeTimeoutMs());
        assertEquals(2800, step.resolveDualTimeoutMs());
    }

    @Test
    void vectorTimeoutsFallbackWhenConfiguredValuesAreInvalid() {
        VectorFetchStep step = newStep();
        ReflectionTestUtils.setField(step, "docTypeHydeTimeoutMs", "0");
        ReflectionTestUtils.setField(step, "docTypeDualTimeoutMs", "-1");
        ReflectionTestUtils.setField(step, "hydeTimeoutMs", "abc");
        ReflectionTestUtils.setField(step, "dualTimeoutMs", " ");

        assertEquals(4000, step.resolveDocTypeHydeTimeoutMs());
        assertEquals(3000, step.resolveDocTypeDualTimeoutMs());
        assertEquals(1000, step.resolveHydeTimeoutMs());
        assertEquals(3000, step.resolveDualTimeoutMs());
    }

    /**
     * 业务功能：构造只包含配置字段的向量化步骤对象。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖外部 AI 服务。
     */
    private VectorFetchStep newStep() {
        VectorFetchStep step = new VectorFetchStep();
        ReflectionTestUtils.setField(step, "docTypeHydeTimeoutMs", "4000");
        ReflectionTestUtils.setField(step, "docTypeDualTimeoutMs", "3000");
        ReflectionTestUtils.setField(step, "hydeTimeoutMs", "1000");
        ReflectionTestUtils.setField(step, "dualTimeoutMs", "3000");
        return step;
    }
}
