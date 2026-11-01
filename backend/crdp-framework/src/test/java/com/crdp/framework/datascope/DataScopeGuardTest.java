package com.crdp.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataScopeGuardTest {

    @Test
    void leavesUncontrolledTablesAlone() {
        String sql = "SELECT id FROM sys_user WHERE username = ?";
        assertEquals(sql, DataScopeGuard.enforce(sql, null, false));
    }

    @Test
    void deniesControlledQueryWithoutScope() {
        assertThrows(
                DataScopeDeniedException.class,
                () -> DataScopeGuard.enforce("SELECT * FROM pt_patient", null, false));
    }

    @Test
    void passesWhenScopeIsAll() {
        String sql = "SELECT * FROM fu_task t WHERE t.status = 'OVERDUE'";
        assertEquals(sql, DataScopeGuard.enforce(sql, ScopeSnapshot.allowAll(), false));
    }

    @Test
    void emptyScopeAppendsFalsePredicateToWherelessSelect() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten = DataScopeGuard.enforce("SELECT * FROM pt_patient", empty, false);
        assertEquals("SELECT * FROM pt_patient WHERE 1 = 0", rewritten);
    }

    @Test
    void emptyScopeAppendsFalsePredicateToExistingWhere() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten =
                DataScopeGuard.enforce("SELECT * FROM pt_patient WHERE deleted = ?", empty, false);
        assertEquals("SELECT * FROM pt_patient WHERE deleted = ? AND 1 = 0", rewritten);
    }

    @Test
    void emptyScopeAppendsFalsePredicateToJoinedQuery() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten =
                DataScopeGuard.enforce(
                        "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                + " WHERE t.status = 'OVERDUE'",
                        empty,
                        false);
        assertEquals(
                "SELECT t.id FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE t.status = 'OVERDUE' AND 1 = 0",
                rewritten);
    }

    @Test
    void unparseableControlledSqlFailsClosedInsteadOfPassingThrough() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        assertThrows(
                IllegalStateException.class,
                () -> DataScopeGuard.enforce("SELECT FROM pt_patient WHERE", empty, false));
    }

    @Test
    void marksRestrictedScopeWithoutRewritingThePredicateYet() {
        ScopeSnapshot wards = ScopeSnapshot.restricted(List.of(3L, 7L), List.of(), false);
        String rewritten = DataScopeGuard.enforce("SELECT * FROM fu_record", wards, false);
        assertTrue(rewritten.contains("crdp-datascope:restricted"));
    }

    @Test
    void ignoreAnnotationBypassesTheGuard() {
        String sql = "SELECT * FROM pt_patient";
        assertEquals(sql, DataScopeGuard.enforce(sql, null, true));
    }
}
