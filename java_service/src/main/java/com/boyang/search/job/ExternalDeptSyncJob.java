package com.boyang.search.job;

import com.boyang.search.service.DeptTreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 外部部门树定时同步任务（ExternalDeptSyncJob）。
 * 业务功能：在应用启动后及每隔指定时间，从外部机构管理系统同步最新部门层级数据，
 *           保证权限判断中的部门上下级关系始终与企业组织结构一致。
 * 关键设计：
 *   - @ApplicationReadyEvent：应用启动完全就绪后才触发首次同步（保证 Spring Bean 已全部初始化）
 *   - @Scheduled：每小时执行一次增量刷新（默认，可通过 external.org.sync-cron 调整）
 *   - 同步失败不影响应用正常运行（DeptTreeService 保留上一次缓存）
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ExternalDeptSyncJob {

    private final DeptTreeService deptTreeService;

    /** 是否启用定时同步（开发环境可关闭） */
    @Value("${external.org.sync.enabled:true}")
    private boolean syncEnabled;

    /**
     * 应用启动就绪后执行首次同步。
     * 使用 ApplicationReadyEvent 而非 @PostConstruct，
     * 确保所有 Bean（含 Redis、RestTemplate）都已初始化再发起外部 HTTP 请求。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        if (!syncEnabled) {
            log.info("[DeptSync] 定时同步已禁用（external.org.sync.enabled=false）");
            return;
        }
        log.info("[DeptSync] 应用启动完成，触发首次部门树同步...");
        try {
            deptTreeService.syncDeptTree();
        } catch (Exception e) {
            log.error("[DeptSync] 首次同步失败，权限判断将降级为前缀匹配 err={}", e.getMessage());
        }
    }

    /**
     * 每小时定时刷新部门树（默认每小时整点执行）。
     * 可通过 external.org.sync-cron 配置 Cron 表达式调整频率。
     * 示例：
     *   每30分钟：0 0/30 * * * ?
     *   每天凌晨2点：0 0 2 * * ?
     */
    @Scheduled(cron = "${external.org.sync-cron:0 0 * * * ?}")
    public void scheduledSync() {
        if (!syncEnabled) return;
        log.info("[DeptSync] 定时触发部门树同步...");
        try {
            deptTreeService.syncDeptTree();
        } catch (Exception e) {
            log.error("[DeptSync] 定时同步失败 err={}", e.getMessage());
        }
    }
}
