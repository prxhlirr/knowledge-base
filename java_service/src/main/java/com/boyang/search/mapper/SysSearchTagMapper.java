package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.SysSearchTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务功能：人工打标记录的数据访问层，负责持久化写入
 */
@Mapper
public interface SysSearchTagMapper extends BaseMapper<SysSearchTag> {
}
