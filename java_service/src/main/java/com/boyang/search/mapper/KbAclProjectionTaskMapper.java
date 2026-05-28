package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.KbAclProjectionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KbAclProjectionTaskMapper extends BaseMapper<KbAclProjectionTask> {

    @Select("SELECT * FROM kb_acl_projection_task " +
            "WHERE status IN ('PENDING','FAILED') AND next_retry_at <= #{now} " +
            "ORDER BY next_retry_at ASC, id ASC LIMIT #{limit}")
    List<KbAclProjectionTask> findRetryable(LocalDateTime now, int limit);
}
