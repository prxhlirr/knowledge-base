package com.boyang.search.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.boyang.search.security.UserContextHolder;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 搜索结果 Redis 缓存服务（P2 #17 修复）。
 * 业务功能：对搜索结果进行 5 分钟 TTL 缓存，降低高频同义查询的全链路延迟。
 * 缓存设计：
 *   key 格式：search:cache:{appCode}:{SHA256(queryText+topK+filters)}
 *   TTL：300 秒（可通过 doc.search.cache-ttl 配置）
 * 容错策略：
 *   - Redis 不可用时降级为 pass-through（不抛异常）
 *   - 写入失败只记录 warn，不影响搜索主流程
 * 使用场景：SearchController 在调 hybridSearch 之前先查缓存；
 *           hybridSearch 完成后通过 putAsync 异步写入（不阻塞响应）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 缓存 TTL（秒），可在 application.yml 中通过 doc.search.cache-ttl 覆盖 */
    private static final long CACHE_TTL_SECONDS = 300L;
    private static final String KEY_PREFIX = "search:cache:";

    /**
     * 构建缓存 key。
     * 将 appCode + queryText + topK + searchMode + 关键 filter 参数拼接后取 SHA-256。
     * searchMode（keyword/semantic/hybrid）不同的查询走不同召回路径，结果集不同，
     * 必须纳入 key，否则三种模式会互相命中对方的缓存，导致结果看起来始终一致。
     *
     * @param appCode    租户鉴权码
     * @param queryText  查询词
     * @param topK       返回数量
     * @param filters    过滤参数（只取 data_source 和 user_dept_code，user_id 不进公共缓存）
     * @param searchMode 检索模式：hybrid | keyword | semantic
     */
    public String buildKey(String appCode, String queryText, int topK,
                           Map<String, Object> filters, String searchMode) {
        // 用户身份和 ACL 摘要必须参与 key，避免不同授权范围共用同一份结果。
        // searchMode 必须参与 key，三种模式走不同召回路径，结果集不同
        String dataSource  = filters != null ? String.valueOf(filters.getOrDefault("data_source", "")) : "";
        String deptCode    = filters != null ? String.valueOf(filters.getOrDefault("user_dept_code", "")) : "";
        String userId      = filters != null ? String.valueOf(filters.getOrDefault("user_id", "")) : "";
        String mode        = searchMode != null ? searchMode : "hybrid";
        Set<String> aclTokenSet = new TreeSet<>(UserContextHolder.getAclTokens());
        String aclDigest = sha256Hex(String.join(",", aclTokenSet));
        String raw = appCode + "|" + queryText + "|" + topK + "|" + dataSource + "|" + deptCode
                + "|" + userId + "|" + aclDigest + "|" + mode;
        String hash = sha256Hex(raw);
        return KEY_PREFIX + appCode + ":" + hash;
    }

    /**
     * 从 Redis 取缓存；若不存在或 Redis 不可用，返回 null（降级为全量查询）。
     *
     * @param key 缓存 key（由 buildKey 构造）
     */
    public List<Map<String, Object>> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[SearchCache] get failed, key={} err={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 异步写入缓存，不阻塞搜索主流程。
     *
     * [P0-1 安全修复] 写入前强制过滤 visibility=PRIVATE|GRANT 的文档，
     * 防止私有文档通过 appCode+query+deptCode 相同的缓存 key 泄露给第三方用户。
     * 原因：缓存 key 不含 user_id，PRIVATE/GRANT 文档是用户级隔离数据，不能进公共缓存。
     * 如果过滤后结果集为空（全是私有文档），则放弃本次缓存写入。
     *
     * @param key     缓存 key
     * @param results 待缓存的搜索结果（可能含 PRIVATE/GRANT 文档）
     */
    @Async
    @SuppressWarnings("unchecked")
    public void putAsync(String key, List<Map<String, Object>> results) {
        try {
            // [P0-1 修复] 过滤掉用户级隔离文档，只缓存可公开共享的文档
            List<Map<String, Object>> cacheable = results.stream()
                .filter(doc -> {
                    Object explicitCacheable = doc.get("cacheable");
                    if (explicitCacheable instanceof Boolean) {
                        return (Boolean) explicitCacheable;
                    }
                    Map<String, Object> source = (Map<String, Object>) doc.get("_source");
                    Map<String, Object> meta = (source != null)
                        ? (Map<String, Object>) source.get("metadata") : null;
                    String vis = (meta != null) ? (String) meta.get("visibility") : null;
                    // PRIVATE（个人私有）和 GRANT（授权指定用户）文档不进公共缓存
                    return "PUBLIC".equals(vis) || "INTERNAL".equals(vis);
                })
                .collect(java.util.stream.Collectors.toList());

            if (cacheable.isEmpty()) {
                // 全部为私有文档，不写入缓存，直接跳过
                log.debug("[SearchCache] 结果全为私有文档，跳过缓存写入 key={}", key);
                return;
            }

            String json = objectMapper.writeValueAsString(cacheable);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(CACHE_TTL_SECONDS));
            log.info("[SearchCache] 写入缓存 key={} total={} cached={}",
                key, results.size(), cacheable.size());
        } catch (Exception e) {
            log.warn("[SearchCache] put failed, key={} err={}", key, e.getMessage());
        }
    }

    /**
     * 按文档 source 名称清除所有相关缓存（权限变更后调用）。
     * [C-1 修复] 使用 SCAN 游标替代 KEYS 全量扫描，避免 Redis 单线程阻塞。
     * SCAN 是增量迭代，每次处理 100 个 key，不会阻塞 Redis Server。
     *
     * @param docSource 文档名称（对应 ES metadata.source 字段）
     */
    @Async
    public void invalidateByDocSource(String docSource) {
        try {
            int deleted = 0;
            // [C-1 修复] 使用 execute 调用底层 connection.scan()，非阻塞分批扫描
            // 替代 redisTemplate.keys() 阻塞命令，避免 Redis 单线程被长时间占用
            java.util.List<String> matchedKeys = new java.util.ArrayList<>();
            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                org.springframework.data.redis.core.ScanOptions opts =
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(KEY_PREFIX + "*")
                        .count(100)
                        .build();
                try (org.springframework.data.redis.core.Cursor<byte[]> cursor =
                         connection.scan(opts)) {
                    while (cursor.hasNext()) {
                        matchedKeys.add(new String(cursor.next(),
                            java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    log.warn("[SearchCache] SCAN 游标失败 err={}", e.getMessage());
                }
                return null;
            });
            for (String key : matchedKeys) {
                String val = redisTemplate.opsForValue().get(key);
                if (val != null && val.contains(docSource)) {
                    redisTemplate.delete(key);
                    deleted++;
                }
            }
            log.info("[SearchCache] 权限变更缓存清除 docSource='{}' deleted={}", docSource, deleted);
        } catch (Exception e) {
            log.warn("[SearchCache] invalidateByDocSource failed, docSource={} err={}", docSource, e.getMessage());
        }
    }

    /**
     * 推入 ES 同步补偿队列（B-2 修复：DocPermissionService ES 更新失败时调用）。
     * key=es:sync:pending（Redis List），由 EsSyncRetryJob 定时消费重试。
     *
     * @param payload JSON 格式的补偿任务描述（含 docId、type、newValue）
     */
    public void pushEsSyncPending(String payload) {
        redisTemplate.opsForList().rightPush("es:sync:pending", payload);
    }

    /** SHA-256 哈希（hex 小写），用于缩短 Redis key 长度 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 在所有 JVM 环境下均可用，此处理论上不可达
            return String.valueOf(input.hashCode());
        }
    }
}
