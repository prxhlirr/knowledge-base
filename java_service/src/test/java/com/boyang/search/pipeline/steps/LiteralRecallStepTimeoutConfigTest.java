package com.boyang.search.pipeline.steps;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiteralRecallStepTimeoutConfigTest {

    @Test
    void literalTimeoutUsesDefaultValueWhenNoConfigProvided() {
        LiteralRecallStep step = newStep();

        assertEquals(1000, step.resolveLiteralTimeoutMs());
    }

    @Test
    void literalTimeoutUsesConfiguredPositiveValue() {
        LiteralRecallStep step = newStep();
        ReflectionTestUtils.setField(step, "literalTimeoutMs", "1500");

        assertEquals(1500, step.resolveLiteralTimeoutMs());
    }

    @Test
    void literalTimeoutFallbackWhenConfiguredValueIsInvalid() {
        LiteralRecallStep step = newStep();
        ReflectionTestUtils.setField(step, "literalTimeoutMs", "abc");
        assertEquals(1000, step.resolveLiteralTimeoutMs());

        ReflectionTestUtils.setField(step, "literalTimeoutMs", "0");
        assertEquals(1000, step.resolveLiteralTimeoutMs());

        ReflectionTestUtils.setField(step, "literalTimeoutMs", "-1");
        assertEquals(1000, step.resolveLiteralTimeoutMs());
    }

    @Test
    void exactMetadataRequestUsesConfiguredLiteralTimeout() throws Exception {
        LiteralRecallStep step = newStep();
        ReflectionTestUtils.setField(step, "literalTimeoutMs", "1800");
        ReflectionTestUtils.setField(step, "utils", new EsRecallUtils());

        Method method = LiteralRecallStep.class.getDeclaredMethod(
                "buildExactMetadataRequest",
                String.class,
                int.class,
                List.class,
                boolean.class,
                String.class,
                List.class,
                String.class);
        method.setAccessible(true);

        SearchRequest request = (SearchRequest) method.invoke(
                step,
                "kb_document_policy",
                5,
                Collections.singletonList("国办发〔2024〕1号"),
                false,
                "u-1",
                Collections.singletonList(FieldValue.of("global")),
                null);

        assertEquals("1800ms", request.timeout());
    }

    /**
     * 业务功能：构造只包含配置字段的字面量召回步骤。
     * 关键流程：通过反射模拟 Spring 配置注入，避免单元测试依赖 ES。
     */
    private LiteralRecallStep newStep() {
        LiteralRecallStep step = new LiteralRecallStep();
        ReflectionTestUtils.setField(step, "literalTimeoutMs", "1000");
        return step;
    }
}
