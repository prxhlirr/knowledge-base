package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Content-level sensitive text policy.
 */
@Data
@TableName("kb_sensitive_policy")
public class KbSensitivePolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String patternType;
    private String patternValue;
    private String action;
    private String replacement;
    private String appliesTo;
    private String subjectType;
    private String subjectValue;
    private Integer priority;
    private Integer isActive;
    private String createdBy;
    private String approvedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
