package com.boyang.search.job;

import com.boyang.search.entity.KbAclProjectionTask;
import com.boyang.search.mapper.KbAclProjectionTaskMapper;
import com.boyang.search.service.DocAclProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ACL 投影重试任务。
 *
 * <p>该任务定期扫描失败的 ES ACL 投影任务，并按 next_retry_at 进行补偿。
 * 任务失败不会影响 MySQL 权威权限判断，只影响 ES 召回层的实时性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AclProjectionRetryJob {

    private final KbAclProjectionTaskMapper taskMapper;
    private final DocAclProjectionService projectionService;

    @Scheduled(fixedDelayString = "${kb.acl-projection.retry-fixed-delay-ms:60000}",
            initialDelayString = "${kb.acl-projection.retry-initial-delay-ms:30000}")
    public void retryFailedProjectionTasks() {
        List<KbAclProjectionTask> tasks = taskMapper.findRetryable(LocalDateTime.now(), 50);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        log.info("[AclProjectionRetryJob] retrying {} ACL projection tasks", tasks.size());
        for (KbAclProjectionTask task : tasks) {
            try {
                task.setStatus("RUNNING");
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.updateById(task);
                projectionService.retryTask(task);
            } catch (Exception e) {
                log.warn("[AclProjectionRetryJob] retry task failed id={} err={}", task.getId(), e.getMessage());
            }
        }
    }
}
