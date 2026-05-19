package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.DocVersionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 文档版本历史 Mapper。
 * 业务功能：提供版本记录的写入与查询接口，配合 ES content_hash 字段实现内容去重和版本管理。
 */
@Mapper
public interface DocVersionHistoryMapper extends BaseMapper<DocVersionHistory> {

    /**
     * 查询指定文档的所有历史版本，按版本号倒序。
     * 用于管理端历史版本列表展示。
     *
     * @param sourceName 文档名称
     * @return 版本历史列表（最新版本在前）
     */
    @Select("SELECT * FROM doc_version_history WHERE source_name = #{sourceName} ORDER BY doc_version DESC")
    List<DocVersionHistory> findBySourceName(@Param("sourceName") String sourceName);

    /**
     * 查询指定内容哈希对应的最新版本记录（用于去重判断）。
     *
     * @param contentHash 正文前 2000 字 MD5
     * @param excludeSource 排除的文档名称（允许同名文档更新）
     * @return 匹配的版本记录（若存在，说明内容重复）
     */
    @Select("SELECT * FROM doc_version_history WHERE content_hash = #{contentHash} AND source_name != #{excludeSource} AND doc_version = (SELECT MAX(doc_version) FROM doc_version_history WHERE content_hash = #{contentHash}) LIMIT 1")
    DocVersionHistory findDuplicateByHash(@Param("contentHash") String contentHash, @Param("excludeSource") String excludeSource);

    /**
     * 查询指定文档当前最大版本号，用于计算下一个版本号。
     *
     * @param sourceName 文档名称
     * @return 当前最大版本号，无记录时返回 null
     */
    @Select("SELECT MAX(doc_version) FROM doc_version_history WHERE source_name = #{sourceName}")
    Integer findMaxVersion(@Param("sourceName") String sourceName);
}
