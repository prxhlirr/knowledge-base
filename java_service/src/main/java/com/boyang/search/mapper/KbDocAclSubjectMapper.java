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

    /**
     * [性能优化] 批量查询多个文档的 ACL 主体规则。
     * 用于 PermissionGuard 后置过滤，将 N 次单条查询合并为 1 次 IN 查询。
     *
     * @param sourceNames 文档名称列表
     * @param scope       权限范围（如 "VIEW"）
     * @return 匹配的 ACL 主体记录列表
     */
    @Select("<script>" +
            "SELECT * FROM kb_doc_acl_subjects WHERE source_name IN " +
            "<foreach item='n' collection='list' open='(' separator=',' close=')'>#{n}</foreach> " +
            "AND is_active = 1 AND scope = #{scope} " +
            "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)" +
            "</script>")
    List<KbDocAclSubject> findActiveBySourcesAndScope(@Param("list") List<String> sourceNames,
                                                      @Param("scope") String scope);
}
