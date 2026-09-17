package com.boyang.search.pipeline.steps;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankStepColbertConcurrencyTest {

    @Test
    void normalizeColbertMaxConcurrencyKeepsPositiveValue() {
        RerankStep step = new RerankStep();

        assertEquals(8, step.normalizeColbertMaxConcurrency(8));
    }

    @Test
    void normalizeColbertMaxConcurrencyFallsBackForZeroOrNegativeValue() {
        RerankStep step = new RerankStep();

        assertEquals(4, step.normalizeColbertMaxConcurrency(0));
        assertEquals(4, step.normalizeColbertMaxConcurrency(-3));
    }

    @Test
    void initColbertSemaphoreAppliesConfiguredConcurrency() {
        RerankStep step = new RerankStep();
        ReflectionTestUtils.setField(step, "configuredColbertMaxConcurrency", 6);

        step.initColbertSemaphore();

        assertEquals(6, step.getColbertMaxConcurrency());
    }

    @Test
    void initColbertSemaphoreFallsBackWhenConfiguredConcurrencyInvalid() {
        RerankStep step = new RerankStep();
        ReflectionTestUtils.setField(step, "configuredColbertMaxConcurrency", 0);

        step.initColbertSemaphore();

        assertEquals(4, step.getColbertMaxConcurrency());
    }

    @Test
    void normalizeColbertTimeoutMsKeepsPositiveValue() {
        RerankStep step = new RerankStep();

        assertEquals(3000L, step.normalizeColbertTimeoutMs(3000L, 6000L));
    }

    @Test
    void normalizeColbertTimeoutMsFallsBackForZeroOrNegativeValue() {
        RerankStep step = new RerankStep();

        assertEquals(6000L, step.normalizeColbertTimeoutMs(0L, 6000L));
        assertEquals(8000L, step.normalizeColbertTimeoutMs(-1L, 8000L));
    }

    @Test
    void resolveColbertTimeoutMsUsesDefaultValues() {
        RerankStep step = new RerankStep();

        assertEquals(6000L, step.resolveColbertTimeoutMs(false));
        assertEquals(8000L, step.resolveColbertTimeoutMs(true));
    }

    @Test
    void resolveColbertTimeoutMsUsesConfiguredValues() {
        RerankStep step = new RerankStep();
        ReflectionTestUtils.setField(step, "configuredColbertTimeoutMs", 4500L);
        ReflectionTestUtils.setField(step, "configuredColbertSemanticTimeoutMs", 9500L);

        assertEquals(4500L, step.resolveColbertTimeoutMs(false));
        assertEquals(9500L, step.resolveColbertTimeoutMs(true));
    }

    @Test
    void resolveColbertTimeoutMsFallsBackWhenConfiguredValuesInvalid() {
        RerankStep step = new RerankStep();
        ReflectionTestUtils.setField(step, "configuredColbertTimeoutMs", 0L);
        ReflectionTestUtils.setField(step, "configuredColbertSemanticTimeoutMs", -5L);

        assertEquals(6000L, step.resolveColbertTimeoutMs(false));
        assertEquals(8000L, step.resolveColbertTimeoutMs(true));
    }
}
