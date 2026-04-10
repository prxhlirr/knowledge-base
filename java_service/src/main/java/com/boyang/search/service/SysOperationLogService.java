package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.boyang.search.entity.SysOperationLog;

/**
 * 操作日志服务接口。
 *
 * 业务功能：
 *   提供操作日志的异步写入能力和多条件分页查询能力，
 *   供 OperationLogAspect（AOP 切面）和 OperationLogController（管理接口）使用。
 *
 * 核心设计原则：
 *   - saveAsync：@Async 异步执行，写库失败不影响业务主链路
 *   - queryPage：同步执行，供管理接口实时查询使用
 */
public interface SysOperationLogService extends IService<SysOperationLog> {

    /**
     * 异步保存操作日志（由 OperationLogAspect 切面自动调用）。
     *
     * 业务功能：将操作日志持久化到 sys_operation_log 表，与业务主链路完全异步隔离。
     * 关键方法：通过 @Async("operationLogExecutor") 在独立线程池执行，不占用 SEARCH_EXECUTOR 资源。
     * 流程说明：
     *   1. 由 AOP 切面在方法执行完毕（或异常捕获）后构建日志对象
     *   2. 调用此方法，Spring @Async 将其投递到 operationLogExecutor 线程池
     *   3. 异步线程调用 MyBatis-Plus save() 写库
     *   4. 写库异常仅打印 WARN 日志，不向上抛出，保证业务主链路不受影响
     *
     * @param log 由 OperationLogAspect 构建的完整日志对象
     */
    void saveAsync(SysOperationLog log);

    /**
     * 多条件分页查询操作日志（管理接口使用）。
     *
     * 业务功能：供管理员在后台按多维度检索操作记录，支持操作人/模块/机构/时间段/成功标志复合过滤。
     * 关键方法：调用 SysOperationLogMapper.selectPageByCondition() 动态 SQL。
     * 流程说明：
     *   1. 接收管理接口传入的各过滤条件
     *   2. 将所有条件透传至 Mapper 层的动态 SQL
     *   3. 返回 MyBatis-Plus Page 对象（含 records/total/pages 等分页信息）
     *
     * @param current     当前页码（从 1 开始）
     * @param size        每页条数（建议不超过 100）
     * @param userId      操作人 ID（模糊匹配，null 则不过滤）
     * @param module      操作模块（精确匹配，null 则不过滤）
     * @param deptCode    机构代码（精确匹配，null 则不过滤）
     * @param serviceName 服务来源（java_service/ai_service，null 则不过滤）
     * @param success     成功标志（null 则不过滤）
     * @param startTime   开始时间字符串（格式 yyyy-MM-dd HH:mm:ss，null 则不限下界）
     * @param endTime     结束时间字符串（格式 yyyy-MM-dd HH:mm:ss，null 则不限上界）
     * @return MyBatis-Plus 分页结果对象
     */
    Page<SysOperationLog> queryPage(
            long current,
            long size,
            String userId,
            String module,
            String deptCode,
            String serviceName,
            Boolean success,
            String startTime,
            String endTime
    );
}
