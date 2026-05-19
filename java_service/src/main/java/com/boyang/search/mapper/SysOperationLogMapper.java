package com.boyang.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.SysOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 操作日志 Mapper 接口。
 *
 * 业务功能：
 *   继承 MyBatis-Plus BaseMapper，自动获得标准 CRUD 能力（insert/selectById/updateById/deleteById）。
 *   自定义分页条件查询方法供 OperationLogController 管理接口使用，支持多维度复合过滤。
 *
 * 查询场景：
 *   - 按操作人（userId）查看个人操作历史
 *   - 按机构（deptCode）审计部门操作记录
 *   - 按模块（module）查看功能使用情况
 *   - 按时间段过滤，结合 success 字段定位异常操作
 *   - 按服务来源（serviceName）分别查看 Java/AI 两侧日志
 */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /**
     * 多条件分页查询操作日志。
     *
     * 业务功能：支持管理界面的复合条件查询，所有条件均为可选（null 则不过滤）。
     * 关键方法：调用对应 XML 中的动态 SQL（<if> 标签控制条件拼接）。
     * 流程说明：
     *   1. 接收分页对象（current/size）和查询条件 DTO
     *   2. 按存在的条件构建 WHERE 子句（动态 AND 拼接）
     *   3. 按 created_at DESC 排序，保证最新日志优先展示
     *
     * @param page   MyBatis-Plus 分页对象（含 current、size 属性）
     * @param userId    操作人 ID（模糊匹配，null 则不过滤）
     * @param module    操作模块（精确匹配，null 则不过滤）
     * @param deptCode  机构代码（精确匹配，null 则不过滤）
     * @param serviceName 服务来源（精确匹配，null 则不过滤）
     * @param success   成功标志（true/false，null 则不过滤）
     * @param startTime 开始时间（ISO 字符串，null 则不限下界）
     * @param endTime   结束时间（ISO 字符串，null 则不限上界）
     * @return 分页结果，含 records（当前页数据）、total（总数）等
     */
    Page<SysOperationLog> selectPageByCondition(
            Page<SysOperationLog> page,
            @Param("userId")      String userId,
            @Param("module")      String module,
            @Param("deptCode")    String deptCode,
            @Param("serviceName") String serviceName,
            @Param("success")     Boolean success,
            @Param("startTime")   String startTime,
            @Param("endTime")     String endTime
    );
}
