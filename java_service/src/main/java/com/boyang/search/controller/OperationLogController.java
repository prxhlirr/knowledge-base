package com.boyang.search.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.SysOperationLog;
import com.boyang.search.security.PermissionGuard;
import com.boyang.search.service.SysOperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志管理查询接口。
 *
 * 业务功能：
 *   为后台管理界面提供操作日志的多条件分页查询能力，支持以下检索维度：
 *   - 按操作人 ID（userId）检索个人操作历史
 *   - 按操作模块（module）统计功能使用情况
 *   - 按机构代码（deptCode）做部门审计
 *   - 按服务来源（serviceName）分别查看 Java/AI 两侧日志
 *   - 按成功标志（success）快速定位失败操作
 *   - 按时间段（startTime/endTime）收窄查询范围
 *
 * 安全说明：
 *   所有接口均在 /api/v1/admin/ 路径下，由 JwtAuthInterceptor 统一要求 X-Internal-Token 鉴权。
 *   无需在 Controller 层重复校验，保持接口简洁。
 */
@RestController
@RequestMapping("/api/v1/admin/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final SysOperationLogService operationLogService;
    private final PermissionGuard        permissionGuard;

    /**
     * 多条件分页查询操作日志。
     *
     * 业务功能：管理员可按多维度复合条件检索操作日志，所有过滤参数均为可选。
     * 关键方法：SysOperationLogService.queryPage()，底层使用动态 SQL 拼接条件。
     * 流程说明：
     *   1. 校验 X-Internal-Token（通过 PermissionGuard.isValidInternalToken）
     *   2. 将请求参数透传至 Service 层查询
     *   3. 返回带分页信息的结果（records/total/pages/current/size）
     *
     * @param current     当前页码（默认 1）
     * @param size        每页条数（默认 20，最大 100）
     * @param userId      操作人 ID（模糊匹配，可选）
     * @param module      操作模块（精确匹配，可选）
     * @param deptCode    机构代码（精确匹配，可选）
     * @param serviceName 服务来源：java_service / ai_service（精确匹配，可选）
     * @param success     是否成功：true/false（可选）
     * @param startTime   开始时间，格式：yyyy-MM-dd HH:mm:ss（可选）
     * @param endTime     结束时间，格式：yyyy-MM-dd HH:mm:ss（可选）
     * @return 分页结果，code=200 时 data 字段含 Page 对象
     */
    @GetMapping("/page")
    public Map<String, Object> pageQuery(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1")   long    current,
            @RequestParam(defaultValue = "20")  long    size,
            @RequestParam(required = false)     String  userId,
            @RequestParam(required = false)     String  module,
            @RequestParam(required = false)     String  deptCode,
            @RequestParam(required = false)     String  serviceName,
            @RequestParam(required = false)     Boolean success,
            @RequestParam(required = false)     String  startTime,
            @RequestParam(required = false)     String  endTime) {

        Map<String, Object> res = new HashMap<>();

        // 鉴权校验：管理接口统一要求 X-Internal-Token
        if (!permissionGuard.isValidInternalToken(request.getHeader("X-Internal-Token"))) {
            res.put("code", 401);
            res.put("msg", "未授权访问，需携带有效内部服务凭证（X-Internal-Token）");
            return res;
        }

        // 参数安全校验：防止 size 过大导致全表扫描
        size = Math.min(size, 100);

        try {
            Page<SysOperationLog> page = operationLogService.queryPage(
                    current, size, userId, module, deptCode, serviceName, success, startTime, endTime);
            res.put("code", 200);
            res.put("data", page);
        } catch (Exception e) {
            res.put("code", 500);
            res.put("msg", "查询操作日志失败: " + e.getMessage());
        }
        return res;
    }
}
