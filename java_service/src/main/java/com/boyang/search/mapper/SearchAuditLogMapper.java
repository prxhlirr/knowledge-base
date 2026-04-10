package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.SearchAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务功能：搜索审计日志 Mapper 接口
 */
@Mapper
public interface SearchAuditLogMapper extends BaseMapper<SearchAuditLog> {
}
