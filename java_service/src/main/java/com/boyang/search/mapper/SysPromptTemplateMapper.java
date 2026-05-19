package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.boyang.search.entity.SysPromptTemplate;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Prompt 模板数据访问层
 *
 * 业务功能：为 sys_prompt_template 表提供 CRUD 访问能力。
 * 继承 BaseMapper 获得标准 CRUD 能力（insert/selectById/updateById/deleteById）。
 * 无需额外 XML，所有查询通过 MyBatis-Plus LambdaQueryWrapper 构建。
 *
 * 主要查询场景：
 *   1. AI 服务拉取全量激活 Prompt（内部接口，60s TTL 缓存）
 *   2. 管理界面按 scene 分组展示
 *   3. 单条按 prompt_key 精确查询/更新
 */
@Mapper
public interface SysPromptTemplateMapper extends BaseMapper<SysPromptTemplate> {
}
