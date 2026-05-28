package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbDocAclSubject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KbDocAclSubjectMapper extends BaseMapper<KbDocAclSubject> {

    @Select("SELECT * FROM kb_doc_acl_subjects " +
            "WHERE source_name = #{sourceName} " +
            "  AND is_active = 1 " +
            "  AND scope = #{scope} " +
            "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    List<KbDocAclSubject> findActiveBySourceAndScope(@Param("sourceName") String sourceName,
                                                     @Param("scope") String scope);

    @Select("SELECT * FROM kb_doc_acl_subjects " +
            "WHERE source_name = #{sourceName} " +
            "  AND is_active = 1 " +
            "ORDER BY created_at DESC")
    List<KbDocAclSubject> findActiveBySource(@Param("sourceName") String sourceName);

    @Select("SELECT COUNT(1) FROM kb_doc_acl_subjects " +
            "WHERE source_name = #{sourceName} " +
            "  AND subject_type = #{subjectType} " +
            "  AND subject_value = #{subjectValue} " +
            "  AND scope = #{scope} " +
            "  AND effect = #{effect} " +
            "  AND is_active = 1 " +
            "  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
    int countActiveSubject(@Param("sourceName") String sourceName,
                           @Param("subjectType") String subjectType,
                           @Param("subjectValue") String subjectValue,
                           @Param("scope") String scope,
                           @Param("effect") String effect);
}
