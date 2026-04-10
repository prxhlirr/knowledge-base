package com.boyang.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 操作日志异步线程池配置。
 *
 * 业务功能：
 *   为 OperationLogAspect 的异步写库操作提供专属线程池，
 *   与 SearchController 的 SEARCH_EXECUTOR 和 LOG_EXECUTOR 完全隔离，
 *   避免日志写库任务与搜索任务竞争线程资源。
 *
 * 关键设计：
 *   - Bean 名称 "operationLogExecutor" 与 @Async("operationLogExecutor") 对应
 *   - corePoolSize = 3：日志写入属于 IO 密集型，3 个线程足够应对正常并发
 *   - 使用 CallerRunsPolicy：队列满时由调用者线程（Tomcat Worker）直接执行写库，
 *     避免业务日志静默丢失（比 AbortPolicy 更安全）
 *   - 守护线程：JVM 退出时自动回收，无孤儿线程风险
 */
@EnableAsync
@Configuration
public class OperationLogAsyncConfig {

    /** 操作日志写入线程池大小（可通过 application.yml 调整）*/
    @Value("${log.operation.async-pool-size:3}")
    private int asyncPoolSize;

    /**
     * 操作日志专属异步线程池。
     *
     * 业务功能：承载 @Async("operationLogExecutor") 标注的日志写库任务。
     * 关键参数：
     *   - corePoolSize = asyncPoolSize（默认 3）
     *   - maxPoolSize = asyncPoolSize * 2：突发流量时可临时扩容
     *   - queueCapacity = 200：队列积压超 200 再触发扩容逻辑
     *   - keepAlive = 60s：空闲核心线程保留 60s，减少频繁创建销毁开销
     *   - CallerRunsPolicy：队列满时退回调用者线程执行，不丢弃日志
     *
     * @return Spring 管理的 Executor，供 @Async 框架注入使用
     */
    @Bean("operationLogExecutor")
    public Executor operationLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncPoolSize);
        executor.setMaxPoolSize(asyncPoolSize * 2);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("op-log-writer-");
        // CallerRunsPolicy：队列满时由调用者线程直接执行，保证日志不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
