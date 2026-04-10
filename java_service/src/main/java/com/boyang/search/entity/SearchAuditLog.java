package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 业务功能：搜索审计日志实体类
 * 流程说明：映射到 search_audit_log 表，用于记录核心检索轨迹、性能指标及权限行为。
 *
 * [Phase 2 新增字段]
 *   admin_bypass          — 是否超管旁路（true 表示本次检索跳过了后置权限校验，属特权访问）
 *   post_filter_denied_count — 后置 PermissionGuard 过滤掉的文档数量（> 0 表示存在幽灵文档漏出）
 */
@Data
@TableName("search_audit_log")
public class SearchAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用标识 */
    private String appCode;

    /** 用户原始输入 */
    private String queryText;

    /** 标准化后的查询词 */
    private String normalizedQuery;

    /** 召回条数 */
    private Integer topHitsCount;

    /** 向量化耗时 (ms) */
    private Integer embeddingCostMs;

    /** ES检索耗时 (ms) */
    private Integer esCostMs;

    /** 重排耗时 (ms) */
    private Integer rerankCostMs;

    /** 总耗时 (ms) */
    private Integer totalCostMs;

    /** 用户ID */
    private String userId;

    /**
     * [Phase 2] 超管旁路标志。
     * true  → 本次请求由超管身份触发，后置 PermissionGuard 校验被跳过（属特权访问，已知且合理）
     * false → 普通用户访问，正常执行权限校验
     * 此字段用于安全审计：识别哪些检索会话使用了超级管理员特权，便于合规审查。
     */
    private Boolean adminBypass;

    /**
     * [Phase 2] 后置权限过滤拦截计数。
     * 表示本次检索中，PermissionGuard 在 Pipeline 末端过滤掉的文档数量。
     * 值 > 0 意味着 ES 权限过滤出现遗漏（acl_tokens 数据质量问题），是重要的监控告警指标。
     */
    private Integer postFilterDeniedCount;

    /** 记录时间 */
    private LocalDateTime createTime;
}

