package com.boyang.search.service;

import com.boyang.search.entity.KbDocGrants;
import com.boyang.search.mapper.KbDocGrantsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 文档 GRANT 授权管理 Service（P0-5 完整实现）。
 * 业务功能：管理 kb_doc_grants 表，提供 GRANT 类文档的授权 CRUD 能力。
 * 权威来源：MySQL kb_doc_grants 表作为 GRANT 权限的唯一权威来源，
 *           替代原来依赖 ES granted_users 字段的不可靠方案。
 * 关键流程：
 *   1. grantAccess  — 向指定用户授权，幂等（已有记录则跳过），写入审计日志
 *   2. revokeAccess — 撤销用户授权（软删除，保留历史），写入审计日志
 *   3. checkAccess  — 校验用户是否有有效 GRANT 授权（PermissionGuard 调用）
 *   4. listGrants   — 查询文档的所有有效授权列表（管理端展示）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocGrantsService {

    private final KbDocGrantsMapper grantsMapper;

    /**
     * 向指定用户授予文档访问权（P0-5 核心方法）。
     * 幂等处理：若该用户对该文档已有有效授权，直接返回已存在的记录（不重复插入）。
     * 审计记录：授权操作写入 log.info，包含 grantedBy/granteeId/sourceName 三要素。
     *
     * @param sourceName  文档 source 名称（与 ES metadata.source 一致）
     * @param registryId  文档在 kb_doc_registry 表中的主键 ID
     * @param granteeId   被授权用户 ID
     * @param granteeName 被授权用户姓名（展示用）
     * @param grantedBy   操作人 ID（写入授权操作审计）
     * @param expiresAt   到期时间（null 表示永久授权）
     * @param remark      授权原因 / 备注
     * @return 新建的授权记录（若已存在则返回 null，上层 Controller 据此判断提示信息）
     */
    @Transactional
    public KbDocGrants grantAccess(String sourceName, Long registryId,
                                   String granteeId, String granteeName,
                                   String grantedBy, LocalDateTime expiresAt, String remark) {
        // 幂等检查：已有有效授权则直接返回 null（不重复插入）
        if (grantsMapper.countActiveGrant(sourceName, granteeId) > 0) {
            log.info("[DocGrant] 幂等跳过：sourceName='{}' granteeId='{}' 授权已存在",
                     sourceName, granteeId);
            return null;
        }

        KbDocGrants grant = new KbDocGrants();
        grant.setRegistryId(registryId);
        grant.setSourceName(sourceName);
        grant.setGranteeId(granteeId);
        grant.setGranteeName(granteeName);
        grant.setGrantedBy(grantedBy);
        grant.setExpiresAt(expiresAt);
        grant.setRemark(remark);
        grant.setIsActive(1);
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());

        grantsMapper.insert(grant);
        log.info("[DocGrant][AUDIT] 授权成功 sourceName='{}' granteeId='{}' grantedBy='{}' expiresAt={}",
                 sourceName, granteeId, grantedBy, expiresAt != null ? expiresAt : "永久");
        return grant;
    }

    /**
     * 撤销指定用户对文档的 GRANT 授权（软删除，保留审计历史）。
     * 撤销后 is_active=0，PermissionGuard 的 checkAccess 将返回拒绝。
     *
     * @param sourceName 文档 source 名称
     * @param granteeId  被撤销用户 ID
     * @param operatorId 操作人 ID（写入撤销审计）
     * @return true 表示撤销成功，false 表示授权记录不存在或已撤销
     */
    @Transactional
    public boolean revokeAccess(String sourceName, String granteeId, String operatorId) {
        int affected = grantsMapper.revokeGrant(sourceName, granteeId);
        if (affected > 0) {
            log.info("[DocGrant][AUDIT] 撤销授权 sourceName='{}' granteeId='{}' operatorId='{}'",
                     sourceName, granteeId, operatorId);
            return true;
        }
        log.warn("[DocGrant] 撤销失败（记录不存在或已撤销）sourceName='{}' granteeId='{}'",
                 sourceName, granteeId);
        return false;
    }

    /**
     * 校验用户是否拥有指定文档的有效 GRANT 授权（PermissionGuard 核心调用方法）。
     * 有效条件：is_active=1 且（expires_at 为 NULL 或 expires_at > 当前时间）。
     *
     * @param sourceName 文档 source 名称
     * @param granteeId  被校验用户 ID
     * @return true 表示有效授权存在，false 表示无授权或已到期/撤销
     */
    public boolean checkAccess(String sourceName, String granteeId) {
        if (sourceName == null || granteeId == null) return false;
        return grantsMapper.isGranted(sourceName, granteeId) > 0;
    }

    /**
     * 查询指定文档的所有有效授权列表（管理端展示）。
     *
     * @param sourceName 文档 source 名称
     * @return 有效授权记录列表（is_active=1，按创建时间倒序）
     */
    public List<KbDocGrants> listGrants(String sourceName) {
        return grantsMapper.findActiveGrants(sourceName);
    }

    /**
     * 分页查询指定文档的所有有效授权列表（管理端展示）。
     *
     * @param page 分页参数
     * @param sourceName 文档 source 名称
     * @return 包含本页数据的的分页对象
     */
    public com.baomidou.mybatisplus.core.metadata.IPage<KbDocGrants> pageGrants(com.baomidou.mybatisplus.core.metadata.IPage<KbDocGrants> page, String sourceName) {
        return grantsMapper.findActiveGrantsPage(page, sourceName);
    }

    /**
     * 查询指定用户持有的所有有效 GRANT 授权的文档 sourceName 列表。
     * 此方法专供 AclTokenBuilder 在构建 ACL Token 集合时调用，结果由 Redis 缓存（TTL=5min）。
     *
     * 业务规则：
     *   - is_active = 1（未撤销）
     *   - expires_at 为 NULL（永久授权）或 expires_at > 当前时间（未到期）
     *
     * 性能说明：
     *   单个用户被授权的文档数量在正常业务场景下通常较少（< 100 条），全量加载可接受。
     *   若未来业务演进导致单用户授权数量暴增，可考虑分页 + 增量缓存策略。
     *
     * @param userId 被授权用户 ID
     * @return 该用户持有有效 GRANT 授权的文档 sourceName 列表（不含重复，按创建时间倒序）
     */
    public List<String> queryGrantedDocsByUser(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return grantsMapper.findGrantedSourceNamesByUser(userId);
    }
}

