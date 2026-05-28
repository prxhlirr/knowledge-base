package com.boyang.search.service;

import com.boyang.search.entity.KbSensitivePolicy;
import com.boyang.search.mapper.KbSensitivePolicyMapper;
import com.boyang.search.security.JwtVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensitivePolicyServiceTest {

    @Mock
    private KbSensitivePolicyMapper mapper;
    @Mock
    private PolicyVersionService policyVersionService;
    @Mock
    private SensitivePolicyAuditService auditService;

    private SensitivePolicyService service;

    @BeforeEach
    void setUp() {
        service = new SensitivePolicyService(mapper, policyVersionService, auditService);
        ReflectionTestUtils.setField(service, "maxRegexPatternLength", 128);
        ReflectionTestUtils.setField(service, "maxRegexInputLength", 1000);
        ReflectionTestUtils.setField(service, "maxMatcherCacheSize", 8);
        when(policyVersionService.currentGlobalVersion()).thenReturn("1");
        lenient().doNothing().when(auditService).recordHit(any(), anyString(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void literalPoliciesMaskWithSinglePassMatcher() {
        when(mapper.findActiveForStage("SEARCH")).thenReturn(Arrays.asList(
                policy(1L, "WORD", "secret", "MASK", "***", "SEARCH", 100),
                policy(2L, "WORD", "Alice", "MASK", "[PERSON]", "SEARCH", 90)
        ));

        SensitivePolicyService.FilterResult result =
                service.filterText("Alice knows the secret.", "SEARCH", identity());

        assertFalse(result.isBlocked());
        assertTrue(result.isChanged());
        assertEquals("[PERSON] knows the ***.", result.getText());
        assertEquals(Arrays.asList(1L, 2L), result.getMatchedPolicyIds());
        assertEquals(2, result.getHitCount());
    }

    @Test
    void blockPolicyStopsOutput() {
        when(mapper.findActiveForStage("QA")).thenReturn(Collections.singletonList(
                policy(3L, "WORD", "blocked-name", "BLOCK_QA", null, "QA", 100)
        ));

        SensitivePolicyService.FilterResult result =
                service.filterText("answer contains blocked-name", "QA", identity());

        assertTrue(result.isBlocked());
        assertEquals("BLOCK_QA", result.getAction());
        assertEquals(Collections.singletonList(3L), result.getMatchedPolicyIds());
    }

    @Test
    void unsafeRegexIsSkipped() {
        when(mapper.findActiveForStage("SEARCH")).thenReturn(Collections.singletonList(
                policy(4L, "REGEX", "(a+)+", "BLOCK_DOC", null, "SEARCH", 100)
        ));

        SensitivePolicyService.FilterResult result =
                service.filterText("aaaaaaaaaaaaaaaaaaaa", "SEARCH", identity());

        assertFalse(result.isBlocked());
        assertFalse(result.isChanged());
        assertEquals("aaaaaaaaaaaaaaaaaaaa", result.getText());
    }

    private KbSensitivePolicy policy(Long id,
                                     String type,
                                     String value,
                                     String action,
                                     String replacement,
                                     String appliesTo,
                                     int priority) {
        KbSensitivePolicy policy = new KbSensitivePolicy();
        policy.setId(id);
        policy.setPatternType(type);
        policy.setPatternValue(value);
        policy.setAction(action);
        policy.setReplacement(replacement == null ? "[REDACTED]" : replacement);
        policy.setAppliesTo(appliesTo);
        policy.setSubjectType("ALL");
        policy.setSubjectValue("*");
        policy.setPriority(priority);
        policy.setIsActive(1);
        return policy;
    }

    private JwtVerifier.UserIdentity identity() {
        return new JwtVerifier.UserIdentity("u1", "D001", "app", null);
    }
}
