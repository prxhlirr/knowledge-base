package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbIndexAclSubject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KbIndexAclSubjectMapper extends BaseMapper<KbIndexAclSubject> {

    @Select("SELECT * FROM kb_index_acl_subjects " +
            "WHERE index_name = #{indexName} AND scope = #{scope} AND is_active = 1 " +
            "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) " +
            "ORDER BY CASE WHEN effect = 'DENY' THEN 0 ELSE 1 END, id ASC")
    List<KbIndexAclSubject> findActiveByIndexAndScope(String indexName, String scope);

    @Select("SELECT * FROM kb_index_acl_subjects " +
            "WHERE index_name = #{indexName} AND is_active = 1 " +
            "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) " +
            "ORDER BY id DESC")
    List<KbIndexAclSubject> findActiveByIndex(String indexName);
}
