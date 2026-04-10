package com.boyang.search.security;

import com.boyang.search.service.DeptTreeService;
import com.boyang.search.service.KbDocGrantsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ACL Token 构建器（Phase 1 核心组件）。
 *
 * 业务功能：将用户的复杂身份信息（userId/deptCode/roles/GRANT授权）翻译为
 *           一维扁平化的 ACL Token 集合，供 Elasticsearch terms filter 直接使用。
 *
 * 核心设计思想（"写时复杂，读时极简"）：
 *   - 过去：ES查询时计算"用户是哪个部门 → 文档属于哪个部门 → 是否有权"（嵌套DSL，慢且易漏）
 *   - 现在：请求进入时一次性展开所有身份资产 → ES只做高效的集合求交
 *
 * Token 格式规范：
 *   - "_PUBLIC"         : 公开内容，任何人（含匿名）均可访问
 *   - "_INTERNAL"       : 内部内容，任何已登录用户可访问
 *   - "_SUPER_ADMIN"    : 超级管理员，可绕过所有权限过滤
 *   - "DEPT:{deptCode}" : 部门级权限，该部门及其下级成员可访问
 *   - "USER:{userId}"   : 用户级权限（上传者本人 / PRIVATE 文档）
 *   - "DOC:{sourceName}": 文档级授权（GRANT 类文档的被授权用户）
 *
 * 性能优化：
 *   用户的 GRANT 授权列表通过 Redis 缓存（TTL=5min），避免每次请求都查 MySQL。
 *   缺失缓存降级查 MySQL 并回填 Redis。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AclTokenBuilder {

    private final DeptTreeService       deptTreeService;
    private final KbDocGrantsService    grantsService;
    private final StringRedisTemplate   redisTemplate;
    private final ObjectMapper          objectMapper;

    /** IAM 用户信息接口地址（/sys/user/getUserInfo），用于获取用户角色列表 */
    @Value("${external.iam.user-info-url:}")
    private String iamUserInfoUrl;

    /** IAM 接口调用 Token（Bearer），从配置中心读取，禁止硬编码 */
    @Value("${external.iam.api-token:}")
    private String iamApiToken;

    /** Redis 缓存 Key 前缀：用户 GRANT 授权文档列表 */
    private static final String CACHE_GRANTS_PREFIX = "acl:grants:user:";

    /** Redis 缓存 Key 前缀：用户 IAM 角色列表（role:: tokens） */
    private static final String CACHE_ROLES_PREFIX  = "acl:roles:user:";

    /** GRANT 列表缓存 TTL（5分钟） */
    private static final long CACHE_TTL_MINUTES = 5;

    /** 角色列表缓存 TTL（5分钟） */
    private static final long ROLE_CACHE_TTL_MINUTES = 5;

    /**
     * 为当前用户构建完整的 ACL Token 集合（V2 版本）。
     * 此方法由 JwtAuthInterceptor 在每次请求进入时调用一次，结果缓存在 UserContext 中，
     * 后续整个 Pipeline 处理过程中复用同一份 token 集合。
     *
     * V2 Token 格式规范（与 DocIngestService.computeAclTokens 写入侧完全对齐）：
     *   _PUBLIC         : 公开内容，任何人（含匿名）均可访问
     *   _INTERNAL       : 内部内容，任何已登录用户可访问
     *   _SUPER_ADMIN    : 超级管理员，可绕过所有权限过滤
     *   dept::{code}    : 部门级权限（当前部门 + 所有祖先部门，实现双向穿透）
     *   user::{userId}  : 用户级权限（PRIVATE 文档访问，本人始终持有）
     *   role::{roleCode}: 角色级权限（从 IAM 系统查询，用于 GRANT 模式角色匹配）
     *   DOC:{sourceName}: 文档级授权（向后兼容旧 GRANT 文档，V3 迁移后废弃）
     *
     * @param identity 已通过 JWT 验签的用户身份（不可为 null）
     * @return 扁平化的 ACL Token 集合（永不为 null）
     */
    public Set<String> buildAclTokens(JwtVerifier.UserIdentity identity) {
        if (identity == null) {
            // 匿名/未登录：仅可访问 PUBLIC 内容
            return Collections.singleton("_PUBLIC");
        }

        Set<String> tokens = new LinkedHashSet<>();

        // Step 1：所有人（含匿名）始终具有 PUBLIC 访问权限
        tokens.add("_PUBLIC");

        String userId   = identity.getUserId();
        String deptCode = identity.getDeptCode();

        // Step 2：已登录用户具有 INTERNAL 访问权限
        if (userId != null && !userId.trim().isEmpty()) {
            tokens.add("_INTERNAL");

            // Step 3：超级管理员直接注入 _SUPER_ADMIN 并短路返回
            if (identity.isSuperAdmin()) {
                tokens.add("_SUPER_ADMIN");
                log.debug("[AclTokenBuilder] 超管身份识别 userId={} tokens={}", userId, tokens);
                return tokens;
            }

            // Step 4：注入用户自身 token（用于 PRIVATE 文档访问，V2 格式：user::userId）
            tokens.add("user::" + userId);

            // Step 5：展开部门树，注入当前部门及所有祖先部门的 dept:: token（V2 格式）
            // 语义：文档 DEPT=6201（兰州市），用户在 620102（城关区）的祖先链包含 6201 → 可访问
            // 双向穿透：文档写祖先链（父可见子），用户写自身链（子可见父级文档）
            if (deptCode != null && !deptCode.trim().isEmpty()) {
                Set<String> ancestors = deptTreeService.getAncestors(deptCode);
                for (String ancestorCode : ancestors) {
                    // V2 格式：dept::（小写双冒号），与 DocIngestService 写入端完全一致
                    tokens.add("dept::" + ancestorCode);
                }
                // 兜底：部门树未同步时直接注入用户自身部门
                if (ancestors.isEmpty()) {
                    tokens.add("dept::" + DeptTreeService.normalizeDeptCode(deptCode));
                }
            }

            // Step 6：从 IAM 系统查询用户角色列表，注入 role:: token
            // 用于 GRANT 模式的角色级权限匹配（文档写 role::roleCode，用户持有 role::roleCode → 有权）
            // 结果缓存 Redis 5 分钟，避免每次请求都调用 IAM 接口
            List<String> roles = getUserRolesCached(userId);
            roles.forEach(roleCode -> tokens.add("role::" + roleCode));

            // Step 7：查询该用户持有的所有有效 GRANT 授权，注入文档级 token（向后兼容旧 GRANT 文档）
            // V2 新 GRANT 文档已改用 user:: + role:: 无需此步骤，此处仅兼容存量 DOC: 旧文档
            List<String> grantedDocs = getGrantedDocsCached(userId);
            for (String sourceName : grantedDocs) {
                tokens.add("DOC:" + sourceName);
            }
        }

        log.debug("[AclTokenBuilder] userId={} deptCode={} tokenCount={} tokens={}",
                  userId, deptCode, tokens.size(), tokens);
        return tokens;
    }

    /**
     * 获取用户 IAM 角色列表（带 Redis 缓存，TTL=5分钟）。
     * 从 IAM /sys/user/getUserInfo 接口取 roleList[].roleCode，注入 role:: Token 供 GRANT 角色匹配。
     * 缓存 Key：acl:roles:user:{userId}，Value：换行符分隔的 roleCode 列表。
     *
     * 降级策略：IAM 不可用时返回空列表（不阻塞请求），已授权用户仍可通过 user:: token 访问文档。
     *
     * @param userId 用户 ID
     * @return 该用户持有的 IAM 角色 Code 列表（roleCode，如 ["mock_admin", "mock_user"]）
     */
    private List<String> getUserRolesCached(String userId) {
        String cacheKey = CACHE_ROLES_PREFIX + userId;
        // 优先读 Redis 缓存
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) return Collections.emptyList();
                return Arrays.asList(cached.split("\n"));
            }
        } catch (Exception e) {
            log.warn("[AclTokenBuilder] Redis 读角色缓存失败，降级查 IAM userId={} err={}", userId, e.getMessage());
        }

        // 缓存 Miss：从 IAM 接口获取用户角色
        List<String> roles = fetchRolesFromIam(userId);
        try {
            String value = roles.isEmpty() ? "" : String.join("\n", roles);
            redisTemplate.opsForValue().set(cacheKey, value, ROLE_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[AclTokenBuilder] Redis 写角色缓存失败 userId={} err={}", userId, e.getMessage());
        }
        return roles;
    }

    /**
     * 调用 IAM 系统 /sys/user/getUserInfo 接口获取用户角色列表。
     * IAM 响应格式（参照 user-info.json Mock 数据）：
     * {
     *   "result": {
     *     "userInfo": {
     *       "roleList": [{"roleCode": "mock_admin", ...}, ...]
     *     }
     *   }
     * }
     *
     * @param userId 用户 ID（通过 userId 查询，IAM URL 需支持 ?userId= 参数）
     * @return roleCode 列表，IAM 不可用时返回空列表（降级）
     */
    private List<String> fetchRolesFromIam(String userId) {
        if (iamUserInfoUrl == null || iamUserInfoUrl.trim().isEmpty()) {
            log.debug("[AclTokenBuilder] external.iam.user-info-url 未配置，跳过 IAM 角色查询");
            return Collections.emptyList();
        }
        try {
            RestTemplate restTemplate = new RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (iamApiToken != null && !iamApiToken.trim().isEmpty()) {
                headers.set("Authorization", "Bearer " + iamApiToken);
            }
            headers.set("Accept", "application/json");
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);

            // 调用 IAM 接口：GET /sys/user/getUserInfo?userId={userId}
            String url = iamUserInfoUrl + (iamUserInfoUrl.contains("?") ? "&" : "?") + "userId=" + userId;
            org.springframework.http.ResponseEntity<String> resp =
                restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[AclTokenBuilder] IAM 接口返回异常 userId={} status={}", userId, resp.getStatusCode());
                return Collections.emptyList();
            }

            // 解析 IAM 响应：result.userInfo.roleList[].roleCode
            Map<String, Object> response = objectMapper.readValue(resp.getBody(),
                new TypeReference<Map<String, Object>>() {});
            Object result = response.get("result");
            if (!(result instanceof Map)) return Collections.emptyList();

            Object userInfo = ((Map<?, ?>) result).get("userInfo");
            if (!(userInfo instanceof Map)) return Collections.emptyList();

            Object roleList = ((Map<?, ?>) userInfo).get("roleList");
            if (!(roleList instanceof List)) return Collections.emptyList();

            List<String> roles = new ArrayList<>();
            for (Object roleObj : (List<?>) roleList) {
                if (roleObj instanceof Map) {
                    Object roleCode = ((Map<?, ?>) roleObj).get("roleCode");
                    if (roleCode instanceof String && !((String) roleCode).trim().isEmpty()) {
                        roles.add((String) roleCode);
                    }
                }
            }
            log.debug("[AclTokenBuilder] IAM 角色查询成功 userId={} roles={}", userId, roles);
            return roles;

        } catch (Exception e) {
            log.warn("[AclTokenBuilder] IAM 角色查询失败，降级为空角色 userId={} err={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户被授权的文档列表（带 Redis 缓存，TTL=5分钟）。
     * 向后兼容旧 GRANT 机制（DOC:sourceName），V3 迁移完成后此方法可废弃。
     * 缓存 Key：acl:grants:user:{userId}，Value：以换行符分隔的 sourceName 列表。
     */
    private List<String> getGrantedDocsCached(String userId) {
        String cacheKey = CACHE_GRANTS_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (cached.isEmpty()) return Collections.emptyList();
                return Arrays.asList(cached.split("\n"));
            }
        } catch (Exception e) {
            log.warn("[AclTokenBuilder] Redis 读取 GRANT 缓存失败，降级查 MySQL userId={} err={}", userId, e.getMessage());
        }

        // 缓存 Miss：查询 MySQL 并回填 Redis
        List<String> granted = grantsService.queryGrantedDocsByUser(userId);
        try {
            String value = granted.isEmpty() ? "" : String.join("\n", granted);
            redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[AclTokenBuilder] Redis 写入 GRANT 缓存失败 userId={} err={}", userId, e.getMessage());
        }

        return granted;
    }
}
