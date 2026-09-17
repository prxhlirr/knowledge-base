package com.boyang.search.job;

import com.boyang.search.entity.SysDocImportTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocTaskRecoveryJobPayloadTest {

    @Test
    void recoveryPayloadNormalizesHistoricalEncodedOriginalName() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-1");
        task.setFilePath("/tmp/file");
        task.setOriginalName("%E6%A3%80%E9%AA%8C%E6%A3%80%E6%B5%8B%E5%B7%A5%E4%BD%9C.docx");
        task.setVisibility("INTERNAL");
        task.setDeptCode("620100");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_v1");

        assertEquals("检验检测工作.docx", payload.get("originalName"));
        assertEquals("kb_document_v1", payload.get("targetIndex"));
    }

    @Test
    void recoveryPayloadIncludesUnitPermissionProjectionForDeptDocument() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-unit");
        task.setFilePath("/tmp/unit.txt");
        task.setOriginalName("unit.txt");
        task.setVisibility("DEPT");
        task.setDeptCode("620102000000");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_law");

        assertEquals("620102000000", payload.get("deptCode"));
        assertEquals("620102000000", payload.get("ownerUnitCode"));
        assertEquals(java.util.Arrays.asList("620102", "6201", "62"), payload.get("visibleUnitCodes"));
        assertEquals("[\"dept::620102\",\"dept::6201\",\"dept::62\"]", payload.get("acl_tokens_json"));
        assertTrue(((Long) payload.get("permissionVersion")) > 0L);
    }

    @Test
    void recoveryPayloadFallsBackToGlobalWhenDeptIsBlank() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-global");
        task.setFilePath("/tmp/global.txt");
        task.setOriginalName("global.txt");
        task.setVisibility("INTERNAL");
        task.setDeptCode("");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_public");

        assertEquals("global", payload.get("ownerUnitCode"));
        assertEquals(java.util.Arrays.asList("global"), payload.get("visibleUnitCodes"));
        assertEquals("[\"_INTERNAL\"]", payload.get("acl_tokens_json"));
        assertTrue(((Long) payload.get("permissionVersion")) > 0L);
    }

    @Test
    void recoveryPayloadUsesPublicAclTokenForPublicDocument() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-public");
        task.setFilePath("/tmp/public.txt");
        task.setOriginalName("public.txt");
        task.setVisibility("PUBLIC");
        task.setDeptCode("");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_public");

        assertEquals("[\"_PUBLIC\"]", payload.get("acl_tokens_json"));
    }

    @Test
    void recoveryPayloadDoesNotWidenPrivateDocumentToInternal() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-private");
        task.setFilePath("/tmp/private.txt");
        task.setOriginalName("private.txt");
        task.setVisibility("PRIVATE");
        task.setDeptCode("620102000000");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_law");

        assertEquals("[\"_NO_ACCESS\"]", payload.get("acl_tokens_json"));
    }

    @Test
    void recoveryPayloadDoesNotWidenGrantDocumentToInternal() {
        SysDocImportTask task = new SysDocImportTask();
        task.setTaskId("task-grant");
        task.setFilePath("/tmp/grant.txt");
        task.setOriginalName("grant.txt");
        task.setVisibility("GRANT");
        task.setDeptCode("620102000000");

        Map<String, Object> payload = DocTaskRecoveryJob.buildRecoveryPayload(task, "kb_document_law");

        assertEquals("[\"_NO_ACCESS\"]", payload.get("acl_tokens_json"));
    }
}
