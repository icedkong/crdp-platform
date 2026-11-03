package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientOverviewMapper;
import com.crdp.followup.patient.infra.mapper.PatientStatusHistoryMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010 单元测试（验收「概览显示基线、计划、最近随访」）。
 *
 * <p>锁定口径：概览 = PatientHeader 数据（姓名 / 病案号只输出脱敏值；基线与随访资料完整度、 下次随访（计划）、最近随访关键指标来自 pt_patient_summary
 * 派生列，E13-T02 / E14 / E15 写入前为 null，前端显示「—」）； 已终止患者（死亡 / 停止 / 失访）terminated = true 且 actions
 * 不含需在随访中的操作； 时间轴按时间顺序（旧 → 新）返回出院节点与状态变更事件（登记初始行不重复展示）， 随访记录与计划外随访节点随 E15 接入。越范围 / 不存在一律
 * 40400（AGENTS.md §5.2）。全部虚构数据。
 */
class PatientOverviewServiceTest {

    private static final long OPERATOR_ID = 9L;
    private static final long PATIENT_ID = 101L;
    private static final String NAME = "张小明（示例）";
    private static final String MRN = "IT-MRN-1001";

    private final PatientOverviewGateway gateway = mock(PatientOverviewGateway.class);
    private final PatientStatusGateway statusGateway = mock(PatientStatusGateway.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final PatientOverviewService service =
            new PatientOverviewService(gateway, statusGateway, crypto);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(List.of()), null, List.of()));
        when(crypto.decrypt(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0).toString().replace("CIPHER:", ""));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 概览（验收：显示基线、计划、最近随访）

    @Test
    void summaryMasksIdentityAndCarriesBaselinePlanAndLatestFollowup() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));

        PatientOverviewService.PatientOverviewView view = service.summaryOf(PATIENT_ID);

        assertEquals("101", view.id());
        assertEquals("张*）", view.name(), "姓名只输出脱敏值（明文走 E07-T05 专用接口）");
        assertEquals("IT*******01", view.patientNo(), "病案号脱敏（前 2 后 2）");
        assertEquals("OLD-A-1", view.legacyNo());
        assertEquals("M", view.gender());
        assertEquals(71, view.age(), "年龄 = 手术日期 − 出生日期（周岁，V36 口径）");
        assertFalse(view.terminated(), "随访中患者未终止");
        assertEquals("示例二病区", view.wardName());
        assertEquals("王医生（示例）", view.responsibleName());
        // 基线（A-PAT-004）：基线资料完整度 + 登记实体列（分节随 E13-T02）
        assertEquals(92, view.baselineCompleteness());
        assertEquals(LocalDate.of(2026, 3, 8), view.dischargeDate());
        // 计划（下次随访窗口，pt_patient_summary；E14 写入前为 null → 前端显示占位）
        assertEquals(LocalDate.of(2026, 11, 5), view.nextVisit().planDate());
        assertEquals(LocalDate.of(2026, 10, 29), view.nextVisit().windowStart());
        assertEquals(LocalDate.of(2026, 11, 12), view.nextVisit().windowEnd());
        // 最近随访（关键指标，pt_patient_summary；E15 写入前为 null）
        assertEquals(LocalDate.of(2026, 6, 10), view.latestFollowup().date());
        assertEquals("ALIVE", view.latestFollowup().survivalStatus());
        assertEquals(55, view.latestFollowup().keyIndicators().get("ef"));
        assertEquals(
                "2025-03-02", view.latestFollowup().endpointEvents().get("re_hospitalization"));
        assertNull(view.device(), "C 当前器械随 E21 植入数据开放，A / E21 前恒 null");
    }

    @Test
    void summaryKeepsDerivedColumnsNullUntilWritersLand() {
        PatientOverviewMapper.OverviewRow row =
                new PatientOverviewMapper.OverviewRow(
                        PATIENT_ID,
                        "CIPHER:" + NAME,
                        "CIPHER:" + MRN,
                        null,
                        null,
                        "F",
                        null,
                        "ACTIVE",
                        "示例一病区",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row);

        PatientOverviewService.PatientOverviewView view = service.summaryOf(PATIENT_ID);

        assertNull(view.age(), "无出生日期时手术年龄不可算（A 随基线补录）");
        assertNull(view.baselineCompleteness(), "基线完整度随 E13-T02 写入");
        assertNull(view.followupCompleteness(), "随访完整度随 E15 写入");
        assertNull(view.nextVisit(), "下次随访窗口随 E14 写入");
        assertNull(view.latestFollowup(), "最近随访关键指标随 E15 写入");
        assertTrue(view.actions().isEmpty(), "无操作权限时 actions 为空（只读角色不显示操作按钮）");
    }

    @Test
    void summaryMarksTerminatedPatientAndDropsInFollowupActions() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("DEAD"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(
                                        List.of(
                                                "followup:record:edit",
                                                "followup:scan:upload",
                                                "followup:plan:enroll")),
                                null,
                                List.of()));

        PatientOverviewService.PatientOverviewView view = service.summaryOf(PATIENT_ID);

        assertTrue(view.terminated(), "死亡 / 停止 / 失访视为已终止");
        assertFalse(view.actions().contains("unplanned"), "已终止不能新建计划外随访");
        assertFalse(view.actions().contains("contact"), "已终止不能记录沟通");
        assertFalse(view.actions().contains("stop"), "已终止不能再停止");
        assertTrue(view.actions().contains("upload"), "上传报告单不受随访状态限制");
    }

    @Test
    void summaryComputesActionsFromPermissionsAndStatus() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(
                                        List.of(
                                                "followup:record:edit",
                                                "followup:scan:upload",
                                                "followup:patient:edit")),
                                null,
                                List.of()));

        PatientOverviewService.PatientOverviewView view = service.summaryOf(PATIENT_ID);

        // 停止随访：A followup:plan:enroll / C followup:patient:edit（OQ-15 暂行口径，二者其一即可）
        assertEquals(List.of("unplanned", "upload", "contact", "stop"), view.actions());
    }

    @Test
    void summaryReturns40400WhenPatientOutOfScopeOrMissing() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.summaryOf(PATIENT_ID));
        assertEquals(40400, error.code(), "越范围 / 不存在不暴露对象存在（AGENTS.md §5.2）");
    }

    // ---------------------------------------------------------------- 时间轴（A-PAT-013：按时间顺序）

    @Test
    void timelineOrdersDischargeAndStatusEventsChronologically() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("DEAD"));
        when(statusGateway.historyOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                statusRow(2L, "DEAD", LocalDate.of(2026, 9, 1), "心源性死亡", "MANUAL"),
                                statusRow(
                                        1L,
                                        "ACTIVE",
                                        LocalDate.of(2026, 3, 8),
                                        null,
                                        "REGISTRATION")));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        assertEquals("101", timeline.patientId());
        assertEquals(2, timeline.items().size(), "登记初始行（REGISTRATION）不单独成节点，与出院节点同源");
        PatientOverviewService.TimelineEntryView discharge = timeline.items().get(0);
        assertEquals("DISCHARGE", discharge.kind());
        assertEquals(LocalDate.of(2026, 3, 8), discharge.date());
        assertEquals("出院", discharge.label());
        PatientOverviewService.TimelineEntryView death = timeline.items().get(1);
        assertEquals("STATUS", death.kind());
        assertEquals("DEAD", death.status(), "状态文案由前端 PATIENT_STATUS_TEXT 映射");
        assertEquals(LocalDate.of(2026, 9, 1), death.date());
        assertEquals("心源性死亡", death.note());
    }

    @Test
    void timelineSkipsDischargeNodeWhenDateMissing() {
        // C-PAT-001 最小必填集不含出院日期：无出院日期不出出院节点（无法定位时间轴）
        PatientOverviewMapper.OverviewRow row = row("ACTIVE");
        PatientOverviewMapper.OverviewRow noDischarge =
                new PatientOverviewMapper.OverviewRow(
                        row.id(),
                        row.nameCipher(),
                        row.mrnCipher(),
                        row.legacyNo(),
                        row.visitNo(),
                        row.gender(),
                        row.age(),
                        row.status(),
                        row.wardName(),
                        row.deptName(),
                        row.responsibleName(),
                        row.surgeryDate(),
                        row.admissionDate(),
                        null,
                        row.dischargeStatus(),
                        row.patientType(),
                        row.baselineCompleteness(),
                        row.followupCompleteness(),
                        row.latestFollowupDate(),
                        row.survivalStatus(),
                        row.keyIndicators(),
                        row.endpointEvents(),
                        row.nextPlanDate(),
                        row.nextWindowStart(),
                        row.nextWindowEnd());
        when(gateway.findOverview(PATIENT_ID)).thenReturn(noDischarge);
        when(statusGateway.historyOf(PATIENT_ID)).thenReturn(List.of());

        assertEquals(0, service.timelineOf(PATIENT_ID).items().size());
    }

    @Test
    void timelineReturns40400WhenPatientOutOfScopeOrMissing() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.timelineOf(PATIENT_ID));
        assertEquals(40400, error.code());
        verify(statusGateway, never()).historyOf(anyLong());
    }

    // ---------------------------------------------------------------- 夹具

    private static PatientOverviewMapper.OverviewRow row(String status) {
        return new PatientOverviewMapper.OverviewRow(
                PATIENT_ID,
                "CIPHER:" + NAME,
                "CIPHER:" + MRN,
                "OLD-A-1",
                null,
                "M",
                71,
                status,
                "示例二病区",
                null,
                "王医生（示例）",
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 2, 24),
                LocalDate.of(2026, 3, 8),
                1,
                null,
                92,
                new BigDecimal("78.00"),
                LocalDate.of(2026, 6, 10),
                "ALIVE",
                "{\"ef\":55,\"medications\":[\"阿司匹林（示例）\"]}",
                "{\"re_hospitalization\":\"2025-03-02\"}",
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 10, 29),
                LocalDate.of(2026, 11, 12));
    }

    private static PatientStatusHistoryMapper.StatusRow statusRow(
            long id, String status, LocalDate effectiveDate, String reason, String source) {
        return new PatientStatusHistoryMapper.StatusRow(
                id, status, effectiveDate, reason, source, OPERATOR_ID, LocalDateTime.now());
    }

    private static SessionPrincipal principal(List<String> permissionCodes) {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "王医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                permissionCodes,
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                null);
    }
}
