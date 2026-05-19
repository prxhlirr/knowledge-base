package com.boyang.search.security;

import com.boyang.search.security.JwtVerifier.UserIdentity;

import java.util.Collections;
import java.util.Set;

/**
 * 线程级别的用户上下文容器。
 * 将认证从拦截层透传至 Service/Controller 层，解耦强依赖。
 * aclTokens 在 JwtAuthInterceptor 通过 AclTokenBuilder 填充后，
 * 可通过 getAclTokens() 直接取出供 EsRecallStep terms filter 使用。
 */
public class UserContextHolder {
    private static final ThreadLocal<UserIdentity> CONTEXT = new ThreadLocal<>();

    public static void setIdentity(UserIdentity identity) {
        CONTEXT.set(identity);
    }

    public static UserIdentity getIdentity() {
        return CONTEXT.get();
    }

    /**
     * 便捷方法：直接获取当前用户的 ACL Token 集合。
     * 若当前线程无身份信息（未登录/匿名），返回仅含 "_PUBLIC" 的最小集合，
     * 保证 ES terms filter 始终能构造出有效的过滤条件。
     */
    public static Set<String> getAclTokens() {
        UserIdentity identity = CONTEXT.get();
        if (identity == null || identity.getAclTokens().isEmpty()) {
            return Collections.singleton("_PUBLIC");
        }
        return identity.getAclTokens();
    }

    /**
     * 便捷方法：判断当前用户是否为超级管理员。
     * 供 PermissionGuard 和 EsRecallStep 做快速旁路判断。
     */
    public static boolean isSuperAdmin() {
        UserIdentity identity = CONTEXT.get();
        return identity != null && identity.isSuperAdmin();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

