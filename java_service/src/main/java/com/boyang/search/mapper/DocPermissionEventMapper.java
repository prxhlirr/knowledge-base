package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.DocPermissionEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 文档权限事件溯源 Mapper。
 * 业务功能：提供权限事件的写入与查询接口。
 * 关键约束：本表只追加，查询时按 docId 和时间排序获取完整事件链。
 */
@Mapper
public interface DocPermissionEventMapper extends BaseMapper<DocPermissionEvent> {

    /**
     * 按文档ID查询所有权限事件，按时间升序排列，用于权限历史回溯。
     *
     * @param docId 文档标识
     * @return 该文档的完整权限变更历史
     */
    @Select("SELECT * FROM doc_permission_events WHERE doc_id = #{docId} ORDER BY created_at ASC")
    List<DocPermissionEvent> findByDocId(@Param("docId") String docId);

    /**
     * 按经手人ID查询其参与处理的所有文档（用于个人权限范围计算）。
     *
     * @param userId 用户ID
     * @return 该用户参与处理的文档权限事件列表
     */
    @Select("SELECT * FROM doc_permission_events WHERE target_type = 'USER' AND target_value = #{userId} ORDER BY created_at DESC")
    List<DocPermissionEvent> findByHandlerUserId(@Param("userId") String userId);
}
