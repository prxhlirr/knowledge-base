package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Unified document ACL subject.
 */
@Data
@TableName("kb_doc_acl_subjects")
public class KbDocAclSubject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long registryId;
    private String sourceName;
    private Integer docVersion;
    private String subjectType;
    private String subjectValue;
    private String scope;
    private String effect;
    private String sourceType;
    private LocalDateTime expiresAt;
    private Integer isActive;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
