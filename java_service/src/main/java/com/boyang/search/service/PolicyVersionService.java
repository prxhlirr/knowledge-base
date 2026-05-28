package com.boyang.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 权限与敏感策略版本服务。
 *
 * <p>业务背景：搜索结果缓存不能只按 query/appCode 隔离，因为文档 ACL、索引 ACL、敏感词策略一旦发生变化，
 * 旧缓存可能继续命中。这里使用 Redis 版本号参与缓存 key，策略变更时只递增版本号，新请求会自然使用新 key，
 * 避免生产环境中对 Redis 做大范围 SCAN/删除。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyVersionService {

    private final StringRedisTemplate redisTemplate;

    private static final String GLOBAL_VERSION_KEY = "policy:version:global";

    /**
     * 读取全局策略版本。Redis 不可用时降级为 0，保证检索主链路不被缓存版本服务拖垮。
     */
    public String currentGlobalVersion() {
        try {
            String version = redisTemplate.opsForValue().get(GLOBAL_VERSION_KEY);
            return version == null || version.trim().isEmpty() ? "0" : version;
        } catch (Exception e) {
            log.warn("[PolicyVersion] read global version failed: {}", e.getMessage());
            return "0";
        }
    }

    /**
     * 递增全局策略版本。任意会改变可见结果的动作都应调用该方法。
     *
     * @param reason 版本变更原因，仅用于日志审计定位
     */
    public void bumpGlobalVersion(String reason) {
        try {
            Long version = redisTemplate.opsForValue().increment(GLOBAL_VERSION_KEY);
            log.info("[PolicyVersion] bumped global policy version to {} reason={}", version, reason);
        } catch (Exception e) {
            log.warn("[PolicyVersion] bump global version failed reason={} err={}", reason, e.getMessage());
        }
    }
}
