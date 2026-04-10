package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 业务功能：搜索结果的用户打标信息实体类
 * 流程说明：映射到 sys_search_tag 表，用于记录人工补充的标签和关键词，供后台管理同步到知识库向量网络。
 */
@Data
@TableName("sys_search_tag")
public class SysSearchTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ES中文档全局唯一标识符 */
    private String docId;

    /** 数据所在的索引名称 */
    private String indexName;

    /** 逗号分隔的标签字符串 */
    private String tags;

    /** 人工补充或修改的关键词段落 */
    private String keywords;

    /** 同步至ES与向量更新的状态 (0:未同步 1:已同步 2:同步失败) */
    private Integer syncStatus;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间 */
    private LocalDateTime updateTime;
}
