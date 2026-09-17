package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class RerankStepPermissionProjectionTest {

    @Test
    void putFirstPresentKeepsSourceValueBeforeMetadataFallback() throws Exception {
        RerankStep step = new RerankStep();
        Map<String, Object> target = new LinkedHashMap<>();

        invokePutFirstPresent(step, target, "source_index", "kb_document_official", "kb_document_public");

        assertEquals("kb_document_official", target.get("source_index"));
    }

    @Test
    void putFirstPresentUsesFallbackAndPreservesListValue() throws Exception {
        RerankStep step = new RerankStep();
        Map<String, Object> target = new LinkedHashMap<>();
        List<String> visibleUnits = new ArrayList<>(Arrays.asList("A", "A01"));

        invokePutFirstPresent(step, target, "visible_unit_codes", null, visibleUnits);

        assertSame(visibleUnits, target.get("visible_unit_codes"));
    }

    @Test
    void putFirstPresentSkipsBlankString() throws Exception {
        RerankStep step = new RerankStep();
        Map<String, Object> target = new LinkedHashMap<>();

        invokePutFirstPresent(step, target, "owner_unit_code", "   ", "A");

        assertFalse(target.containsKey("owner_unit_code"));
    }

    /**
     * 通过反射验证权限投影的最小契约。
     *
     * <p>这里不启动 Spring/ES，是因为本测试只关心出站字段保留规则；
     * 端到端过滤能力已由 Python HTTP/ES 测试覆盖。</p>
     */
    private void invokePutFirstPresent(
            RerankStep step,
            Map<String, Object> target,
            String key,
            Object primary,
            Object fallback) throws Exception {
        Method method = RerankStep.class.getDeclaredMethod(
                "putFirstPresent",
                Map.class,
                String.class,
                Object.class,
                Object.class);
        method.setAccessible(true);
        method.invoke(step, target, key, primary, fallback);
    }
}
