package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.boyang.search.entity.KbAclProjectionTask;
import com.boyang.search.mapper.KbAclProjectionTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocAclProjectionServiceTest {

    private ElasticsearchClient esClient;
    private KbAclProjectionTaskMapper taskMapper;
    private DocAclProjectionService service;

    @BeforeEach
    void setUp() throws Exception {
        esClient = mock(ElasticsearchClient.class);
        when(esClient.updateByQuery(any(UpdateByQueryRequest.class)))
                .thenReturn(mock(UpdateByQueryResponse.class));
        taskMapper = mock(KbAclProjectionTaskMapper.class);
        service = new DocAclProjectionService(
                esClient,
                new IndexAliasResolver(),
                taskMapper
        );
        ReflectionTestUtils.setField(service, "docMetaWriteIndex", "kb_doc_meta_write");
        ReflectionTestUtils.setField(service, "docSearchWriteIndex", "kb_doc_search_write");
        ReflectionTestUtils.setField(service, "qaWriteIndex", "kb_qa_write");
    }

    @Test
    void grantTokenProjectsToChunkMetaSearchAndQaIndexes() throws Exception {
        service.grantToken("kb_document_law", "doc-a.txt", "role::law_reader");

        List<UpdateByQueryRequest> requests = capturedRequests(4);
        assertEquals("kb_document_law", requests.get(0).index().get(0));
        assertEquals("kb_doc_meta_write", requests.get(1).index().get(0));
        assertEquals("kb_doc_search_write", requests.get(2).index().get(0));
        assertEquals("kb_qa_write", requests.get(3).index().get(0));
        assertTrue(script(requests.get(3)).contains("acl_tokens"));
    }

    @Test
    void syncUnitProjectionProjectsToChunkMetaSearchAndQaIndexes() throws Exception {
        service.syncUnitProjection(
                "kb_document_law",
                "doc-b.txt",
                "620102",
                Arrays.asList("620102", "6201", "62"),
                2026063005L
        );

        List<UpdateByQueryRequest> requests = capturedRequests(4);
        assertEquals("kb_document_law", requests.get(0).index().get(0));
        assertEquals("kb_doc_meta_write", requests.get(1).index().get(0));
        assertEquals("kb_doc_search_write", requests.get(2).index().get(0));
        assertEquals("kb_qa_write", requests.get(3).index().get(0));
        assertTrue(script(requests.get(0)).contains("metadata.visible_unit_codes"));
        assertTrue(script(requests.get(3)).contains("visible_unit_codes"));
        assertTrue(script(requests.get(3)).contains("permission_version"));
    }

    @Test
    void syncAclTokensProjectionReplacesTokensInChunkMetaSearchAndQaIndexes() throws Exception {
        service.syncAclTokensProjection(
                "kb_document_law",
                "doc-c.txt",
                Arrays.asList("_INTERNAL", "role::law_reader")
        );

        List<UpdateByQueryRequest> requests = capturedRequests(4);
        assertEquals("kb_document_law", requests.get(0).index().get(0));
        assertEquals("kb_doc_meta_write", requests.get(1).index().get(0));
        assertEquals("kb_doc_search_write", requests.get(2).index().get(0));
        assertEquals("kb_qa_write", requests.get(3).index().get(0));
        assertTrue(script(requests.get(0)).contains("metadata.acl_tokens"));
        assertTrue(script(requests.get(3)).contains("ctx._source.acl_tokens = params.aclTokens"));
    }

    @Test
    void syncAclTokensProjectionCreatesReplayableTaskWhenChunkProjectionFails() throws Exception {
        doThrow(new RuntimeException("es down"))
                .doReturn(mock(UpdateByQueryResponse.class))
                .doReturn(mock(UpdateByQueryResponse.class))
                .doReturn(mock(UpdateByQueryResponse.class))
                .when(esClient).updateByQuery(any(UpdateByQueryRequest.class));

        service.syncAclTokensProjection(
                "kb_document_law",
                "doc-d.txt",
                Arrays.asList("_INTERNAL", "role::law_reader")
        );

        verify(taskMapper).insert(argThat(task ->
                "ACL_TOKENS_SYNC".equals(task.getTaskType())
                        && "SYNC".equals(task.getOperation())
                        && task.getPayloadJson() != null
                        && task.getPayloadJson().contains("role::law_reader")));
    }

    @Test
    void syncAclTokensProjectionCreatesReplayableTaskWhenAuxiliaryProjectionFails() throws Exception {
        when(esClient.updateByQuery(any(UpdateByQueryRequest.class)))
                .thenReturn(mock(UpdateByQueryResponse.class))
                .thenThrow(new RuntimeException("doc meta down"))
                .thenReturn(mock(UpdateByQueryResponse.class))
                .thenReturn(mock(UpdateByQueryResponse.class));

        service.syncAclTokensProjection(
                "kb_document_law",
                "doc-aux-acl.txt",
                Arrays.asList("_INTERNAL", "role::law_reader")
        );

        verify(taskMapper).insert(argThat(task ->
                "ACL_TOKENS_SYNC".equals(task.getTaskType())
                        && "SYNC".equals(task.getOperation())
                        && task.getPayloadJson() != null
                        && task.getPayloadJson().contains("role::law_reader")));
    }

    @Test
    void syncUnitProjectionCreatesReplayableTaskWhenAuxiliaryProjectionFails() throws Exception {
        when(esClient.updateByQuery(any(UpdateByQueryRequest.class)))
                .thenReturn(mock(UpdateByQueryResponse.class))
                .thenReturn(mock(UpdateByQueryResponse.class))
                .thenThrow(new RuntimeException("doc search down"))
                .thenReturn(mock(UpdateByQueryResponse.class));

        service.syncUnitProjection(
                "kb_document_law",
                "doc-aux-unit.txt",
                "620102",
                Arrays.asList("620102", "6201", "62"),
                2026070101L
        );

        verify(taskMapper).insert(argThat(task ->
                "UNIT_SYNC".equals(task.getTaskType())
                        && "SYNC".equals(task.getOperation())
                        && task.getPayloadJson() != null
                        && task.getPayloadJson().contains("620102")));
    }

    @Test
    void retryAclTokensProjectionTaskReplaysPayloadAndMarksSuccess() throws Exception {
        KbAclProjectionTask task = new KbAclProjectionTask();
        task.setId(7L);
        task.setTaskType("ACL_TOKENS_SYNC");
        task.setTargetIndex("kb_document_law");
        task.setSourceName("doc-e.txt");
        task.setPayloadJson("{\"aclTokens\":[\"_INTERNAL\",\"role::law_reader\"]}");

        boolean ok = service.retryTask(task);

        assertTrue(ok);
        List<UpdateByQueryRequest> requests = capturedRequests(4);
        assertEquals("kb_document_law", requests.get(0).index().get(0));
        verify(taskMapper).updateById(argThat(updated ->
                Long.valueOf(7L).equals(updated.getId())
                        && "SUCCESS".equals(updated.getStatus())));
    }

    @Test
    void retryUnitProjectionTaskReplaysPayloadAndMarksSuccess() throws Exception {
        KbAclProjectionTask task = new KbAclProjectionTask();
        task.setId(8L);
        task.setTaskType("UNIT_SYNC");
        task.setTargetIndex("kb_document_law");
        task.setSourceName("doc-f.txt");
        task.setPayloadJson("{\"ownerUnitCode\":\"620102\",\"visibleUnitCodes\":[\"620102\",\"6201\"],\"permissionVersion\":2026063006}");

        boolean ok = service.retryTask(task);

        assertTrue(ok);
        List<UpdateByQueryRequest> requests = capturedRequests(4);
        assertTrue(script(requests.get(0)).contains("metadata.visible_unit_codes"));
        verify(taskMapper).updateById(argThat(updated ->
                Long.valueOf(8L).equals(updated.getId())
                        && "SUCCESS".equals(updated.getStatus())));
    }

    private List<UpdateByQueryRequest> capturedRequests(int count) throws Exception {
        org.mockito.ArgumentCaptor<UpdateByQueryRequest> captor =
                org.mockito.ArgumentCaptor.forClass(UpdateByQueryRequest.class);
        verify(esClient, times(count)).updateByQuery(captor.capture());
        return captor.getAllValues();
    }

    private String script(UpdateByQueryRequest request) {
        return request.script().inline().source();
    }
}
