package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.KbDocRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档注册中心 Mapper。
 * 业务功能：操作 kb_doc_registry 表，提供文档注册、版本管理、分页查询的持久化能力。
 * 关键方法：
 *   - markOlderVersionsNotLatest：新版本入库前将旧版 is_latest 置 0
 *   - findLatestBySourceName：按文档名获取最新版记录（快速去重判断）
 *   - pageLatest：分页查询当前有效文档列表（is_latest=1 + 关键词过滤）
 */
@Mapper
public interface KbDocRegistryMapper extends BaseMapper<KbDocRegistry> {

    /**
     * 将指定文档名的所有旧版本标记为非最新（is_latest=0）。
     * 在写入新版本之前调用，保证同一文档只有一条 is_latest=1 的记录。
     *
     * @param sourceName 文档名称
     * @return 影响行数
     */
    @Update("UPDATE kb_doc_registry SET is_latest = 0, updated_at = CURRENT_TIMESTAMP " +
            "WHERE source_name = #{sourceName} AND is_latest = 1")
    int markOlderVersionsNotLatest(@Param("sourceName") String sourceName);

    /**
     * 按文档名查询最新版本记录，用于获取当前版本号以计算下一个版本号。
     *
     * @param sourceName 文档名称
     * @return 最新版记录（不存在时返回 null）
     */
    @Select("SELECT * FROM kb_doc_registry WHERE source_name = #{sourceName} " +
            "AND status IN ('INDEXED','INDEXED_FULL','INDEXED_PARTIAL','PROCESSING') " +
            "ORDER BY doc_version DESC LIMIT 1")
    KbDocRegistry findLatestBySourceName(@Param("sourceName") String sourceName);

    /**
     * 分页查询文档列表（支持关键词搜索 + 状态筛选）。
     * 关键词匹配 source_name / unit / doc_number / tags。
     *
     * @param page     MyBatisPlus 分页对象
     * @param keyword  搜索关键词（为 null 或空则不过滤）
     * @param status   状态筛选（为 null 则不过滤，通常传 "INDEXED"）
     * @return 分页结果
     */
    @Select("<script>" +
            "SELECT * FROM kb_doc_registry WHERE is_latest = 1 " +
            "<if test='status != null and status != \"\"'> " +
            "  <choose> " +
            "    <when test='status == \"INDEXED\"'> AND status IN ('INDEXED','INDEXED_FULL','INDEXED_PARTIAL') </when> " +
            "    <otherwise> AND status = #{status} </otherwise> " +
            "  </choose> " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'> " +
            "  AND (source_name ILIKE CONCAT('%',#{keyword},'%') " +
            "    OR unit ILIKE CONCAT('%',#{keyword},'%') " +
            "    OR doc_number ILIKE CONCAT('%',#{keyword},'%') " +
            "    OR tags ILIKE CONCAT('%',#{keyword},'%')) " +
            "</if>" +
            "ORDER BY created_at DESC" +
            "</script>")
    IPage<KbDocRegistry> pageLatest(Page<KbDocRegistry> page,
                                    @Param("keyword") String keyword,
                                    @Param("status") String status);

    /**
     * 查询指定文档名的所有历史版本记录，按版本号倒序。
     *
     * @param sourceName 文档名称
     * @return 所有版本列表
     */
    @Select("SELECT * FROM kb_doc_registry WHERE source_name = #{sourceName} ORDER BY doc_version DESC")
    List<KbDocRegistry> findAllVersionsBySourceName(@Param("sourceName") String sourceName);
}
