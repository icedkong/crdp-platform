package com.fu.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fu.framework.security.ScopeSnapshot;
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
    void appendsFalsePredicateWhenRestrictedScopeIsEmpty() {
        ScopeSnapshot empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        String rewritten = DataScopeGuard.enforce("SELECT * FROM pt_patient", empty, false);
        assertTrue(rewritten.contains("1 = 0"));
    }

    @Test
    void marksRestrictedScopeWithoutRewritingThePredicateYet() {
        ScopeSnapshot wards = ScopeSnapshot.restricted(List.of(3L, 7L), List.of(), false);
        String rewritten = DataScopeGuard.enforce("SELECT * FROM fu_record", wards, false);
        assertTrue(rewritten.contains("fu-datascope:restricted"));
    }

    @Test
    void ignoreAnnotationBypassesTheGuard() {
        String sql = "SELECT * FROM pt_patient";
        assertEquals(sql, DataScopeGuard.enforce(sql, null, true));
    }
}
