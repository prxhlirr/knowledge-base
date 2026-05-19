package com.boyang.search.util;

import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 异步上下文透传工具类。
 *
 * 业务功能：
 *   封装 CompletableFuture 异步调用，确保以下两类上下文自动从调用线程透传到子线程：
 *     1. UserContextHolder（JWT 身份信息 ThreadLocal）
 *     2. SLF4J MDC（traceId 等链路追踪参数）
 *
 * 根因说明：
 *   JwtAuthInterceptor 在 Tomcat 主线程写入的 MDC.traceId 和 UserIdentity，
 *   在 SEARCH_EXECUTOR 子线程中均为 null（ThreadLocal 不跨线程传播）。
 *   AiEngineGateway 的 traceIdInterceptor 从 MDC 读取 traceId 注入 X-Trace-Id Header，
 *   若此时 MDC 为空，AI 服务自生成新 traceId，造成 Java 与 AI 两端日志 trace_id 不一致。
 *
 * 修复策略：
 *   在调用方（主线程）提前快照 UserIdentity + MDC.getCopyOfContextMap()，
 *   在子线程 Lambda 中恢复，执行完毕后清理，防止线程池复用时上下文串号。
 */
public class AsyncContextUtil {

    /**
     * 带上下文透传的 supplyAsync（使用公共 ForkJoinPool）。
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        // 在主线程提前快照，避免子线程取不到 ThreadLocal
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        return CompletableFuture.supplyAsync(() -> {
            try {
                restoreContext(identity, mdcSnapshot);
                return supplier.get();
            } finally {
                clearContext();
            }
        });
    }

    /**
     * 带上下文透传的 supplyAsync（使用指定 Executor）。
     */
    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        return CompletableFuture.supplyAsync(() -> {
            try {
                restoreContext(identity, mdcSnapshot);
                return supplier.get();
            } finally {
                clearContext();
            }
        }, executor);
    }

    /**
     * 带上下文透传的 runAsync（使用公共 ForkJoinPool）。
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        return CompletableFuture.runAsync(() -> {
            try {
                restoreContext(identity, mdcSnapshot);
                runnable.run();
            } finally {
                clearContext();
            }
        });
    }

    /**
     * 带上下文透传的 runAsync（使用指定 Executor）。
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        return CompletableFuture.runAsync(() -> {
            try {
                restoreContext(identity, mdcSnapshot);
                runnable.run();
            } finally {
                clearContext();
            }
        }, executor);
    }

    // ─── 私有工具方法 ──────────────────────────────────────────────

    /**
     * 在子线程恢复主线程快照的 UserIdentity 和 MDC。
     * 注意：若 identity 为 null（匿名用户），不设置 UserContextHolder，保持干净。
     */
    private static void restoreContext(JwtVerifier.UserIdentity identity,
                                       Map<String, String> mdcSnapshot) {
        if (identity != null) {
            UserContextHolder.setIdentity(identity);
        }
        if (mdcSnapshot != null) {
            MDC.setContextMap(mdcSnapshot);
        }
    }

    /**
     * 子线程任务结束后清理 ThreadLocal 和 MDC，防止线程池复用时上下文串号。
     */
    private static void clearContext() {
        UserContextHolder.clear();
        MDC.clear();
    }
}
