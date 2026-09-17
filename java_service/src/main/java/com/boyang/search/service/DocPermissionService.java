package com.boyang.search.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.boyang.search.entity.DocPermissionEvent;
import com.boyang.search.entity.DocVersionHistory;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.mapper.DocPermissionEventMapper;
import com.boyang.search.mapper.DocVersionHistoryMapper;
import com.boyang.search.utils.DocumentTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档权限事件服务层。
 * 业务功能：管理文档权限变更事件的写入和版本历史记录，并把权限变更统一交给文档注册中心闭环处理。
 * 关键流程：
 *   1. 入库完成后，Python 侧调用 recordIngestion() 写入版本记录和授权事件。
 *   2. 文档经手时，调用 addHandlerEvent() 追加经手人事件。
 *   3. 可见度变更时，调用 addVisibilityChange()：
 *      - 追加 MySQL 变更事件（审计日志，不可变）
 *      - 调用 KbDocRegistryService.updateMeta，重建 ACL subject 并同步 ES 权限投影
 *      - 触发 SearchCacheService 清除相关缓存（P2.11 修复）
 * 设计原则：事件只追加不修改，所有历史均可回溯；MySQL 是权限权威，ES 只作为可补偿投影。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocPermissionService extends ServiceImpl<DocPermissionEventMapper, DocPermissionEvent> {

    private final DocPermissionEventMapper eventMapper;
    private final DocVersionHistoryMapper  versionMapper;
    private final SearchCacheService       searchCacheService;
    private final KbDocRegistryService     registryService;

    /**
     * 文档入库完成后写入版本历史和初始权限事件。
     * 流程：插入版本历史 → 生成 HANDLER 事件 → 生成 GRANT DEPT 事件（DEPT 类型时）
     *
     * @param sourceName  文档名称（对应 ES metadata.source）
     * @param docVersion  本次入库版本号
     * @param contentHash 正文前 2000 字 MD5
     * @param chunkCount  切片总数
     * @param uploaderId  上传者ID
     * @param visibility  可见度（PUBLIC/INTERNAL/DEPT/PRIVATE/GRANT）
     * @param deptCode    文档归属部门编码（12位，DEPT 类型时非空）
     */
    @Transactional
    public void recordIngestion(String sourceName, int docVersion, String contentHash,
                                int chunkCount, String uploaderId,
                                String visibility, String deptCode) {
        sourceName = DocumentTextNormalizer.normalizeFilename(sourceName);
        // 幂等保护：同名文档同版本号已存在时跳过插入，避免重复回调触发唯一约束异常
        Integer existingMax = versionMapper.findMaxVersion(sourceName);
        boolean alreadyRecorded = existingMax != null && existingMax >= docVersion;
        if (!alreadyRecorded) {
            DocVersionHistory history = new DocVersionHistory();
            history.setSourceName(sourceName);
            history.setDocVersion(docVersion);
            history.setContentHash(contentHash);
            history.setChunkCount(chunkCount);
            history.setOperatorId(uploaderId);
            history.setVisibility(visibility);
            history.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(history);
        } else {
            System.out.printf("[PermEvent] 幂等跳过：'%s' v%d 版本记录已存在%n", sourceName, docVersion);
        }

        if (uploaderId != null && !uploaderId.trim().isEmpty()) {
            addEvent(sourceName, "HANDLER", "USER", uploaderId, uploaderId, "文档入库操作人");
        }
        if ("DEPT".equals(visibility) && deptCode != null && !deptCode.trim().isEmpty()) {
            addEvent(sourceName, "GRANT", "DEPT", deptCode, uploaderId, "入库时按部门授权");
        }
        System.out.printf("[PermEvent] 文档 '%s' v%d 权限事件已记录%n", sourceName, docVersion);
    }

    /**
     * 追加经手人事件（文档被新的用户/部门操作时调用）。
     */
    @Transactional
    public void addHandlerEvent(String docId, String handlerId, String handlerDept, String operatorId) {
        if (handlerId != null) addEvent(docId, "HANDLER", "USER", handlerId, operatorId, "文档经手记录");
        if (handlerDept != null) addEvent(docId, "HANDLER", "DEPT", handlerDept, operatorId, "经手人所在部门");
    }

    /**
     * 业务功能：变更文档可见度，并复用注册中心的权限闭环。
     * 关键流程：查询最新版 registry → 校验 DEPT 必备 deptCode → updateMeta → 追加审计事件 → 清缓存。
     * 设计原因：旧实现只直写 ES metadata.visibility，会绕过 ACL subject、acl_tokens 覆盖投影和单位链同步；
     *          权限变更必须以 MySQL registry 为权威入口，ES 只能作为最终一致投影。
     */
    @Transactional
    public void addVisibilityChange(String docId, String newVisibility, String deptCode, String operatorId) {
        if (docId == null || docId.trim().isEmpty()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        if (newVisibility == null || newVisibility.trim().isEmpty()) {
            throw new IllegalArgumentException("visibility 不能为空");
        }
        KbDocRegistry doc = registryService.findLatest(docId);
        if (doc == null || "DELETED".equalsIgnoreCase(doc.getStatus())) {
            throw new IllegalArgumentException("文档不存在或已删除: " + docId);
        }

        Map<String, String> fields = new HashMap<>();
        fields.put("visibility", newVisibility.trim().toUpperCase());
        if (deptCode != null && !deptCode.trim().isEmpty()) {
            fields.put("deptCode", deptCode.trim());
        }
        if ("DEPT".equalsIgnoreCase(newVisibility)) {
            String finalDeptCode = fields.containsKey("deptCode") ? fields.get("deptCode") : doc.getDeptCode();
            if (finalDeptCode == null || finalDeptCode.trim().isEmpty()) {
                throw new IllegalArgumentException("visibility=DEPT 时 deptCode 不能为空");
            }
        }

        boolean ok = registryService.updateMeta(doc.getId(), fields);
        if (!ok) {
            throw new IllegalStateException("文档权限元数据更新失败: " + docId);
        }

        addEvent(docId, "VISIBILITY_CHANGE", "USER", operatorId, operatorId,
                 "可见度变更 → " + newVisibility);
        log.info("[Visibility] docId='{}' 权限闭环变更完成 → {} by {}", docId, newVisibility, operatorId);

        try {
            searchCacheService.invalidateByDocSource(docId);
        } catch (Exception e) {
            log.warn("[Visibility] 缓存清除失败 docId='{}' err={}", docId, e.getMessage());
        }
    }

    public void addVisibilityChange(String docId, String newVisibility, String operatorId) {
        addVisibilityChange(docId, newVisibility, null, operatorId);
    }


    /**
     * 查询指定文档的完整权限历史事件链。
     */
    public List<DocPermissionEvent> getEventHistory(String docId) {
        return eventMapper.findByDocId(docId);
    }

    /**
     * 查询指定文档的版本列表。
     */
    public List<DocVersionHistory> getVersionHistory(String sourceName) {
        return versionMapper.findBySourceName(sourceName);
    }

    private void addEvent(String docId, String action, String targetType,
                          String targetValue, String operatorId, String remark) {
        DocPermissionEvent evt = new DocPermissionEvent();
        evt.setDocId(docId);
        evt.setAction(action);
        evt.setTargetType(targetType);
        evt.setTargetValue(targetValue);
        evt.setOperatorId(operatorId != null ? operatorId : "system");
        evt.setRemark(remark);
        evt.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(evt);
    }
}
