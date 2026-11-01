package com.crdp.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E07-T02 / PLT-PERM-007、A-WARD-003、C-WARD-004：七种数据范围类型的快照归并（纯函数； MySQL 取数与登录链路由 DataScopeTypesIT
 * 覆盖）。
 *
 * <p>口径：多角色范围类型先并集（角色类型行由 SQL DISTINCT），快照再把触发的清单 并起来——病区取本人负责病区（WARD），科室取 主科室（DEPT）∪
 * 负责科室（RESPONSIBLE_DEPT）∪ 角色自定义科室清单（CUSTOM_DEPT， 多角色再次并集），本人取 SELF ∪ RESPONSIBLE_PATIENT；任一角色 ALL
 * 即全院（A-WARD-007）。病区与科室之间是 OR（改写侧），未触发 的类型即使表里有行也不生效。
 */
class HospitalDirectoryScopeTest {

    @Test
    void allWinsOverEverythingElse() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("WARD", "ALL"), List.of(1L), List.of(2L), 3L, List.of(4L));
        assertTrue(scope.all());
        assertFalse(scope.empty());
    }

    @Test
    void wardScopeTakesResponsibleWardsOnly() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("WARD"), List.of(1L, 2L), List.of(9L), 8L, List.of(7L));
        assertFalse(scope.all());
        assertEquals(List.of(1L, 2L), scope.wardIds());
        assertTrue(scope.deptIds().isEmpty(), "未触发科室类型时科室清单不生效");
        assertFalse(scope.self());
    }

    @Test
    void deptScopeTakesMainDept() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(List.of("DEPT"), List.of(), List.of(9L), 8L, List.of(7L));
        assertEquals(List.of(8L), scope.deptIds());
        assertTrue(scope.wardIds().isEmpty());
    }

    @Test
    void responsibleDeptScopeTakesUserResponsibleDepts() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("RESPONSIBLE_DEPT"), List.of(), List.of(9L, 10L), 8L, List.of(7L));
        assertEquals(List.of(9L, 10L), scope.deptIds());
    }

    @Test
    void customDeptScopeTakesRoleCustomDepts() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("CUSTOM_DEPT"), List.of(), List.of(9L), 8L, List.of(7L, 6L));
        assertEquals(List.of(7L, 6L), scope.deptIds());
    }

    @Test
    void deptBranchesUnionAndDedup() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("DEPT", "RESPONSIBLE_DEPT", "CUSTOM_DEPT"),
                        List.of(),
                        List.of(9L, 8L),
                        8L,
                        List.of(7L, 9L));
        assertEquals(List.of(8L, 9L, 7L), scope.deptIds(), "主科室在前，负责科室与自定义科室并集去重");
    }

    @Test
    void missingMainDeptContributesNothing() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(List.of("DEPT"), List.of(), List.of(), null, List.of());
        assertTrue(scope.deptIds().isEmpty());
        assertTrue(scope.empty(), "只剩一个空科室分支 → 空范围，看不到任何患者");
    }

    @Test
    void selfBranchCoversSelfAndResponsiblePatient() {
        assertTrue(
                HospitalDirectory.scopeOf(List.of("SELF"), List.of(), List.of(), null, List.of())
                        .self());
        assertTrue(
                HospitalDirectory.scopeOf(
                                List.of("RESPONSIBLE_PATIENT"),
                                List.of(),
                                List.of(),
                                null,
                                List.of())
                        .self());
        assertFalse(
                HospitalDirectory.scopeOf(List.of("WARD"), List.of(), List.of(), null, List.of())
                        .self());
    }

    /** E07-T02 验收：范围为「本人负责病区」但未分配任何病区 → 病区分支为空。 */
    @Test
    void wardScopeWithoutAssignedWardsKeepsOtherBranches() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("WARD", "RESPONSIBLE_PATIENT"),
                        List.of(),
                        List.of(),
                        null,
                        List.of());
        assertFalse(scope.all());
        assertTrue(scope.self(), "仅本人负责的患者分支仍在");
        assertFalse(scope.empty(), "有 self 分支就不算空");
    }

    /** E07-T02 验收：只有 WARD 且未分配病区 → 空范围（看不到患者）。 */
    @Test
    void wardOnlyWithoutAssignedWardsDeniesEverything() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(List.of("WARD"), List.of(), List.of(), null, List.of());
        assertFalse(scope.all());
        assertTrue(scope.empty(), "WARD 类型但没有任何负责病区 → 空范围（看不到患者）");
    }

    @Test
    void unknownTypesAreIgnoredDefensively() {
        ScopeSnapshot scope =
                HospitalDirectory.scopeOf(
                        List.of("SOMETHING_NEW"), List.of(1L), List.of(2L), 3L, List.of(4L));
        assertFalse(scope.all());
        assertTrue(scope.empty(), "未知范围类型不放开任何清单（防御脏数据）");
    }
}
