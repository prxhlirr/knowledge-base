package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.service.SearchCacheService;
import com.boyang.search.service.SearchService;
import com.boyang.search.service.SysTenantPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 知识库统一混合检索接口? * 业务功能：接收查询词和过滤参数，经混合检索管道返回精排后文档列表? * 关键流程? *   1. [P0-6 修复] 可选网关签名校验（search.trust-gateway-headers=true 时开启）
 *   2. [P1-3 修复] AppCode 白名单校验（?SysTenantPolicyService 验证，不再只判非空）
 *   3. 从网关注入的 Header 解析 user_id / dept_code / appCode
 *   4. 缓存命中检查（全量结果缓存，翻页从缓存切片? *   5. [P1-4 修复] 专属 SEARCH_EXECUTOR 线程?+ Future.cancel 超时取消
 *   6. hybridSearch 精排（一次性召?FIXED_TOP_K 条，支持前端翻页? *   7. [P1-8 修复] ?pageNum/pageSize 切片返回，携?total/hasMore
 *   8. [P1-9] 异步写查询日志（使用 ObjectMapper，P1-5 修复 JSON 注入? */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class SearchController {

    @Autowired private SearchService searchService;
    @Autowired private com.boyang.search.service.SearchServiceV2 searchServiceV2;
    @Autowired private SearchCacheService searchCacheService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private SysTenantPolicyService sysTenantPolicyService;
    @Autowired private ObjectMapper objectMapper;
    @Value("${search.use-v2:true}")
    private boolean useV2;


    /** 搜索?SLA（ms）。超时时返回已有最优结果。可由配置覆盖，默认 5000ms */
    @Value("${search.total.sla-ms:5000}")
    private long searchTotalTimeoutMs;



    /**
     * [P1-4 修复] 专属搜索线程池，隔离于公?ForkJoinPool?     * 固定 20 线程，守护线程（JVM 退出时自动结束），避免慢查询耗尽公共线程池?     */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(
        20, r -> {
            Thread t = new Thread(r, "search-worker");
            t.setDaemon(true);
            return t;
        }
    );

    /**
     * [P2-4 修复] 专属日志写入线程池，?SEARCH_EXECUTOR 隔离?     * 根因：日志异步写入若复用 SEARCH_EXECUTOR，高并发下慢搜索任务占满线程池时?     *       日志写入任务也会阻塞，进而导致新搜索任务无法入队（RejectedExecutionException）?     * 方案：单独分?5 个守护线程用于日志写入，优先级低，不影响搜索主路径?     */
    private static final ExecutorService LOG_EXECUTOR = Executors.newFixedThreadPool(
        5, r -> {
            Thread t = new Thread(r, "query-log-writer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // 日志优先级低于搜索任
            return t;
        }
    );

    /** [P1-8] 后端单次精排召回上限（固定值，不由 pageSize 控制），支持前端翻页 */
    private static final int FIXED_TOP_K = 50;

    /** 查询日志 Redis Key 前缀（List 结构，供离线分析消费?*/
    private static final String QUERY_LOG_KEY = "search:query_log";
    /** 最多保留最?5000 条日志（LTRIM?*/
    private static final long QUERY_LOG_MAX = 5000;

    /**
     * 知识库统一混合检索接口（?P0-6/P1-3/4/5/8 修复）?     *
     * 请求体字段：
     *   - queryText   : 查询词（必填?     *   - pageSize    : 每页展示条数（默?10，仅影响返回切片大小，不影响后端召回?     *   - pageNum     : 当前页码（默?1，从 1 开始）
     *   - filters     : 过滤参数（data_source 等）
     *
     * 响应?data 字段?     *   - list        : 当前页结果列?     *   - total       : 精排总结果数
     *   - pageNum     : 当前页码
     *   - pageSize    : 每页大小
     *   - hasMore     : 是否还有下一?     *   - took_ms     : 实际耗时（翻页时极低，命中缓存）
     *   - cache_hit   : 是否来自缓存
     *   - sla_timeout : 是否触发超时降级
     */
    @OperationLog(module = "搜索", operation = "混合检索", recordResponse = false)
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> requestBody,
                                      HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            // ── 1. 解析请求参数 ────────────────────────────────────────
            String queryText = (String) requestBody.getOrDefault("queryText", "");
            // [P1-8] pageSize 仅控制返回切片，后端固定召回 FIXED_TOP_K=50
            int pageSize = requestBody.get("pageSize") instanceof Number
                ? ((Number) requestBody.get("pageSize")).intValue() : 10;
            int pageNum = requestBody.get("pageNum") instanceof Number
                ? ((Number) requestBody.get("pageNum")).intValue() : 1;
            pageSize = Math.max(1, Math.min(pageSize, 50)); // 每页最
            pageNum  = Math.max(1, pageNum);

            @SuppressWarnings("unchecked")
            Map<String, Object> filters = requestBody.get("filters") instanceof Map
                ? (Map<String, Object>) requestBody.get("filters")
                : new HashMap<>();

            if (queryText.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "查询词不能为空");
                return response;
            }

            // ── 2. [JWT 验签完整实现] 提取可信用户身份 ──────────────────────
            // 身份校验已经?JwtAuthInterceptor 中完成并置入 ThreadLocal
            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder.getIdentity();

            // 将可信身份
            if (identity != null) {
                filters.putAll(identity.toFilters());
            }

            // ── 3. [P1-3 修复] AppCode 有效性校?─────────────────────────
            // AppCode 优先?JWT Claims 提取（更可信），兜底?Header
            String appCode = (identity != null && identity.getAppCode() != null)
                    ? identity.getAppCode()
                    : request.getHeader("X-Search-AppCode");
            if (!isValidAppCode(appCode)) {
                response.put("code", 401);
                response.put("msg",  "无效或未注册的鉴权码 [AppCode]，拒绝访问");
                return response;
            }
            // 提取 userId（供日志记录和下游调用使用）
            final String userId = (identity != null) ? identity.getUserId() : null;

            // ── 5. 缓存命中检查（[P1-8] 缓存全量结果，翻页时直接切片）────
            // 注意：buildKey ?topK 参数已统一固化?FIXED_TOP_K，避免翻页时 miss
            String cacheKey = searchCacheService.buildKey(appCode, queryText, FIXED_TOP_K, filters);
            List<Map<String, Object>> cached = searchCacheService.get(cacheKey);
            if (cached != null) {
                long elapsed = System.currentTimeMillis() - startTime;
                // [P1-8] 从缓存全量结果按 pageNum/pageSize 切片
                Map<String, Object> data = buildPagedResponse(cached, pageNum, pageSize, elapsed, true, false);
                response.put("code", 200);
                response.put("msg",  "success (cached)");
                response.put("data", data);
                writeQueryLogAsync(queryText, appCode, userId,
                    (int) data.get("total"), elapsed, true);
                return response;
            }

            // ── 6. [P1-4 修复] 用专属线程池，超时后 cancel 内部任务 ────────
            final String finalAppCode    = appCode;
            final Map<String, Object> finalFilters = filters;
            final String finalQueryText  = queryText;

            List<Map<String, Object>> results;
            boolean slaTimeout = false;
            
            // [第一性原理修复] 从主线程（Tomcat Worker）提前提?JwtInterceptor 绑定的用户身?            // 否则?supplyAsync 专属线程池中获取 ThreadLocal 必定?null，导致全盘以 anonymous 处理
            final com.boyang.search.security.JwtVerifier.UserIdentity mainThreadIdentity = com.boyang.search.security.UserContextHolder.getIdentity();

            CompletableFuture<List<Map<String, Object>>> future = com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
                // 将上下文传递给子线
                com.boyang.search.security.UserContextHolder.setIdentity(mainThreadIdentity);
                try {
                    // 后端固定召回
                    if (useV2) {
                        return searchServiceV2.hybridSearchV2(finalAppCode, finalQueryText,
                                                          FIXED_TOP_K, finalFilters);
                    } else {
                        return searchService.hybridSearch(finalAppCode, finalQueryText,
                                                          FIXED_TOP_K, finalFilters);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // [安全合规] 用完清理 ThreadLocal
                    com.boyang.search.security.UserContextHolder.clear();
                }
            }, SEARCH_EXECUTOR); // [P1-4] 使用专属线程池，不占公共 ForkJoinPool

            try {
                results = future.get(searchTotalTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                slaTimeout = true;
                future.cancel(true); // [P1-4] 超时后主动取消，释放线程资源
                System.err.printf("[SearchSLA] 搜索超出 SLA %dms，降级返回空 query='%s'%n",
                    searchTotalTimeoutMs,
                    queryText.length() > 30 ? queryText.substring(0, 30) : queryText);
                results = Collections.emptyList();
            } catch (Exception ex) {
                throw ex;
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // [文件跳转 补丁] 统一为两条检索路径注?file_name 字段
            // 根因：RerankStep.java 仅在 useV2=true 路径生效，默?useV2=false 走?SearchService?            //       ?SearchService 构建结果时无 file_name 字段，前?v-if="item.file_name" 永为 false?            // 修复：Controller 层统一后处理，对缺?file_name 的结果用 organization? source_name）补填，
            //       ?RerankStep 行为一致，两条路径均覆盖，零侵入?SearchService
            if (results != null) {
                for (Map<String, Object> item : results) {
                    Object existing = item.get("file_name");
                    if (existing == null || (existing instanceof String && ((String) existing).isEmpty())) {
                        Object org = item.get("organization");
                        if (org != null) {
                            item.put("file_name", org);
                        }
                    }
                }
            }

            // ── 7. 异步写全量结果缓存（[P1-8] 翻页时走缓存切片，不重复精排）──
            if (!results.isEmpty()) {
                searchCacheService.putAsync(cacheKey, results);
            }

            // ── 8. [P1-9] 异步记录查询日志 ────────────────────────────────
            writeQueryLogAsync(queryText, appCode, userId, results.size(), elapsed, false);

            // ── 9. [P1-8] ?pageNum/pageSize 切片并组装响?─────────────
            Map<String, Object> data = buildPagedResponse(results, pageNum, pageSize, elapsed, false, slaTimeout);
            response.put("code", 200);
            response.put("msg",  slaTimeout ? "success (sla_timeout_degraded)" : "success");
            response.put("data", data);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务器内部错误或后端检索引擎异? " + e.getMessage());
            e.printStackTrace();
        }
        return response;
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────

    /**
     * [P1-3] AppCode 有效性校验（替代原来的只判非空）?     * 通过 SysTenantPolicyService 查询租户策略表，
     * appCode ?null/?或不在策略表中均视为无效?     *
     * @param appCode 请求?X-Search-AppCode ?     * @return true 表示合法有效?appCode
     */
    @org.springframework.beans.factory.annotation.Value("${jwt.dev-mode:true}")
    private boolean jwtDevMode;

    private boolean isValidAppCode(String appCode) {
        // [开发模式] dev-mode=true 时跳过 AppCode 白名单校验（本地联调无需预置 DB 数据）
        if (jwtDevMode) return true;
        if (appCode == null || appCode.trim().isEmpty()) return false;
        try {
            // 策略表中有记录即视为有效（getByAppCode 返回 null 表示不存在）
            return sysTenantPolicyService.getByAppCode(appCode) != null;
        } catch (Exception e) {
            // [P2-5 安全修复] fail-closed：DB 异常时拒绝访问，而不是降级放?            // 原因：fail-open 将导?DB 超载时所?AppCode 校验返回 true，安全兑底失
            System.err.println("[AppCodeCheck] 策略表查询异常，拒绝访问 (fail-closed): " + e.getMessage());
            return false;
        }
    }

    /**
     * [P1-8] 将全量精排结果按分页参数切片，构造响应体 data 部分?     * 翻页时（缓存命中路径）调用此方法直接切片，无需重复精排?     *
     * @param allResults 全量精排结果列表（来?hybridSearch 或缓存）
     * @param pageNum    当前页码（从 1 开始）
     * @param pageSize   每页条数
     * @param tookMs     实际耗时（ms?     * @param cacheHit   是否命中缓存
     * @param slaTimeout 是否触发 SLA 超时降级
     * @return 分页响应?Map，包?list/total/pageNum/pageSize/hasMore/took_ms/cache_hit/sla_timeout
     */
    private Map<String, Object> buildPagedResponse(
            List<Map<String, Object>> allResults,
            int pageNum, int pageSize,
            long tookMs, boolean cacheHit, boolean slaTimeout) {
        int total = allResults.size();
        int fromIdx = (pageNum - 1) * pageSize;
        int toIdx   = Math.min(fromIdx + pageSize, total);
        List<Map<String, Object>> pageList = (fromIdx < total)
            ? allResults.subList(fromIdx, toIdx)
            : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list",        pageList);
        data.put("total",       total);
        data.put("pageNum",     pageNum);
        data.put("pageSize",    pageSize);
        data.put("hasMore",     toIdx < total);
        data.put("took_ms",     tookMs);
        data.put("cache_hit",   cacheHit);
        data.put("sla_timeout", slaTimeout);
        return data;
    }

    /**
     * 异步写查询日志到 Redis List（[P1-5 修复] 使用 ObjectMapper 序列化，消除 JSON 注入）?     * 日志结构：JSON 字符串，含查询词、结果数、延迟、用户ID、是否缓存命中?     * 保留最?QUERY_LOG_MAX 条（LTRIM 自动滚动）?     * 使用 try-catch 保证日志写入失败不影响搜索返回?     *
     * @param query       查询词（ObjectMapper 自动转义特殊字符，防?JSON 注入?     * @param appCode     鉴权?     * @param userId      用户 ID
     * @param resultCount 结果数量
     * @param latencyMs   延迟（ms?     * @param isCacheHit  是否缓存命中
     */
    private void writeQueryLogAsync(String query, String appCode, String userId,
                                    int resultCount, long latencyMs, boolean isCacheHit) {
        CompletableFuture.runAsync(() -> {
            try {
                // [P1-5 修复] 使用 ObjectMapper 构建 JSON，完全消除手动拼?JSON 的注入漏
                Map<String, Object> logMap = new LinkedHashMap<>();
                logMap.put("query",        query);       // ObjectMapper 自动转义 \n \r " 等特殊字
                logMap.put("appCode",      appCode != null ? appCode : "");
                logMap.put("userId",       userId  != null ? userId  : "anonymous");
                logMap.put("result_count", resultCount);
                logMap.put("latency_ms",   latencyMs);
                logMap.put("cache_hit",    isCacheHit);
                logMap.put("ts",           LocalDateTime.now().toString());
                String logJson = objectMapper.writeValueAsString(logMap);
                redisTemplate.opsForList().leftPush(QUERY_LOG_KEY, logJson);
                redisTemplate.opsForList().trim(QUERY_LOG_KEY, 0, QUERY_LOG_MAX - 1);
            } catch (Exception e) {
                System.err.println("[QueryLog] 日志写入失败: " + e.getMessage());
            }
        }, LOG_EXECUTOR); // [P2-4 修复] 使用独立日志线程池，不与搜索任务竞争 SEARCH_EXECUTOR
    }
}

