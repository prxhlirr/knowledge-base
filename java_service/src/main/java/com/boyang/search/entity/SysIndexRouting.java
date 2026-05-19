package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态索引路由实体类
 *
 * 业务功能：映射由业务传来的中文 tag 到目标 Elasticsearch 分区索引。
 *
 * 对应数据库表：sys_index_routing
 */
@Data
@TableName("sys_index_routing")
public class SysIndexRouting {

    /**主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务标准分类编码，如 LAW, NEWS, OFFICIAL */
    private String tagCode;

    /** 中文匹配词，兼容旧版或者直接使用中文传递的内容 */
    private String tagName;

    /** Elasticsearch 的目标物理索引名 */
    private String targetIndex;

    /** 启停状态：1启用 0禁用 */
    private Integer isActive;

    /** 描述备注 */
    private String description;

    /** 乐观锁版本号控制，防止并发修改污染路由数据 */
    @Version
    private Integer version;

    /** 创建人 */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新人 */
    private String updateBy;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
