package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 政务同义词词典实体。
 *
 * 业务功能：
 *   支撑 SearchService 的 GovAbbrExpander 工具，在 BM25 查询时将缩略词展开为完整词列表，
 *   消除政务领域"词汇鸿沟"（如"环评" → "环境影响评价"）。
 *
 * 设计决策：
 *   - full_terms 使用逗号分隔的字符串而非 JSON，兼顾简洁性与 LIKE 查询的便利性。
 *   - enabled 软禁用替代物理删除，保留历史词条审计能力。
 *   - synonym_type 区分单向展开（ABBR）和双向同义（EQUIV），
 *     EQUIV 类型在搜索时会同时展开每个词到其他所有词。
 */
@Data
@TableName("sys_gov_synonyms")
public class SysGovSynonym {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 缩略词/触发词（如"环评"），查询时的匹配键。
     * 唯一约束，不允许重复。
     */
    private String abbr;

    /**
     * 展开后的完整词列表，逗号分隔（如"环境影响评价,环境评价"）。
     * SearchService 使用时会 split(",") 展开为多个 should 子句。
     */
    private String fullTerms;

    /**
     * 同义词类型：
     *   ABBR  - 缩略语单向展开（abbr → fullTerms，不反向）
     *   EQUIV - 等价词双向展开（abbr 与 fullTerms 中每个词相互等价）
     */
    private String synonymType;

    /**
     * 启用状态：1=启用，0=停用。
     * SearchService 加载时只加载 enabled=1 的词条。
     */
    private Integer enabled;

    /**
     * 备注（数据来源、适用范围等），供管理员在后台 UI 查看。
     */
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
