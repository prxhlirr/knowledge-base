package com.boyang.search.service;

import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private PolicyVersionService policyVersionService;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void cacheKeyContainsPolicyVersion() {
        SearchCacheService service = new SearchCacheService(redisTemplate, new ObjectMapper(), policyVersionService);
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("u1", "D001", "app", null);
        identity.setAclTokens(new HashSet<>(java.util.Arrays.asList("_PUBLIC", "user::u1")));
        UserContextHolder.setIdentity(identity);

        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "u1");
        filters.put("user_dept_code", "D001");

        when(policyVersionService.currentGlobalVersion()).thenReturn("1", "2");

        String keyV1 = service.buildKey("app", "query", 10, filters, "hybrid");
        String keyV2 = service.buildKey("app", "query", 10, filters, "hybrid");

        assertNotEquals(keyV1, keyV2);
        assertTrue(keyV1.contains(":v1:"));
        assertTrue(keyV2.contains(":v2:"));
    }

    @Test
    void cacheKeyContainsJwtRolesBecauseRolesControlReadableIndexes() {
        SearchCacheService service = new SearchCacheService(redisTemplate, new ObjectMapper(), policyVersionService);
        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", "u1");
        filters.put("user_dept_code", "D001");
        when(policyVersionService.currentGlobalVersion()).thenReturn("1", "1");

        UserContextHolder.setIdentity(identityWithRole("official_reader"));
        String officialKey = service.buildKey("app", "query", 10, filters, "keyword");

        UserContextHolder.setIdentity(identityWithRole("public_reader"));
        String publicKey = service.buildKey("app", "query", 10, filters, "keyword");

        assertNotEquals(officialKey, publicKey);
    }

    @Test
    void putAsyncDoesNotTrustExplicitCacheableTrueForDeptDocument() {
        SearchCacheService service = new SearchCacheService(redisTemplate, new ObjectMapper(), policyVersionService);
        Map<String, Object> doc = new HashMap<>();
        doc.put("cacheable", true);
        doc.put("visibility", "DEPT");

        service.putAsync("search:cache:test", Collections.singletonList(doc));

        verify(redisTemplate, never()).opsForValue();
    }

    private JwtVerifier.UserIdentity identityWithRole(String role) {
        DefaultClaims claims = new DefaultClaims();
        claims.put("roles", java.util.Collections.singletonList(role));
        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("u1", "D001", "app", claims);
        identity.setAclTokens(new HashSet<>(java.util.Arrays.asList("_PUBLIC", "user::u1")));
        return identity;
    }
}
