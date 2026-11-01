package com.crdp.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataScopeGuardTest {

    private static final long USER_ID = 5L;

    private static String enforce(String sql, ScopeSnapshot snapshot) {
        return DataScopeGuard.enforce(sql, snapshot, USER_ID, false);
    }

    @Test
    void leavesUncontrolledTablesAlone() {
        String sql = "SELECT id FROM sys_user WHERE username = ?";
        assertEquals(sql, DataScopeGuard.enforce(sql, null, USER_ID, false));
    }

    @Test
    void deniesControlledQueryWithoutScope() {
        assertThrows(
                DataScopeDeniedException.class,
                () -> DataScopeGuard.enforce("SELECT * FROM pt_patient", null, USER_ID, false));
    }

    @Test
    void passesWhenScopeIsAll() {
        String sql = "SELECT * FROM fu_task t WHERE t.status = 'OVERDUE'";
        assertEquals(sql, enforce(sql, ScopeSnapshot.allowAll()));
    }

    @Test
    void emptyScopeAppendsFalsePredicateToWherelessSelect() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten = enforce("SELECT * FROM pt_patient", empty);
        assertEquals("SELECT * FROM pt_patient WHERE 1 = 0", rewritten);
    }

    @Test
    void emptyScopeAppendsFalsePredicateToExistingWhere() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten = enforce("SELECT * FROM pt_patient WHERE deleted = ?", empty);
        assertEquals("SELECT * FROM pt_patient WHERE deleted = ? AND 1 = 0", rewritten);
    }

    @Test
    void emptyScopeAppendsFalsePredicateToJoinedQuery() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten =
                enforce(
                        "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                + " WHERE t.status = 'OVERDUE'",
                        empty);
        assertEquals(
                "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE t.status = 'OVERDUE' AND 1 = 0",
                rewritten);
    }

    /** E07-T02 验收「未分配病区看不到患者」：只有 WARD 类型但没有任何病区 → 空范围拒绝。 */
    @Test
    void wardScopeWithoutAnyWardDeniesEverything() {
        ScopeSnapshot noWard = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten = enforce("SELECT * FROM pt_patient WHERE deleted = 0", noWard);
        assertEquals("SELECT * FROM pt_patient WHERE deleted = 0 AND 1 = 0", rewritten);
    }

    @Test
    void unparseableControlledSqlFailsClosedInsteadOfPassingThrough() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        assertThrows(
                IllegalStateException.class, () -> enforce("SELECT FROM pt_patient WHERE", empty));
    }

    @Test
    void ignoreAnnotationBypassesTheGuard() {
        String sql = "SELECT * FROM pt_patient";
        assertEquals(sql, DataScopeGuard.enforce(sql, null, USER_ID, true));
    }

    // ------------------------------------------------ E07-T02：受限范围的病区 / 科室 / 本人改写

    /** 患者表本身：条件直接落在外层 WHERE（tech-plan §5 拦截器实现）。 */
    @Test
    void rewritesPatientTableWithWardDeptOrConditions() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L, 7L), List.of(11L), false);
        String rewritten = enforce("SELECT id FROM pt_patient WHERE deleted = 0", scope);
        assertEquals(
                "SELECT id FROM pt_patient WHERE deleted = 0"
                        + " AND (pt_patient.ward_id IN (3, 7) OR pt_patient.dept_id IN (11))",
                rewritten);
    }

    /** 无 WHERE 的患者查询也能改写（JSqlParser 生成 WHERE 节点）。 */
    @Test
    void rewritesWherelessPatientQuery() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten = enforce("SELECT id FROM pt_patient", scope);
        assertEquals("SELECT id FROM pt_patient WHERE pt_patient.ward_id IN (3)", rewritten);
    }

    /** 带别名的患者表：条件用别名限定。 */
    @Test
    void rewritesAliasedPatientTableWithAliasQualifier() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(11L), true);
        String rewritten = enforce("SELECT p.id FROM pt_patient p", scope);
        assertEquals(
                "SELECT p.id FROM pt_patient p"
                        + " WHERE (p.ward_id IN (3) OR p.dept_id IN (11) OR p.responsible_user_id = 5)",
                rewritten);
    }

    /** 子表统一 EXISTS 关联 pt_patient；本人分支含任务负责人（A-WARD-003 / tech-plan 示例）。 */
    @Test
    void rewritesChildTableWithExistsCorrelatedToPatient() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L, 7L), List.of(), true);
        String rewritten = enforce("SELECT * FROM fu_task t WHERE t.status = 'OVERDUE'", scope);
        assertEquals(
                "SELECT * FROM fu_task t WHERE t.status = 'OVERDUE'"
                        + " AND EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = t.patient_id"
                        + " AND crdp_p.deleted = 0"
                        + " AND (crdp_p.ward_id IN (3, 7) OR crdp_p.responsible_user_id = 5"
                        + " OR t.assignee_id = 5))",
                rewritten);
    }

    /** 没有负责人分支语义的子表（如导入行）只带患者侧条件。 */
    @Test
    void rewritesChildTableWithoutOwnerColumn() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), true);
        String rewritten = enforce("SELECT row_no FROM imp_row r", scope);
        assertEquals(
                "SELECT row_no FROM imp_row r"
                        + " WHERE EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = r.patient_id"
                        + " AND crdp_p.deleted = 0"
                        + " AND (crdp_p.ward_id IN (3) OR crdp_p.responsible_user_id = 5))",
                rewritten);
    }

    /** 未加别名的子表用表名限定关联列。 */
    @Test
    void rewritesUnaliasedChildTableWithTableNameQualifier() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten = enforce("SELECT id FROM rpt_report", scope);
        assertEquals(
                "SELECT id FROM rpt_report"
                        + " WHERE EXISTS (SELECT 1 FROM pt_patient crdp_p"
                        + " WHERE crdp_p.id = rpt_report.patient_id AND crdp_p.deleted = 0"
                        + " AND crdp_p.ward_id IN (3))",
                rewritten);
    }

    /** 病区与科室之间 OR（验收标准）；多角色并集后的清单在同一分支内 IN。 */
    @Test
    void combinesWardAndDeptBranchesWithOr() {
        ScopeSnapshot scope =
                ScopeSnapshot.restricted(List.of(3L, 7L, 9L), List.of(11L, 12L), true);
        String rewritten = enforce("SELECT id FROM pt_patient p", scope);
        assertEquals(
                "SELECT id FROM pt_patient p"
                        + " WHERE (p.ward_id IN (3, 7, 9) OR p.dept_id IN (11, 12)"
                        + " OR p.responsible_user_id = 5)",
                rewritten);
    }

    /** 仅本人负责的患者：患者责任医生 = 本人。 */
    @Test
    void rewritesPatientTableWithSelfOnly() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(), List.of(), true);
        String rewritten = enforce("SELECT id FROM pt_patient", scope);
        assertEquals(
                "SELECT id FROM pt_patient WHERE pt_patient.responsible_user_id = 5", rewritten);
    }

    /** 患者表与子表同场：患者直接条件 + 子表 EXISTS，两者 AND（更严不放宽）。 */
    @Test
    void rewritesJoinedPatientAndChildTables() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten =
                enforce(
                        "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                + " WHERE t.status = 'OVERDUE'",
                        scope);
        assertEquals(
                "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE t.status = 'OVERDUE' AND p.ward_id IN (3)"
                        + " AND EXISTS (SELECT 1 FROM pt_patient crdp_p"
                        + " WHERE crdp_p.id = t.patient_id AND crdp_p.deleted = 0"
                        + " AND crdp_p.ward_id IN (3))",
                rewritten);
    }

    /** WHERE 子查询里的受控表同样改写，不允许从内层绕过范围。 */
    @Test
    void rewritesControlledTableInsideWhereSubquery() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten =
                enforce(
                        "SELECT id FROM sys_dept d WHERE d.id IN"
                                + " (SELECT p.dept_id FROM pt_patient p)",
                        scope);
        assertEquals(
                "SELECT id FROM sys_dept d WHERE d.id IN"
                        + " (SELECT p.dept_id FROM pt_patient p WHERE p.ward_id IN (3))",
                rewritten);
    }

    /** FROM 子查询里的受控表同样改写。 */
    @Test
    void rewritesControlledTableInsideFromSubquery() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten =
                enforce("SELECT x.patient_id FROM (SELECT patient_id FROM fu_task) x", scope);
        assertEquals(
                "SELECT x.patient_id FROM (SELECT patient_id FROM fu_task"
                        + " WHERE EXISTS (SELECT 1 FROM pt_patient crdp_p"
                        + " WHERE crdp_p.id = fu_task.patient_id AND crdp_p.deleted = 0"
                        + " AND crdp_p.ward_id IN (3))) x",
                rewritten);
    }

    /** UNION 的每个分支都改写。 */
    @Test
    void rewritesEveryBranchOfSetOperation() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String rewritten =
                enforce("SELECT id FROM pt_patient UNION SELECT patient_id FROM fu_record", scope);
        assertEquals(
                "SELECT id FROM pt_patient WHERE pt_patient.ward_id IN (3)"
                        + " UNION SELECT patient_id FROM fu_record"
                        + " WHERE EXISTS (SELECT 1 FROM pt_patient crdp_p"
                        + " WHERE crdp_p.id = fu_record.patient_id AND crdp_p.deleted = 0"
                        + " AND crdp_p.ward_id IN (3))",
                rewritten);
    }

    /** 无法覆盖的受控表位置（如 SELECT 项里的标量子查询）拒绝执行，不放行。 */
    @Test
    void failsClosedWhenControlledTableSitsOutsideCoveredPositions() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false);
        String sql =
                "SELECT (SELECT COUNT(*) FROM pt_patient) AS total FROM sys_user u WHERE u.id = ?";
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> enforce(sql, scope));
        assertTrue(failure.getMessage().contains("pt_patient"), "报错应指出未覆盖的表");
    }

    /** 改写后的 SQL 不再带 E07-T01 的占位标记。 */
    @Test
    void restrictedRewriteDropsTheTemporaryMarker() {
        ScopeSnapshot wards = ScopeSnapshot.restricted(List.of(3L, 7L), List.of(), false);
        String rewritten = enforce("SELECT id FROM fu_record", wards);
        assertFalse(rewritten.contains("crdp-datascope:restricted"));
        assertTrue(rewritten.contains("EXISTS"));
    }
}
