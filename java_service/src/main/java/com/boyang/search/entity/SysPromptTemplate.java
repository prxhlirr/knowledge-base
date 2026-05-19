package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM Prompt 模板实体类
 *
 * 业务功能：将 AI 服务中所有硬编码的 Prompt 集中到数据库管理，
 * 支持运营团队通过管理界面热更新，无需重启 AI 服务即可调优效果。
 *
 * 关键设计：
 *   - prompt_key 全局唯一，AI 服务以此为查询键（格式：{SCENE}_{ROLE}，如 HYDE_GENERAL_SYSTEM）
 *   - is_active=0 时 AI 服务降级回内置默认值，保证服务不中断
 *   - version 字段追踪修改次数，辅助效果溯源
 *
 * 对应数据库表：sys_prompt_template
 */
@Data
@TableName("sys_prompt_template")
public class SysPromptTemplate {

    /**
     * 主键ID（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Prompt 唯一标识，格式约定：{SCENE}_{ROLE}
     * 示例：HYDE_GENERAL_SYSTEM、REWRITE_USER、RERANK_SYSTEM
     * AI 服务 PromptRegistry 以此 key 查询并缓存 Prompt 内容
     */
    private String promptKey;

    /**
     * 功能场景分组，供管理界面按组折叠展示
     * 枚举值：HYDE_GENERAL / HYDE_SHORT / HYDE_GONGSHU / HYDE_FAGUI /
     *         HYDE_TONGZHI / REWRITE / RERANK / LEGACY_HYDE
     */
    private String scene;

    /**
     * 消息角色：system（定义 AI 角色）或 user（含查询内容，含 {query} 等占位符）
     */
    private String role;

    /**
     * Prompt 正文文本
     * 支持动态占位符：
     *   {query}     - 用户原始搜索词
     *   {docs}      - 候选文档列表（RERANK 场景）
     *   {doc_count} - 文档数量（RERANK 场景）
     */
    private String content;

    /**
     * 中文说明，帮助管理员理解该 Prompt 的用途与适用场景
     */
    private String description;

    /**
     * 是否启用：1=生效（AI 服务使用此 Prompt）
     *           0=停用（AI 服务退回内置默认值，不影响服务可用性）
     */
    private Short isActive;

    /**
     * Prompt 内容修改次数，每次更新自动 +1，用于追溯调优频率
     */
    private Integer version;

    /**
     * 最后修改人用户 ID（对应系统登录用户）
     */
    private String updatedBy;

    /**
     * 记录创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间（每次修改 content 时由 DB 触发更新）
     */
    private LocalDateTime updatedAt;
}
