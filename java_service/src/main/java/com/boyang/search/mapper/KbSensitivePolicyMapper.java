package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbSensitivePolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KbSensitivePolicyMapper extends BaseMapper<KbSensitivePolicy> {

    @Select("SELECT * FROM kb_sensitive_policy " +
            "WHERE is_active = 1 " +
            "  AND (applies_to = 'ALL' OR applies_to = #{appliesTo}) " +
            "ORDER BY priority DESC, id ASC")
    List<KbSensitivePolicy> findActiveForStage(@Param("appliesTo") String appliesTo);
}
