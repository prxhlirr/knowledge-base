package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.boyang.search.entity.SysGovSynonym;
import org.apache.ibatis.annotations.Mapper;

/**
 * 政务同义词词典 Mapper。
 *
 * 基于 MyBatis-Plus BaseMapper，提供标准 CRUD 操作。
 * 复杂查询通过 Wrapper 完成，无需额外 XML。
 */
@Mapper
public interface SysGovSynonymMapper extends BaseMapper<SysGovSynonym> {
}
