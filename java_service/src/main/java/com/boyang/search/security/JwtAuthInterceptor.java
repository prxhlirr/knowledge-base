package com.boyang.search.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import org.slf4j.MDC;
import java.util.Map;

/**
 * 全局统一鉴权与 JWT 拦截器。
 * 业务功能：自动区分 Admin 路由（校验 internal token）和 Search 路由（校验 JWT 并注入 UserContext）。
 * 此类消除了各 Controller 硬编码的安全检测逻辑。
 *
 * [Phase 1 新增]
 * 在身份写入 UserContextHolder 之前，调用 AclTokenBuilder 将用户身份展开为扁平化 ACL Token 集合，
 * 实现"写时复杂，读时极简"的权限架构——后续整个检索 Pipeline 只需一次 terms filter 即可完成权限判断。
 */
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private PermissionGuard permissionGuard;

    @Autowired
    private JwtVerifier jwtVerifier;

    @Autowired
    private ObjectMapper objectMapper;

    /** [Phase 1] ACL Token 构建器：在拦截阶段一次性展开用户所有身份资产为扁平化 token 集合 */
    @Autowired
    private AclTokenBuilder aclTokenBuilder;

    @Value("${search.trust-gateway-headers:false}")
    private boolean trustGatewayHeaders;

    /** [上帝模式] 全局权限免疫开关 */
    @Value("${kb.security.god-mode:false}")
    private boolean godMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // [CORS 修复] 放行浏览器的 OPTIONS 预检请求。
        // 预检请求（Preflight）不会携带自定义 Headers（如 X-Internal-Token 或 JWT），
        // 如果这里不直接放行，会在下方被 401 拦截，导致 Spring 无法追加跨域响应头，表现为 CORS 报错。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // [全局上帝模式拦截] 如果开启，赋予调用者超级管理员免校验通行证
        if (godMode) {
            String devAppCode = request.getHeader("X-Search-AppCode");
            if (devAppCode == null || devAppCode.trim().isEmpty()) {
                devAppCode = "ADMIN_MASTER_KEY"; // 兜底
            }
            JwtVerifier.UserIdentity godIdentity = new JwtVerifier.UserIdentity("god-admin", "620102900000", devAppCode, null) {
                @Override
                public boolean isSuperAdmin() { return true; }
            };
            godIdentity.getAclTokens().add("_SUPER_ADMIN");
            UserContextHolder.setIdentity(godIdentity);
            // 放行，不验证任何后面的 headers 或者 token
            return true;
        }

        String uri = request.getRequestURI();

        // 1. 对于管理和内部下发请求，强验 X-Internal-Token
        if (uri.startsWith("/api/v1/admin/") || uri.startsWith("/api/v1/internal/")) {
            if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
                return writeError(response, 401, "未授权访问，需携带有效内部服务凭证（X-Internal-Token）");
            }

            if (uri.startsWith("/api/v1/internal/")) {
                return true; // 服务间回调继续只走内部凭证，不绑定用户身份。
            }

            JwtVerifier.UserIdentity adminIdentity = resolveIdentity(request, response, false);
            if (adminIdentity == null || adminIdentity.getUserId() == null
                    || adminIdentity.getUserId().trim().isEmpty()) {
                if (!response.isCommitted()) {
                    return writeError(response, 401, "管理端操作必须携带有效用户身份");
                }
                return false;
            }
            bindIdentity(adminIdentity);
        }

        // 2. 搜索及其它请求，按要求抽取 JWT 或者开发模式 Headers 放行并转存 ThreadLocal
        JwtVerifier.UserIdentity identity = null;
        if (!uri.startsWith("/api/v1/admin/")) {
            identity = resolveIdentity(request, response, true);
            if (response.isCommitted()) {
                return false;
            }
        }


        // 3. 将验证产生的身份封装注入当前线程，供后续链路透传
        if (identity != null) {
            bindIdentity(identity);
        }

        // [操作日志] 生成跨服务链路 ID，写入 MDC 供 OperationLogAspect 读取。
        // 同时通过 X-Trace-Id Response Header 回传，便于 Java 调用 AI 服务时透传 traceId。
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        request.setAttribute("traceId", traceId); // 存入 request 属性，供 Controller 透传 AI 服务

        return true;
    }

    /**
     * 解析请求用户身份。
     * 业务功能：统一管理生产 JWT 与开发 Header 模式，避免 admin/search 两类路由各自实现身份解析。
     * 关键流程：生产网关模式必须验签 JWT；开发模式仅在允许匿名兜底时构造临时身份。
     */
    private JwtVerifier.UserIdentity resolveIdentity(HttpServletRequest request, HttpServletResponse response,
                                                     boolean allowDevAnonymous)
            throws IOException {
        if (trustGatewayHeaders) {
            String jwtToken = request.getHeader(jwtVerifier.getJwtHeader());
            if (jwtToken == null || jwtToken.trim().isEmpty()) {
                writeError(response, 401, "生产安全模式：必须携带 JWT Token（Header: " + jwtVerifier.getJwtHeader() + "）");
                return null;
            }
            try {
                return jwtVerifier.verify(jwtToken);
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                writeError(response, 401, "Token 已过期，请重新登录");
                return null;
            } catch (io.jsonwebtoken.JwtException e) {
                writeError(response, 401, "Token 无效或签名错误");
                return null;
            }
        }

        if (jwtVerifier.isDevMode()) {
            JwtVerifier.UserIdentity identity = jwtVerifier.fromHeaders(request);
            // 开发模式匿名身份只用于离线检索调试，管理端写操作必须显式携带操作者。
            if (identity == null
                    || identity.getUserId() == null
                    || identity.getUserId().trim().isEmpty()) {
                if (!allowDevAnonymous) {
                    return null;
                }
                String devAppCode = (identity != null) ? identity.getAppCode() : null;
                return new JwtVerifier.UserIdentity("dev-anonymous", null, devAppCode, null);
            }
            return identity;
        }

        return null;
    }

    /**
     * 绑定当前请求身份。
     * 业务功能：在请求线程中写入用户身份和 ACL Token，供入库、检索和权限后置校验共用。
     * 关键流程：先构建扁平化 ACL Token，再写入 ThreadLocal，保证后续链路读取到完整权限上下文。
     */
    private void bindIdentity(JwtVerifier.UserIdentity identity) {
        identity.setAclTokens(aclTokenBuilder.buildAclTokens(identity));
        UserContextHolder.setIdentity(identity);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求完成，必须清理上下文以防止内存泄漏及线程池并发串号！！！
        UserContextHolder.clear();
        // [操作日志] 同步清理 MDC，防止线程池复用时 traceId 串入下一个请求
        MDC.clear();
    }

    private boolean writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("msg", msg);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
