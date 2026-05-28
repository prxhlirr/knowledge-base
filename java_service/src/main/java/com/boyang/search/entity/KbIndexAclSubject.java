package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 索引级 ACL 主体规则。
 *
 * <p>该模型用于表达“某个用户/角色/部门是否可以访问某个物理索引”，与文档 ACL 解耦。
 * 租户索引范围仍由 sys_tenant_policy 控制，索引 ACL 在租户范围内继续做精细化收敛。</p>
 */
@Data
@TableName("kb_index_acl_subjects")
public class KbIndexAclSubject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String indexName;
    private String readAlias;
    private String subjectType;
    private String subjectValue;
    private String scope;
    private String effect;
    private LocalDateTime expiresAt;
    private Integer isActive;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
