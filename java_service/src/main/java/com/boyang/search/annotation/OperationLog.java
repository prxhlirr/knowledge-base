package com.boyang.search.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解——标记需要记录操作日志的 Controller 方法。
 *
 * 业务功能：
 *   被此注解标记的方法，将被 OperationLogAspect 拦截并自动完成以下操作：
 *   1. 记录操作人（userId/deptCode/deptName，来自 JWT ThreadLocal）
 *   2. 记录请求入参快照（JSON，可配置截断长度）
 *   3. 记录响应体快照（JSON，可配置截断长度）
 *   4. 记录耗时（ms）
 *   5. 记录操作模块和操作名称（由注解属性声明）
 *   6. 异步写入 sys_operation_log 表，不阻塞主链路
 *
 * 使用示例：
 *   @OperationLog(module = "文档管理", operation = "删除文档")
 *   @DeleteMapping("/admin/docs/{id}")
 *   public Map<String, Object> deleteDoc(...) { ... }
 *
 * 注意事项：
 *   - 此注解只应用于 Controller 层方法，不适用于 Service/Mapper 层
 *   - 对于含敏感数据的参数（如密码、Token），建议设置 recordRequest = false
 *   - 对于返回体超大（如文件下载）的接口，建议设置 recordResponse = false
 */
@Target(ElementType.METHOD)         // 只允许标注在方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时保留，供 AOP 反射读取
public @interface OperationLog {

    /**
     * 操作模块名称（中文，用于管理界面分类过滤）。
     * 建议值：「文档管理」「搜索」「标签管理」「同义词管理」「文档处理」「系统配置」
     */
    String module() default "";

    /**
     * 具体操作名称（中文，准确描述该接口的业务动作）。
     * 建议值：「混合检索」「删除文档」「新增标签」「触发导入」「软重试」
     */
    String operation() default "";

    /**
     * 是否记录请求入参快照（默认 true）。
     * 对于含敏感数据的接口（如修改密码），应设置为 false。
     */
    boolean recordRequest() default true;

    /**
     * 是否记录响应体快照（默认 true）。
     * 对于大体积响应（如文件下载、批量数据导出），应设置为 false，避免大字段写库。
     */
    boolean recordResponse() default true;
}
