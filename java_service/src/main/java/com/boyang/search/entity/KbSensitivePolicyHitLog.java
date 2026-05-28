package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感内容策略命中审计日志。
 *
 * <p>该表只记录策略、阶段、动作、字段、文档和用户上下文，不记录完整命中文本，
 * 避免审计日志自身成为新的敏感数据泄漏源。</p>
 */
@Data
@TableName("kb_sensitive_policy_hit_log")
public class KbSensitivePolicyHitLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long policyId;
    private String appCode;
    private String userId;
    private String stage;
    private String sourceName;
    private String docId;
    private String fieldName;
    private String action;
    private Integer hitCount;
    private String traceId;
    private LocalDateTime createdAt;
}
