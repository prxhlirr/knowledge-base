package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档授权记录实体（P0-5 完整实现）。
 * 业务功能：作为 GRANT 类文档的授权明细存储，是管理端权限判断的唯一 MySQL 权威来源。
 * 替代原方案：之前 granted_users 暂存于 ES，导致管理端无法校验授权关系；
 *             本表将 MySQL 确立为权威来源，ES granted_users 字段在入库时同步写入（冗余备份）。
 * 设计约束：(doc_id, grantee_id) 联合唯一索引，防止重复授权；
 *           expires_at 为 NULL 表示永久授权；
 *           is_active=0 表示已撤销（软删除，保留审计历史）。
 */
@Data
@TableName("kb_doc_grants")
public class KbDocGrants {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文档注册表主键 ID（关联 kb_doc_registry.id）。
     * 注意：使用 registry id 而非 ES doc_id，MySQL 内部关联更稳定。
     */
    private Long registryId;

    /**
     * 文档 source_name（冗余存储，避免 JOIN，与 ES metadata.source 一致）。
     * 用于 PermissionGuard 按文档名查询授权列表。
     */
    private String sourceName;

    /**
     * 被授权人 ID（对接鉴权体系的用户标识，与 X-User-Id Header 一致）。
     */
    private String granteeId;

    /**
     * 被授权人姓名（冗余存储，便于管理端展示，无需关联用户表）。
     */
    private String granteeName;

    /**
     * 授权到期时间（NULL 表示永久授权）。
     */
    private LocalDateTime expiresAt;

    /**
     * 授权人 ID（操作审计：谁执行了这次授权）。
     */
    private String grantedBy;

    /**
     * 授权原因 / 备注（供审计追溯使用）。
     */
    private String remark;

    /**
     * 是否有效（1=有效，0=已撤销）。
     * 撤销时置 0，不直接删除记录，保留审计历史。
     */
    private Integer isActive;

    /** 授权创建时间 */
    private LocalDateTime createdAt;

    /** 最后修改时间（撤销操作时更新） */
    private LocalDateTime updatedAt;
}
