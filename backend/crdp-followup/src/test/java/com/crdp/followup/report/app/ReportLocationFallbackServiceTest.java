package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.ScanRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * E16-T15 单测锁定（全部虚构数据，A-ATT-021、C-SCAN-021）：模板调整后的位置退回作业。
 * 验收原文：分节删除后位置退回到分类或整条记录，锚点不存在时移除并提示「原位置已调整」，均写操作日志；重复执行结果不变。
 */
@ExtendWith(MockitoExtension.class)
class ReportLocationFallbackServiceTest {

    private static final long TENANT = 1L;

    private static final String TENANT_CODE = "demoa";

    private static final long PATIENT = 301L;

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final FormService formService = mock(FormService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private final ReportLocationFallbackService service =
            new ReportLocationFallbackService(
                    jobScheduler, mapper, formService, audit, jdbc, transactionManager);

    @BeforeEach
    void setUp() {
        TenantContext.useTenant(TENANT, TENANT_CODE);
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        // 基线当前发布版：含 basic / case 两个分类（巡检与标签共用）
        lenient()
                .when(formService.publishedVersion(BaselineSectionService.BASELINE_FORM_CODE))
                .thenReturn(3);
        lenient()
                .when(formService.schema(BaselineSectionService.BASELINE_FORM_CODE, 3))
                .thenReturn(schema(List.of(category("basic", "基本信息"), category("case", "病例基本信息"))));
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 验收：分节删除后退回整条记录

    /** 记录固化版本已不含该分组 → 退回整条记录（锚点清空 + adjusted=1）+ 报告单标记 + 版本 +1 + 操作日志。 */
    @Test
    void sectionAnchorGoneFallsBackToWholeRecord() {
        when(mapper.scanActiveLinks()).thenReturn(List.of(sectionRow(900L, 11L, 88L, "gone", 0)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));
        // 记录固化版本 v2 的分组清单里没有 gone（模板调整删掉了该分组）
        when(formService.schema("A_ANNUAL_FU", 2))
                .thenReturn(schema(List.of(category("echo", "超声复查"))));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(1, summary.reports());
        assertEquals(1, summary.fallbacks(), "分组退回整条记录");
        assertEquals(0, summary.removals());
        verify(mapper).fallbackToWholeRecord(11L);
        verify(mapper, never()).deleteLinkById(anyLong());
        verify(mapper).markReportLocationAdjusted(900L);
        verify(mapper).bumpVersionByJob(900L);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_LOCATION_FALLBACK", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertTrue(entry.description().contains("退回 1 个"), entry.description());
        assertTrue(
                entry.description().contains("第2年随访 2026-06-08 · gone → 整条记录"),
                "描述带退回前后位置（分组已删、标签回落键值）：" + entry.description());
        assertTrue(entry.beforeJson().contains("gone"), entry.beforeJson());
        assertTrue(entry.afterJson().contains("\"anchorKey\":null"), entry.afterJson());
    }

    /** 同一记录的整条位置已在册：分组行移除、既有整条行置退回标记（同一报告单同一位置唯一，V63 uk）。 */
    @Test
    void sectionFallingBackOntoExistingWholeRowMerges() {
        when(mapper.scanActiveLinks())
                .thenReturn(
                        List.of(
                                wholeRow(900L, 10L, 88L, 0),
                                sectionRow(900L, 11L, 88L, "gone", 0)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));
        when(formService.schema("A_ANNUAL_FU", 2))
                .thenReturn(schema(List.of(category("echo", "超声复查"))));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(1, summary.reports());
        assertEquals(0, summary.fallbacks(), "整条已在册，不新增行");
        assertEquals(1, summary.removals(), "分组行按移除落库");
        verify(mapper, never()).fallbackToWholeRecord(anyLong());
        verify(mapper).deleteLinkById(11L);
        verify(mapper).markLinkAdjusted(10L);
        verify(mapper).markReportLocationAdjusted(900L);
        verify(mapper).bumpVersionByJob(900L);
        verify(audit).append(any(AuditEntry.class)); // 均写操作日志（合并场景同样一条 RPT_LOCATION_FALLBACK）
    }

    // ---------------------------------------------------------------- 验收：锚点不存在时移除

    /** 基线分类已不在当前发布版（基线分类即最细粒度、无上一级可退）→ 移除位置并提示。 */
    @Test
    void baselineCategoryGoneFromCurrentTemplateRemovesLink() {
        when(mapper.scanActiveLinks()).thenReturn(List.of(baselineRow(901L, 21L, "vanished", 0)));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(1, summary.removals());
        verify(mapper).deleteLinkById(21L);
        verify(mapper, never()).fallbackToWholeRecord(anyLong());
        verify(mapper).markReportLocationAdjusted(901L);
        verify(mapper).bumpVersionByJob(901L);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().description().contains("移除 1 个"),
                captor.getValue().description());
        assertTrue(
                captor.getValue().description().contains("基线 · vanished"),
                captor.getValue().description());
    }

    /** 随访记录不存在（整条 / 分组都无退回目标）→ 移除位置。 */
    @Test
    void missingRecordRemovesFollowupLinks() {
        when(mapper.scanActiveLinks())
                .thenReturn(
                        List.of(
                                wholeRow(902L, 30L, 99L, 0),
                                sectionRow(902L, 31L, 99L, "echo", 0)));
        when(mapper.findRecordById(99L)).thenReturn(null);

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(1, summary.reports());
        assertEquals(2, summary.removals(), "整条与分组行都移除");
        verify(mapper).deleteLinkById(30L);
        verify(mapper).deleteLinkById(31L);
        verify(mapper).markReportLocationAdjusted(902L);
    }

    // ---------------------------------------------------------------- 验收：重复执行结果不变

    /** 锚点全部有效 → 零写入、零日志、版本不动（幂等：重复执行结果不变）。 */
    @Test
    void validAnchorsLeaveEverythingUntouched() {
        when(mapper.scanActiveLinks())
                .thenReturn(
                        List.of(
                                baselineRow(900L, 10L, "case", 0),
                                wholeRow(900L, 11L, 88L, 0),
                                sectionRow(901L, 12L, 88L, "echo", 0)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));
        when(formService.schema("A_ANNUAL_FU", 2))
                .thenReturn(schema(List.of(category("echo", "超声复查"))));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(0, summary.reports());
        assertEquals(0, summary.fallbacks());
        assertEquals(0, summary.removals());
        verify(mapper, never()).fallbackToWholeRecord(anyLong());
        verify(mapper, never()).deleteLinkById(anyLong());
        verify(mapper, never()).markLinkAdjusted(anyLong());
        verify(mapper, never()).markReportLocationAdjusted(anyLong());
        verify(mapper, never()).bumpVersionByJob(anyLong());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 退回后的整条行再次巡检不再命中（锚点已空、记录仍在）——重复执行同一租户结果不变。 */
    @Test
    void fallenBackRowIsStableOnRerun() {
        when(mapper.scanActiveLinks()).thenReturn(List.of(wholeRow(900L, 11L, 88L, 1)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(0, summary.reports());
        verify(mapper, never()).fallbackToWholeRecord(anyLong());
        verify(mapper, never()).deleteLinkById(anyLong());
        verify(mapper, never()).bumpVersionByJob(anyLong());
    }

    // ---------------------------------------------------------------- 已电子化随位置移除回待录入（E21-T27 /
    // C-SCAN-020、C 规格 §7）

    /**
     * 记录不存在移除其随访位置（整条 / 分组都移除）→ 已电子化报告单自动回「待录入」（C-SCAN-020 §7「移除其随访记录位置时， 状态回到待录入」），写
     * RPT_DIGITIZE_CHANGE；分组退回整条（记录仍在）不触发。
     */
    @Test
    void removingLastFollowupLocationRevertsDoneToPending() {
        when(mapper.scanActiveLinks())
                .thenReturn(
                        List.of(
                                wholeRow(902L, 30L, 99L, 0),
                                sectionRow(902L, 31L, 99L, "echo", 0)));
        when(mapper.findRecordById(99L)).thenReturn(null);
        // 作业口径的回退 UPDATE 命中 1 行（= 当前态 DONE）
        when(mapper.revertDoneToPendingByJob(902L)).thenReturn(1);

        service.reconcileCurrentTenant();

        verify(mapper).deleteLinkById(30L);
        verify(mapper).deleteLinkById(31L);
        verify(mapper).revertDoneToPendingByJob(902L);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, atLeast(2)).append(captor.capture());
        AuditEntry revert =
                captor.getAllValues().stream()
                        .filter(entry -> "RPT_DIGITIZE_CHANGE".equals(entry.action()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("未写 RPT_DIGITIZE_CHANGE"));
        assertTrue(revert.description().contains("已电子化 → 待录入"), "描述记自动回退：" + revert.description());
        assertTrue(revert.description().contains("移除随访记录位置"), "描述记触发原因：" + revert.description());
        assertTrue(revert.beforeJson().contains("DONE"), revert.beforeJson());
        assertTrue(revert.afterJson().contains("PENDING"), revert.afterJson());
    }

    /** 分组退回整条记录（记录仍在，随访位置未失去）与 UPDATE 未命中（当前态非 DONE）都不产生回退日志。 */
    @Test
    void fallbackToWholeRecordOrNonDoneStatusKeepsDigitize() {
        // ① 分组退回整条：随访位置仍在（记录 88 存在）
        when(mapper.scanActiveLinks()).thenReturn(List.of(sectionRow(900L, 11L, 88L, "gone", 0)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));
        when(formService.schema("A_ANNUAL_FU", 2))
                .thenReturn(schema(List.of(category("echo", "超声复查"))));
        service.reconcileCurrentTenant();
        verify(mapper, never()).revertDoneToPendingByJob(anyLong());

        // ② 位置确被移除但 UPDATE 命中 0 行（当前态非 DONE / 已回退过）：不写回退日志（幂等、无变化不留痕）
        reset(mapper, audit, formService);
        lenient()
                .when(formService.publishedVersion(BaselineSectionService.BASELINE_FORM_CODE))
                .thenReturn(3);
        lenient()
                .when(formService.schema(BaselineSectionService.BASELINE_FORM_CODE, 3))
                .thenReturn(schema(List.of(category("basic", "基本信息"), category("case", "病例基本信息"))));
        when(mapper.scanActiveLinks()).thenReturn(List.of(wholeRow(903L, 40L, 98L, 0)));
        when(mapper.findRecordById(98L)).thenReturn(null);
        when(mapper.revertDoneToPendingByJob(903L)).thenReturn(0);

        service.reconcileCurrentTenant();

        verify(mapper).revertDoneToPendingByJob(903L);
        verify(audit, never())
                .append(argThat(e -> e != null && "RPT_DIGITIZE_CHANGE".equals(e.action())));
    }

    // ---------------------------------------------------------------- 防御与扇出

    /** 记录固化版本快照读不出（防御：正常运营不会发生）→ 该行跳过，不做无依据的退回。 */
    @Test
    void unreadableRecordSnapshotSkipsRow() {
        when(mapper.scanActiveLinks()).thenReturn(List.of(sectionRow(900L, 11L, 88L, "echo", 0)));
        when(mapper.findRecordById(88L)).thenReturn(record(88L));
        when(formService.schema("A_ANNUAL_FU", 2))
                .thenThrow(new ApiException(404, 40400, "模板版本不存在"));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(0, summary.reports());
        verify(mapper, never()).fallbackToWholeRecord(anyLong());
        verify(mapper, never()).deleteLinkById(anyLong());
    }

    /** 单报告单失败不拖垮整院：坏行 WARN 后继续处理下一个报告单。 */
    @Test
    void singleReportFailureDoesNotBlockOthers() {
        when(mapper.scanActiveLinks())
                .thenReturn(
                        List.of(
                                sectionRow(900L, 11L, 88L, "echo", 0), // 记录缺失 → 正常移除
                                baselineRow(901L, 21L, "vanished", 0)));
        when(mapper.findRecordById(88L)).thenReturn(null);
        when(mapper.deleteLinkById(11L)).thenThrow(new IllegalStateException("DB 抖动（示例）"));

        ReportLocationFallbackService.TenantSummary summary = service.reconcileCurrentTenant();

        assertEquals(1, summary.reports(), "失败的报告单不计入，后续报告单照常处理");
        assertEquals(1, summary.removals());
        verify(mapper).deleteLinkById(21L);
        verify(mapper).markReportLocationAdjusted(901L);
    }

    /** runForDate 经 JobScheduler 扇出；模块未开通的租户作业体直接跳过。 */
    @Test
    void tenantJobSkipsWhenFollowupModuleClosed() {
        when(jobScheduler.runForEachTenant(anyString(), any(), any())).thenReturn(List.of());
        service.runForDate(LocalDate.of(2026, 11, 7));
        verify(jobScheduler).runForEachTenant(eq("rpt-location-fallback"), any(), any());

        // 模块闸门：平台库计数 0 → 不巡检
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT))).thenReturn(0);
        service.runForCurrentTenant(LocalDate.of(2026, 11, 7));
        verify(mapper, never()).scanActiveLinks();
    }

    // ---------------------------------------------------------------- 造数

    private static ScanRow baselineRow(long reportId, long linkId, String key, int adjusted) {
        return new ScanRow(
                linkId,
                reportId,
                PATIENT,
                "检验",
                "血常规（示例）",
                "baseline",
                null,
                "category",
                key,
                3,
                adjusted);
    }

    private static ScanRow wholeRow(long reportId, long linkId, long recordId, int adjusted) {
        return new ScanRow(
                linkId,
                reportId,
                PATIENT,
                "检验",
                "血常规（示例）",
                "followup_record",
                recordId,
                null,
                null,
                2,
                adjusted);
    }

    private static ScanRow sectionRow(
            long reportId, long linkId, long recordId, String key, int adjusted) {
        return new ScanRow(
                linkId,
                reportId,
                PATIENT,
                "检验",
                "血常规（示例）",
                "followup_record",
                recordId,
                "section",
                key,
                2,
                adjusted);
    }

    private static RecordRow record(long id) {
        return new RecordRow(
                id,
                500L,
                "第2年随访",
                "TASK",
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 6, 8),
                "A_ANNUAL_FU",
                2,
                "SUBMITTED");
    }

    private static FormService.SchemaView schema(List<FormService.CategoryView> categories) {
        return new FormService.SchemaView(
                "A_ANNUAL_FU", "年度随访（示例）", "FOLLOWUP", 2, categories, List.of());
    }

    private static FormService.CategoryView category(String key, String label) {
        return new FormService.CategoryView(key, label, 10, List.of());
    }
}
