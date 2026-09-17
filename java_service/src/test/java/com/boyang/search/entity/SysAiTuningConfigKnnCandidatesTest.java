package com.boyang.search.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SysAiTuningConfigKnnCandidatesTest {

    @Test
    void resolveKnnNumCandidatesUsesBillionScaleDefaultWhenUnset() {
        SysAiTuningConfig config = new SysAiTuningConfig();

        assertEquals(1000, config.getKnnNumCandidates());
        assertEquals(1000, config.resolveKnnNumCandidates(100));
    }

    @Test
    void resolveKnnNumCandidatesRaisesHistoricalSmallConfiguredValue() {
        SysAiTuningConfig config = new SysAiTuningConfig();
        config.setKnnNumCandidates(500);

        assertEquals(1000, config.resolveKnnNumCandidates(100));
    }

    @Test
    void resolveKnnNumCandidatesKeepsLargerOperationalConfig() {
        SysAiTuningConfig config = new SysAiTuningConfig();
        config.setKnnNumCandidates(1800);

        assertEquals(1800, config.resolveKnnNumCandidates(100));
    }

    @Test
    void resolveKnnNumCandidatesStillSatisfiesEsKGreaterThanConfiguredWindow() {
        SysAiTuningConfig config = new SysAiTuningConfig();
        config.setKnnNumCandidates(1000);

        assertEquals(1600, config.resolveKnnNumCandidates(800));
    }
}
