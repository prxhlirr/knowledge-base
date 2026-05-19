package com.boyang.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体类。
 *
 * 业务功能：
 *   映射到 sys_operation_log 表，记录所有业务操作的完整审计轨迹。
 *   Java 服务和 AI 服务共用此表，通过 serviceName 字段区分来源，
 *   通过 traceId 串联同一请求在两个服务中的日志记录。
 *
 * 字段分组：
 *   [链路标识] traceId、serviceName
 *   [操作描述] module、operation、method、requestUri
 *   [请求/响应] requestParams、responseData、statusCode
 *   [结果状态] success、errorMsg、costMs
 *   [操作人信息] userId、userName、deptCode、deptName（来自 JWT，冗余存储）
 *   [网络信息] clientIp
 *   [时间] createdAt
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跨服务请求链路 ID，Java 端由 JwtAuthInterceptor 写入 MDC，AI 端通过 X-Trace-Id Header 接收 */
    private String traceId;

    /** 日志来源服务：java_service / ai_service */
    private String serviceName;

    /** 操作模块（由 @OperationLog(module) 注解声明，如"文档管理"/"搜索"/"向量推理"）*/
    private String module;

    /** 具体操作名称（如"混合检索"/"删除文档"/"BGE向量化"）*/
    private String operation;

    /** HTTP 方法（GET/POST/DELETE/PUT）*/
    private String method;

    /** 请求路径（含路径参数，如 /api/v1/admin/docs/123）*/
    private String requestUri;

    /** 请求入参 JSON 快照（超出 request-max-bytes 时截断，末尾追加 [truncated]）*/
    private String requestParams;

    /** 响应体 JSON 快照（超出 response-max-bytes 时截断，末尾追加 [truncated]）*/
    private String responseData;

    /** HTTP 状态码（200/400/401/500 等）*/
    private Integer statusCode;

    /** 是否成功（无异常且非 5xx 状态码时为 true）*/
    private Boolean success;

    /** 异常信息（success=false 时填充，包含异常类型和消息）*/
    private String errorMsg;

    /** 接口耗时（毫秒，从方法入口到返回/异常的完整时长）*/
    private Integer costMs;

    /** 操作人用户 ID（来自 JWT claim userId；AI 服务内部调用时固定为 "system"）*/
    private String userId;

    /** 操作人姓名（来自 JWT claim，冗余存储，避免关联查询用户表）*/
    private String userName;

    /** 操作单位代码（来自 JWT claim deptCode）*/
    private String deptCode;

    /** 操作单位中文名称（来自 JWT claim deptName，已确认 JWT 含此字段）*/
    private String deptName;

    /** 客户端真实 IP（X-Forwarded-For 优先，无则取 RemoteAddr）*/
    private String clientIp;

    /** 记录时间（数据库层 DEFAULT NOW() 自动填充，无需应用层设置）*/
    private LocalDateTime createdAt;
}
