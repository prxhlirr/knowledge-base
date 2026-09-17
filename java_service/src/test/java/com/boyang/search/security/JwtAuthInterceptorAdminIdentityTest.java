package com.boyang.search.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthInterceptorAdminIdentityTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void adminRouteRequiresInternalTokenAndBindsOperatorIdentity() throws Exception {
        PermissionGuard permissionGuard = mock(PermissionGuard.class);
        JwtVerifier jwtVerifier = mock(JwtVerifier.class);
        AclTokenBuilder aclTokenBuilder = mock(AclTokenBuilder.class);
        JwtAuthInterceptor interceptor = newInterceptor(permissionGuard, jwtVerifier, aclTokenBuilder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/doc/batch_import");
        request.addHeader("X-Internal-Token", "valid-internal-token");
        request.addHeader("X-User-Id", "admin-user");
        request.addHeader("X-User-Dept", "620102");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtVerifier.UserIdentity identity = new JwtVerifier.UserIdentity("admin-user", "620102", "app", null);
        when(permissionGuard.isValidInternalToken("valid-internal-token")).thenReturn(true);
        when(jwtVerifier.isDevMode()).thenReturn(true);
        when(jwtVerifier.fromHeaders(request)).thenReturn(identity);
        when(aclTokenBuilder.buildAclTokens(identity)).thenReturn(new HashSet<>());

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("admin-user", UserContextHolder.getIdentity().getUserId());
    }

    @Test
    void adminRouteRejectsWhenOperatorIdentityIsMissing() throws Exception {
        PermissionGuard permissionGuard = mock(PermissionGuard.class);
        JwtVerifier jwtVerifier = mock(JwtVerifier.class);
        AclTokenBuilder aclTokenBuilder = mock(AclTokenBuilder.class);
        JwtAuthInterceptor interceptor = newInterceptor(permissionGuard, jwtVerifier, aclTokenBuilder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/doc/batch_import");
        request.addHeader("X-Internal-Token", "valid-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(permissionGuard.isValidInternalToken("valid-internal-token")).thenReturn(true);
        when(jwtVerifier.isDevMode()).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertNull(UserContextHolder.getIdentity());
        assertTrue(response.getContentAsString().contains("管理端操作必须携带有效用户身份"));
    }

    @Test
    void adminRouteRejectsDevAnonymousFallback() throws Exception {
        PermissionGuard permissionGuard = mock(PermissionGuard.class);
        JwtVerifier jwtVerifier = mock(JwtVerifier.class);
        AclTokenBuilder aclTokenBuilder = mock(AclTokenBuilder.class);
        JwtAuthInterceptor interceptor = newInterceptor(permissionGuard, jwtVerifier, aclTokenBuilder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/doc/upload");
        request.addHeader("X-Internal-Token", "valid-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JwtVerifier.UserIdentity emptyIdentity = new JwtVerifier.UserIdentity(null, null, "app", null);
        when(permissionGuard.isValidInternalToken("valid-internal-token")).thenReturn(true);
        when(jwtVerifier.isDevMode()).thenReturn(true);
        when(jwtVerifier.fromHeaders(request)).thenReturn(emptyIdentity);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertNull(UserContextHolder.getIdentity());
        assertTrue(response.getContentAsString().contains("管理端操作必须携带有效用户身份"));
    }

    @Test
    void internalRouteKeepsServiceCredentialOnlySemantics() throws Exception {
        PermissionGuard permissionGuard = mock(PermissionGuard.class);
        JwtVerifier jwtVerifier = mock(JwtVerifier.class);
        AclTokenBuilder aclTokenBuilder = mock(AclTokenBuilder.class);
        JwtAuthInterceptor interceptor = newInterceptor(permissionGuard, jwtVerifier, aclTokenBuilder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/task/callback");
        request.addHeader("X-Internal-Token", "valid-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(permissionGuard.isValidInternalToken("valid-internal-token")).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContextHolder.getIdentity());
    }

    /**
     * 构造只包含鉴权依赖的拦截器。
     * 业务功能：隔离 Web 容器，仅验证 admin/internal 路由的身份边界。
     * 关键流程：通过反射注入 Spring 运行时字段，避免启动完整应用上下文。
     */
    private JwtAuthInterceptor newInterceptor(PermissionGuard permissionGuard,
                                              JwtVerifier jwtVerifier,
                                              AclTokenBuilder aclTokenBuilder) {
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "permissionGuard", permissionGuard);
        ReflectionTestUtils.setField(interceptor, "jwtVerifier", jwtVerifier);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(interceptor, "aclTokenBuilder", aclTokenBuilder);
        ReflectionTestUtils.setField(interceptor, "trustGatewayHeaders", false);
        ReflectionTestUtils.setField(interceptor, "godMode", false);
        return interceptor;
    }
}
