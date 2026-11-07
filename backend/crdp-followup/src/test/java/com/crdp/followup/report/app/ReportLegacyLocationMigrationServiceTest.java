package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.LegacyScanRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.YearRecordRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * E18-T08 单测锁定（全部虚构数据，A-ATT-002 / 2026-10-31 位置必填变更）：历史报告单位置迁移。
 * 验收原文：迁移脚本可重入；按年关联的落到该年随访记录整条，原关联基线的进入未指定位置，迁移报告列出数量。
 */
@ExtendWith(MockitoExtension.class)
class ReportLegacyLocationMigrationServiceTest {

    private static final long TENANT = 1L;

    private static final String TENANT_CODE = "demoa";

    private static final long PATIENT = 301L;

    private static final long OPERATOR = 7L;

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private final ReportLegacyLocationMigrationService service =
            new ReportLegacyLocationMigrationService(mapper, audit, transactionManager);

    @BeforeEach
    void setUp() {
        TenantContext.useTenant(TENANT, TENANT_CODE);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 验收：按年关联 → 该年随访记录整条

    /** 按年关联且该年有随访记录 → 位置行落整条（模板版本 = 记录固化版本）+ 版本推进 + 标记迁移 + 操作日志。 */
    @Test
    void yearLinkedReportLandsOnThatYearWholeRecord() {
        when(mapper.scanPendingLegacyReports()).thenReturn(List.of(legacyRow(900L, "YEAR", 3)));
        when(mapper.linksOfReportForMigration(900L)).thenReturn(List.of());
        when(mapper.findYearRecord(PATIENT, 3)).thenReturn(new YearRecordRow(88L, 2));

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(1, report.yearLinked(), "按年关联落到该年随访记录整条");
        assertEquals(0, report.baselineUnassigned());
        assertEquals(0, report.yearWithoutRecord());
        assertEquals(0, report.invalidSkipped());
        assertEquals(0, report.alreadyMigrated());

        ArgumentCaptor<ReportMapper.LinkInsert> link =
                ArgumentCaptor.forClass(ReportMapper.LinkInsert.class);
        verify(mapper).insertLink(link.capture());
        assertEquals("followup_record", link.getValue().linkType());
        assertEquals(88L, link.getValue().linkRecordId());
        assertEquals(null, link.getValue().anchorType());
        assertEquals(null, link.getValue().anchorKey());
        assertEquals(PATIENT, link.getValue().patientId());
        assertEquals(2, link.getValue().templateVersion(), "模板版本取该记录固化版本");
        verify(mapper).bumpVersion(900L, OPERATOR);
        verify(mapper).markLegacyMigrated(900L);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_LEGACY_LOCATION_MIGRATE", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertTrue(entry.description().contains("第3年随访"), entry.description());
        assertTrue(entry.description().contains("整条"), entry.description());
    }

    /** 该报告单已有同记录整条位置（人工先行指定）→ 不重复落位置行，只标记迁移并计数。 */
    @Test
    void alreadyPlacedWholeRecordLinkNotDuplicated() {
        when(mapper.scanPendingLegacyReports()).thenReturn(List.of(legacyRow(901L, "YEAR", 5)));
        when(mapper.linksOfReportForMigration(901L))
                .thenReturn(
                        List.of(new LinkRow(11L, 901L, "followup_record", 88L, null, null, 2, 0)));
        when(mapper.findYearRecord(PATIENT, 5)).thenReturn(new YearRecordRow(88L, 2));

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(1, report.yearLinked(), "已在册同位置视作已落到该年记录");
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).bumpVersion(anyLong(), anyLong());
        verify(mapper).markLegacyMigrated(901L);
    }

    // ---------------------------------------------------------------- 验收：基线关联 → 未指定位置

    /** 原关联基线 → 不落位置行（零位置 = 未指定位置组，A-ATT-020 不集中补指定）+ 标记迁移 + 操作日志。 */
    @Test
    void baselineLinkedReportEntersUnassigned() {
        when(mapper.scanPendingLegacyReports())
                .thenReturn(List.of(legacyRow(910L, "BASELINE", null)));
        when(mapper.linksOfReportForMigration(910L)).thenReturn(List.of());

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(1, report.baselineUnassigned(), "原关联基线进入未指定位置");
        assertEquals(0, report.yearLinked());
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).bumpVersion(anyLong(), anyLong());
        verify(mapper).markLegacyMigrated(910L);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().description().contains("未指定位置"), captor.getValue().description());
    }

    // ---------------------------------------------------------------- 验收：可重入与报告数量

    /** 按年关联但该年无随访记录（窗口未结束 / 未导入）→ 保持待迁移不标记，迁移报告计数；下次执行可补。 */
    @Test
    void yearWithoutRecordStaysPendingAndCounted() {
        when(mapper.scanPendingLegacyReports()).thenReturn(List.of(legacyRow(920L, "YEAR", 9)));
        when(mapper.linksOfReportForMigration(920L)).thenReturn(List.of());
        when(mapper.findYearRecord(PATIENT, 9)).thenReturn(null);

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(1, report.yearWithoutRecord(), "该年无随访记录：留在未指定位置、保持待迁移");
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).markLegacyMigrated(anyLong());
        verify(audit, never()).append(any());
    }

    /** 重入：无待迁移行（上次已迁移）→ 零写入零日志，报告只列已迁移数量。 */
    @Test
    void reRunWithoutPendingRowsWritesNothing() {
        when(mapper.scanPendingLegacyReports()).thenReturn(List.of());
        when(mapper.countMigratedLegacyReports()).thenReturn(4L);

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(0, report.yearLinked());
        assertEquals(0, report.baselineUnassigned());
        assertEquals(0, report.yearWithoutRecord());
        assertEquals(4, report.alreadyMigrated(), "迁移报告列出已迁移数量");
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).markLegacyMigrated(anyLong());
        verify(audit, never()).append(any());
        assertEquals(TENANT_CODE, report.tenantCode());
    }

    /** 防御：不认识的关联形态 / YEAR 缺年度 → 跳过并计数，不做无依据的落位。 */
    @Test
    void invalidLegacyShapeSkippedAndCounted() {
        when(mapper.scanPendingLegacyReports())
                .thenReturn(List.of(legacyRow(930L, "YEAR", null), legacyRow(931L, "WHATEVER", 1)));

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(2, report.invalidSkipped());
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).markLegacyMigrated(anyLong());
        verify(mapper, never()).findYearRecord(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** 单报告单失败不拖垮整批（一条坏行 WARN 继续，与 E16-T15 作业同口径）。 */
    @Test
    void singleReportFailureDoesNotAbortBatch() {
        when(mapper.scanPendingLegacyReports())
                .thenReturn(List.of(legacyRow(940L, "YEAR", 2), legacyRow(941L, "BASELINE", null)));
        when(mapper.findYearRecord(PATIENT, 2)).thenReturn(new YearRecordRow(88L, 2));
        when(mapper.linksOfReportForMigration(anyLong()))
                .thenThrow(new IllegalStateException("坏行（示例）"))
                .thenReturn(List.of());

        ReportLegacyLocationMigrationService.MigrationReport report =
                service.migrateCurrentTenant();

        assertEquals(0, report.yearLinked(), "失败报告单不计入完成数");
        assertEquals(1, report.baselineUnassigned(), "后续报告单照常迁移");
    }

    // ---------------------------------------------------------------- 构造

    private static LegacyScanRow legacyRow(long reportId, String type, Integer year) {
        return new LegacyScanRow(reportId, PATIENT, "心脏超声（示例）", "经胸超声心动图（示例）", type, year);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                TENANT_CODE,
                "医院 A（示例租户）",
                OPERATOR,
                "admin.demo",
                "管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
