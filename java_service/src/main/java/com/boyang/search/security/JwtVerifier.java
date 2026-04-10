package com.boyang.search.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关 JWT 验签组件（JwtVerifier）。
 * 业务功能：验证请求中携带的 JWT Token 合法性，并从 Claims 中提取
 *           用户身份（userId、deptCode），作为权限判断的可信来源。
 * 关键流程：
 *   1. 网关验证外部系统登录态（SSO/OAuth2），验证通过后签发 JWT 并注入 X-Gateway-Token
 *   2. 知识库侧通过 JwtVerifier 验签，提取 userId、deptCode
 *   3. 不信任前端直接传的 X-User-Id Header，必须经过 JWT 验签才能信任身份
 * 签名算法：
 *   - HS256（HMAC-SHA256，对称密钥）：适合单体或内网网关场景
 *   - RS256（RSA，非对称）：适合多系统联合认证，通过 jwt.public-key-base64 配置公钥
 * 配置方式：
 *   支持 HS256（secret）和 RS256（publicKey）两种，优先使用 RS256（若公钥已配置）。
 */
@Slf4j
@Component
public class JwtVerifier {

    /**
     * HS256 签名密钥（≥256 bit，即 ≥32 字符）。
     * 生产环境通过 JWT_SECRET 环境变量注入，禁止使用默认值。
     * 前缀 "Base64:" 表示值为 Base64 编码的原始密钥字节。
     */
    @Value("${jwt.secret:default-dev-secret-must-change-in-prod-32chars}")
    private String secretKey;

    /**
     * RS256 公钥（Base64 编码的 X.509 DER 格式）。
     * 若配置此项则优先使用 RS256 验签，忽略 jwt.secret。
     * 生产建议使用 RS256，密钥对由统一认证中心管理。
     */
    @Value("${jwt.public-key-base64:}")
    private String publicKeyBase64;

    /**
     * JWT 对应 Header 名称（网关注入）。
     * 默认为 Authorization（Bearer 格式），可改为 X-Gateway-Token。
     */
    @Value("${jwt.header:Authorization}")
    private String jwtHeader;

    /**
     * JWT 中存储 userId 的 Claim Key。与网关签发侧约定一致。
     */
    @Value("${jwt.claim.user-id:userId}")
    private String claimUserId;

    /**
     * JWT 中存储 deptCode 的 Claim Key。与网关签发侧约定一致。
     */
    @Value("${jwt.claim.dept-code:deptCode}")
    private String claimDeptCode;

    /**
     * JWT 中存储 appCode（租户标识）的 Claim Key。
     */
    @Value("${jwt.claim.app-code:appCode}")
    private String claimAppCode;

    /** 开发模式：关闭 JWT 验签，从 X-User-Id 等 Header 直接读取身份（慎用）*/
    @Value("${jwt.dev-mode:true}")
    private boolean devMode;

    private Key signingKey;
    private boolean useRsa = false;

    /**
     * 初始化签名密钥。
     * 优先 RS256（公钥 Base64 已配置）→ 降级 HS256（对称密钥）。
     */
    @PostConstruct
    public void init() {
        if (publicKeyBase64 != null && !publicKeyBase64.trim().isEmpty()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
                java.security.spec.X509EncodedKeySpec spec =
                    new java.security.spec.X509EncodedKeySpec(keyBytes);
                signingKey = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
                useRsa = true;
                log.info("[JwtVerifier] 初始化成功，签名算法: RS256（公钥模式）");
            } catch (Exception e) {
                log.error("[JwtVerifier] RS256 公钥解析失败，降级 HS256 err={}", e.getMessage());
                initHmacKey();
            }
        } else {
            initHmacKey();
        }
        if (devMode) {
            log.warn("[JwtVerifier] ⚠️ 开发模式已开启（jwt.dev-mode=true），JWT 验签已跳过！生产环境必须关闭！");
        }
    }

    private void initHmacKey() {
        // 密钥如带 "Base64:" 前缀则先解码，否则直接用字符串字节
        byte[] keyBytes;
        if (secretKey.startsWith("Base64:")) {
            keyBytes = Base64.getDecoder().decode(secretKey.substring(7));
        } else {
            keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        }
        // JJWT 要求 HS256 密钥 ≥ 256 bit（32 字节），不足则补零
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
        useRsa = false;
        log.info("[JwtVerifier] 初始化成功，签名算法: HS256（对称密钥）");
    }

    /**
     * 验证 JWT 并提取用户身份信息。
     *
     * @param rawToken Bearer Token（含或不含"Bearer "前缀）
     * @return 解析成功返回用户身份；验签失败或 devMode 时返回 null
     * @throws JwtException token 格式错误或签名无效时抛出，供 Controller 捕获返回 401
     */
    public UserIdentity verify(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return null;
        }
        // 去掉 "Bearer " 前缀
        String token = rawToken.startsWith("Bearer ") ? rawToken.substring(7) : rawToken;

        try {
            JwtParser parser = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build();
            Claims claims = parser.parseClaimsJws(token).getBody();

            // 校验过期时间
            Date expiration = claims.getExpiration();
            if (expiration != null && expiration.before(new Date())) {
                throw new ExpiredJwtException(null, claims, "Token 已过期");
            }

            String userId   = claims.get(claimUserId, String.class);
            String deptCode = claims.get(claimDeptCode, String.class);
            String appCode  = claims.get(claimAppCode, String.class);

            log.debug("[JwtVerifier] 验签通过 userId={} deptCode={} appCode={}",
                userId, deptCode, appCode);
            return new UserIdentity(userId, deptCode, appCode, claims);

        } catch (ExpiredJwtException e) {
            log.warn("[JwtVerifier] Token 已过期 err={}", e.getMessage());
            throw e;
        } catch (SecurityException | MalformedJwtException e) {
            log.warn("[JwtVerifier] Token 签名无效 err={}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("[JwtVerifier] Token 解析失败 err={}", e.getMessage());
            throw new JwtException("Token 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 开发模式下从 Header 直接读取用户身份（跳过验签）。
     * 生产环境 jwt.dev-mode=false 时此方法不应被调用。
     */
    public UserIdentity fromHeaders(javax.servlet.http.HttpServletRequest request) {
        String userId   = request.getHeader("X-User-Id");
        String deptCode = request.getHeader("X-User-Dept");
        String appCode  = request.getHeader("X-Search-AppCode");
        return new UserIdentity(userId, deptCode, appCode, null);
    }

    /** 是否处于开发模式（跳过验签） */
    public boolean isDevMode() { return devMode; }

    /** JWT Header 名称（如 "Authorization"） */
    public String getJwtHeader() { return jwtHeader; }

    // ─── 内部类：用户身份 ─────────────────────────────────────────────

    /**
     * 从 JWT 解析出的用户身份（不可变，线程安全）。
     * aclTokens 字段由 AclTokenBuilder 在 JwtAuthInterceptor 阶段填充，
     * 它将用户所有身份资产展开为一维的标签集合，供 ES pre-filter 直接用于求交集操作。
     */
    public static class UserIdentity {
        private final String userId;
        private final String deptCode;
        private final String appCode;
        private final Claims rawClaims;

        /** 用户角色列表（从 JWT roles claim 提取） */
        private final java.util.Set<String> roles;

        /** 是否为超级管理员（roles 中包含 SYS_ADMIN 时为 true） */
        private final boolean isSuperAdmin;

        /**
         * 权限标签集合（ACL Tokens）。
         * 由 AclTokenBuilder 在运行时填充，格式如：
         * ["_PUBLIC", "_INTERNAL", "DEPT:620102", "DEPT:6201", "USER:U9527", "DOC:xxx.pdf"]
         * 此字段初始为空集合，不为 null，避免 NPE。
         */
        private java.util.Set<String> aclTokens = new java.util.HashSet<>();

        public UserIdentity(String userId, String deptCode, String appCode, Claims rawClaims) {
            this.userId       = userId;
            this.deptCode     = deptCode;
            this.appCode      = appCode;
            this.rawClaims    = rawClaims;
            this.roles        = extractRoles(rawClaims);
            this.isSuperAdmin = this.roles.contains("SYS_ADMIN");
        }

        /**
         * 从 JWT Claims 中提取角色列表。
         * 支持多种 Claims 格式：List<String>、逗号分隔字符串、单个字符串。
         */
        @SuppressWarnings("unchecked")
        private static java.util.Set<String> extractRoles(Claims claims) {
            if (claims == null) return java.util.Collections.emptySet();
            Object rolesObj = claims.get("roles");
            if (rolesObj == null) rolesObj = claims.get("authorities");
            if (rolesObj == null) return java.util.Collections.emptySet();
            if (rolesObj instanceof java.util.List) {
                java.util.Set<String> result = new java.util.HashSet<>();
                for (Object item : (java.util.List<?>) rolesObj) {
                    if (item != null) result.add(item.toString());
                }
                return result;
            }
            // 处理逗号分隔字符串格式
            return new java.util.HashSet<>(Arrays.asList(rolesObj.toString().split(",")));
        }

        public String getUserId()   { return userId;      }
        public String getDeptCode() { return deptCode;    }
        public String getAppCode()  { return appCode;     }
        public Claims getRawClaims(){ return rawClaims;   }
        public java.util.Set<String> getRoles()     { return roles;       }
        public boolean isSuperAdmin()               { return isSuperAdmin; }
        public java.util.Set<String> getAclTokens() { return aclTokens;   }

        /**
         * 由 AclTokenBuilder 调用，在 JwtAuthInterceptor 阶段填充权限标签集合。
         * 此方法只在请求处理开始时调用一次，不允许在检索队列中重复调用。
         */
        public void setAclTokens(java.util.Set<String> tokens) {
            this.aclTokens = tokens != null ? tokens : new java.util.HashSet<>();
        }

        /** 转为 filters Map，供 SearchService 使用 */
        public Map<String, Object> toFilters() {
            Map<String, Object> m = new HashMap<>();
            if (userId   != null && !userId.isEmpty())   m.put("user_id",       userId);
            if (deptCode != null && !deptCode.isEmpty()) m.put("user_dept_code", deptCode);
            return m;
        }

        @Override
        public String toString() {
            return "UserIdentity{userId=" + userId + ", deptCode=" + deptCode
                + ", appCode=" + appCode + ", isSuperAdmin=" + isSuperAdmin
                + ", aclTokenCount=" + aclTokens.size() + "}";
        }
    }
}
