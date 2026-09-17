package com.boyang.search.security;

import com.boyang.search.entity.KbDocRegistry;
import com.boyang.search.service.DeptTreeService;
import com.boyang.search.service.KbDocAclSubjectService;
import com.boyang.search.service.KbDocGrantsService;
import com.boyang.search.service.KbDocRegistryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionGuardDeptVisibilityTest {

    @Test
    void deptDocumentAllowsOwnerAndAncestorDepartmentsOnly() {
        DeptTreeService deptTreeService = mock(DeptTreeService.class);
        when(deptTreeService.isAncestorOrSelf("620102", "620102")).thenReturn(true);
        when(deptTreeService.isAncestorOrSelf("6201", "620102")).thenReturn(true);
        when(deptTreeService.isAncestorOrSelf("62", "620102")).thenReturn(true);
        when(deptTreeService.isAncestorOrSelf("620103", "620102")).thenReturn(false);
        when(deptTreeService.isAncestorOrSelf("62010201", "620102")).thenReturn(false);

        PermissionGuard guard = newGuard(deptTreeService);
        KbDocRegistry doc = deptDoc("unit-doc.txt", "620102");

        assertTrue(guard.canAccess(doc, "user-a", "620102").isAllowed());
        assertTrue(guard.canAccess(doc, "user-city", "6201").isAllowed());
        assertTrue(guard.canAccess(doc, "user-province", "62").isAllowed());
        assertFalse(guard.canAccess(doc, "user-sibling", "620103").isAllowed());
        assertFalse(guard.canAccess(doc, "user-child", "62010201").isAllowed());
    }

    @Test
    void deptDocumentNormalizesAdministrativeTrailingZerosBeforeJudgement() {
        DeptTreeService deptTreeService = mock(DeptTreeService.class);
        when(deptTreeService.isAncestorOrSelf("6201", "620102")).thenReturn(true);
        PermissionGuard guard = newGuard(deptTreeService);
        KbDocRegistry doc = deptDoc("unit-doc-zero.txt", "620102000000");

        assertTrue(guard.canAccess(doc, "user-city", "620100000000").isAllowed());
    }

    private PermissionGuard newGuard(DeptTreeService deptTreeService) {
        return new PermissionGuard(
                mock(KbDocRegistryService.class),
                mock(KbDocGrantsService.class),
                mock(KbDocAclSubjectService.class),
                deptTreeService);
    }

    private KbDocRegistry deptDoc(String sourceName, String deptCode) {
        KbDocRegistry doc = new KbDocRegistry();
        doc.setSourceName(sourceName);
        doc.setVisibility("DEPT");
        doc.setDeptCode(deptCode);
        doc.setStatus("INDEXED");
        return doc;
    }
}
