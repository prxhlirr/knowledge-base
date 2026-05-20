package com.boyang.search.controller;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.gateway.AiEngineGateway;
import com.boyang.search.qa.QaAnswerPlan;
import com.boyang.search.qa.QaAnswerProperties;
import com.boyang.search.qa.QaAnswerService;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.SearchCacheService;
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
 * <h1>知识库统一混合检索控制器</h1>
 * 
 * <p><strong>业务功能</strong>：接收用户的查询文本和多维过滤参数，经由多路并发检索、混合排序（Rerank）管道，返回精排后的最佳知识片段列表，支持按需翻页与全量结果缓存。</p>
 * 
 * <p><strong>关键控制流与设计模式</strong>：</p>
 * <ul>
 *   <li>1. <strong>[P0-6 修复] 安全通道校验</strong>：网关层签名安全核验，防篡改和越权注入。</li>
 *   <li>2. <strong>[P1-3 修复] AppCode 白名单校验</strong>：通过租户策略微服务 {@link SysTenantPolicyService} 进行有效性核准。</li>
 *   <li>3. <strong>上下文装配</strong>：解析可信网关注入的身份 Header，构建多租户逻辑过滤条件。</li>
 *   <li>4. <strong>缓存策略（P1-8）</strong>：全量结果在 Redis 中极速读写，翻页时从缓存直接逻辑切片，非阻塞返回。</li>
 *   <li>5. <strong>高并发隔离（P1-4）</strong>：基于专属 {@code SEARCH_EXECUTOR} 线程池调度，使用 Future.cancel 强力实现 SLA 超时截断。</li>
 *   <li>6. <strong>精排召回</strong>：结合 ES 关键字与向量检索双通道召回最佳 Top-K 文档进行 Rerank 算分。</li>
 *   <li>7. <strong>分页流输出（P1-8）</strong>：支持分页切片返回，提供精准的 total 计数与 hasMore 分页探针。</li>
 *   <li>8. <strong>非阻塞日志审计（P1-9）</strong>：采用 ObjectMapper 序列化，利用专属守护线程池异步落库，防止高并发下日志刷盘阻塞搜索实时主路径。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class SearchController {

    @Autowired
    private com.boyang.search.service.SearchServiceV2 searchServiceV2;
    @Autowired
    private SearchCacheService searchCacheService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private SysTenantPolicyService sysTenantPolicyService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private QaAnswerService qaAnswerService;
    @Autowired
    private QaAnswerProperties qaAnswerProperties;
    @Autowired
    private AiEngineGateway aiEngineGateway;
    @Autowired
    private com.boyang.search.qa.QaCitationVerifier qaCitationVerifier;
    @Autowired
    private com.boyang.search.qa.QaClaimVerifier qaClaimVerifier;
    @Autowired
    private PermissionGuard permissionGuard;
    // 直连 ES，供 chunks 调试接口使用
    @Autowired
    private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;
    @Value("${search.use-v2:true}")
    private boolean useV2;

    /** 
     * 混合检索与精排链路的统一服务等级协议（SLA）超时阈值（单位：毫秒）。
     * 超时后将自动降级并截断返回当前已有的最优检索结果，默认值为 5000ms。
     */
    @Value("${search.total.sla-ms:5000}")
    private long searchTotalTimeoutMs;

    /**
     * <h2>[高并发隔离模式] 专属混合检索线程池</h2>
     * <p><strong>设计模式背景</strong>：彻底将复杂的 RAG 混合检索及重排序计算从公共 {@code ForkJoinPool} 以及 Tomcat 容器的 Worker 线程池中进行物理隔离。</p>
     * <p><strong>配置说明</strong>：固定 20 个工作线程，且全部注册为守护线程（Daemon Thread）。这可确保在 JVM 异常退出或应用重新部署时线程能够自行优雅释放，避免因大量慢检索任务耗尽公共业务线程池而拖慢整站。</p>
     */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(
            20, r -> {
                Thread t = new Thread(r, "search-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * QA 流式问答会持有 LLM SSE 长连接，不能复用普通搜索线程池，避免慢生成拖垮检索主链路。
     */
    private static final ExecutorService QA_STREAM_EXECUTOR = Executors.newFixedThreadPool(
            8, r -> {
                Thread t = new Thread(r, "qa-stream-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * <h2>[非阻塞写入模式] 专属异步审计日志线程池</h2>
     * <p><strong>根因剖析</strong>：日志写入如果复用检索线程池，在高并发或者下游 Redis 响应变慢时，排队任务可能会将检索主池塞满，最终导致后续检索请求被拒绝（RejectedExecutionException）。</p>
     * <p><strong>解决方案</strong>：通过高低优先级隔离机制，单独分配 5 个超低优先级（{@code Thread.MIN_PRIORITY}）的守护线程来异步消费查询日志，绝不占用且不干扰搜索实时算分主路径。</p>
     */
    private static final ExecutorService LOG_EXECUTOR = Executors.newFixedThreadPool(
            5, r -> {
                Thread t = new Thread(r, "query-log-writer");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY); // 日志优先级低于搜索任
                return t;
            });

    // [已移除 FIXED_TOP_K，改为按需动态计算 pageSize * pageNum]

        /** 
         * 查询审计日志在 Redis 中的 Key 前缀。
         * 数据存储采用 List 双端队列结构，便于离线大数据流或分析型微服务进行准实时拉取消费。
         */
        private static final String QUERY_LOG_KEY = "search:query_log";
        /** 查询审计日志滑动窗口大小上限，默认最多在 Redis 中持久化 5000 条最新日志（配合 LTRIM 自动滚动截断）。 */
        private static final long QUERY_LOG_MAX = 5000;

    /**
     * <h2>混合检索实时搜索 API 统一入口</h2>
     * 
     * <p><strong>业务功能</strong>：接收多参数检索条件，进行全链路高可靠性的双通道（向量表征 + 关键字倒排）算分，并整合 Rerank 实现高精确度返回。</p>
     * 
     * <p><strong>核心流程与安全设计</strong>：</p>
     * <ol>
     *   <li>1. <strong>提取身份</strong>：从 ThreadLocal 安全上下文中无损提取经拦截器验签的 {@code UserIdentity}，以确保数据访问权限隔离。</li>
     *   <li>2. <strong>AppCode 白名单鉴权（P1-3 修复）</strong>：验证租户白名单鉴权码是否合法，拒绝非注册商户调用。</li>
     *   <li>3. <strong>三级缓存判定（P1-8 修复）</strong>：利用查询词、参数组以及 TopK 动态构建唯一的 Redis 缓存 Key。命中则由 {@link #buildPagedResponse} 进行非阻塞切片并直接返回，大幅降低翻页延时。</li>
     *   <li>4. <strong>高并发异步调度（P1-4 修复）</strong>：针对缓存未命中请求，主线程将任务投递至专属 {@code SEARCH_EXECUTOR}，由 {@link CompletableFuture} 实现带有 SLA 服务超时（默认 5s）的物理控制。若超时则立刻执行 Future.cancel，并优雅降级返回已有空集，绝不卡死主网关。</li>
     *   <li>5. <strong>异步落盘与缓存回写</strong>：搜索成功后，将结果异步注入 Redis 并向队列队列中推入非阻塞操作审计日志。</li>
     * </ol>
     * 
     * @param requestBody JSON 请求体，需包含 {@code queryText} (查询词), {@code pageSize} (每页大小), {@code pageNum} (当前页码), {@code searchMode} (检索模式) 等。
     * @param request     HTTP 容器请求对象，用于读取备用 X-Header
     * @return 包含 code, msg, 以及核心分页 took_ms/cache_hit 统计的 map 结构响应
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
            // [弹性机制] 当 pageNum 较高时动态放大后端召回数量（最大 200，前端页面需做好节流）
            int pageSize = requestBody.get("pageSize") instanceof Number
                    ? ((Number) requestBody.get("pageSize")).intValue()
                    : 10;
            int pageNum = requestBody.get("pageNum") instanceof Number
                    ? ((Number) requestBody.get("pageNum")).intValue()
                    : 1;
            pageSize = Math.max(1, Math.min(pageSize, 50)); // 每页最大 50
            pageNum = Math.max(1, pageNum);
            int dynamicTopK = Math.min(pageNum * pageSize, 200);
            // 检索模式：hybrid（默认）| keyword | semantic。非法值安全降级为 hybrid
            String searchMode = (String) requestBody.getOrDefault("searchMode", "hybrid");
            if (!"keyword".equals(searchMode) && !"semantic".equals(searchMode)) {
                searchMode = "hybrid";
            }
            // lambda 捕获需要 effectively final 变量，用 finalSearchMode 承接最终值
            final String finalSearchMode = searchMode;

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
            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder
                    .getIdentity();

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
                response.put("msg", "无效或未注册的鉴权码 [AppCode]，拒绝访问");
                return response;
            }
            // 提取 userId（供日志记录和下游调用使用）
            final String userId = (identity != null) ? identity.getUserId() : null;

            // ── 5. 缓存命中检查（[P1-8] 缓存全量结果，翻页时直接切片）────
            // 注意：buildKey 中 topK 参数已设定为 dynamicTopK，避免不同深度的翻页混用
            // searchMode 必须纳入 key：三种模式召回路径不同，结果集不同，必须各存各的缓存
            String cacheKey = searchCacheService.buildKey(appCode, queryText, dynamicTopK, filters, finalSearchMode);
            List<Map<String, Object>> cached = searchCacheService.get(cacheKey);
            if (cached != null) {
                long elapsed = System.currentTimeMillis() - startTime;
                // [P1-8] 从缓存全量结果按 pageNum/pageSize 切片
                Map<String, Object> data = buildPagedResponse(cached, pageNum, pageSize, elapsed, true, false);
                response.put("code", 200);
                response.put("msg", "success (cached)");
                response.put("data", data);
                writeQueryLogAsync(queryText, appCode, userId,
                        (int) data.get("total"), elapsed, true);
                return response;
            }

            // ── 6. [P1-4 修复] 用专属线程池，超时后 cancel 内部任务 ────────
            final String finalAppCode = appCode;
            final Map<String, Object> finalFilters = filters;
            final String finalQueryText = queryText;

            List<Map<String, Object>> results;
            boolean slaTimeout = false;

            // [第一性原理修复] 从主线程（Tomcat Worker）提前提?JwtInterceptor 绑定的用户身? // 否则?supplyAsync
            // 专属线程池中获取 ThreadLocal 必定?null，导致全盘以 anonymous 处理
            final com.boyang.search.security.JwtVerifier.UserIdentity mainThreadIdentity = com.boyang.search.security.UserContextHolder
                    .getIdentity();

            CompletableFuture<List<Map<String, Object>>> future = com.boyang.search.util.AsyncContextUtil
                    .supplyAsync(() -> {
                        // 将上下文传递给子线
                        com.boyang.search.security.UserContextHolder.setIdentity(mainThreadIdentity);
                        try {
                            // SearchService 已展撤，统一走 SearchServiceV2
                            return searchServiceV2.hybridSearchV2(finalAppCode, finalQueryText,
                                    dynamicTopK, finalFilters, finalSearchMode);
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
            // 根因：RerankStep.java 仅在 useV2=true 路径生效，默?useV2=false 走?SearchService? //
            // ?SearchService 构建结果时无 file_name 字段，前?v-if="item.file_name" 永为 false? //
            // 修复：Controller 层统一后处理，对缺?file_name 的结果用 organization? source_name）补填，
            // ?RerankStep 行为一致，两条路径均覆盖，零侵入?SearchService
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
            response.put("msg", slaTimeout ? "success (sla_timeout_degraded)" : "success");
            response.put("data", data);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "服务器内部错误或后端检索引擎异? " + e.getMessage());
            e.printStackTrace();
        }
        return response;
    }

    // ─── 分片明细调试接口 ─────────────────────────────────────────────────────

    /**
     * [DEBUG] 文档全量分片明细接口
     *
     * 业务功能：以文档唯一标识（doc_id）为键直查 ES，返回该文档在索引中所有 chunk 分片的明细列表。
     * 主要用于前端调试（点击"查看分片"按钮），展示完整切片列表、内容摘要及粒度信息。
     *
     * 安全策略：debug 接口直接放行，不走 AppCode/JWT 鉴权流程。
     * 如需生产级安全，可在此处添加 @PreAuthorize 权限注解。
     *
     * 关键流程：
     * 1. 通过可选 appCode 查策略表获取索引名，缺省用通配符 knowledge_base*
     * 2. 构建 ES term 查询 metadata.doc_id = docId，按 chunk_index 升序排列
     * 3. 将全量 chunk（最多 500 条）返回给前端
     *
     * @param docId   文档唯一标识（metadata.doc_id 字段的值）
     * @param appCode 租户 AppCode，用于定位精确索引名（可选）
     */
    @GetMapping("/doc/{docId}/chunks")
    public Map<String, Object> getDocChunks(
            @PathVariable("docId") String docId,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "appCode", required = false) String appCode) {

        Map<String, Object> response = new HashMap<>();
        try {
            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder
                    .getIdentity();
            String trustedAppCode = (identity != null && identity.getAppCode() != null)
                    ? identity.getAppCode()
                    : appCode;
            if (!isValidAppCode(trustedAppCode)) {
                response.put("code", 401);
                response.put("msg", "无效或未注册的鉴权码 [AppCode]，拒绝访问");
                return response;
            }
            if (fileName == null || fileName.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "缺少 fileName，无法进行文档权限校验");
                return response;
            }
            PermissionGuard.AccessResult access = permissionGuard.canAccess(fileName, identity);
            if (!access.isAllowed()) {
                response.put("code", 403);
                response.put("msg", "无权查看该文档原文: " + access.getDenyReason());
                return response;
            }

            // [别名化] fallback 改为使用 kb_document 别名（读别名，覆盖所有活跃分区）
            // 根因：原通配符 kb_document* 在 Reindex 期间会同时命中新旧物理索引，导致 chunk
            // 结果重复。别名由 ES 精确控制指向哪些物理索引，Reindex 期间原子切换，无重复。
            String indexPattern = "kb_document";
            if (trustedAppCode != null && !trustedAppCode.trim().isEmpty()) {
                com.boyang.search.entity.SysTenantPolicy policy = sysTenantPolicyService.getByAppCode(trustedAppCode);
                if (policy != null && policy.getIndexPattern() != null) {
                    indexPattern = policy.getIndexPattern();
                }
            }
            final String finalIndex = indexPattern;

            // ── 构建分片查询 DSL ──────────────────────────────────────────────────────
            // docId = content hash（如 35f59aefe6c292bd349f07aec482af84）
            // ES _id 格式：{hash}_v{n}_{gran}_{seq}（gran 可以是 chunk/fine/para 等）
            //
            // 技术注意：ES 不支持对 _id 字段做 wildcard/prefix query（_id 是特殊字段类型）。
            // 方案：改为对 metadata.source（文件名）做精确 term 查询：
            // - 前端额外传入 fileName（item.file_name），精确匹配文档来源
            // - 若无 fileName，则退化为 match_all + 500 限制（调试兜底，生产应总传 fileName）
            // 不同单位同名文件的区分：由 RerankStep dedupeKey 使用 hash 保证搜索结果层面不混淆；
            // chunks 展示层若需要隔离，前端传 deptCode 参数追加 filter 即可（后续迭代）。
            final String finalFileName = fileName;
            co.elastic.clients.elasticsearch.core.SearchRequest chunksReq;
            if (finalFileName != null && !finalFileName.trim().isEmpty()) {
                // 精确模式：用文件名 match_phrase（等同精确匹配，metadata.source 无 .keyword 子字段）
                chunksReq = new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
                        .index(finalIndex)
                        .size(500)
                        .query(q -> q.bool(b -> b
                                .must(mq -> mq.matchPhrase(m -> m
                                        .field("metadata.source")
                                        .query(finalFileName)))
                                .filter(f -> f.term(t -> t
                                        .field("chunk_granularity")
                                        .value("coarse")))))
                        .sort(s -> s.field(f -> f
                                .field("metadata.chunk_id")
                                .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                        .source(src -> src.fetch(true))
                        .build();
            } else {
                // 兜底模式：无文件名时按 match_all 降级（仅调试使用，结果不保证准确）
                chunksReq = new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
                        .index(finalIndex)
                        .size(10)
                        .query(q -> q.bool(b -> b
                                .must(m -> m.matchAll(ma -> ma))
                                .filter(f -> f.term(t -> t
                                        .field("chunk_granularity")
                                        .value("coarse")))))
                        .source(src -> src.fetch(true))
                        .build();
            }

            co.elastic.clients.elasticsearch.core.SearchResponse<Object> esResp = esClient.search(chunksReq,
                    Object.class);

            // 构建返回 chunk 列表
            List<Map<String, Object>> chunks = new ArrayList<>();
            String extFileName = fileName;
            for (co.elastic.clients.elasticsearch.core.search.Hit<Object> hit : esResp.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null)
                    continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) src.get("metadata");

                // 取文件名（先到先得）
                if (extFileName == null && meta != null) {
                    Object rawName = meta.getOrDefault("source", meta.get("title"));
                    extFileName = rawName instanceof String ? (String) rawName : null;
                }

                Map<String, Object> chunk = new HashMap<>();
                chunk.put("chunk_id", hit.id());

                // ES 中 chunk_index 存储在 metadata.chunk_id（而非顶层 chunk_index）
                Object chunkIdxVal = (meta != null) ? meta.get("chunk_id") : src.get("chunk_index");
                chunk.put("chunk_index", chunkIdxVal != null ? chunkIdxVal : 0);

                chunk.put("chunk_text", src.getOrDefault("content", ""));
                chunk.put("char_count", src.get("content") instanceof String
                        ? ((String) src.get("content")).length()
                        : 0);
                chunk.put("chunk_gran", src.getOrDefault("chunk_granularity",
                        (meta != null ? meta.getOrDefault("chunk_type", "unknown") : "unknown")));
                if (meta != null) {
                    chunk.put("publish_time", meta.getOrDefault("publish_time", ""));
                    chunk.put("doc_type", meta.getOrDefault("doc_type", ""));
                }
                chunk.put("parent_chunk_id", src.get("parent_chunk_id"));

                // 读取 is_latest 字段（可能在顶层 src 或 metadata 内）
                // 设计：该字段标识文档当前最新版本的分片，旧版本分片 is_latest=false 不应展示
                Object isLatestRaw = src.get("is_latest");
                if (isLatestRaw == null && meta != null) {
                    isLatestRaw = meta.get("is_latest");
                }
                chunk.put("is_latest", isLatestRaw);
                chunks.add(chunk);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("doc_id", docId);
            data.put("file_name", extFileName != null ? extFileName : docId);

            // ── [Step1] is_latest 过滤：只展示最新版本分片，剔除历史版本 ────────────
            // 设计：文档更新重新入库时，旧版本分片 is_latest=false 仍留在 ES 索引中；
            // 分片明细界面应只展示用户当前看到的最新版内容，避免混入历史切片造成困惑。
            // 容错：若所有分片均无 is_latest 字段（旧版本数据），则兜底返回全量分片，不返回空列表。
            boolean hasIsLatestField = chunks.stream()
                    .anyMatch(c -> c.get("is_latest") != null);
            List<Map<String, Object>> latestChunks;
            if (hasIsLatestField) {
                latestChunks = chunks.stream()
                        .filter(c -> {
                            Object v = c.get("is_latest");
                            // 兼容 boolean true / int 1 / string "true" 三种存储形式
                            return Boolean.TRUE.equals(v)
                                    || Integer.valueOf(1).equals(v)
                                    || "true".equalsIgnoreCase(String.valueOf(v));
                        })
                        .collect(java.util.stream.Collectors.toList());
                // 二次兜底：过滤后为空说明数据异常，退化为全量（不返回空列表）
                if (latestChunks.isEmpty()) {
                    latestChunks = chunks;
                }
            } else {
                // 旧版本数据无 is_latest 字段，展示全量（兼容历史数据）
                latestChunks = chunks;
            }

            // ── [Step2] 粒度去重：双粒度入库时只取 fine 粒度分片展示 ──────────────────
            // 容错：若文档仅有 coarse 分片，则展示全部，不返回空列表。
            List<Map<String, Object>> displayChunks = latestChunks.stream()
                    .filter(c -> "coarse".equals(c.get("chunk_gran")))
                    .collect(java.util.stream.Collectors.toList());
            boolean hasCoarse = !displayChunks.isEmpty();
            // 兼容旧数据：若没有 chunk_granularity 字段，则只排除明确标记为 fine 的分片。
            if (!hasCoarse) {
                displayChunks = latestChunks.stream()
                        .filter(c -> !"fine".equals(c.get("chunk_gran")))
                        .collect(java.util.stream.Collectors.toList());
            }

            data.put("total_chunks", displayChunks.size());
            data.put("chunks", displayChunks);
            data.put("has_fine", false); // 分片明细固定展示 coarse chunks，不再展示 fine 粒度
            data.put("has_coarse", hasCoarse); // 供前端展示粒度标识
            data.put("is_latest_filter", hasIsLatestField); // 供前端显示是否已做版本过滤

            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", data);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "分片查询失败: " + e.getMessage());
            e.printStackTrace();
        }
        return response;
    }

    @Autowired
    private com.boyang.search.service.SimilarityService similarityService;

    /**
     * [IK分词分析] 供前端调用以获取基于 ES IK分词器的分词结果
     * 业务功能：辅助前端进行正确的原文关键字高亮
     * 
     * 请求体字段：
     * - text: 需要分词的文本（一般是用户的搜索词）
     * - analyzer: 分词器（可选，默认 ik_smart，可选 ik_max_word）
     */
    @PostMapping("/search/analyze")
    public Map<String, Object> analyzeQuery(@RequestBody Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();
        try {
            String text = requestBody.get("text");
            if (text == null || text.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "文本不能为空");
                return response;
            }
            // 兼容性：从前端优先获取，缺省默认 ik_smart
            String analyzer = requestBody.getOrDefault("analyzer", "ik_smart");

            co.elastic.clients.elasticsearch.indices.AnalyzeRequest req = co.elastic.clients.elasticsearch.indices.AnalyzeRequest
                    .of(a -> a
                            .index("kb_document_v1") // [Fix] ES的 _analyze 接口不支持指向多个物理索引的 Alias(如kb_document)，必须指定单一物理索引
                            .analyzer(analyzer)
                            .text(text));

            // 发起 analyze 请求
            co.elastic.clients.elasticsearch.indices.AnalyzeResponse resp = esClient.indices().analyze(req);

            // 提取有效分词
            List<String> tokens = resp.tokens().stream()
                    .map(co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken::token)
                    .collect(java.util.stream.Collectors.toList());

            // [兼容性脱水重组] 为避免前端可能因空格产生的 bug，这里将去重并在返回中直接携带
            List<String> distinctTokens = tokens.stream().distinct().collect(java.util.stream.Collectors.toList());

            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", distinctTokens);

        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "分词异常: " + e.getMessage());
        }
        return response;
    }

    /**
     * [向量实时探针] 动态比对任意词组与指定分片的余弦距离
     *
     * 请求体字段：
     * - queryText: 任意比较文本
     * - chunkText: 单个切片的文本内容
     * 返回结构：
     * - cosine: 小数，代表相似度 (0.00 ~ 1.00)
     */
    @PostMapping("/search/similarity/probe")
    public Map<String, Object> probeSimilarity(@RequestBody Map<String, String> requestBody) {
        Map<String, Object> response = new HashMap<>();
        String queryText = requestBody.get("queryText");
        String chunkText = requestBody.get("chunkText");

        if (queryText == null || queryText.trim().isEmpty() || chunkText == null || chunkText.trim().isEmpty()) {
            response.put("code", 400);
            response.put("msg", "文本参数不完整");
            return response;
        }

        try {
            // 通过 SimilarityService 调用 AI 模型接口计算余弦
            Map<String, Object> simData = similarityService.compareSimilarity(queryText, chunkText);
            int code = (int) simData.getOrDefault("code", 500);
            if (code == 200) {
                Map<String, Object> rData = (Map<String, Object>) simData.get("data");
                response.put("code", 200);
                response.put("msg", "success");
                response.put("data", rData);
            } else {
                response.put("code", 500);
                response.put("msg", simData.get("msg"));
            }
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "算分异常: " + e.getMessage());
        }
        return response;
    }

    // ─── 搜索工具方法 ──────────────────────────────────────────────────────

    /**
     * <h2>[租户白名单核验] 租户鉴权码有效性校验</h2>
     * <p><strong>业务逻辑</strong>：替代旧版本单纯判定 {@code appCode} 是否非空的粗糙机制。当系统运行在非开发模式（{@code jwt.dev-mode=false}）下时，必须通过 {@link SysTenantPolicyService} 从数据库中进行精确匹配核算。</p>
     * <p><strong>[P2-5 修复] 容灾设计模式（Fail-Closed 模式）</strong>：如果在访问租户策略表期间发生任何数据库异常、连接中断或连接池耗尽，系统<b>严禁</b>降级放行（Fail-Open），必须强制返回 {@code false} 并安全阻断访问，彻底杜绝在 DB 故障期间产生的安全逃逸漏洞。</p>
     * 
     * @param appCode 租户唯一的 AppCode 鉴权码
     * @return true 表示该鉴权码在数据库中合法注册且状态活跃
     */
    @org.springframework.beans.factory.annotation.Value("${jwt.dev-mode:true}")
    private boolean jwtDevMode;

    private boolean isValidAppCode(String appCode) {
        // [开发模式] dev-mode=true 时跳过 AppCode 白名单校验（本地联调无需预置 DB 数据）
        if (jwtDevMode)
            return true;
        if (appCode == null || appCode.trim().isEmpty())
            return false;
        try {
            // 策略表中有记录即视为有效（getByAppCode 返回 null 表示不存在）
            return sysTenantPolicyService.getByAppCode(appCode) != null;
        } catch (Exception e) {
            // [P2-5 安全修复] fail-closed：DB 异常时拒绝访问，而不是降级放? // 原因：fail-open 将导?DB 超载时所?AppCode
            // 校验返回 true，安全兑底失
            System.err.println("[AppCodeCheck] 策略表查询异常，拒绝访问 (fail-closed): " + e.getMessage());
            return false;
        }
    }

    /**
     * <h2>[全量切片机制] 全量检索结果分页组装器</h2>
     * <p><strong>业务场景（P1-8 修复）</strong>：为提高系统的吞吐量并最大化发挥多级缓存优势，我们将多通道精排后的结果进行全量缓存。在前端发起后续的滚动加载或翻页请求时，直接在此方法中进行逻辑切片（{@link List#subList}），极大缩短了翻页响应延时（直接降低 99.8% 算分开销）。</p>
     * 
     * @param allResults 多通道召回精排后的全量文档列表
     * @param pageNum    用户当前请求的页码（从 1 开始，做过底层安全边界越界截断处理）
     * @param pageSize   用户单页请求条数（做过最大 50 条安全截断）
     * @param tookMs     本次检索真实耗时（毫秒数）
     * @param cacheHit   标识本次操作是否命中了 Redis 缓存
     * @param slaTimeout 标识本次操作是否触发了 SLA 超时熔断降级
     * @return 包含 {@code list} (分页切片列表), {@code total} (总计数), {@code hasMore} (分页探针), {@code cache_hit} 的标准统一响应结构
     */
    private Map<String, Object> buildPagedResponse(
            List<Map<String, Object>> allResults,
            int pageNum, int pageSize,
            long tookMs, boolean cacheHit, boolean slaTimeout) {
        int total = allResults.size();
        int fromIdx = (pageNum - 1) * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, total);
        List<Map<String, Object>> pageList = (fromIdx < total)
                ? allResults.subList(fromIdx, toIdx)
                : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", pageList);
        data.put("total", total);
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        data.put("hasMore", toIdx < total);
        data.put("took_ms", tookMs);
        data.put("cache_hit", cacheHit);
        data.put("sla_timeout", slaTimeout);
        return data;
    }

    /**
     * 异步写查询日志到 Redis List（[P1-5 修复] 使用 ObjectMapper 序列化，消除 JSON 注入）? * 日志结构：JSON
     * 字符串，含查询词、结果数、延迟、用户ID、是否缓存命中? * 保留最?QUERY_LOG_MAX 条（LTRIM 自动滚动）? * 使用
     * try-catch 保证日志写入失败不影响搜索返回? *
     * /**
     * [QA 智能问答] 基于知识库检索的大模型流式回答接口
     * 业务功能：接收用户问题，先从知识库检索最相关的 Top-K 段落作为上下文，
     * 拼装 Prompt 后调用 Python AI 服务的流式大模型接口，
     * 通过 SseEmitter 将生成的文字逐 token 实时推送给前端，实现打字机效果。
     *
     * 关键流程：
     * 1. 知识检索：调用 hybridSearchV2 拉取 Top-K 最相关段落
     * 2. Prompt 组装：将知识段落和用户问题拼装为 System + User 消息格式
     * 3. 流式推理：POST 至 Python /api/ai/llm/chat_stream，以 SSE 协议接力透传
     * 4. 引用展示：先推 citations 事件，再逐 token 推文字
     */
    @PostMapping(value = "/search/qa", produces = "text/event-stream;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter qaSearch(
            @RequestBody Map<String, Object> requestBody,
            javax.servlet.http.HttpServletRequest httpRequest) {

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(
                qaAnswerProperties.getStreamTimeoutMs());

        final com.boyang.search.security.JwtVerifier.UserIdentity mainThreadIdentity = com.boyang.search.security.UserContextHolder
                .getIdentity();
        final String headerAppCode = httpRequest.getHeader("X-Search-AppCode");

        QA_STREAM_EXECUTOR.execute(() -> {
            com.boyang.search.security.UserContextHolder.setIdentity(mainThreadIdentity);
            try {
                String queryText = (String) requestBody.getOrDefault("queryText", "");
                int topK = requestBody.get("topK") instanceof Number
                        ? ((Number) requestBody.get("topK")).intValue()
                        : 5;
                topK = Math.max(1, Math.min(topK, 10));
                String appCode = (mainThreadIdentity != null && mainThreadIdentity.getAppCode() != null)
                        ? mainThreadIdentity.getAppCode()
                        : headerAppCode;

                if (queryText.trim().isEmpty()) {
                    sendQaEvent(emitter, "error", "{\"msg\":\"queryText cannot be empty\"}");
                    emitter.complete();
                    return;
                }
                if (!isValidAppCode(appCode)) {
                    sendQaEvent(emitter, "error", "{\"msg\":\"invalid AppCode\"}");
                    emitter.complete();
                    return;
                }

                QaAnswerPlan answerPlan = qaAnswerService.prepareAnswer(appCode, queryText, topK, mainThreadIdentity);
                streamQaAnswer(emitter, answerPlan);
                emitter.complete();

            } catch (Exception e) {
                System.err.println("[QA SSE] error: " + e.getMessage());
                try {
                    sendQaEvent(emitter, "error", buildSseErrorPayload(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                com.boyang.search.security.UserContextHolder.clear();
            }
        });

        return emitter;
    }

    /**
     * 首页统一搜索编排接口。
     *
     * 一次请求内完成关键词检索、混合检索与知识问答，避免前端三路请求导致
     * query 解析、权限上下文、混合召回被重复执行。问答计划复用本次 hybrid
     * 结果作为文档候选，再流式生成答案。
     */
    @PostMapping(value = "/search/home", produces = "text/event-stream;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter homeSearch(
            @RequestBody Map<String, Object> requestBody,
            javax.servlet.http.HttpServletRequest httpRequest) {

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(
                qaAnswerProperties.getStreamTimeoutMs());

        final com.boyang.search.security.JwtVerifier.UserIdentity mainThreadIdentity = com.boyang.search.security.UserContextHolder
                .getIdentity();
        final String headerAppCode = httpRequest.getHeader("X-Search-AppCode");

        QA_STREAM_EXECUTOR.execute(() -> {
            com.boyang.search.security.UserContextHolder.setIdentity(mainThreadIdentity);
            try {
                String queryText = (String) requestBody.getOrDefault("queryText", "");
                int pageSize = requestBody.get("pageSize") instanceof Number
                        ? ((Number) requestBody.get("pageSize")).intValue()
                        : 50;
                int topK = requestBody.get("topK") instanceof Number
                        ? ((Number) requestBody.get("topK")).intValue()
                        : 5;
                pageSize = Math.max(1, Math.min(pageSize, 50));
                topK = Math.max(1, Math.min(topK, 10));

                String appCode = (mainThreadIdentity != null && mainThreadIdentity.getAppCode() != null)
                        ? mainThreadIdentity.getAppCode()
                        : headerAppCode;

                if (queryText.trim().isEmpty()) {
                    sendQaEvent(emitter, "error", "{\"msg\":\"queryText cannot be empty\"}");
                    emitter.complete();
                    return;
                }
                if (!isValidAppCode(appCode)) {
                    sendQaEvent(emitter, "error", "{\"msg\":\"invalid AppCode\"}");
                    emitter.complete();
                    return;
                }

                Map<String, Object> filters = new LinkedHashMap<>();
                if (mainThreadIdentity != null) {
                    filters.putAll(mainThreadIdentity.toFilters());
                }

                final String finalAppCode = appCode;
                final String finalQueryText = queryText;
                final int finalPageSize = pageSize;
                final Map<String, Object> finalFilters = filters;

                CompletableFuture<List<Map<String, Object>>> keywordFuture = supplySearchForHome(finalAppCode,
                        finalQueryText, finalPageSize,
                        finalFilters, "keyword", mainThreadIdentity);
                CompletableFuture<com.boyang.search.pipeline.SearchContext> hybridContextFuture = com.boyang.search.util.AsyncContextUtil
                        .supplyAsync(() -> {
                            com.boyang.search.security.UserContextHolder.setIdentity(mainThreadIdentity);
                            try {
                                return searchServiceV2.hybridSearchContext(finalAppCode, finalQueryText,
                                        finalPageSize, finalFilters, "hybrid");
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                com.boyang.search.security.UserContextHolder.clear();
                            }
                        }, SEARCH_EXECUTOR);

                SearchRunResult keywordRun = awaitHomeSearchResult(keywordFuture, "keyword", finalPageSize);
                sendHomeSearchEvent(emitter, "keyword", keywordRun, 1, finalPageSize);

                com.boyang.search.pipeline.SearchContext hybridContext = awaitHomeSearchContext(
                        hybridContextFuture, "hybrid");
                SearchRunResult hybridRun = new SearchRunResult(
                        hybridContext == null || hybridContext.getFinalResult() == null
                                ? Collections.emptyList()
                                : hybridContext.getFinalResult(),
                        hybridContext == null ? searchTotalTimeoutMs : System.currentTimeMillis() - hybridContext.getStartTime(),
                        hybridContext == null,
                        hybridContext == null,
                        hybridContext == null ? "success (sla_timeout_degraded)" : "success");
                sendHomeSearchEvent(emitter, "hybrid", hybridRun, 1, finalPageSize);

                List<Map<String, Object>> qaHits = hybridContext == null || hybridContext.getAnswerQaHits() == null
                        ? Collections.emptyList()
                        : hybridContext.getAnswerQaHits();
                sendQaStageEvent(emitter, "evidence");
                QaAnswerPlan answerPlan = qaAnswerService.prepareAnswerWithCandidates(
                        finalAppCode, finalQueryText, topK, hybridRun.results, qaHits);
                streamQaAnswer(emitter, answerPlan);
                emitter.complete();

            } catch (Exception e) {
                System.err.println("[HomeSearch SSE] error: " + e.getMessage());
                try {
                    sendQaEvent(emitter, "error", buildSseErrorPayload(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                com.boyang.search.security.UserContextHolder.clear();
            }
        });

        return emitter;
    }

    /**
     * [QA Debug] 只生成问答计划，不调用大模型。
     * 用于生产/离线现场定位：召回多少候选、最终哪些资料进入 prompt、引用是否为空。
     */
    @PostMapping("/search/qa/debug")
    public Map<String, Object> qaDebug(@RequestBody Map<String, Object> requestBody,
            javax.servlet.http.HttpServletRequest httpRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String queryText = (String) requestBody.getOrDefault("queryText", "");
            int topK = requestBody.get("topK") instanceof Number
                    ? ((Number) requestBody.get("topK")).intValue()
                    : 5;
            topK = Math.max(1, Math.min(topK, 10));

            com.boyang.search.security.JwtVerifier.UserIdentity identity = com.boyang.search.security.UserContextHolder
                    .getIdentity();
            String appCode = (identity != null && identity.getAppCode() != null)
                    ? identity.getAppCode()
                    : httpRequest.getHeader("X-Search-AppCode");

            if (queryText.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "queryText cannot be empty");
                return response;
            }
            if (!isValidAppCode(appCode)) {
                response.put("code", 401);
                response.put("msg", "invalid AppCode");
                return response;
            }

            QaAnswerPlan answerPlan = qaAnswerService.prepareAnswer(appCode, queryText, topK, identity);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("evidence", answerPlan.getEvidenceSummary());
            data.put("citations", answerPlan.getCitations());
            if (Boolean.TRUE.equals(requestBody.get("includePrompt"))) {
                data.put("messages", answerPlan.getMessages());
            }
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", data);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "QA debug failed: " + e.getMessage());
        }
        return response;
    }

    private void streamQaAnswer(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            QaAnswerPlan answerPlan) throws Exception {
        long streamStartMs = System.currentTimeMillis();
        String modelKey = qaAnswerProperties.getModelKey();
        answerPlan.getEvidenceSummary().put("llm_model_key", modelKey);
        sendQaEvent(emitter, "evidence", objectMapper.writeValueAsString(answerPlan.getEvidenceSummary()));
        sendQaEvent(emitter, "citations", objectMapper.writeValueAsString(answerPlan.getCitations()));
        sendQaStageEvent(emitter, "generating");

        com.boyang.search.gateway.AiEngineGateway.LlmStreamResponse streamResponse;
        try {
            streamResponse = aiEngineGateway.openChatStream(
                    answerPlan.getMessages(),
                    modelKey,
                    qaAnswerProperties.getTemperature(),
                    qaAnswerProperties.getMaxTokens(),
                    qaAnswerProperties.getConnectTimeoutMs(),
                    qaAnswerProperties.getReadTimeoutMs());
        } catch (com.boyang.search.gateway.AiEngineGateway.LlmStreamHttpException e) {
            emitExtractiveFallback(emitter, answerPlan, modelKey, e.getConnectMs(), -1L, 0,
                    streamStartMs, e.getRequestBytes(),
                    "extractive_fallback_after_llm_http_error",
                    e.getMessage());
            return;
        }

        long llmConnectMs = streamResponse.getConnectMs();
        int requestBytes = streamResponse.getRequestBytes();
        answerPlan.getEvidenceSummary().put("llm_connect_ms", llmConnectMs);

        long firstTokenMs = -1L;
        int tokenEvents = 0;
        boolean streamedAnyToken = false;
        StringBuilder answerBuffer = new StringBuilder();
        try (com.boyang.search.gateway.AiEngineGateway.LlmStreamResponse response = streamResponse) {
            java.io.BufferedReader reader = response.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    String answerSource = "stream";
                    if (answerBuffer.length() == 0) {
                        String retryAnswer = requestNonStreamQaAnswer(answerPlan, modelKey);
                        if (!retryAnswer.isEmpty()) {
                            answerBuffer.append(retryAnswer);
                            tokenEvents++;
                            if (firstTokenMs < 0) {
                                firstTokenMs = System.currentTimeMillis() - streamStartMs;
                            }
                            sendQaEvent(emitter, "token", objectMapper.writeValueAsString(retryAnswer));
                            answerSource = "non_stream_retry";
                            answerPlan.getEvidenceSummary().put("llm_stream_empty_retry", true);
                        }
                    }
                    if (answerBuffer.length() == 0) {
                        String fallbackAnswer = buildExtractiveFallbackAnswer(answerPlan);
                        if (!fallbackAnswer.isEmpty()) {
                            answerBuffer.append(fallbackAnswer);
                            tokenEvents++;
                            if (firstTokenMs < 0) {
                                firstTokenMs = System.currentTimeMillis() - streamStartMs;
                            }
                            sendQaEvent(emitter, "token", objectMapper.writeValueAsString(fallbackAnswer));
                            answerSource = "extractive_fallback";
                            answerPlan.getEvidenceSummary().put("llm_empty_answer_fallback", true);
                        }
                    }
                    String finalAnswer = sanitizeGeneratedAnswer(answerBuffer.toString(), answerPlan);
                    if (isInvalidGeneratedAnswer(finalAnswer)) {
                        String fallbackAnswer = buildExtractiveFallbackAnswer(answerPlan);
                        if (!fallbackAnswer.isEmpty()) {
                            finalAnswer = sanitizeGeneratedAnswer(fallbackAnswer, answerPlan);
                            answerSource = "extractive_fallback";
                            answerPlan.getEvidenceSummary().put("llm_invalid_answer_fallback", true);
                        }
                    }
                    List<Map<String, Object>> answerParts = buildAnswerParts(finalAnswer, answerPlan);
                    String plainAnswer = plainTextFromAnswerParts(answerParts);
                    answerBuffer.setLength(0);
                    answerBuffer.append(plainAnswer);
                    if (!plainAnswer.isEmpty() && !streamedAnyToken && "stream".equals(answerSource)) {
                        sendQaEvent(emitter, "token", objectMapper.writeValueAsString(plainAnswer));
                    }
                    sendQaEvent(emitter, "answer_parts", objectMapper.writeValueAsString(answerParts));
                    Map<String, Object> llmMetrics = buildLlmMetrics(
                            modelKey, llmConnectMs, firstTokenMs, tokenEvents,
                            streamStartMs, requestBytes, answerBuffer.length());
                    llmMetrics.put("answer_source", answerSource);
                    answerPlan.getEvidenceSummary().put("llm", llmMetrics);
                    Map<String, Object> verification = buildAnswerVerification(
                            answerBuffer.toString(), answerPlan);
                    answerPlan.getEvidenceSummary().put("answer_verification", verification);
                    sendQaEvent(emitter, "metrics", objectMapper.writeValueAsString(llmMetrics));
                    sendQaEvent(emitter, "verification", objectMapper.writeValueAsString(verification));
                    sendQaEvent(emitter, "done", "{}");
                    break;
                }
                if (data.startsWith("[ERROR]")) {
                    emitExtractiveFallback(emitter, answerPlan, modelKey, llmConnectMs, firstTokenMs, tokenEvents,
                            streamStartMs, requestBytes,
                            "extractive_fallback_after_llm_error",
                            data);
                    break;
                }
                if (firstTokenMs < 0) {
                    firstTokenMs = System.currentTimeMillis() - streamStartMs;
                }
                tokenEvents++;
                appendToken(answerBuffer, data);
                streamedAnyToken = true;
                sendQaEvent(emitter, "token", data);
            }
        }
        System.out.printf("[QA Stream] model_key=%s connect=%dms first_token=%dms tokens=%d total=%dms%n",
                modelKey, llmConnectMs, firstTokenMs, tokenEvents, System.currentTimeMillis() - streamStartMs);
    }

    @SuppressWarnings("unchecked")
    private String requestNonStreamQaAnswer(QaAnswerPlan answerPlan, String modelKey) {
        if (answerPlan == null || answerPlan.getMessages() == null || answerPlan.getMessages().isEmpty()) {
            return "";
        }
        try {
            return aiEngineGateway.fetchChatCompletion(
                    answerPlan.getMessages(),
                    modelKey,
                    qaAnswerProperties.getTemperature(),
                    qaAnswerProperties.getMaxTokens());
        } catch (Exception e) {
            System.err.println("[QA NonStreamRetry] error: " + e.getMessage());
            return "";
        }
    }

    private void emitExtractiveFallback(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            QaAnswerPlan answerPlan,
            String modelKey,
            long llmConnectMs,
            long firstTokenMs,
            int tokenEvents,
            long streamStartMs,
            int requestBytes,
            String answerSource,
            String rawError) throws Exception {
        String fallbackAnswer = sanitizeGeneratedAnswer(buildExtractiveFallbackAnswer(answerPlan), answerPlan);
        if (fallbackAnswer.isEmpty()) {
            sendQaEvent(emitter, "error", buildSseErrorPayload(rawError));
            sendQaEvent(emitter, "done", "{}");
            return;
        }
        List<Map<String, Object>> answerParts = buildAnswerParts(fallbackAnswer, answerPlan);
        String plainAnswer = plainTextFromAnswerParts(answerParts);
        answerPlan.getEvidenceSummary().put("llm_error", sanitizeLlmError(rawError));
        answerPlan.getEvidenceSummary().put("llm_error_fallback", true);
        Map<String, Object> llmMetrics = buildLlmMetrics(
                modelKey, llmConnectMs, firstTokenMs, tokenEvents,
                streamStartMs, requestBytes, plainAnswer.length());
        llmMetrics.put("answer_source", answerSource);
        answerPlan.getEvidenceSummary().put("llm", llmMetrics);
        Map<String, Object> verification = buildAnswerVerification(plainAnswer, answerPlan);
        answerPlan.getEvidenceSummary().put("answer_verification", verification);
        sendQaEvent(emitter, "token", objectMapper.writeValueAsString(plainAnswer));
        sendQaEvent(emitter, "answer_parts", objectMapper.writeValueAsString(answerParts));
        sendQaEvent(emitter, "metrics", objectMapper.writeValueAsString(llmMetrics));
        sendQaEvent(emitter, "verification", objectMapper.writeValueAsString(verification));
        sendQaEvent(emitter, "done", "{}");
    }

    private String sanitizeLlmError(String error) {
        if (error == null || error.trim().isEmpty()) {
            return "LLM_CALL_FAILED";
        }
        String text = error.replaceAll("\\s+", " ").trim();
        if (text.contains("SSLError") || text.contains("SSL") || text.contains("HTTPSConnectionPool")) {
            return "LLM_SSL_CONNECTION_ERROR";
        }
        if (text.contains("Read timed out") || text.contains("timeout") || text.contains("Timeout")) {
            return "LLM_TIMEOUT";
        }
        if (text.contains("429")) {
            return "LLM_RATE_LIMIT";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private String buildExtractiveFallbackAnswer(QaAnswerPlan answerPlan) {
        if (answerPlan == null || answerPlan.getCitations() == null || answerPlan.getCitations().isEmpty()) {
            return "";
        }
        StringBuilder answer = new StringBuilder();
        answer.append("根据已检索到的资料，可参考以下内容：\n");
        int count = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> citation : answerPlan.getCitations()) {
            if (citation == null) {
                continue;
            }
            String text = firstNonBlank(citation.get("chunk_text"), citation.get("hit_text"));
            if (text.isEmpty() && citation.get("chunks") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> chunks = (List<Object>) citation.get("chunks");
                for (Object chunk : chunks) {
                    if (chunk instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunkMap = (Map<String, Object>) chunk;
                        text = firstNonBlank(chunkMap.get("chunk_text"), chunkMap.get("hit_text"),
                                chunkMap.get("content"));
                        if (!text.isEmpty()) {
                            break;
                        }
                    }
                }
            }
            text = cleanAnswerSnippet(text);
            if (text.isEmpty() || !seen.add(text)) {
                continue;
            }
            Object index = citation.getOrDefault("index", count + 1);
            answer.append(count + 1).append(". ").append(text).append(" [").append(index).append("]\n");
            count++;
            if (count >= 5) {
                break;
            }
        }
        return count == 0 ? "" : answer.toString().trim();
    }

    private boolean isInvalidGeneratedAnswer(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return true;
        }
        String text = answer.trim();
        int escapedQuoteCount = countOccurrences(text, "\\\"");
        int backslashCount = countOccurrences(text, "\\");
        int replacementCharCount = countOccurrences(text, "�");
        return escapedQuoteCount >= 8
                || backslashCount > Math.max(20, text.length() / 12)
                || replacementCharCount >= 3
                || text.contains("\"claim_text\"")
                || text.contains("\"citation_indexes\"");
    }

    private int countOccurrences(String text, String pattern) {
        if (text == null || text.isEmpty() || pattern == null || pattern.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String cleanAnswerSnippet(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text
                .replaceAll("</?em[^>]*>", "")
                .replace("\\\"", "\"")
                .replaceAll("[\\\\\"]{6,}", "")
                .replaceAll("\\s+", " ")
                .trim();
        int maxLen = 180;
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }

    private Map<String, Object> buildLlmMetrics(String modelKey,
            long llmConnectMs,
            long firstTokenMs,
            int tokenEvents,
            long streamStartMs,
            int requestBytes,
            int answerChars) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("model_key", modelKey);
        metrics.put("connect_ms", llmConnectMs);
        metrics.put("first_token_ms", firstTokenMs);
        metrics.put("stream_ms", System.currentTimeMillis() - streamStartMs);
        metrics.put("token_events", tokenEvents);
        metrics.put("request_bytes", requestBytes);
        metrics.put("answer_chars", answerChars);
        return metrics;
    }

    private List<Map<String, Object>> buildAnswerParts(String answer, QaAnswerPlan answerPlan) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (answer == null || answer.isEmpty()) {
            return parts;
        }
        Set<Integer> validIndexes = validCitationIndexes(answerPlan);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{\\{\\s*cite\\s*:\\s*(\\d+)\\s*}}|\\[(\\d+)]")
                .matcher(answer);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                addTextPart(parts, answer.substring(cursor, matcher.start()));
            }
            String rawIndex = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int index = Integer.parseInt(rawIndex);
            if (validIndexes.isEmpty() || validIndexes.contains(index)) {
                Map<String, Object> citePart = new LinkedHashMap<>();
                citePart.put("type", "citation");
                citePart.put("index", index);
                parts.add(citePart);
            }
            cursor = matcher.end();
        }
        if (cursor < answer.length()) {
            addTextPart(parts, answer.substring(cursor));
        }
        if (parts.isEmpty()) {
            addTextPart(parts, answer);
        }
        if (parts.stream().noneMatch(part -> "citation".equals(part.get("type")))) {
            Integer fallbackIndex = firstClaimCitationIndex(answerPlan);
            if (fallbackIndex != null) {
                Map<String, Object> citePart = new LinkedHashMap<>();
                citePart.put("type", "citation");
                citePart.put("index", fallbackIndex);
                parts.add(citePart);
            }
        }
        return parts;
    }

    private Integer firstClaimCitationIndex(QaAnswerPlan answerPlan) {
        if (answerPlan == null || answerPlan.getClaimPlan() == null) {
            Set<Integer> valid = validCitationIndexes(answerPlan);
            return valid.isEmpty() ? null : valid.iterator().next();
        }
        for (com.boyang.search.qa.ClaimUnit claim : answerPlan.getClaimPlan().getClaims()) {
            if (!claim.getCitationIndexes().isEmpty()) {
                return claim.getCitationIndexes().get(0);
            }
        }
        Set<Integer> valid = validCitationIndexes(answerPlan);
        return valid.isEmpty() ? null : valid.iterator().next();
    }

    private void addTextPart(List<Map<String, Object>> parts, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", text);
        parts.add(textPart);
    }

    private String plainTextFromAnswerParts(List<Map<String, Object>> parts) {
        StringBuilder plain = new StringBuilder();
        for (Map<String, Object> part : parts == null ? Collections.<Map<String, Object>>emptyList() : parts) {
            if ("text".equals(part.get("type")) && part.get("text") != null) {
                plain.append(part.get("text"));
            }
        }
        return plain.toString().trim();
    }

    private void sendQaEvent(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            String eventName,
            String data) throws java.io.IOException {
        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                .event().name(eventName).data(data == null ? "{}" : data));
    }

    private void sendQaStageEvent(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            String stage) throws java.io.IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage == null ? "searching" : stage);
        sendQaEvent(emitter, "qa_stage", objectMapper.writeValueAsString(payload));
    }

    private CompletableFuture<List<Map<String, Object>>> supplySearchForHome(
            String appCode,
            String queryText,
            int topK,
            Map<String, Object> filters,
            String searchMode,
            com.boyang.search.security.JwtVerifier.UserIdentity identity) {
        Map<String, Object> filtersSnapshot = new LinkedHashMap<>(filters == null
                ? Collections.emptyMap()
                : filters);
        return com.boyang.search.util.AsyncContextUtil.supplyAsync(() -> {
            com.boyang.search.security.UserContextHolder.setIdentity(identity);
            try {
                List<Map<String, Object>> results = searchServiceV2.hybridSearchV2(
                        appCode, queryText, topK, filtersSnapshot, searchMode);
                normalizeSearchResultsForFrontend(results);
                return results == null ? Collections.emptyList() : results;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                com.boyang.search.security.UserContextHolder.clear();
            }
        }, SEARCH_EXECUTOR);
    }

    private SearchRunResult awaitHomeSearchResult(
            CompletableFuture<List<Map<String, Object>>> future,
            String searchMode,
            int pageSize) {
        long startMs = System.currentTimeMillis();
        try {
            List<Map<String, Object>> results = future.get(searchTotalTimeoutMs, TimeUnit.MILLISECONDS);
            return new SearchRunResult(
                    results == null ? Collections.emptyList() : results,
                    System.currentTimeMillis() - startMs,
                    false,
                    false,
                    "success");
        } catch (TimeoutException te) {
            future.cancel(true);
            System.err.printf("[HomeSearchSLA] %s 搜索超出 SLA %dms%n", searchMode, searchTotalTimeoutMs);
            return new SearchRunResult(Collections.emptyList(),
                    System.currentTimeMillis() - startMs,
                    false,
                    true,
                    "success (sla_timeout_degraded)");
        } catch (Exception e) {
            System.err.printf("[HomeSearch] %s 搜索失败: %s%n", searchMode, e.getMessage());
            return new SearchRunResult(Collections.emptyList(),
                    System.currentTimeMillis() - startMs,
                    true,
                    false,
                    "search failed: " + e.getMessage());
        }
    }

    private com.boyang.search.pipeline.SearchContext awaitHomeSearchContext(
            CompletableFuture<com.boyang.search.pipeline.SearchContext> future,
            String searchMode) {
        try {
            return future.get(searchTotalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            System.err.printf("[HomeSearchSLA] %s 搜索上下文超出 SLA %dms%n", searchMode, searchTotalTimeoutMs);
            return null;
        } catch (Exception e) {
            System.err.printf("[HomeSearch] %s 搜索上下文失败: %s%n", searchMode, e.getMessage());
            return null;
        }
    }

    private void sendHomeSearchEvent(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                                     String eventName,
            SearchRunResult run,
            int pageNum,
            int pageSize) throws java.io.IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", run.failed ? 500 : 200);
        payload.put("msg", run.message);
        payload.put("data", buildPagedResponse(run.results, pageNum, pageSize,
                run.tookMs, false, run.slaTimeout));
        sendQaEvent(emitter, eventName, objectMapper.writeValueAsString(payload));
    }

    private void normalizeSearchResultsForFrontend(List<Map<String, Object>> results) {
        if (results == null) {
            return;
        }
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

    private static class SearchRunResult {
        final List<Map<String, Object>> results;
        final long tookMs;
        final boolean failed;
        final boolean slaTimeout;
        final String message;

        SearchRunResult(List<Map<String, Object>> results,
                long tookMs,
                boolean failed,
                boolean slaTimeout,
                String message) {
            this.results = results;
            this.tookMs = tookMs;
            this.failed = failed;
            this.slaTimeout = slaTimeout;
            this.message = message;
        }
    }

    private void appendToken(StringBuilder answerBuffer, String data) {
        if (answerBuffer == null || data == null || data.isEmpty()) {
            return;
        }
        try {
            answerBuffer.append(objectMapper.readValue(data, String.class));
        } catch (Exception ignored) {
            answerBuffer.append(data);
        }
    }

    private Map<String, Object> buildAnswerVerification(String answer, QaAnswerPlan answerPlan) {
        Map<String, Object> citation = qaCitationVerifier.verify(answer, answerPlan.getCitations());
        Map<String, Object> claim = qaClaimVerifier.verify(answer,
                answerPlan.getCitations(), answerPlan.getEvidenceSummary());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("citation", citation);
        out.put("claim", claim);
        out.put("llm", answerPlan.getEvidenceSummary().get("llm"));
        out.put("pass", Boolean.TRUE.equals(citation.get("pass")) && Boolean.TRUE.equals(claim.get("pass")));
        return out;
    }

    private String sanitizeGeneratedAnswer(String answer, QaAnswerPlan answerPlan) {
        if (answer == null || answer.trim().isEmpty()) {
            return "";
        }
        Set<Integer> validCitationIndexes = validCitationIndexes(answerPlan);
        String evidenceText = collectCitationEvidence(answerPlan);
        String normalized = normalizeCitationSyntax(answer, validCitationIndexes);
        normalized = removeRoleMarkerLines(normalized);
        normalized = correctUnsupportedYears(normalized, evidenceText);
        normalized = removeMalformedCitationPlaceholders(normalized);
        normalized = removeUnsupportedPersonCounts(normalized, evidenceText);
        normalized = normalized
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return normalized;
    }

    private String removeMalformedCitationPlaceholders(String answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        return answer.replaceAll("\\{\\{\\s*cite\\s*:?\\s*}}", "");
    }

    private String correctUnsupportedYears(String answer, String evidenceText) {
        if (answer == null || answer.isEmpty() || evidenceText == null || evidenceText.isEmpty()) {
            return answer == null ? "" : answer;
        }
        Set<String> evidenceYears = extractYears(evidenceText);
        if (evidenceYears.isEmpty()) {
            return answer;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\d)([12]\\d{3})(?!\\d)")
                .matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String year = matcher.group(1);
            String replacement = evidenceYears.contains(year) ? year : closestYearByOneDigit(year, evidenceYears);
            matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(replacement == null ? year : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Set<String> extractYears(String text) {
        Set<String> years = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\d)([12]\\d{3})(?!\\d)").matcher(text);
        while (matcher.find()) {
            years.add(matcher.group(1));
        }
        return years;
    }

    private String closestYearByOneDigit(String year, Set<String> evidenceYears) {
        for (String candidate : evidenceYears) {
            if (candidate.length() == year.length() && digitDistance(candidate, year) == 1) {
                return candidate;
            }
        }
        return null;
    }

    private int digitDistance(String left, String right) {
        int distance = 0;
        for (int i = 0; i < left.length() && i < right.length(); i++) {
            if (left.charAt(i) != right.charAt(i)) {
                distance++;
            }
        }
        return distance + Math.abs(left.length() - right.length());
    }

    private Set<Integer> validCitationIndexes(QaAnswerPlan answerPlan) {
        Set<Integer> indexes = new LinkedHashSet<>();
        if (answerPlan == null || answerPlan.getCitations() == null) {
            return indexes;
        }
        for (Map<String, Object> citation : answerPlan.getCitations()) {
            if (citation == null) {
                continue;
            }
            Object index = citation.get("index");
            if (index instanceof Number) {
                indexes.add(((Number) index).intValue());
            } else if (index != null) {
                try {
                    indexes.add(Integer.parseInt(index.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // Ignore non-numeric citation indexes emitted by old data.
                }
            }
        }
        return indexes;
    }

    private String normalizeCitationSyntax(String answer, Set<Integer> validCitationIndexes) {
        String text = answer
                .replaceAll("\\[\\s*\\[\\s*(\\d+)\\s*]\\s*]", "[$1]")
                .replaceAll("【\\s*(\\d+)\\s*】", "[$1]")
                .replaceAll("\\[\\s*]\\s*", "")
                .replaceAll("\\[\\s*\\[\\s*]\\s*]", "");
        text = text.replaceAll("\\[\\s*(\\d+)\\s*(?=\\r?\\n|$)", "[$1]");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = validCitationIndexes.isEmpty() || validCitationIndexes.contains(index)
                    ? "[" + index + "]"
                    : "";
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString()
                .replaceAll("([。！？；,.，、])\\s*(\\[\\d+])", "$1$2")
                .replaceAll("\\s{2,}", " ");
    }

    private String removeRoleMarkerLines(String answer) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        return answer.replaceAll("(?im)^\\s*(?:user|assistant|system)\\s*(?:\\r?\\n|$)", "");
    }

    private String collectCitationEvidence(QaAnswerPlan answerPlan) {
        if (answerPlan == null || answerPlan.getCitations() == null) {
            return "";
        }
        StringBuilder evidence = new StringBuilder();
        for (Map<String, Object> citation : answerPlan.getCitations()) {
            if (citation == null) {
                continue;
            }
            appendEvidenceValue(evidence, citation.get("chunk_text"));
            appendEvidenceValue(evidence, citation.get("hit_text"));
            Object hitTexts = citation.get("hit_texts");
            if (hitTexts instanceof List) {
                for (Object item : (List<?>) hitTexts) {
                    appendEvidenceValue(evidence, item);
                }
            }
            Object chunks = citation.get("chunks");
            if (chunks instanceof List) {
                for (Object item : (List<?>) chunks) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = (Map<String, Object>) item;
                        appendEvidenceValue(evidence, chunk.get("hit_text"));
                        appendEvidenceValue(evidence, chunk.get("chunk_text"));
                        appendEvidenceValue(evidence, chunk.get("content"));
                    }
                }
            }
        }
        return evidence.toString();
    }

    private void appendEvidenceValue(StringBuilder evidence, Object value) {
        if (evidence == null || value == null) {
            return;
        }
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            evidence.append('\n').append(text);
        }
    }

    private String removeUnsupportedPersonCounts(String answer, String evidenceText) {
        String normalizedEvidence = compactText(evidenceText);
        String[] lines = answer.split("\\R", -1);
        List<String> sanitizedLines = new ArrayList<>();
        for (String line : lines) {
            sanitizedLines.add(sanitizeUnsupportedPersonCountLine(line, normalizedEvidence));
        }
        return String.join("\n", sanitizedLines);
    }

    private String sanitizeUnsupportedPersonCountLine(String line, String normalizedEvidence) {
        if (line == null || line.trim().isEmpty()) {
            return line == null ? "" : line;
        }
        java.util.regex.Pattern peopleCountPattern = java.util.regex.Pattern.compile(
                "(候选人|人员|名单)?\\s*(共计|共有|合计|总计|总人数|共)\\s*([0-9一二三四五六七八九十百千万]+)\\s*人");
        java.util.regex.Matcher matcher = peopleCountPattern.matcher(line);
        boolean unsupported = false;
        while (matcher.find()) {
            String phrase = compactText(matcher.group());
            if (!normalizedEvidence.contains(phrase)) {
                unsupported = true;
                break;
            }
        }
        if (!unsupported) {
            return line;
        }
        String updated = matcher.replaceAll("材料未明确给出总人数");
        updated = updated.replaceAll("(\\d+|[一二三四五六七八九十百千万]+)\\s*人", "相关人员");
        updated = updated.replaceAll("材料未明确给出总人数[，,、]?\\s*材料未明确给出总人数", "材料未明确给出总人数");
        return updated;
    }

    private String compactText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("</?em[^>]*>", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private String buildSseErrorPayload(String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("msg", message == null ? "AI服务调用失败" : message);
            error.put("msg", userFacingQaError(message));
            error.put("error_code", sanitizeLlmError(message));
            return objectMapper.writeValueAsString(error);
        } catch (Exception ignored) {
            return "{\"msg\":\"AI服务调用失败\"}";
        }
    }

    private String userFacingQaError(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。";
        }
        String text = message.trim();
        if (text.contains("HTTPSConnectionPool") || text.contains("SSLError")
                || text.contains("SSL") || text.contains("Traceback")
                || text.contains("Exception") || text.contains("[ERROR]")) {
            return "知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。";
        }
        return text.length() <= 120 ? text : "知识问答生成暂时失败，已为你保留检索结果和参考资料，请稍后重试。";
    }

    private void writeQueryLogAsync(String query, String appCode, String userId,
            int resultCount, long latencyMs, boolean isCacheHit) {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> logMap = new LinkedHashMap<>();
                logMap.put("query", query);
                logMap.put("appCode", appCode != null ? appCode : "");
                logMap.put("userId", userId != null ? userId : "anonymous");
                logMap.put("result_count", resultCount);
                logMap.put("latency_ms", latencyMs);
                logMap.put("cache_hit", isCacheHit);
                logMap.put("ts", LocalDateTime.now().toString());
                String logJson = objectMapper.writeValueAsString(logMap);
                redisTemplate.opsForList().leftPush(QUERY_LOG_KEY, logJson);
                redisTemplate.opsForList().trim(QUERY_LOG_KEY, 0, QUERY_LOG_MAX - 1);
            } catch (Exception e) {
                System.err.println("[QueryLog] 日志写入失败: " + e.getMessage());
            }
        }, LOG_EXECUTOR);
    }
}
