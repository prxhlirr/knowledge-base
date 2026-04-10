package com.boyang.search.security;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.service.KbDocGrantsService;
import com.boyang.search.service.KbDocRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档访问权限守卫（后置兜底安全组件）。
 * 业务功能：集中管理文档级别的访问权限后置校验，作为 ES 检索阶段权限过滤的兜底防线，
 *   解决 ES 延迟一致性（Refresh Interval）导致"幽灵文档泄露"的安全风险。
 *
 * [Phase 2 新增]
 *   超管旁路：canAccess(sourceName, UserIdentity) 重载识别 _SUPER_ADMIN token，
 *   直接放行并在 AccessResult 中标记 adminBypass=true，供审计日志记录特权访问。
 *
 * 关键方法：
 *   canAccess(String, UserIdentity)   — [P2 推荐] 基于完整身份上下文的后置校验入口
 *   canAccess(String, String, String) — 兼容旧调用（按 userId + deptCode 独立传参）
 *   isValidInternalToken(String)      — 校验内部服务调用凭证
 *
 * 权限模型（与 EsRecallStep 保持一致）：
 *   PUBLIC  → 所有人可见
 *   INTERNAL→ 任意已登录用户（userId 不为空）可见
 *   DEPT    → 用户部门与文档部门匹配（DeptTreeService 层级推导）
 *   PRIVATE → 仅文档上传者本人可见
 *   GRANT   → kb_doc_grants 存在有效授权记录（MySQL 强一致来源）
 *
 * 使用方式（Pipeline 末端后置过滤）：
 *   AccessResult result = permissionGuard.canAccess(sourceName, identity);
 *   if (!result.isAllowed()) { deniedCount++; }
 *   if (result.isAdminBypass()) auditLog.setAdminBypass(true);
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionGuard {

    private final KbDocRegistryService registryService;

    /**
     * [P0-5] GRANT 授权校验 Service。
     * 将 MySQL kb_doc_grants 表作为 GRANT 权限的唯一权威来源，
     * 替代原来依赖 ES granted_users 字段的不可靠方案。
     */
    private final KbDocGrantsService grantsService;

    /**
     * 部门树服务（替代原来的简单前缀匹配，支持组织层级权限推导）。
     */
    private final com.boyang.search.service.DeptTreeService deptTreeService;

    /**
     * 内部服务间调用凭证（P0-1 修复：从硬编码迁移至 application.yml + 环境变量）。
     * 生产环境必须通过 KB_INTERNAL_TOKEN 环境变量色覆默认値；
     * 长远建议升级为 mTLS 或网关短期 JWT。
     */
    @Value("${kb.internal.token:kb-dev-token-change-me-in-prod}")
    private String internalTokenValue;

    /** 内部 Token Header 名（网关层注入，不对外暴露） */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    // ─── 访问判断 ───────────────────────────────────────────────────

    /**
     * [Phase 2] 基于完整身份上下文的后置权限校验入口（推荐调用方式）。
     *
     * 核心路径：
     *   超管身份（isSuperAdmin=true）→ 直接放行，AccessResult.adminBypass=true（审计标记特权访问）
     *   普通用户 → 从 MySQL 注册表读取文档权限元数据，进行强一致性多维校验
     *
     * 此方法是 SearchServiceV2 Pipeline 末端后置过滤的标准入口，
     * 解决 ES Refresh Interval 延迟导致的"幽灵文档"二次泄露风险。
     *
     * @param docSourceName 文档名称（对应 ES metadata.source / MySQL source_name）
     * @param identity      当前请求的完整用户身份（含 isSuperAdmin / aclTokens 等）
     * @return 访问判断结果（含 adminBypass 标记）
     */
    public AccessResult canAccess(String docSourceName, JwtVerifier.UserIdentity identity) {
        if (identity == null) {
            // 无身份上下文时，回退到匿名访问校验
            return canAccess(docSourceName, null, null);
        }
        // [超管旁路] 超级管理员跳过所有文档权限校验，但访问行为必须被审计标记
        if (identity.isSuperAdmin()) {
            log.info("[PermGuard][SUPER_ADMIN] 超管特权旁路 sourceName='{}' userId='{}'",
                     docSourceName, identity.getUserId());
            return AccessResult.allowAsAdmin();
        }
        // 普通用户：从 MySQL 读取稳定权限元数据（不依赖 ES 延迟刷新数据）
        return canAccess(docSourceName, identity.getUserId(), identity.getDeptCode());
    }

    /**
     * 判断用户是否有权访问指定文档（兼容旧调用：按 userId + deptCode 独立传参）。
     * 此方法从 MySQL 注册表读取权限元数据，不依赖 ES（保证权威性）。
     *
     * @param docSourceName 文档 source 名称（kb_doc_registry.source_name）
     * @param userId        当前用户 ID（null 表示匿名）
     * @param userDeptCode  当前用户部门编码（null 表示未知）
     * @return 访问判断结果
     */
    public AccessResult canAccess(String docSourceName, String userId, String userDeptCode) {
        KbDocRegistry doc = registryService.findLatest(docSourceName);
        if (doc == null || "DELETED".equals(doc.getStatus())) {
            return AccessResult.deny("文档不存在或已删除");
        }
        return canAccess(doc, userId, userDeptCode);
    }

    /**
     * 对已加载的 KbDocRegistry 实体判断访问权限（避免重复查 MySQL）。
     */
    public AccessResult canAccess(KbDocRegistry doc, String userId, String userDeptCode) {
        String vis = doc.getVisibility();
        if (vis == null) vis = "PRIVATE"; // 字段缺失时最严格权限

        switch (vis.toUpperCase()) {
            case "PUBLIC":
                return AccessResult.allow();

            case "INTERNAL":
                if (userId == null || userId.trim().isEmpty()) {
                    return AccessResult.deny("该文档需要登录后才能访问（INTERNAL）");
                }
                return AccessResult.allow();

            case "DEPT":
                if (userId == null || userId.trim().isEmpty()) {
                    return AccessResult.deny("该文档需要登录后才能访问（DEPT）");
                }
                String docDept  = normalizeDeptCode(doc.getDeptCode());
                String userDept = normalizeDeptCode(userDeptCode);
                if (docDept == null || userDept == null || docDept.isEmpty()) {
                    return AccessResult.deny("部门信息不完整，无法校验 DEPT 权限");
                }
                // [部门树推导] 使用 DeptTreeService 判断用户部门是否属于文档部门的下级或同级
                // 对接外部机构系统同步后，此处可正确处理非行政区划编码的 2 级部门结构
                // 未同步时自动降级为前缀匹配（DeptTreeService 内部兜底逻辑）
                if (deptTreeService.isSubDept(docDept, userDept)) {
                    return AccessResult.allow();
                }
                return AccessResult.deny("您所在部门无权访问此文档（DEPT）");

            case "PRIVATE":
                if (doc.getUploaderId() != null && doc.getUploaderId().equals(userId)) {
                    return AccessResult.allow();
                }
                return AccessResult.deny("此文档为私有文档，仅上传者本人可访问（PRIVATE）");

            case "GRANT":
                // [P0-5 完整实现] MySQL kb_doc_grants 表为唯一权威来源。
                // 校验顺序：① 上传者本人始终允许；② 查询 kb_doc_grants 是否存在有效授权。
                // 有效条件：is_active=1 且（expires_at 为 NULL 或 expires_at > 当前时间）。
                if (doc.getUploaderId() != null && doc.getUploaderId().equals(userId)) {
                    return AccessResult.allow();
                }
                if (grantsService.checkAccess(doc.getSourceName(), userId)) {
                    log.debug("[PermGuard][GRANT] 授权校验通过 sourceName='{}' userId='{}'",
                              doc.getSourceName(), userId);
                    return AccessResult.allow();
                }
                log.warn("[PermGuard][GRANT] 权限拒绝：未在 kb_doc_grants 找到授权记录 sourceName='{}' userId='{}'",
                         doc.getSourceName(), userId);
                return AccessResult.deny("此文档为 GRANT 类型，您未在授权名单中，请联系文档上传者申请授权");

            default:
                log.warn("[PermGuard] 未识别的 visibility='{}' docId='{}'", vis, doc.getSourceName());
                return AccessResult.deny("文档权限配置异常，拒绝访问");
        }
    }

    // ─── 内部 Token 校验 ─────────────────────────────────────────────

    /**
     * 校验请求头是否携带内部服务凭证（P0-1 修复：凭证从 yml + 环境变量注入，不再硬编码）。
     * 凭证来源：application.yml kb.internal.token，生产环境通过 KB_INTERNAL_TOKEN 环境变量覆盖。
     * 长远建议：升级为 mTLS 双向证书或网关颁发短期 JWT（ttl=5min）。
     *
     * @param token 请求头 X-Internal-Token 值
     * @return true 表示合法内部调用
     */
    public boolean isValidInternalToken(String token) {
        return token != null && internalTokenValue.equals(token);
    }

    // ─── 部门编码标准化 ──────────────────────────────────────────────

    /**
     * 部门编码标准化（P1.7 + Batch E 修复）。
     * 统一去除尾部全 0（"620102000000" → "620102"），
     * 避免不同系统存储格式不一致导致前缀匹配失败。
     *
     * @param code 原始部门编码，可能是 6/8/12 位
     * @return 标准化后的部门编码（去除尾部 0，最短 2 位）
     */
    public static String normalizeDeptCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String c = code.trim();
        // 从右侧去除成对的 0（行政区划编码每 2 位一级）
        while (c.length() > 2 && c.endsWith("00")) {
            c = c.substring(0, c.length() - 2);
        }
        return c;
    }

    // ─── 内部类 ──────────────────────────────────────────────────────

    /** 访问判断结果，携带允许标志、拒绝原因和超管旁路标记（可直接转为 HTTP 响应） */
    public static class AccessResult {
        private final boolean allowed;
        private final String  denyReason;
        /** [Phase 2] 超管旁路标志：true 表示访问被超管身份强制放行（应被审计记录） */
        private final boolean adminBypass;

        private AccessResult(boolean allowed, String denyReason, boolean adminBypass) {
            this.allowed     = allowed;
            this.denyReason  = denyReason;
            this.adminBypass = adminBypass;
        }

        /** 普通放行（通过权限校验） */
        public static AccessResult allow() {
            return new AccessResult(true, null, false);
        }

        /**
         * [Phase 2] 超管特权放行：权限校验被旁路，访问被强制放行。
         * 与 allow() 的区别：isAdminBypass()=true，供审计日志区分特权访问。
         */
        public static AccessResult allowAsAdmin() {
            return new AccessResult(true, null, true);
        }

        /** 拒绝访问（附带拒绝原因） */
        public static AccessResult deny(String reason) {
            return new AccessResult(false, reason, false);
        }

        public boolean isAllowed()     { return allowed; }
        public String  getDenyReason() { return denyReason; }
        /** [Phase 2] 是否为超管特权旁路（应被记录到审计日志） */
        public boolean isAdminBypass() { return adminBypass; }

        /** 快速生成 403 响应体（供 Controller 直接 return） */
        public java.util.Map<String, Object> denyResponse() {
            java.util.Map<String, Object> res = new java.util.HashMap<>();
            res.put("code", 403);
            res.put("msg",  denyReason != null ? denyReason : "权限不足");
            return res;
        }
    }
}

