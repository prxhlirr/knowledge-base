package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import com.boyang.search.entity.SysDocBatch;
import com.boyang.search.entity.SysDocImportTask;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.strategy.ingest.IngestStrategyFactory;
import com.boyang.search.strategy.ingest.IngestStrategy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务功能：远程文件下载、向量化并入库到 Elasticsearch 的方案A Demo演示与集成测试类。
 * 关键方法：
 *   - testRemoteFileDownloadAndIngest: 模拟真实的 URL 文件远程下载并进行完整向量化入库的同步监控 Demo。
 *   - testSsrfProtection: 模拟非法/内网 URL 传入时，现有项目架构的 SSRF 安全防御与异常捕获。
 * 流程说明：
 *   1. 构造 DocIngestRequest 请求，指定 ingestType 为 "URL" 并提供文件远程下载地址。
 *   2. 调用 DocIngestService.ingest() 触发入库流程（Java 侧异步流：下载 -> 存储至 Minio -> 事务性 Outbox 写入 -> Redis 投递）。
 *   3. 轮询监控器（Poller）以 3 秒为间隔查询数据库任务状态，实时打印 Python Worker 的解析分块与向量化处理进度。
 *   4. 处理完成后，调用 ElasticsearchClient 查询 kb_document 索引，同步验证向量化切片是否真正成功落地。
 */
@SpringBootTest
class DocIngestDemoTest {

    private static final Logger log = LoggerFactory.getLogger(DocIngestDemoTest.class);

    @Autowired
    private DocIngestService docIngestService;

    @Autowired
    private SysDocBatchService sysDocBatchService;

    @Autowired
    private SysDocImportTaskService sysDocImportTaskService;

    @Autowired
    private KbDocRegistryService kbDocRegistryService;

    @Autowired
    private IngestStrategyFactory ingestStrategyFactory;

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 【主Demo用例】测试从远程 URL 下载文件，并按照现有项目流程异步向量化并存储到 ES。
     * 本用例中加入了“同步轮询监控”与“ES 向量数据落地校验”，以便在控制台直观展现全生命周期的执行结果。
     */
    @Test
    void testRemoteFileDownloadAndIngest() throws Exception {
        log.info("========== [Demo开始] 开启远程 URL 文件向量化入库流程演示 ==========");

        // 1. 准备远程下载测试文档。此处使用 W3C 提供的标准 dummy PDF 作为测试样本，其网络状态极其稳定
        String remoteFileUrl = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";
        String targetFileName = "demo_remote_test_document.pdf";

        // 2. 构造入库请求 DTO
        DocIngestRequest request = new DocIngestRequest();
        request.setIngestType("URL");
        request.setFilePath(remoteFileUrl);
        request.setFileName(targetFileName);
        request.setVisibility("INTERNAL");              // 设为内部可见
        request.setTargetIndex("kb_document_v1");        // 目标写入的 ES 索引
        request.setTag("Demo,URL下载,向量化");
        request.setUnit("研发测试中心");
        request.setDocNumber("研发Demo字[2026]0528号");
        request.setOwner("Antigravity-AI");
        request.setPublishTime("2026-05-28");
        request.setSourceSystem("INTEGRATION_TEST");
        request.setForceOcr(false);                     // 非扫描件无需强行启用 OCR，提升解析速度

        log.info("1. 构造 Ingest 请求参数成功: URL='{}', 目标索引='{}'", remoteFileUrl, request.getTargetIndex());

        // 3. 提交请求，触发入库大底座。此处传递操作人 "demo_admin" 以便通过操作审计
        String batchId = docIngestService.ingest(request, "demo_admin");
        assertNotNull(batchId, "提交任务失败，产生的 batchId 不能为空");
        log.info("2. 成功提交入库任务！分配的异步批次 ID (batchId): '{}'", batchId);

        // 4. 【Demo 进度同步轮询器】因为入库解析与向量化是 Python 异步消费完成的，
        //    这里我们每 3 秒读取一次数据库状态，以提供完美的同步 Demo 可视化体验。
        log.info("3. 开启同步监控进度轮询器...");
        SysDocBatch batchInfo = null;
        boolean finished = false;
        int maxRetries = 40; // 限制最大轮询时间为 2 分钟，防止在离线环境 Worker 未启动时产生死循环
        int attempts = 0;

        while (!finished && attempts < maxRetries) {
            Thread.sleep(3000); // 间隔 3 秒轮询一次
            attempts++;

            // 从 MySQL 获取批次表最新状态
            batchInfo = sysDocBatchService.getById(batchId);
            if (batchInfo == null) {
                log.warn("[轮询监控] 尚未检索到批次记录，等待数据库写入...");
                continue;
            }

            String status = batchInfo.getStatus();
            int total = batchInfo.getTotalCount();
            int success = batchInfo.getSuccessCount();
            int error = batchInfo.getErrorCount();

            log.info("[批次监控] 尝试次数={}/{} | 批次状态: '{}' | 文档总量: {} | 成功数: {} | 失败数: {}",
                    attempts, maxRetries, status, total, success, error);

            // 查询该批次下具体的文件任务解析状态
            List<SysDocImportTask> tasks = sysDocImportTaskService.lambdaQuery()
                    .eq(SysDocImportTask::getBatchId, batchId)
                    .list();
            for (SysDocImportTask task : tasks) {
                log.info("  └─ 核心任务 ID: '{}' | 文件名: '{}' | 当前流转状态: '{}'",
                        task.getTaskId(), task.getOriginalName(), task.getStatus());
            }

            // 当状态不再是 "IMPORTING" 时（例如 INDEXED / ERROR / COMPLETED 等），说明 Python 侧已处理完毕
            if (!"IMPORTING".equalsIgnoreCase(status)) {
                finished = true;
                log.info("4. 检测到流转状态已变更，轮询监控器正常退出。最终批次状态: '{}'", status);
            }
        }

        // 5. 断言及 ES 向量落地强校验
        assertNotNull(batchInfo, "批次记录不存在");
        if ("ERROR".equalsIgnoreCase(batchInfo.getStatus())) {
            fail("远程向量化入库 Demo 失败！批次状态为 ERROR，请检查 Redis & Python Worker 的存活状态以及日志报错信息。");
        }

        log.info("5. 开始通过项目中已存在的文档注册服务 (KbDocRegistryService) 验证注册与 ES 向量落地情况...");
        try {
            // 利用已存在的 KbDocRegistryService，按文档名查询最新注册记录
            com.boyang.search.entity.KbDocRegistry registry = kbDocRegistryService.findLatest(targetFileName);
            assertNotNull(registry, "在文档注册中心未找到该文档的注册记录！");
            
            log.info("🎉 [项目注册服务校验成功] 文档级 ID: '{}' | 版本号: v{} | 解析注册状态: '{}' | ES 向量分片总数 (chunkCount): {}",
                    registry.getDocId(), registry.getDocVersion(), registry.getStatus(), registry.getChunkCount());
            
            assertEquals("INDEXED", registry.getStatus(), "文档状态应为成功入库 (INDEXED) 状态");
            assertTrue(registry.getChunkCount() > 0, "文档的向量切片数 (chunkCount) 应大于 0");
            
            // 配合 ES 物理落地的二次校验，确保注册数据与 ES 索引数据百分百吻合
            CountRequest countRequest = CountRequest.of(cr -> cr
                    .index("kb_document_v*")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("metadata.source").value(targetFileName)))
                            .must(m -> m.term(t -> t.field("metadata.is_latest").value(true)))
                    ))
            );
            long chunkCount = esClient.count(countRequest).count();
            log.info("🎉 [ES 物理校验成功] Elasticsearch 中实际索引到的 latest 向量分片数: {}", chunkCount);
            assertEquals(registry.getChunkCount().longValue(), chunkCount, "MySQL 注册表中的切片数应与 ES 物理索引数完全吻合！");

        } catch (Exception e) {
            log.error("向量化校验过程发生异常: {}", e.getMessage(), e);
            throw e;
        }

        log.info("========== [Demo结束] 远程 URL 向量化入库流程演示成功 ==========");
    }

    /**
     * 【安全防御测试用例】测试系统对 SSRF 探测和非法协议的同步防护与熔断拦截。
     * 业务设计：复用项目中已存在的 IngestStrategyFactory，获取 UrlIngestStrategy 以实现同步且确定性的安全异常断言。
     */
    @Test
    void testSsrfProtection() throws Exception {
        log.info("========== [安全测试开始] 验证内网 SSRF 安全防护拦截功能 ==========");

        // 1. 模拟攻击者传入内网回环敏感地址，企图探测本地 Java 服务的监控端口
        String maliciousUrl = "http://127.0.0.1:8080/actuator/env";

        DocIngestRequest request = new DocIngestRequest();
        request.setIngestType("URL");
        request.setFilePath(maliciousUrl);
        request.setFileName("exploit_ssrf.pdf");
        request.setVisibility("INTERNAL");

        log.info("1. 提交恶意内网 URL 到入库引擎: '{}'", maliciousUrl);

        // 2. 利用项目中已有的 IngestStrategyFactory 服务，获取 UrlIngestStrategy 策略处理器以进行同步断言
        log.info("2. 从已有的 IngestStrategyFactory 获取 URL 策略处理器...");
        IngestStrategy strategy = ingestStrategyFactory.getStrategy("URL");
        assertNotNull(strategy, "获取已有的 URL 策略处理器失败");
        
        SysDocBatch tempBatch = new SysDocBatch();
        tempBatch.setBatchId("temp_test_ssrf_batch");
        tempBatch.setErrorCount(0);

        log.info("3. 同步调用已有的策略类 validateUrlSafety 进行安全异常捕获校验...");
        // 核心亮点：validateUrlSafety 会在 strategy.process 内部同步执行并抛出 SecurityException，
        // 这样测试用例可以直接在 JUnit 主线程同步断言抛出的异常，彻底避免了异步多线程测试的捕获漏洞！
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            strategy.process(request, tempBatch);
        }, "应当同步抛出 SecurityException 异常以阻断内网 SSRF 渗透！");

        log.info("🛡️ [防守成功] 系统已成功通过 URL 策略内置的防御逻辑在扫描前置阶段拦截风险！");
        log.info("   └─ 拦截捕获的安全异常信息: '{}'", exception.getMessage());
        assertTrue(exception.getMessage().contains("禁止访问内网地址") || exception.getMessage().contains("内网"),
                "抛出的异常应当是针对内网访问的安全异常拦截");

        log.info("========== [安全测试结束] 内网 SSRF 安全防护拦截功能验证成功 ==========");
    }
}
