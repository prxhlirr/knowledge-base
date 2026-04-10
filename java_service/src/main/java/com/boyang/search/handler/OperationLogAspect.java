package com.boyang.search.handler;

import com.boyang.search.annotation.OperationLog;
import com.boyang.search.entity.SysOperationLog;
import com.boyang.search.security.JwtVerifier;
import com.boyang.search.security.UserContextHolder;
import com.boyang.search.service.SysOperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 操作日志 AOP 切面。
 *
 * 业务功能：
 *   拦截所有标注了 @OperationLog 注解的 Controller 方法，自动完成以下工作：
 *   1. 从 UserContextHolder（JWT ThreadLocal）读取操作人身份信息
 *   2. 序列化请求入参为 JSON 快照（可配置截断长度）
 *   3. 执行业务方法，计算总耗时
 *   4. 序列化响应体为 JSON 快照（可配置截断长度）
 *   5. 方法异常时捕获错误信息，标记 success=false
 *   6. 异步写入 sys_operation_log 表，不阻塞业务主链路
 *
 * 关键设计：
 *   - @Around 环绕通知：同时覆盖正常返回和异常两条路径
 *   - 从 RequestContextHolder 获取 HttpServletRequest，无需 Controller 显式注入 request 参数
 *   - traceId 优先从 MDC 读取（JwtAuthInterceptor 已生成），降级自生成，保证每条日志都有 traceId
 *   - deptName 从 JWT rawClaims 中读取，不污染 UserIdentity 现有接口
 *   - 异常时"先重新抛出"保证业务语义不变，日志写入在此之后异步执行
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    @Autowired
    private SysOperationLogService operationLogService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 响应体快照最大字节数（超出时截断，防止大字段写库） */
    @Value("${log.operation.response-max-bytes:4096}")
    private int responseMaxBytes;

    /** 请求入参快照最大字节数 */
    @Value("${log.operation.request-max-bytes:2048}")
    private int requestMaxBytes;

    /**
     * 环绕通知：拦截所有 @OperationLog 标注的 Controller 方法。
     *
     * 业务功能：在方法执行前后自动采集操作上下文，构建并异步写入操作日志。
     * 关键方法：ProceedingJoinPoint.proceed() 执行原始业务方法。
     * 流程说明：
     *   1. 提取注解属性（module/operation/recordXxx）
     *   2. 从 MDC 读取 traceId（JwtAuthInterceptor 生成），不存在时自生成
     *   3. 从 UserContextHolder 读取用户身份（userId/deptCode/deptName）
     *   4. 序列化请求入参（过滤掉 HttpServletRequest/Response 类型参数）
     *   5. 执行业务方法（try-catch 捕获所有异常）
     *   6. 计算耗时、序列化响应体
     *   7. 构建 SysOperationLog 并异步写入
     *   8. 方法正常/异常均写日志；异常时重新抛出，保证 Controller 层感知
     *
     * @param joinPoint AOP 连接点，含方法签名、参数、目标对象等信息
     * @return 业务方法的原始返回值
     * @throws Throwable 业务方法抛出的原始异常（原样重新抛出，不改变异常语义）
     */
    @Around("@annotation(com.boyang.search.annotation.OperationLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // ── 1. 提取注解属性 ──────────────────────────────────────────
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        OperationLog annotation = method.getAnnotation(OperationLog.class);

        // ── 2. 获取 traceId（优先 MDC，降级自生成）─────────────────────
        // 优先读取 JwtAuthInterceptor 已放入 MDC 的 traceId，
        // 若 MDC 中没有（如单元测试或直接调用场景），则自生成一个短 ID
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // ── 3. 获取 HttpServletRequest（供提取 IP、URI、Method）───────
        HttpServletRequest request = null;
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            request = attrs.getRequest();
        }

        // ── 4. 提取用户身份（ThreadLocal，已由 JwtAuthInterceptor 填充）──
        JwtVerifier.UserIdentity identity = UserContextHolder.getIdentity();
        String userId   = null;
        String deptCode = null;
        String deptName = null;
        String userName = null;

        if (identity != null) {
            userId   = identity.getUserId();
            deptCode = identity.getDeptCode();
            // deptName 和 userName 从 JWT rawClaims 直接读取（已确认 JWT 含此字段）
            Claims rawClaims = identity.getRawClaims();
            if (rawClaims != null) {
                Object dn = rawClaims.get("deptName");
                if (dn != null) deptName = dn.toString();
                Object un = rawClaims.get("userName");
                if (un == null) un = rawClaims.get("realName");
                if (un != null) userName = un.toString();
            }
        }

        // ── 5. 序列化请求入参（过滤不可序列化的 Servlet 对象）────────
        String requestParams = null;
        if (annotation.recordRequest()) {
            Object[] args = joinPoint.getArgs();
            List<Object> filteredArgs = new ArrayList<>();
            for (Object arg : args) {
                // 跳过 HttpServletRequest/Response，避免序列化时触发流读取或循环引用
                if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) {
                    continue;
                }
                filteredArgs.add(arg);
            }
            requestParams = truncate(serialize(filteredArgs.size() == 1
                    ? filteredArgs.get(0) : filteredArgs), requestMaxBytes);
        }

        // ── 6. 执行业务方法（捕获异常以便写日志，最终重新抛出）──────
        Object result = null;
        boolean success = true;
        String errorMsg = null;
        int statusCode = 200;

        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            success = false;
            statusCode = 500;
            errorMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            // 先完成日志写入再重新抛出，保证异常日志有记录
            long costMs = System.currentTimeMillis() - startTime;
            asyncSaveLog(buildLog(traceId, annotation, request, requestParams, null,
                    statusCode, success, errorMsg, (int) costMs,
                    userId, userName, deptCode, deptName));
            throw ex; // 原样重新抛出，Controller 正常感知异常
        }

        // ── 7. 序列化响应体（成功路径）───────────────────────────────
        String responseData = null;
        if (annotation.recordResponse() && result != null) {
            responseData = truncate(serialize(result), responseMaxBytes);
        }

        // ── 8. 异步写日志（成功路径）─────────────────────────────────
        long costMs = System.currentTimeMillis() - startTime;
        asyncSaveLog(buildLog(traceId, annotation, request, requestParams, responseData,
                statusCode, success, null, (int) costMs,
                userId, userName, deptCode, deptName));

        return result;
    }

    // ─── 私有工具方法 ─────────────────────────────────────────────────────────

    /**
     * 构建完整的操作日志对象。
     * 将所有采集到的上下文数据组装为 SysOperationLog 实体，供写库使用。
     */
    private SysOperationLog buildLog(
            String traceId,
            OperationLog annotation,
            HttpServletRequest request,
            String requestParams,
            String responseData,
            int statusCode,
            boolean success,
            String errorMsg,
            int costMs,
            String userId,
            String userName,
            String deptCode,
            String deptName) {

        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setTraceId(traceId);
        operationLog.setServiceName("java_service");
        operationLog.setModule(annotation.module());
        operationLog.setOperation(annotation.operation());
        operationLog.setRequestParams(requestParams);
        operationLog.setResponseData(responseData);
        operationLog.setStatusCode(statusCode);
        operationLog.setSuccess(success);
        operationLog.setErrorMsg(errorMsg);
        operationLog.setCostMs(costMs);
        operationLog.setUserId(userId != null ? userId : "anonymous");
        operationLog.setUserName(userName);
        operationLog.setDeptCode(deptCode);
        operationLog.setDeptName(deptName);

        if (request != null) {
            operationLog.setMethod(request.getMethod());
            operationLog.setRequestUri(request.getRequestURI());
            operationLog.setClientIp(extractClientIp(request));
        }

        return operationLog;
    }

    /**
     * 提交异步日志写入，异常时仅打印警告，不影响外层调用链。
     */
    private void asyncSaveLog(SysOperationLog operationLog) {
        try {
            operationLogService.saveAsync(operationLog);
        } catch (Exception e) {
            log.warn("[OperationLogAspect] 提交异步日志失败: {}", e.getMessage());
        }
    }

    /**
     * 将对象序列化为 JSON 字符串。
     * 序列化失败时返回对象的 toString()，确保日志不丢失。
     */
    private String serialize(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 截断字符串至指定字节数。
     * 超出时末尾追加 [truncated] 标记，便于使用者识别数据不完整。
     * 使用字节数而非字符数，更准确控制数据库存储大小（中文字符 UTF-8 编码为 3 字节）。
     */
    private String truncate(String text, int maxBytes) {
        if (text == null || text.isEmpty()) return text;
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        // 截断到 maxBytes 后追加标记（保留足够空间存放 [truncated]）
        int cutBytes = maxBytes - "[truncated]".length();
        return new String(Arrays.copyOf(bytes, cutBytes),
                java.nio.charset.StandardCharsets.UTF_8) + "[truncated]";
    }

    /**
     * 提取客户端真实 IP。
     * 优先读取 X-Forwarded-For（nginx/LB 反向代理透传），兜底取 RemoteAddr。
     * X-Forwarded-For 可能包含多个 IP（如：client, proxy1, proxy2），取第一个。
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty() && !"unknown".equalsIgnoreCase(xff)) {
            // 取逗号分隔列表中的第一个 IP（最原始的客户端 IP）
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
