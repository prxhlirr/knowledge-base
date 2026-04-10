package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档版本历史记录实体。
 * 业务功能：记录每次文档入库的版本号、内容哈希、操作人等元信息。
 * 关键字段：
 *   - sourceName：文档名称（与 ES metadata.source 对应）
 *   - docVersion：版本号（从 1 开始自增，与 ES doc_version 字段一致）
 *   - contentHash：正文前 2000 字 MD5（供内容去重查询使用）
 * 设计约束：(sourceName, docVersion) 联合唯一，不允许重复版本。
 */
@Data
@TableName("doc_version_history")
public class DocVersionHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档名称（与 ES metadata.source 字段一致） */
    private String sourceName;

    /** 版本号（自增整数，从 1 开始） */
    private Integer docVersion;

    /** 正文前 2000 字 MD5，用于内容去重 */
    private String contentHash;

    /** 该版本的 chunk 总数 */
    private Integer chunkCount;

    /** 操作人ID（上传者） */
    private String operatorId;

    /** 该版本可见度（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT） */
    private String visibility;

    /** 版本创建时间（毫秒精度） */
    private LocalDateTime createdAt;
}
