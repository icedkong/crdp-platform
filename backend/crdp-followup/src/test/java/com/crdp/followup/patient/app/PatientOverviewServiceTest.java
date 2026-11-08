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
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
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
    private final FollowupTaskQueryMapper taskQueryMapper = mock(FollowupTaskQueryMapper.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final PatientOverviewService service =
            new PatientOverviewService(
                    gateway,
                    statusGateway,
                    taskQueryMapper,
                    recordMapper,
                    crypto,
                    TenantViewFieldGuard.passthrough());

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

    // ---------------------------------------------------------------- 随访节点（E15-T07 /
    // A-FU-023、C-FU-023）

    /** 站内提醒任务行（taskType = REMINDER，E15-T06 / A-FU-026）。 */
    private static FollowupTaskQueryMapper.TimelineTaskRow reminderRowOf(
            long id, int occurrence, String status, LocalDate planDate) {
        return new FollowupTaskQueryMapper.TimelineTaskRow(
                id,
                501L,
                11L,
                "核对联系方式（示例）",
                "循环随访（示例）",
                occurrence,
                "REMINDER",
                planDate,
                planDate.minusDays(7),
                planDate.plusDays(7),
                status,
                null,
                "NORMAL",
                null,
                null);
    }

    private static FollowupTaskQueryMapper.TimelineTaskRow taskRowOf(
            long id,
            int occurrence,
            String status,
            String completeKind,
            LocalDate planDate,
            Long recordId,
            LocalDateTime completedAt) {
        return new FollowupTaskQueryMapper.TimelineTaskRow(
                id,
                501L,
                11L,
                "年度随访（示例）",
                "循环随访（示例）",
                occurrence,
                "FORM",
                planDate,
                planDate.minusDays(7),
                planDate.plusDays(7),
                status,
                completeKind,
                "NORMAL",
                recordId,
                completedAt);
    }

    @Test
    void timelineShowsFollowupNodesWithProgressAndNextPlannedDate() {
        // 验收位：时间轴显示子计划进度（x/y）与下次计划日期（计划中 / 待开始）
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(taskQueryMapper.timelineTasks(PATIENT_ID))
                .thenReturn(
                        List.of(
                                taskRowOf(
                                        9001L,
                                        1,
                                        "COMPLETED",
                                        "ON_TIME",
                                        LocalDate.of(2025, 11, 5),
                                        701L,
                                        LocalDateTime.of(2025, 11, 2, 10, 0)),
                                taskRowOf(
                                        9002L,
                                        2,
                                        "IN_PROGRESS",
                                        null,
                                        LocalDate.of(2026, 11, 5),
                                        702L,
                                        null)));
        when(taskQueryMapper.timelineEnrollments(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskQueryMapper.TimelineEnrollmentRow(
                                        501L, "冠心病术后年度随访（示例）", LocalDate.of(2027, 11, 5))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        List<PatientOverviewService.TimelineEntryView> followups =
                timeline.items().stream().filter(item -> "FOLLOWUP".equals(item.kind())).toList();
        assertEquals(2, followups.size(), "任务节点按计划日期进时间轴");
        PatientOverviewService.TimelineEntryView done = followups.get(0);
        assertEquals("COMPLETED", done.status());
        assertEquals(LocalDate.of(2025, 11, 2), done.doneDate(), "完成日期随节点返回");
        assertEquals(1, done.progressDone(), "子计划完成进度 x/y（A-FU-023）");
        assertEquals(1, done.progressTotal());
        assertEquals(true, done.canOpen(), "已关联记录的节点可打开");
        assertEquals("701", done.recordId());
        PatientOverviewService.TimelineEntryView next = followups.get(1);
        assertEquals(0, next.progressDone());
        assertEquals(1, next.progressTotal());

        PatientOverviewService.TimelineEntryView planned =
                timeline.items().stream()
                        .filter(item -> "PLANNED".equals(item.kind()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(LocalDate.of(2027, 11, 5), planned.planDate(), "下次计划日期（实时缓存）");
        assertEquals("PLANNED", planned.status(), "「计划中 / 待开始」由前端映射文案");
        assertEquals(false, planned.canOpen(), "计划中节点不可打开（任务开启日才创建）");
        assertEquals("计划中，任务开启日才创建", planned.note());
    }

    @Test
    void plannedNodeSkippedWhenTaskAlreadyCreatedForCacheDate() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(taskQueryMapper.timelineTasks(PATIENT_ID))
                .thenReturn(
                        List.of(
                                taskRowOf(
                                        9001L,
                                        1,
                                        "PENDING",
                                        null,
                                        LocalDate.of(2026, 11, 5),
                                        null,
                                        null)));
        when(taskQueryMapper.timelineEnrollments(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupTaskQueryMapper.TimelineEnrollmentRow(
                                        501L, "冠心病术后年度随访（示例）", LocalDate.of(2026, 11, 5))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        assertEquals(
                0,
                timeline.items().stream().filter(item -> "PLANNED".equals(item.kind())).count(),
                "缓存指向的次数已有任务（调度已建）不重复出计划中节点");
    }

    @Test
    void timelineShowsBackfillRecordsAsSeparateNodes() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(recordMapper.findTasklessByPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupRecordMapper.RecordRowRaw(
                                        801L,
                                        PATIENT_ID,
                                        null,
                                        "BACKFILL",
                                        LocalDate.of(2024, 5, 10),
                                        "A_ANNUAL_FU",
                                        1,
                                        "SUBMITTED",
                                        2,
                                        "{}",
                                        "{}",
                                        7L,
                                        LocalDateTime.of(2025, 1, 15, 9, 0),
                                        7L,
                                        LocalDateTime.of(2025, 1, 15, 9, 0)),
                                new FollowupRecordMapper.RecordRowRaw(
                                        802L,
                                        PATIENT_ID,
                                        null,
                                        "BACKFILL",
                                        LocalDate.of(2026, 1, 20),
                                        "A_ANNUAL_FU",
                                        1,
                                        "DRAFT",
                                        0,
                                        "{}",
                                        "{}",
                                        7L,
                                        LocalDateTime.of(2026, 2, 1, 9, 0),
                                        7L,
                                        LocalDateTime.of(2026, 2, 1, 9, 0))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        List<PatientOverviewService.TimelineEntryView> backfills =
                timeline.items().stream().filter(item -> "BACKFILL".equals(item.kind())).toList();
        assertEquals(2, backfills.size(), "补录记录单独成节点（含补录、单独标识，C-FU-023）");
        PatientOverviewService.TimelineEntryView submitted = backfills.get(0);
        assertEquals("COMPLETED", submitted.status(), "已提交补录按已完成展示");
        assertEquals(LocalDate.of(2024, 5, 10), submitted.date(), "按实际随访日期排序定位");
        assertTrue(submitted.note().contains("补录"), "补录标识：" + submitted.note());
        assertTrue(submitted.note().contains("2025-01-15"), "录入日期：" + submitted.note());
        PatientOverviewService.TimelineEntryView draft = backfills.get(1);
        assertEquals("IN_PROGRESS", draft.status(), "补录草稿按进行中展示");
        assertEquals("802", draft.recordId());
        assertEquals(true, draft.canOpen(), "补录草稿可继续填写");
    }

    @Test
    void timelineSplitsUnplannedRecordsFromBackfill() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(recordMapper.findTasklessByPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new FollowupRecordMapper.RecordRowRaw(
                                        811L,
                                        PATIENT_ID,
                                        null,
                                        "UNPLANNED",
                                        LocalDate.of(2026, 6, 10),
                                        "A_ANNUAL_FU",
                                        1,
                                        "SUBMITTED",
                                        2,
                                        "{}",
                                        "{}",
                                        7L,
                                        LocalDateTime.of(2026, 6, 10, 15, 0),
                                        7L,
                                        LocalDateTime.of(2026, 6, 10, 15, 0)),
                                new FollowupRecordMapper.RecordRowRaw(
                                        812L,
                                        PATIENT_ID,
                                        null,
                                        "UNPLANNED",
                                        LocalDate.of(2026, 8, 1),
                                        "A_ANNUAL_FU",
                                        1,
                                        "DRAFT",
                                        0,
                                        "{}",
                                        "{}",
                                        7L,
                                        LocalDateTime.of(2026, 8, 2, 9, 0),
                                        7L,
                                        LocalDateTime.of(2026, 8, 2, 9, 0)),
                                new FollowupRecordMapper.RecordRowRaw(
                                        813L,
                                        PATIENT_ID,
                                        null,
                                        "BACKFILL",
                                        LocalDate.of(2024, 5, 10),
                                        "A_ANNUAL_FU",
                                        1,
                                        "SUBMITTED",
                                        2,
                                        "{}",
                                        "{}",
                                        7L,
                                        LocalDateTime.of(2025, 1, 15, 9, 0),
                                        7L,
                                        LocalDateTime.of(2025, 1, 15, 9, 0))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        List<PatientOverviewService.TimelineEntryView> unplanned =
                timeline.items().stream().filter(item -> "UNPLANNED".equals(item.kind())).toList();
        List<PatientOverviewService.TimelineEntryView> backfills =
                timeline.items().stream().filter(item -> "BACKFILL".equals(item.kind())).toList();
        assertEquals(2, unplanned.size(), "计划外随访单独成节点（E15-T06 / C-FU-022）");
        assertEquals(1, backfills.size(), "补录节点不受影响");
        PatientOverviewService.TimelineEntryView submitted = unplanned.get(0);
        assertEquals("计划外随访", submitted.label(), "节点名「计划外随访」");
        assertEquals("COMPLETED", submitted.status());
        assertTrue(submitted.note().contains("不影响后续节点"), "备注写明不影响后续节点：" + submitted.note());
        assertEquals("811", submitted.recordId());
        assertEquals(true, submitted.canOpen(), "可打开表单页查看 / 续填");
        PatientOverviewService.TimelineEntryView draft = unplanned.get(1);
        assertEquals("IN_PROGRESS", draft.status(), "计划外草稿按进行中展示");
    }

    @Test
    void reminderTaskNodeCarriesNoFormNote() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(taskQueryMapper.timelineTasks(PATIENT_ID))
                .thenReturn(List.of(reminderRowOf(9101L, 1, "PENDING", LocalDate.of(2026, 11, 5))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        PatientOverviewService.TimelineEntryView reminder =
                timeline.items().stream()
                        .filter(item -> "FOLLOWUP".equals(item.kind()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("站内提醒：无需填表，到点提醒责任人（A-FU-026）", reminder.note(), "站内提醒任务节点标「无需填表」");
        assertEquals(false, reminder.canOpen(), "无表单不可进录入");
    }

    @Test
    void overdueCompletedFollowupCarriesNote() {
        when(gateway.findOverview(PATIENT_ID)).thenReturn(row("ACTIVE"));
        when(taskQueryMapper.timelineTasks(PATIENT_ID))
                .thenReturn(
                        List.of(
                                taskRowOf(
                                        9003L,
                                        1,
                                        "COMPLETED",
                                        "OVERDUE",
                                        LocalDate.of(2025, 11, 5),
                                        703L,
                                        LocalDateTime.of(2025, 12, 1, 10, 0))));

        PatientOverviewService.TimelineView timeline = service.timelineOf(PATIENT_ID);

        PatientOverviewService.TimelineEntryView node =
                timeline.items().stream()
                        .filter(item -> "FOLLOWUP".equals(item.kind()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("OVERDUE", node.completeKind(), "完成方式随节点返回（前端派生「逾期完成」）");
        assertEquals("窗口结束后完成，记逾期完成", node.note());
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
                        3L,
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
                                                "followup:plan:enroll",
                                                "followup:patient:transfer")),
                                null,
                                List.of()));

        PatientOverviewService.PatientOverviewView view = service.summaryOf(PATIENT_ID);

        assertTrue(view.terminated(), "死亡 / 停止 / 失访视为已终止");
        assertFalse(view.actions().contains("unplanned"), "已终止不能新建计划外随访");
        assertFalse(view.actions().contains("contact"), "已终止不能记录沟通");
        assertFalse(view.actions().contains("stop"), "已终止不能再停止");
        assertTrue(view.actions().contains("upload"), "上传报告单不受随访状态限制");
        assertTrue(view.actions().contains("transfer"), "转病区是管理动作（A-WARD-005），已终止患者仍可调整病区归属");
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
                        row.wardId(),
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

    /**
     * 完成日期按北京业务日（AGENTS.md §6）：锚定「北京 2026-11-06 03:32」（= UTC 2026-11-05 19:32，两时区日历日不同），
     * 无论服务器时区是什么， completed_at 换算后都必须给北京日。UTC 服务器（CI 容器）上旧实现 toLocalDate() 会给 11-05——正是
     * BackfillTimelineIT 在 CI 16:00–24:00 UTC 时段的 flaky 根因。
     */
    @Test
    void beijingDoneDateUsesBusinessZoneNotServerWallClock() {
        java.time.Instant beijingEarlyMorning =
                LocalDateTime.of(2026, 11, 6, 3, 32)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant();
        LocalDateTime serverWallClock =
                LocalDateTime.ofInstant(beijingEarlyMorning, java.time.ZoneId.systemDefault());

        assertEquals(
                LocalDate.of(2026, 11, 6), PatientOverviewService.beijingDoneDate(serverWallClock));
        assertNull(PatientOverviewService.beijingDoneDate(null), "未完成任务无完成日期");
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
                4L,
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
                0L, // 绝对时长用部署配置的全局值,
                null);
    }
}
