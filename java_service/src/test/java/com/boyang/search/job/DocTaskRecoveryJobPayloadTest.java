package com.boyang.search.job;

import com.boyang.search.entity.SysDocImportTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
