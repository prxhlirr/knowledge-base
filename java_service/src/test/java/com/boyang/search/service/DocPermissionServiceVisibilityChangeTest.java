package com.boyang.search.service;

import com.boyang.search.entity.DocPermissionEvent;
import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.mapper.DocPermissionEventMapper;
import com.boyang.search.mapper.DocVersionHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocPermissionServiceVisibilityChangeTest {

    private DocPermissionEventMapper eventMapper;
    private SearchCacheService searchCacheService;
    private KbDocRegistryService registryService;
    private DocPermissionService service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(DocPermissionEventMapper.class);
        searchCacheService = mock(SearchCacheService.class);
        registryService = mock(KbDocRegistryService.class);
        service = new DocPermissionService(
                eventMapper,
                mock(DocVersionHistoryMapper.class),
                searchCacheService,
                registryService
        );
    }

    @Test
    void visibilityChangeUsesRegistryPermissionProjectionClosure() {
        KbDocRegistry doc = doc("doc-a.txt", "INTERNAL", "");
        when(registryService.findLatest("doc-a.txt")).thenReturn(doc);
        when(registryService.updateMeta(eq(10L), argThat(fields ->
                "PRIVATE".equals(fields.get("visibility"))))).thenReturn(true);

        service.addVisibilityChange("doc-a.txt", "PRIVATE", null, "admin-1");

        verify(registryService).updateMeta(eq(10L), argThat(fields ->
                "PRIVATE".equals(fields.get("visibility")) && !fields.containsKey("deptCode")));
        verify(eventMapper).insert(argThat(event ->
                "VISIBILITY_CHANGE".equals(event.getAction())
                        && "doc-a.txt".equals(event.getDocId())
                        && "admin-1".equals(event.getOperatorId())));
        verify(searchCacheService).invalidateByDocSource("doc-a.txt");
    }

    @Test
    void deptVisibilityRequiresDeptCodeWhenRegistryHasNoDept() {
        KbDocRegistry doc = doc("doc-b.txt", "INTERNAL", "");
        when(registryService.findLatest("doc-b.txt")).thenReturn(doc);

        assertThrows(IllegalArgumentException.class,
                () -> service.addVisibilityChange("doc-b.txt", "DEPT", null, "admin-1"));
    }

    @Test
    void deptVisibilityPassesDeptCodeToRegistryUpdate() {
        KbDocRegistry doc = doc("doc-c.txt", "INTERNAL", "");
        when(registryService.findLatest("doc-c.txt")).thenReturn(doc);
        when(registryService.updateMeta(eq(10L), argThat(fields ->
                "DEPT".equals(fields.get("visibility"))
                        && "620102".equals(fields.get("deptCode"))))).thenReturn(true);

        service.addVisibilityChange("doc-c.txt", "DEPT", "620102", "admin-1");

        verify(registryService).updateMeta(eq(10L), argThat(fields ->
                "DEPT".equals(fields.get("visibility"))
                        && "620102".equals(fields.get("deptCode"))));
    }

    private KbDocRegistry doc(String sourceName, String visibility, String deptCode) {
        KbDocRegistry doc = new KbDocRegistry();
        doc.setId(10L);
        doc.setSourceName(sourceName);
        doc.setVisibility(visibility);
        doc.setDeptCode(deptCode);
        doc.setStatus("INDEXED");
        return doc;
    }
}
