package com.boyang.search.service;

import com.boyang.search.mapper.KbDocOutboxMapper;
import com.boyang.search.model.DocIngestRequest;
import com.boyang.search.strategy.ingest.IngestStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocIngestServiceUnitPermissionProjectionTest {

    @Test
    void unitPermissionProjectionUsesDeptChainFromJavaPermissionDomain() {
        DeptTreeService deptTreeService = mock(DeptTreeService.class);
        when(deptTreeService.buildAclChain("620102"))
                .thenReturn(Arrays.asList("620102", "6201", "62"));
        DocIngestService service = newService(deptTreeService);

        Map<String, Object> projection = service.buildUnitPermissionProjection("620102000000");

        assertEquals("620102", projection.get("ownerUnitCode"));
        assertEquals(Arrays.asList("620102", "6201", "62"),
                projection.get("visibleUnitCodes"));
        assertTrue(((Long) projection.get("permissionVersion")) > 0L);
    }

    @Test
    void unitPermissionProjectionFallsBackToGlobalForBlankDept() {
        DocIngestService service = newService(mock(DeptTreeService.class));

        Map<String, Object> projection = service.buildUnitPermissionProjection(" ");

        assertEquals("global", projection.get("ownerUnitCode"));
        assertEquals(Arrays.asList("global"), projection.get("visibleUnitCodes"));
        assertTrue(((Long) projection.get("permissionVersion")) > 0L);
    }

    @Test
    void userIngestWithoutOperatorAndUntrustedSourceIsRejected() {
        DocIngestService service = newService(mock(DeptTreeService.class));
        DocIngestRequest req = new DocIngestRequest();
        req.setSourceSystem("LOCAL_DIR_ADMIN");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.validateIngestPrincipal(req, null));

        assertTrue(ex.getMessage().contains("缺少操作者身份"));
    }

    @Test
    void trustedSystemSyncSourceCanRunWithoutOperator() {
        DocIngestService service = newService(mock(DeptTreeService.class));
        DocIngestRequest req = new DocIngestRequest();
        req.setSourceSystem("DB_DOC_SYNC");

        assertDoesNotThrow(() -> service.validateIngestPrincipal(req, null));
    }

    @Test
    void blankOperatorIsRejectedForUntrustedUserIngestSource() {
        DocIngestService service = newService(mock(DeptTreeService.class));
        DocIngestRequest req = new DocIngestRequest();
        req.setSourceSystem("UPLOAD_ADMIN");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.validateIngestPrincipal(req, " "));

        assertTrue(ex.getMessage().contains("拒绝降级为系统入库"));
    }

    @Test
    void forgedSyncSuffixSourceIsRejectedWithoutOperator() {
        DocIngestService service = newService(mock(DeptTreeService.class));
        DocIngestRequest req = new DocIngestRequest();
        req.setSourceSystem("UPLOAD_SYNC");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.validateIngestPrincipal(req, null));

        assertTrue(ex.getMessage().contains("拒绝降级为系统入库"));
    }

    @Test
    void forgedSystemPrefixSourceIsRejectedWithoutOperator() {
        DocIngestService service = newService(mock(DeptTreeService.class));
        DocIngestRequest req = new DocIngestRequest();
        req.setSourceSystem("SYSTEM_FAKE");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.validateIngestPrincipal(req, null));

        assertTrue(ex.getMessage().contains("拒绝降级为系统入库"));
    }

    private DocIngestService newService(DeptTreeService deptTreeService) {
        return new DocIngestService(
                mock(SysDocBatchService.class),
                mock(SysDocImportTaskService.class),
                mock(ISysFileParseLogService.class),
                mock(StringRedisTemplate.class),
                new ObjectMapper(),
                mock(IngestStrategyFactory.class),
                deptTreeService,
                mock(MinioStorageService.class),
                mock(KbDocOutboxMapper.class),
                mock(ApplicationEventPublisher.class));
    }
}
