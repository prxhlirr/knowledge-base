package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.mapper.KbDocRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档注册中心 Service。
 * 业务功能：管理 kb_doc_registry 表的核心生命周期，包括新版本注册、分页查询、
 * 逻辑删除和元数据更新。通过此 Service，MySQL 成为已入库文档的权威目录。
 * 关键流程：
 * 1. registerDoc：登记新版本；旧回调可同步切换 ES，新 Outbox 链路由 OutboxPoller 激活 ES
 * 2. deleteDoc：逻辑删除同时同步 ES，将对应文档所有 chunk 的 is_latest 置 false（P0 #11 修复）
 * 3. updateMeta：只更新非 ES 字段（tags/docNumber/unit），updated_at 同步刷新
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocRegistryService {

    private final KbDocRegistryMapper registryMapper;
    /** [P0 #11] 注入 ES 客户端，deleteDoc() 删除时同步将文档 chunk is_latest 置 false */
    private final ElasticsearchClient esClient;

    /**
     * 注册一个新版本文档。
     * 调用时机：Python rag_pipeline 成功 bulk 写入 ES 后，通过 Java 内部接口回调。
     * 事务保证：旧版本置 0 和新版本插入在同一个事务中，避免出现双 latest。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbDocRegistry registerDoc(String sourceName, int docVersion, String docId,
            String storagePath, String targetIndex, int chunkCount,
            String contentHash, String docNumber, String unit,
            String tags, String publishTime, String visibility,
            String deptCode, String uploaderId, String uploaderName) {
        return registerDoc(sourceName, docVersion, docId, storagePath, targetIndex, chunkCount,
            contentHash, docNumber, unit, tags, publishTime, visibility, deptCode, uploaderId,
            uploaderName, "INDEXED");
    }

    @Transactional(rollbackFor = Exception.class)
    public KbDocRegistry registerDoc(String sourceName, int docVersion, String docId,
            String storagePath, String targetIndex, int chunkCount,
            String contentHash, String docNumber, String unit,
            String tags, String publishTime, String visibility,
            String deptCode, String uploaderId, String uploaderName, String parseStatus) {
        return registerDoc(sourceName, docVersion, docId, storagePath, targetIndex, chunkCount,
            contentHash, docNumber, unit, tags, publishTime, visibility, deptCode, uploaderId,
            uploaderName, parseStatus, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public KbDocRegistry registerDoc(String sourceName, int docVersion, String docId,
            String storagePath, String targetIndex, int chunkCount,
            String contentHash, String docNumber, String unit,
            String tags, String publishTime, String visibility,
            String deptCode, String uploaderId, String uploaderName, String parseStatus,
            boolean activateEsImmediately) {

        if (activateEsImmediately) {
            // 旧版回调兼容路径：没有 OutboxPoller 兜底时，仍同步切换 ES 可见版本。
            try {
                UpdateByQueryRequest esReq = UpdateByQueryRequest.of(r -> r
                        .index(targetIndex != null ? targetIndex : "kb_document")
                        .query(q -> q.term(t -> t.field("metadata.source").value(sourceName)))
                        .script(s -> s.inline(i -> i
                                .source("if (ctx._source.metadata.doc_version != null && ctx._source.metadata.doc_version.toString().equals(params.ver.toString())) {"
                                        +
                                        "  ctx._source.metadata.is_latest = true;" +
                                        "} else {" +
                                        "  ctx._source.metadata.is_latest = false;" +
                                        "}")
                                .params("ver", co.elastic.clients.json.JsonData.of(docVersion))
                                .lang("painless")))
                        .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
                co.elastic.clients.elasticsearch.core.UpdateByQueryResponse resp = esClient.updateByQuery(esReq);
                log.info("[DocRegistry] ES 状态同步切换完成 source={} affected={}", sourceName, resp.updated());
            } catch (Exception e) {
                log.error("[DocRegistry] ES 状态切换失败，事务回滚 source={} err={}", sourceName, e.getMessage());
                throw new RuntimeException("ES 强同步操作失败，触发数据库回滚", e);
            }
        } else {
            log.info("[DocRegistry] ES 激活交由 OutboxPoller 处理 source={} v={}", sourceName, docVersion);
        }

        // Step 1b: QA 索引版本切换（与主索引 2PC 独立，失败降级为 warn 不触发回滚）
        // 逻辑：kb_qa_pairs 中 source=sourceName 且 doc_version==docVersion → is_latest=true，其余 → false
        // 存量保护：doc_version 为 null（回填前历史数据）时 Painless 直接 return，不误改为 false
        // 方案B补充：执行 backfill_qa_doc_version.py 后此 null 分支理论上不再触发
        try {
            UpdateByQueryRequest qaVersionReq = UpdateByQueryRequest.of(r -> r
                    .index("kb_qa_pairs")
                    .query(q -> q.term(t -> t.field("source").value(sourceName)))
                    .script(s -> s.inline(i -> i
                            .source(
                                "if (ctx._source.doc_version == null) { return; } " +
                                "if (params.ver.equals(ctx._source.doc_version)) { " +
                                "  ctx._source.is_latest = true; " +
                                "} else { " +
                                "  ctx._source.is_latest = false; " +
                                "}"
                            )
                            .params("ver", co.elastic.clients.json.JsonData.of(docVersion))
                            .lang("painless")))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse qaResp = esClient.updateByQuery(qaVersionReq);
            log.info("[DocRegistry] 2PC QA 版本切换完成 source={} version={} affected={}", sourceName, docVersion, qaResp.updated());
        } catch (Exception e) {
            // QA 2PC 失败：旧版 QA 短暂可见，属精度损失而非数据错误，不回滚主事务
            log.warn("[DocRegistry] 2PC QA 版本切换失败（可补偿，不影响主索引） source={} err={}", sourceName, e.getMessage());
        }

        // 2. 将同名文档所有旧版本标记为非最新（MySQL 层面）
        registryMapper.markOlderVersionsNotLatest(sourceName);

        // 3. 查找是否在 getNextVersion 时已存在其占位草稿
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocRegistry> qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(KbDocRegistry::getSourceName, sourceName)
                .eq(KbDocRegistry::getDocVersion, docVersion);
        KbDocRegistry entry = registryMapper.selectOne(qw);

        boolean isUpdate = true;
        if (entry == null) {
            entry = new KbDocRegistry();
            isUpdate = false;
        }

        entry.setDocId(docId != null ? docId : sourceName + ":" + docVersion);
        entry.setSourceName(sourceName);
        entry.setDocVersion(docVersion);
        entry.setIsLatest(1);
        entry.setStoragePath(storagePath);
        entry.setTargetIndex(targetIndex != null ? targetIndex : "kb_document_v1");
        entry.setChunkCount(chunkCount);
        entry.setContentHash(contentHash);
        entry.setDocNumber(docNumber);
        entry.setUnit(unit);
        entry.setTags(tags);

        if (publishTime != null && !publishTime.trim().isEmpty()) {
            try {
                entry.setPublishTime(java.time.LocalDate.parse(publishTime.substring(0, 10)));
            } catch (Exception ignore) {
            }
        }

        entry.setVisibility(visibility != null ? visibility : "INTERNAL");
        entry.setDeptCode(deptCode);
        entry.setUploaderId(uploaderId);
        entry.setUploaderName(uploaderName);
        entry.setStatus(parseStatus != null ? parseStatus : "INDEXED");
        entry.setUpdatedAt(OffsetDateTime.now());

        if (isUpdate) {
            registryMapper.updateById(entry);
        } else {
            entry.setCreatedAt(OffsetDateTime.now());
            registryMapper.insert(entry);
        }

        log.info("[DocRegistry] 注册成功 (2PC) source={} v={} chunks={}", sourceName, docVersion, chunkCount);
        return entry;
    }

    /**
     * 分页查询当前有效文档列表（is_latest=1）。
     *
     * @param page    页码（从 1 开始）
     * @param size    每页数量（默认 20）
     * @param keyword 关键词（匹配文档名/单位/文号/标签）
     * @param status  状态筛选（"INDEXED"/"DELETED"，null 则不过滤）
     */
    public IPage<KbDocRegistry> listDocs(int page, int size, String keyword, String status) {
        Page<KbDocRegistry> pageReq = new Page<>(page, size);
        return registryMapper.pageLatest(pageReq, keyword, status);
    }

    /**
     * 逻辑删除文档。
     * 关键流程（P0 #11 修复）：
     * 1. MySQL registry：status 置为 DELETED
     * 2. ES UpdateByQuery：按 metadata.source 精确匹配，
     * 通过 Painless Script 将所有匹配 chunk 的 metadata.is_latest 置 false
     * 效果：搜索侧 is_latest=true 过滤器立即屏蔽已删除文档，不再返回其内容。
     * 容错：ES 同步失败时不回滚 MySQL，记录 warn 日志，后续可由恢复任务补偿。
     *
     * @param id 注册表主键 ID
     */
    public boolean deleteDoc(Long id) {
        KbDocRegistry entry = registryMapper.selectById(id);
        if (entry == null)
            return false;

        // Step 1: MySQL 逻辑删除
        entry.setStatus("DELETED");
        entry.setUpdatedAt(OffsetDateTime.now());
        registryMapper.updateById(entry);
        log.info("[DocRegistry] MySQL 逻辑删除完成 id={} source={}", id, entry.getSourceName());

        // Step 2: ES 同步 — 将该文档所有 chunk 的 is_latest 置为 false
        String sourceName = entry.getSourceName();
        String idx = entry.getTargetIndex() != null ? entry.getTargetIndex() : "kb_document_v1";
        try {
            UpdateByQueryRequest esReq = UpdateByQueryRequest.of(r -> r
                    .index(idx)
                    // 按文档名精确匹配（metadata.source 是 keyword 子字段）
                    .query(q -> q.term(t -> t
                            .field("metadata.source")
                            .value(sourceName)))
                    // Painless 脚本：置 is_latest = false
                    .script(s -> s.inline(i -> i
                            .source("ctx._source.metadata.is_latest = false")
                            .lang("painless")))
                    // 版本冲突时继续处理其他文档，不中断
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse resp = esClient.updateByQuery(esReq);
            log.info("[DocRegistry] ES 同步删除完成 source={} updated={}", sourceName, resp.updated());
        } catch (Exception e) {
            log.warn("[DocRegistry] ES 同步删除失败（MySQL 已标记 DELETED，需手动补偿）" +
                    " source={} err={}", sourceName, e.getMessage());
        }

        // Step 3: QA 同步 — 将该文档所有 QA 问答对的 is_latest 置为 false
        // 索引：kb_qa_pairs（固定，QA 不走多索引路由）
        // 匹配字段：source（与主索引 metadata.source 一致）
        // 容错：ES 失败不回滚 MySQL，与 Step2 保持一致，记录 warn 可补偿
        try {
            UpdateByQueryRequest qaReq = UpdateByQueryRequest.of(r -> r
                    .index("kb_qa_pairs")
                    .query(q -> q.term(t -> t.field("source").value(sourceName)))
                    .script(s -> s.inline(i -> i
                            .source("ctx._source.is_latest = false")
                            .lang("painless")))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
            co.elastic.clients.elasticsearch.core.UpdateByQueryResponse qaResp = esClient.updateByQuery(qaReq);
            log.info("[DocRegistry] QA 同步删除完成 source={} updated={}", sourceName, qaResp.updated());
        } catch (Exception e) {
            log.warn("[DocRegistry] QA 同步删除失败（需手动补偿） source={} err={}", sourceName, e.getMessage());
        }

        // Step 4: kb_doc_meta 同步 — is_latest 置为 false
        // 匹配字段：source_name（kb_doc_meta 中的 keyword 副本字段，与写入时保持一致）
        try {
            UpdateByQueryRequest metaReq = UpdateByQueryRequest.of(r -> r
                    .index("kb_doc_meta")
                    .query(q -> q.term(t -> t.field("source_name").value(sourceName)))
                    .script(s -> s.inline(i -> i
                            .source("ctx._source.is_latest = false")
                            .lang("painless")))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));
            esClient.updateByQuery(metaReq);
            log.info("[DocRegistry] Meta 同步删除完成 source={}", sourceName);
        } catch (Exception e) {
            log.warn("[DocRegistry] Meta 同步删除失败 source={} err={}", sourceName, e.getMessage());
        }

        return true;
    }

    /**
     * 更新文档元数据（仅限不影响 ES 向量内容的字段）。
     * 支持字段：tags / docNumber / unit / visibility / deptCode / uploaderName。
     */
    public boolean updateMeta(Long id, Map<String, String> fields) {
        KbDocRegistry entry = registryMapper.selectById(id);
        if (entry == null)
            return false;

        if (fields.containsKey("tags"))
            entry.setTags(fields.get("tags"));
        if (fields.containsKey("docNumber"))
            entry.setDocNumber(fields.get("docNumber"));
        if (fields.containsKey("unit"))
            entry.setUnit(fields.get("unit"));
        if (fields.containsKey("visibility"))
            entry.setVisibility(fields.get("visibility"));
        if (fields.containsKey("deptCode"))
            entry.setDeptCode(fields.get("deptCode"));
        if (fields.containsKey("uploaderName"))
            entry.setUploaderName(fields.get("uploaderName"));

        entry.setUpdatedAt(OffsetDateTime.now());
        registryMapper.updateById(entry);
        log.info("[DocRegistry] 元数据更新 id={} fields={}", id, fields.keySet());
        return true;
    }

    /**
     * 查询指定文档的所有历史版本。
     */
    public List<KbDocRegistry> getVersionHistory(String sourceName) {
        return registryMapper.findAllVersionsBySourceName(sourceName);
    }

    /**
     * 按文档名查询最新版注册记录（供重复文件检测和任务恢复使用）。
     *
     * @param sourceName 文档名称
     * @return 最新版注册记录，null 表示不存在
     */
    public KbDocRegistry findLatest(String sourceName) {
        return registryMapper.findLatestBySourceName(sourceName);
    }

    /**
     * 按文档级 doc_id 精确查询注册记录（用于文件预览的精确定位）。
     * 业务功能：搜索结果返回的 chunk doc_id（格式 {hash}_v{N}_chunk_{i}），
     * 截取 _chunk_/_fine_ 前缀后得到文档级 id（{hash}_v{N}），精确对应唯一文件版本，
     * 解决 findLatest(sourceName) 无法区分同名文件的问题。
     *
     * @param docId 文档级 id，格式 {file_base_hash}_v{version}
     * @return 对应注册记录，不存在时返回 null
     */
    public KbDocRegistry findByDocId(String docId) {
        if (docId == null || docId.isEmpty())
            return null;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocRegistry> qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(KbDocRegistry::getDocId, docId).last("LIMIT 1");
        return registryMapper.selectOne(qw);
    }

    /**
     * 检查是否已存在相同内容哈希的文档（P1.6 幂等上传去重）。
     * contentHash 为文件前 8KB 的 SHA-256 hex，相同即视为内容重复，跳过入库。
     *
     * @param contentHash SHA-256 hex 小写
     * @return true 表示已存在相同内容的已入库文档
     */
    public boolean existsByContentHash(String contentHash) {
        if (contentHash == null || contentHash.isEmpty() || "unknown".equals(contentHash))
            return false;
        // 使用 MyBatis-Plus lambda 查询，避免新增 Mapper xml
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocRegistry> qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(KbDocRegistry::getContentHash, contentHash)
                // 注意：is_latest 在 PostgreSQL 中为 SMALLINT（1=是，0=否），不能传 boolean
                .eq(KbDocRegistry::getIsLatest, 1)
                .last("LIMIT 1");
        KbDocRegistry found = registryMapper.selectOne(qw);
        return found != null;
    }

    /**
     * 获取文档下一版本号（P1-7 修复：替代 rag_pipeline.py 从 ES 读最大版本的 TOCTOU 竞争方案）。
     * 在事务保护下查询当前最大版本号并返回 +1，由 MySQL 行级锁保证原子性。
     * Python rag_pipeline 在入库前通过 GET /internal/doc/next-version 调用此方法。
     *
     * @param sourceName 文档 source 名称
     * @return 下一个应使用的版本号（从 1 开始单调递增）
     */
    @Transactional(rollbackFor = Exception.class)
    public int getNextVersion(String sourceName) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocRegistry> qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(KbDocRegistry::getSourceName, sourceName)
                .orderByDesc(KbDocRegistry::getDocVersion)
                .last("LIMIT 1");
        KbDocRegistry latest = registryMapper.selectOne(qw);
        int next = latest != null ? latest.getDocVersion() + 1 : 1;

        // 生成占位草稿记录，利用 MySQL 的 (source_name, doc_version) 唯一约束实现多节点间高并发的安全预分配。
        // 如果发生竞争抢占同一个版本号，后续提交会被 DuplicateKeyException 拒绝。
        KbDocRegistry draft = new KbDocRegistry();
        draft.setDocId("draft_" + sourceName + "_" + next); // 临时 ID
        draft.setSourceName(sourceName);
        draft.setDocVersion(next);
        draft.setIsLatest(0); // 不当作最新版返回
        draft.setStatus("PROCESSING"); // 草稿占位态
        draft.setCreatedAt(OffsetDateTime.now());
        draft.setUpdatedAt(OffsetDateTime.now());
        registryMapper.insert(draft);

        log.info("[DocRegistry] 预分配并强制锁定版本号 source='{}' nextVersion={}", sourceName, next);
        return next;
    }

    /**
     * 按主键 ID 查询注册记录（P1-2：供 DocManagementController.getVersions 使用）。
     * 直接主键查询，O(1)，替代原来的 listDocs(1,1).stream().filter() Bug 实现。
     *
     * @param id 注册表主键
     * @return 对应的 KbDocRegistry，不存在时返回 null
     */
    public KbDocRegistry getById(Long id) {
        return registryMapper.selectById(id);
    }
}
