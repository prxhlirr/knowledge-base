package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档 ACL 到 Elasticsearch 的投影任务。
 *
 * <p>MySQL 中的 ACL 规则是权限权威来源，ES 中的 acl_tokens 只是召回阶段的加速投影。
 * 当 ES update_by_query 失败时，必须保留任务并重试，避免授权后长时间召回不到或撤权后反复被后置过滤。</p>
 */
@Data
@TableName("kb_acl_projection_task")
public class KbAclProjectionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sourceName;
    private String targetIndex;
    private String subjectType;
    private String subjectValue;
    private String aclToken;
    private String operation;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
