package com.boyang.search.service;

import com.boyang.search.entity.KbSensitivePolicy;
import com.boyang.search.entity.KbSensitivePolicyHitLog;
import com.boyang.search.mapper.KbSensitivePolicyHitLogMapper;
import com.boyang.search.security.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 敏感策略命中审计服务。
 *
 * <p>审计写入异步执行，失败只记录 warn，不影响搜索、预览或问答主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivePolicyAuditService {

    private final KbSensitivePolicyHitLogMapper mapper;

    @Async
    public void recordHit(KbSensitivePolicy policy,
                          String stage,
                          JwtVerifier.UserIdentity identity,
                          String fieldName,
                          String sourceName,
                          String docId,
                          int hitCount,
                          String traceId) {
        if (policy == null) {
            return;
        }
        try {
            KbSensitivePolicyHitLog row = new KbSensitivePolicyHitLog();
            row.setPolicyId(policy.getId());
            row.setStage(stage);
            row.setAction(policy.getAction());
            row.setFieldName(fieldName);
            row.setSourceName(sourceName);
            row.setDocId(docId);
            row.setHitCount(Math.max(1, hitCount));
            row.setTraceId(traceId);
            if (identity != null) {
                row.setUserId(identity.getUserId());
                row.setAppCode(identity.getAppCode());
            }
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("[SensitivePolicyAudit] write hit log failed policyId={} err={}",
                    policy.getId(), e.getMessage());
        }
    }
}
