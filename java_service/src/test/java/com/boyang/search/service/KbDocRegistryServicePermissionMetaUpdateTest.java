package com.boyang.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.boyang.search.entity.KbDocAclSubject;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.mapper.KbDocRegistryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbDocRegistryServicePermissionMetaUpdateTest {

    private KbDocRegistryMapper registryMapper;
    private DeptTreeService deptTreeService;
    private DocAclProjectionService projectionService;
    private KbDocAclSubjectService aclSubjectService;
    private KbDocRegistryService service;

    @BeforeEach
    void setUp() {
        registryMapper = mock(KbDocRegistryMapper.class);
        deptTreeService = mock(DeptTreeService.class);
        projectionService = mock(DocAclProjectionService.class);
        aclSubjectService = mock(KbDocAclSubjectService.class);
        service = new KbDocRegistryService(
                registryMapper,
                mock(ElasticsearchClient.class),
                deptTreeService,
                projectionService,
                aclSubjectService
        );
    }

    @Test
    void updateMetaDoesNotSyncPermissionProjectionForOrdinaryMetadata() {
        KbDocRegistry doc = doc();
        when(registryMapper.selectById(10L)).thenReturn(doc);

        Map<String, String> fields = new HashMap<>();
        fields.put("tags", "政策,测试");
        fields.put("unit", "测试单位");

        boolean ok = service.updateMeta(10L, fields);

        assertEquals(true, ok);
        verify(registryMapper).updateById(doc);
        verify(aclSubjectService, never()).replaceInitialSubjects(
                eq(doc), anyList(), anyList(), eq("uploader-1"));
        verify(projectionService, never()).syncAclTokensProjection(
                eq("kb_document_law"), eq("doc-a.txt"), anyList());
    }

    @Test
    void updateMetaRebuildsAclAndProjectsUnitFieldsWhenPermissionFieldsTouched() {
        KbDocRegistry doc = doc();
        when(registryMapper.selectById(10L)).thenReturn(doc);
        when(deptTreeService.buildAclChain("620102"))
                .thenReturn(Arrays.asList("620102", "6201", "62"));
        when(aclSubjectService.listActive("doc-a.txt")).thenReturn(Arrays.asList(
                subject("DEPT", "620102"),
                subject("USER", "reader-1"),
                subject("ROLE", "law_reader")
        ));
        when(aclSubjectService.toAclToken("DEPT", "620102")).thenReturn("dept::620102");
        when(aclSubjectService.toAclToken("USER", "reader-1")).thenReturn("user::reader-1");
        when(aclSubjectService.toAclToken("ROLE", "law_reader")).thenReturn("role::law_reader");

        Map<String, String> fields = new HashMap<>();
        fields.put("visibility", "DEPT");
        fields.put("deptCode", "620102000000");

        boolean ok = service.updateMeta(10L, fields);

        assertEquals(true, ok);
        assertEquals("DEPT", doc.getVisibility());
        assertEquals("620102000000", doc.getDeptCode());
        verify(aclSubjectService).replaceInitialSubjects(
                eq(doc), eq(Collections.emptyList()), eq(Collections.emptyList()), eq("uploader-1"));
        verify(projectionService).syncAclTokensProjection(
                eq("kb_document_law"),
                eq("doc-a.txt"),
                eq(Arrays.asList("dept::620102", "user::reader-1", "role::law_reader")));
        verify(projectionService).syncUnitProjection(
                eq("kb_document_law"),
                eq("doc-a.txt"),
                eq("620102"),
                eq(Arrays.asList("620102", "6201", "62")),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private KbDocRegistry doc() {
        KbDocRegistry doc = new KbDocRegistry();
        doc.setId(10L);
        doc.setSourceName("doc-a.txt");
        doc.setTargetIndex("kb_document_law");
        doc.setDocVersion(3);
        doc.setVisibility("INTERNAL");
        doc.setDeptCode("");
        doc.setUploaderId("uploader-1");
        return doc;
    }

    private KbDocAclSubject subject(String subjectType, String subjectValue) {
        KbDocAclSubject subject = new KbDocAclSubject();
        subject.setSubjectType(subjectType);
        subject.setSubjectValue(subjectValue);
        subject.setScope("VIEW");
        subject.setEffect("ALLOW");
        subject.setIsActive(1);
        return subject;
    }
}
