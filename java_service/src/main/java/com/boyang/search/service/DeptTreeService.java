package com.boyang.search.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 部门树服务（DeptTreeService）。
 * 业务功能：从外部机构管理系统同步部门层级数据，在本地维护一份部门树缓存，
 *           供权限判断时进行部门上下级推导（避免每次权限校验都调用外部系统）。
 * 关键流程：
 *   1. 启动时从外部机构系统 HTTP 接口拉取全量部门树（或从本地 JSON 配置文件加载）
 *   2. 构建 deptCode → 祖先路径 Map，缓存到 Redis（TTL=1h）+ 本地 ConcurrentHashMap（兜底）
 *   3. 定时任务每小时增量刷新（由 ExternalDeptSyncJob 调用）
 *   4. PermissionGuard.DEPT 权限判断时调用 isSubDept() 利用树形关系做权限推导
 * 部门树格式（外部接口返回 JSON 数组）：
 * <pre>
 * [
 *   {"deptCode": "62", "name": "甘肃省", "parentCode": null},
 *   {"deptCode": "6201", "name": "兰州市", "parentCode": "62"},
 *   {"deptCode": "620102", "name": "城关区", "parentCode": "6201"}
 * ]
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptTreeService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper         objectMapper;

    /** 外部机构系统 HTTP 接口地址，返回完整部门树 JSON */
    @Value("${external.org.api.dept-tree-url:}")
    private String deptTreeUrl;

    /** 外部机构系统调用凭证（Bearer Token 或 API Key） */
    @Value("${external.org.api.token:}")
    private String orgApiToken;

    /** 开发模式：使用内置测试数据，不调用外部接口 */
    @Value("${external.org.dev-mode:true}")
    private boolean devMode;

    /** Redis 缓存 Key 前缀 */
    private static final String REDIS_DEPT_PREFIX  = "dept:ancestors:";
    /** 缓存 TTL（1小时） */
    private static final int    CACHE_TTL_HOURS    = 1;

    /**
     * 本地内存兜底缓存：deptCode → 祖先 code 集合（含自身）。
     * 当 Redis 不可用时保证权限判断不中断。
     */
    private final Map<String, Set<String>> localCache = new ConcurrentHashMap<>();

    /** 部门节点同步状态（false=未初始化，true=已完成至少一次完整同步）*/
    private volatile boolean synced = false;

    // ─── 核心接口 ────────────────────────────────────────────────────────────

    /**
     * 判断 userDeptCode 是否属于 docDeptCode 的下级（或同级）部门。
     * 权限语义：文档设置 DEPT=620102（城关区），则 620102 及其内部科室均可访问；
     *           上级 6201（兰州市）不可访问（防止上级越权访问下级文档）。
     *
     * @param docDeptCode  文档所属部门编码
     * @param userDeptCode 当前用户所在部门编码
     * @return true = 用户有权访问（userDept 是 docDept 的自身或下级）
     */
    public boolean isSubDept(String docDeptCode, String userDeptCode) {
        if (docDeptCode == null || userDeptCode == null) return false;

        String normDoc  = normalizeDeptCode(docDeptCode);
        String normUser = normalizeDeptCode(userDeptCode);

        // 直接相等
        if (normDoc.equals(normUser)) return true;

        // 基于部门树：检查 docDeptCode 是否在 userDeptCode 的祖先路径中
        // 语义：userDept 的祖先包含 docDept，则 user 在 doc 部门的下级
        Set<String> userAncestors = getAncestors(normUser);
        if (userAncestors.contains(normDoc)) {
            return true;
        }

        // 降级：使用原始前缀匹配（部门树未同步时的兜底）
        if (!synced) {
            log.debug("[DeptTree] 部门树未初始化，降级前缀匹配 doc={} user={}", normDoc, normUser);
            return normUser.startsWith(normDoc);
        }

        return false;
    }

    /**
     * 获取指定部门的所有祖先编码集合（含自身）。
     * 先查 Redis，缺失则查本地内存缓存。
     */
    public Set<String> getAncestors(String deptCode) {
        if (deptCode == null) return Collections.emptySet();
        String normalized = normalizeDeptCode(deptCode);

        // 从 Redis 读取
        try {
            String key   = REDIS_DEPT_PREFIX + normalized;
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<Set<String>>() {});
            }
        } catch (Exception e) {
            log.debug("[DeptTree] Redis 读取失败，走本地缓存 dept={} err={}", normalized, e.getMessage());
        }

        // 本地内存兜底
        return localCache.getOrDefault(normalized, Collections.singleton(normalized));
    }

    /**
     * 构建部门 ACL 链（供文档写入侧使用）。
     * 返回 deptCode 本身及所有祖先部门编码的有序列表，用于生成文档的 dept:: Token 集合。
     *
     * 设计语义（双向穿透）：
     *   文档写入时携带当前部门 + 所有祖先 → 父部门用户也能访问子部门文档（管理者可见下属数据）。
     *
     * 示例：buildAclChain("620102") → ["620102", "6201", "62"]
     *       buildAclChain("62") → ["62"]
     *
     * @param deptCode 部门编码（可带尾零，内部会自动标准化）
     * @return 有序的部门编码列表（自身在首位），为空时返回仅含原始 deptCode 的单元素列表
     */
    public List<String> buildAclChain(String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String normalized = normalizeDeptCode(deptCode);
        // getAncestors() 返回 Set（含自身），转为有序 List（LinkedHashSet 保持插入顺序：自身→父→祖）
        Set<String> ancestors = getAncestors(normalized);
        if (ancestors.isEmpty()) {
            // 部门树未同步时降级：至少保证自身编码写入
            return Collections.singletonList(normalized);
        }
        return new ArrayList<>(ancestors);
    }

    /**
     * 判断指定部门编码是否存在于已同步的部门树中。
     * 用于文档入库时校验 deptCode 合规性，防止伪造不存在的部门写入 ES。
     *
     * 降级策略：若部门树尚未完成初始同步（synced=false），乐观放行（不阻塞入库），
     * 同时在日志中记录警告便于后续审计。
     *
     * @param deptCode 待校验的部门编码
     * @return true = 部门存在（或部门树未同步时的乐观放行）
     */
    public boolean exists(String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty()) return false;
        String normalized = normalizeDeptCode(deptCode);
        if (!synced) {
            log.warn("[DeptTree] 部门树尚未同步，乐观放行 exists() 校验 dept={}", normalized);
            return true; // 降级：乐观放行，避免阻塞入库
        }
        return localCache.containsKey(normalized);
    }

    /**
     * 判断 ancestorDeptCode 是否是 targetDeptCode 的祖先（或两者相同）。
     * 用于校验操作者是否有权限将文档归属到目标部门：
     *   - 操作者部门 = 目标部门 → 允许（同部门）
     *   - 操作者部门 是 目标部门的祖先 → 允许（上级可操作下级）
     *   - 否则 → 拒绝
     *
     * @param ancestorDeptCode 操作者所在部门编码
     * @param targetDeptCode   文档归属的目标部门编码
     * @return true = 有权操作（祖先或平级）
     */
    public boolean isAncestorOrSelf(String ancestorDeptCode, String targetDeptCode) {
        if (ancestorDeptCode == null || targetDeptCode == null) return false;
        String normAncestor = normalizeDeptCode(ancestorDeptCode);
        String normTarget   = normalizeDeptCode(targetDeptCode);
        if (normAncestor.equals(normTarget)) return true;
        // 目标部门的祖先集合中包含操作者部门 → 操作者是祖先
        Set<String> targetAncestors = getAncestors(normTarget);
        return targetAncestors.contains(normAncestor);
    }

    // ─── 同步方法（供定时任务和启动时调用） ──────────────────────────────────

    /**
     * 全量同步部门树（启动时 + 每小时定时调用）。
     * 若外部接口不可用，保留现有缓存不清空。
     */
    public synchronized void syncDeptTree() {
        log.info("[DeptTree] 开始同步部门树 devMode={} url={}", devMode, deptTreeUrl);
        try {
            List<DeptNode> nodes = devMode
                    ? loadDevDeptTree()
                    : fetchFromExternalApi();

            if (nodes == null || nodes.isEmpty()) {
                log.warn("[DeptTree] 获取部门树为空，跳过本次同步");
                return;
            }

            // 构建 code → node 索引
            Map<String, DeptNode> codeIndex = new HashMap<>();
            for (DeptNode n : nodes) {
                if (n.getDeptCode() != null) {
                    codeIndex.put(normalizeDeptCode(n.getDeptCode()), n);
                }
            }

            // 计算每个节点的祖先集合（含自身）
            Map<String, Set<String>> ancestorMap = new HashMap<>();
            for (DeptNode node : nodes) {
                String code = normalizeDeptCode(node.getDeptCode());
                ancestorMap.put(code, buildAncestors(code, codeIndex));
            }

            // 写入 Redis（带 TTL）并更新本地缓存
            int redisWritten = 0;
            for (Map.Entry<String, Set<String>> entry : ancestorMap.entrySet()) {
                localCache.put(entry.getKey(), entry.getValue());
                try {
                    String json = objectMapper.writeValueAsString(entry.getValue());
                    redisTemplate.opsForValue().set(
                        REDIS_DEPT_PREFIX + entry.getKey(), json,
                        CACHE_TTL_HOURS, TimeUnit.HOURS);
                    redisWritten++;
                } catch (Exception e) {
                    log.debug("[DeptTree] Redis 写入失败 dept={} err={}", entry.getKey(), e.getMessage());
                }
            }

            synced = true;
            log.info("[DeptTree] 同步完成 nodes={} redisWritten={}", nodes.size(), redisWritten);

        } catch (Exception e) {
            log.error("[DeptTree] 同步失败，保留现有缓存 err={}", e.getMessage(), e);
        }
    }

    // ─── 外部接口调用 ─────────────────────────────────────────────────────────

    /**
     * 调用外部机构管理系统 HTTP 接口获取完整部门树。
     * 接口应返回 JSON 数组，每个元素含 deptCode、name、parentCode 三个字段。
     * 若接口需要认证，通过 external.org.api.token 配置 Bearer Token。
     */
    private List<DeptNode> fetchFromExternalApi() throws Exception {
        if (deptTreeUrl == null || deptTreeUrl.trim().isEmpty()) {
            log.warn("[DeptTree] external.org.api.dept-tree-url 未配置，跳过外部同步");
            return Collections.emptyList();
        }

        RestTemplate restTemplate = new RestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (orgApiToken != null && !orgApiToken.trim().isEmpty()) {
            headers.set("Authorization", "Bearer " + orgApiToken);
        }
        headers.set("Accept", "application/json");

        org.springframework.http.HttpEntity<Void> entity =
            new org.springframework.http.HttpEntity<>(headers);
        org.springframework.http.ResponseEntity<String> resp =
            restTemplate.exchange(deptTreeUrl, org.springframework.http.HttpMethod.GET,
                entity, String.class);

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new RuntimeException("外部机构接口返回异常: " + resp.getStatusCode());
        }

        // 尝试解析为数组，若外部接口返回 {"data": [...]} 格式则需要调整
        try {
            return objectMapper.readValue(resp.getBody(), new TypeReference<List<DeptNode>>() {});
        } catch (Exception e) {
            // 尝试从 data 字段提取
            Map<String, Object> wrapper = objectMapper.readValue(resp.getBody(),
                new TypeReference<Map<String, Object>>() {});
            Object data = wrapper.get("data");
            if (data == null) data = wrapper.get("list");
            return objectMapper.convertValue(data, new TypeReference<List<DeptNode>>() {});
        }
    }

    /**
     * 开发模式：返回内置的测试部门树（模拟行政区划结构）。
     * 生产环境切换 external.org.dev-mode=false 后自动停用。
     */
    private List<DeptNode> loadDevDeptTree() {
        List<DeptNode> nodes = new ArrayList<>();
        nodes.add(new DeptNode("62",     "甘肃省",   null));
        nodes.add(new DeptNode("6201",   "兰州市",   "62"));
        nodes.add(new DeptNode("620102", "城关区",   "6201"));
        nodes.add(new DeptNode("620103", "七里河区", "6201"));
        nodes.add(new DeptNode("6202",   "嘉峪关市", "62"));
        nodes.add(new DeptNode("IT-001", "信息化处", null));  // 非行政编码
        nodes.add(new DeptNode("HR-002", "人事处",   null));
        return nodes;
    }

    // ─── 辅助计算 ─────────────────────────────────────────────────────────────

    /** 递归构建指定部门的祖先集合（含自身，防循环引用） */
    private Set<String> buildAncestors(String code, Map<String, DeptNode> index) {
        Set<String> ancestors = new LinkedHashSet<>();
        String current = code;
        int depth = 0;
        while (current != null && depth++ < 20) { // 最深 20 层，防止循环
            ancestors.add(current);
            DeptNode node = index.get(current);
            if (node == null || node.getParentCode() == null) break;
            current = normalizeDeptCode(node.getParentCode());
        }
        return ancestors;
    }

    /** 部门编码标准化：去除尾部成对 0（与 PermissionGuard 保持一致） */
    public static String normalizeDeptCode(String code) {
        if (code == null || code.trim().isEmpty()) return "";
        String c = code.trim();
        while (c.length() > 2 && c.endsWith("00")) {
            c = c.substring(0, c.length() - 2);
        }
        return c;
    }

    /** 是否已完成至少一次完整同步 */
    public boolean isSynced() { return synced; }

    // ─── 内部数据模型 ─────────────────────────────────────────────────────────

    /** 部门树节点（对应外部接口返回的 JSON 元素） */
    @Data
    public static class DeptNode {
        private String deptCode;
        private String name;
        private String parentCode;

        public DeptNode() {}
        public DeptNode(String deptCode, String name, String parentCode) {
            this.deptCode   = deptCode;
            this.name       = name;
            this.parentCode = parentCode;
        }
    }
}
