package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;

import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordInsert;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.stats.infra.mapper.AbnormalRecordsMapper;
import com.crdp.framework.datascope.DataScopeContext;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OQR-05A：真实持久化、首次提交不变、旧值未知、统计窗口与回滚；全部虚构示例。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class RecordFactTimeIT {
    private static final long WARD = 987650321L;
    private final String marker = "OQR05A-" + UUID.randomUUID();
    private final Map<Long, Long> patients = new LinkedHashMap<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired FollowupRecordMapper records;
    @Autowired ImportCommitMapper imports;
    @Autowired DashboardStatsMapper stats;
    @Autowired AbnormalRecordsMapper abnormal;
    @Autowired PlatformTransactionManager transactions;

    private <T> T tenant(long id, Supplier<T> action) {
        var scope = ScopeSnapshot.restricted(List.of(WARD), List.of(), false);
        var principal =
                new SessionPrincipal(
                        "test",
                        "HOSPITAL",
                        id,
                        id == 1 ? "demoa" : "demob",
                        "医院（示例）",
                        1L,
                        "user.example",
                        "医生（示例）",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("followup"),
                        1L,
                        scope,
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        DataScopeContext.set(scope, 1L);
        try {
            return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", action);
        } finally {
            DataScopeContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void emptyScope() {
        var p = com.crdp.framework.security.CurrentSession.requireHospital();
        var empty = ScopeSnapshot.restricted(List.of(), List.of(), false);
        var principal =
                new SessionPrincipal(
                        p.token(),
                        p.sessionType(),
                        p.tenantId(),
                        p.tenantCode(),
                        p.tenantName(),
                        p.userId(),
                        p.username(),
                        p.displayName(),
                        p.roles(),
                        p.menuIds(),
                        p.permissionCodes(),
                        p.openedModules(),
                        p.permissionVersion(),
                        empty,
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        DataScopeContext.set(empty, 1);
    }

    private long patient(long tenant) {
        return patients.computeIfAbsent(
                tenant,
                ignored -> {
                    jdbc.update(
                            "INSERT INTO pt_patient(mrn_cipher,mrn_blind,name_cipher,name_blind,gender,ward_id) VALUES (?,?,?,?,'F',?)",
                            "密文占位（示例）",
                            marker,
                            "密文占位（示例）",
                            marker,
                            WARD);
                    return jdbc.queryForObject(
                            "SELECT id FROM pt_patient WHERE mrn_blind=?", Long.class, marker);
                });
    }

    private long draft(long tenant) {
        var insert =
                new FollowupRecordInsert(
                        patient(tenant),
                        null,
                        "BACKFILL",
                        LocalDate.of(2000, 1, 2),
                        null,
                        "A_ANNUAL_FU",
                        1,
                        "{}",
                        "{}",
                        1L);
        records.insertRecord(insert);
        return insert.getId();
    }

    private LocalDateTime first(long id) {
        return jdbc.queryForObject(
                "SELECT first_submitted_at FROM fu_record WHERE id=?", LocalDateTime.class, id);
    }

    @AfterEach
    void cleanup() {
        for (var entry : patients.entrySet())
            tenant(
                    entry.getKey(),
                    () -> {
                        jdbc.update("DELETE FROM fu_record WHERE patient_id=?", entry.getValue());
                        jdbc.update("DELETE FROM pt_patient WHERE id=?", entry.getValue());
                        return null;
                    });
    }

    @Test
    void firstSubmissionIsImmutableAcrossDraftMappingEditRecalculationAndDeleteInBothTenants() {
        for (long tenant : List.of(1L, 2L))
            tenant(
                    tenant,
                    () -> {
                        long id = draft(tenant);
                        assertThat(first(id)).isNull();
                        assertThat(records.updateDraft(id, 0, "{}", 1)).isEqualTo(1);
                        assertThat(records.applyMappingWrite(id, 1, "{}", 1)).isEqualTo(1);
                        assertThat(first(id)).isNull();
                        assertThat(records.submitRecord(id, 2, "{}", "{}", null, 1)).isEqualTo(1);
                        LocalDateTime submitted = first(id);
                        assertThat(submitted).isNotNull();
                        assertThat(records.findRecord(id).firstSubmittedAt()).isEqualTo(submitted);
                        assertThat(records.submitRecord(id, 2, "{}", "{}", null, 1)).isZero();
                        assertThat(records.updateSubmittedRecord(id, 3, "{}", "{}", 1))
                                .isEqualTo(1);
                        assertThat(records.applyMappingWrite(id, 4, "{}", 1)).isEqualTo(1);
                        jdbc.update(
                                "UPDATE fu_record SET highlight='[]',sequence_no=7,updated_at='2040-01-01 00:00:00' WHERE id=?",
                                id);
                        assertThat(first(id)).isEqualTo(submitted);
                        records.softDelete(id, "删除（示例）", 1);
                        assertThat(first(id)).isEqualTo(submitted);
                        return null;
                    });
    }

    @Test
    void importSetsKnownTimeOnlyOnNewSubmissionAndPreservesLegacyUnknown() {
        tenant(
                1,
                () -> {
                    for (String status : List.of("DRAFT", "SUBMITTED")) {
                        var row =
                                new ImportCommitMapper.ImportedRecordInsert(
                                        patient(1),
                                        null,
                                        null,
                                        null,
                                        1999,
                                        null,
                                        null,
                                        null,
                                        "HIST_IMPORT",
                                        LocalDate.of(1999, 1, 1),
                                        "A_ANNUAL_FU",
                                        1,
                                        status,
                                        "{}",
                                        "{}",
                                        1L);
                        imports.insertImportedRecord(row);
                        long id = row.getId();
                        if (status.equals("DRAFT")) assertThat(first(id)).isNull();
                        else assertThat(first(id)).isNotNull();
                        imports.updateImportedRecord(
                                id,
                                "HIST_IMPORT",
                                "SUBMITTED",
                                LocalDate.of(1999, 2, 1),
                                "{}",
                                "{}",
                                1L);
                        LocalDateTime known = first(id);
                        assertThat(known).isNotNull();
                        imports.updateImportedRecord(
                                id,
                                "HIST_IMPORT",
                                "SUBMITTED",
                                LocalDate.of(1999, 3, 1),
                                "{}",
                                "{}",
                                1L);
                        assertThat(first(id)).isEqualTo(known);
                        jdbc.update("UPDATE fu_record SET first_submitted_at=NULL WHERE id=?", id);
                        imports.updateImportedRecord(
                                id,
                                "HIST_IMPORT",
                                "SUBMITTED",
                                LocalDate.of(1999, 4, 1),
                                "{}",
                                "{}",
                                1L);
                        assertThat(first(id)).isNull();
                    }
                    return null;
                });
    }

    @Test
    void statisticsUseSubmissionFactAndExposeUnknownWithoutCountingModifiedLegacyRows() {
        tenant(
                1,
                () -> {
                    long old = draft(1),
                            recent = draft(1),
                            unknown = draft(1),
                            deleted = draft(1),
                            pending = draft(1);
                    for (long id : List.of(old, recent, unknown, deleted))
                        records.submitRecord(id, 0, "{}", "{}", null, 1);
                    jdbc.update(
                            "UPDATE fu_record SET highlight='[]',updated_at='2040-01-01 00:00:00' WHERE patient_id=?",
                            patient(1));
                    jdbc.update(
                            "UPDATE fu_record SET first_submitted_at='2000-01-01 00:00:00' WHERE id=?",
                            old);
                    jdbc.update(
                            "UPDATE fu_record SET first_submitted_at='2026-11-18 10:00:00',updated_at='2000-01-01 00:00:00' WHERE id=?",
                            recent);
                    jdbc.update("UPDATE fu_record SET first_submitted_at=NULL WHERE id=?", unknown);
                    records.softDelete(deleted, "删除（示例）", 1);
                    var since = LocalDateTime.of(2026, 11, 1, 0, 0);
                    assertThat(stats.countRecentHighlightRecords(since, WARD)).isEqualTo(1);
                    assertThat(abnormal.findRecentHighlights(since, WARD, 20))
                            .extracting(AbnormalRecordsMapper.HighlightRecordRow::id)
                            .containsExactly(recent);
                    assertThat(stats.countUnknownHighlightSubmissionTimes(WARD)).isEqualTo(1);
                    // 无范围时三个读取都应为空；不能暴露未知时间的其他患者数量。
                    emptyScope();
                    assertThat(stats.countRecentHighlightRecords(since, WARD)).isZero();
                    emptyScope();
                    assertThat(abnormal.findRecentHighlights(since, WARD, 20)).isEmpty();
                    emptyScope();
                    assertThat(stats.countUnknownHighlightSubmissionTimes(WARD)).isZero();
                    return null;
                });
        tenant(
                2,
                () -> {
                    assertThat(
                                    stats.countRecentHighlightRecords(
                                            LocalDateTime.of(2026, 11, 1, 0, 0), WARD))
                            .isZero();
                    return null;
                });
    }

    @Test
    void submissionFailureRollsBackTimestampTogetherWithStatusAndVersion() {
        tenant(
                1,
                () -> {
                    long id = draft(1);
                    assertThatThrownBy(
                                    () ->
                                            new TransactionTemplate(transactions)
                                                    .execute(
                                                            status -> {
                                                                records.submitRecord(
                                                                        id, 0, "{}", "{}", null, 1);
                                                                throw new IllegalStateException(
                                                                        "提交后续失败（示例）");
                                                            }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(first(id)).isNull();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM fu_record WHERE id=?",
                                            String.class,
                                            id))
                            .isEqualTo("DRAFT");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT version FROM fu_record WHERE id=?",
                                            Integer.class,
                                            id))
                            .isZero();
                    return null;
                });
    }
}
