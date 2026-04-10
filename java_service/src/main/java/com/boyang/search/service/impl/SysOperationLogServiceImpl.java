package com.boyang.search.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.SysOperationLog;
import com.boyang.search.mapper.SysOperationLogMapper;
import com.boyang.search.service.SysOperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务实现类。
 *
 * 业务功能：
 *   基于 MyBatis-Plus ServiceImpl 提供 CRUD 基础能力，
 *   在此基础上扩展异步写入（saveAsync）和多条件分页查询（queryPage）。
 *
 * 关键设计：
 *   - saveAsync 使用专属线程池 "operationLogExecutor"（在 SearchApplication 或独立 Config 中配置）
 *   - 写入失败仅打印 WARN，绝不向上抛出异常（防止日志写库失败影响业务链路）
 */
@Slf4j
@Service
public class SysOperationLogServiceImpl
        extends ServiceImpl<SysOperationLogMapper, SysOperationLog>
        implements SysOperationLogService {

    /**
     * 异步写入操作日志。
     *
     * 业务功能：将 AOP 切面构建的日志对象异步持久化到 sys_operation_log 表。
     * 关键方法：@Async("operationLogExecutor") 确保在专属线程池执行，不与搜索任务竞争线程资源。
     * 流程说明：
     *   1. Spring 接收调用后，立即将任务投递到 operationLogExecutor 线程池队列
     *   2. 主线程立即返回，不等待写库完成
     *   3. 异步线程调用 this.save(log)（MyBatis-Plus INSERT）
     *   4. 写库失败捕获异常，仅打印 WARN，不向外抛出
     *
     * @param log 完整的操作日志对象（由 OperationLogAspect 构建）
     */
    @Async("operationLogExecutor")
    @Override
    public void saveAsync(SysOperationLog operationLog) {
        try {
            this.save(operationLog);
        } catch (Exception e) {
            // 写库失败绝不能影响业务主链路，仅记录警告日志
            log.warn("[OperationLog] 日志写库失败，traceId={}, uri={}, err={}",
                    operationLog.getTraceId(), operationLog.getRequestUri(), e.getMessage());
        }
    }

    /**
     * 多条件分页查询操作日志。
     *
     * 业务功能：供后台管理接口按多维度检索操作记录。
     * 关键方法：调用 SysOperationLogMapper.selectPageByCondition() 动态 SQL。
     * 流程说明：
     *   1. 构建 MyBatis-Plus Page 对象（设置 current/size）
     *   2. 将所有过滤条件透传至 Mapper 层
     *   3. Mapper 层根据条件是否为 null 动态拼接 WHERE 子句
     *   4. 返回带分页信息（total/pages/records）的 Page 对象
     */
    @Override
    public Page<SysOperationLog> queryPage(
            long current,
            long size,
            String userId,
            String module,
            String deptCode,
            String serviceName,
            Boolean success,
            String startTime,
            String endTime) {

        Page<SysOperationLog> page = new Page<>(current, size);
        return baseMapper.selectPageByCondition(
                page, userId, module, deptCode, serviceName, success, startTime, endTime);
    }
}
