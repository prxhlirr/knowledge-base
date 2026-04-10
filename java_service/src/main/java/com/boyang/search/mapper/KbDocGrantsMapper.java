package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbDocGrants;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 文档授权明细 Mapper（P0-5 GRANT 权限完整方案）。
 * 业务功能：操作 kb_doc_grants 表，提供 GRANT 授权查询、新增、撤销的持久化能力。
 * 关键方法：
 *   - isGranted        : 检查指定用户是否有权访问某文档（核心权限校验）
 *   - findActiveGrants : 查询文档的所有有效授权列表（管理端展示）
 *   - insertGrant      : 新增授权（幂等：ON CONFLICT DO NOTHING）
 *   - revokeGrant      : 撤销授权（软删除，置 is_active=0）
 */
@Mapper
public interface KbDocGrantsMapper extends BaseMapper<KbDocGrants> {

    /**
     * 校验用户是否拥有指定文档的有效 GRANT 授权（PermissionGuard 核心校验方法）。
     * 有效条件：is_active=1 且（expires_at 为 NULL 或 expires_at > 当前时间）。
     *
     * @param sourceName 文档名称（对应 ES metadata.source）
     * @param granteeId  被授权用户 ID（对应 X-User-Id Header）
     * @return 若存在有效授权记录则返回 1（COUNT=1），否则返回 0
     */
    @Select("SELECT COUNT(1) FROM kb_doc_grants " +
            "WHERE source_name = #{sourceName} " +
            "  AND grantee_id = #{granteeId} " +
            "  AND is_active = 1 " +
            "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    int isGranted(@Param("sourceName") String sourceName,
                  @Param("granteeId") String granteeId);

    /**
     * 查询指定文档的所有有效授权列表（管理端展示用，含过期过滤）。
     *
     * @param sourceName 文档名称
     * @return 有效授权记录列表（按授权时间倒序）
     */
    @Select("SELECT * FROM kb_doc_grants " +
            "WHERE source_name = #{sourceName} AND is_active = 1 " +
            "ORDER BY created_at DESC")
    List<KbDocGrants> findActiveGrants(@Param("sourceName") String sourceName);

    /**
     * 撤销指定用户对指定文档的授权（软删除，置 is_active=0 并更新时间戳）。
     *
     * @param sourceName 文档名称
     * @param granteeId  被授权用户 ID
     * @return 影响行数（0 表示授权记录不存在或已撤销）
     */
    @Update("UPDATE kb_doc_grants SET is_active = 0, updated_at = CURRENT_TIMESTAMP " +
            "WHERE source_name = #{sourceName} AND grantee_id = #{granteeId} AND is_active = 1")
    int revokeGrant(@Param("sourceName") String sourceName,
                    @Param("granteeId") String granteeId);

    /**
     * 检查文档是否存在 is_active=1 的授权记录（辅助去重，配合 ON CONFLICT 使用）。
     *
     * @param sourceName 文档名称
     * @param granteeId  被授权用户 ID
     * @return 记录数
     */
    @Select("SELECT COUNT(1) FROM kb_doc_grants " +
            "WHERE source_name = #{sourceName} AND grantee_id = #{granteeId} AND is_active = 1")
    int countActiveGrant(@Param("sourceName") String sourceName,
                         @Param("granteeId") String granteeId);

    /**
     * 按用户 ID 反向查询其持有有效 GRANT 授权的所有文档 sourceName 列表。
     * 此方法专供 AclTokenBuilder 在构建 ACL Token 集合时调用：
     *   每个有效授权对应一条 "DOC:{sourceName}" token，使用户可在 ES terms filter 中
     *   命中 acl_tokens 包含 "DOC:{sourceName}" 的私密授权文档。
     *
     * 有效条件：is_active=1 且（expires_at 为 NULL 或 expires_at > 当前时间）。
     *
     * @param userId 被授权用户 ID
     * @return 该用户持有有效 GRANT 授权的文档 sourceName 列表（DISTINCT，避免重复）
     */
    @Select("SELECT DISTINCT source_name FROM kb_doc_grants " +
            "WHERE grantee_id = #{userId} " +
            "  AND is_active = 1 " +
            "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    List<String> findGrantedSourceNamesByUser(@Param("userId") String userId);
}

