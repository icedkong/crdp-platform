package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJob;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E14-T04 / A-FU-001、C-FU-004、A-NFR-006 作业体单测（验收：任务在提醒开始日与窗口开始日较早一天创建；
 * 按日幂等、补跑逐日推进、模块关闭跳过、单入组失败不拖垮整院）。全部虚构数据。
 */
class FollowupTaskCreationServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;
    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;
    private static final LocalDate BASIS = LocalDate.of(2025, 3, 15);

    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FollowupTaskStatusService statusService = mock(FollowupTaskStatusService.class);

    private final FollowupTaskCreationService service =
            new FollowupTaskCreationService(
                    jobScheduler, taskMapper, planMapper, outbox, audit, jdbc, statusService);

    // ---------------------------------------------------------------- 装配（A 年度形态：开启日 2026-03-08）

    private void moduleOpen() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT_ID))).thenReturn(1);
    }

    private void annualEnrollment() {
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        null,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null)));
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(BASIS);
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow("WARD_DEFAULT")));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(55L, 66L));
    }

    private static PlanMapper.NodeRow subplanRow() {
        return new PlanMapper.NodeRow(
                SUBPLAN_ID,
                null,
                "SUBPLAN",
                "年度随访（示例）",
                "CYCLE",
                null,
                1,
                "YEAR",
                null,
                0,
                "YEAR",
                0,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                null,
                null,
                null,
                null,
                null,
                10);
    }

    private static PlanMapper.NodeRow nodeRow(String ownerType) {
        return new PlanMapper.NodeRow(
                NODE_ID,
                SUBPLAN_ID,
                "NODE",
                "电话随访（示例）",
                null,
                null,
                null,
                null,
                "PLAN_START",
                1,
                "YEAR",
                7,
                "DAY",
                7,
                "DAY",
                7,
                "DAY",
                "FORM",
                ownerType,
                "fu_annual_v1",
                null,
                null,
                20);
    }

    private void insertSucceeds() {
        // 幂等前置：该次不存在（显式 null——本仓 Mockito 默认对未打桩的包装数值返回 0，不是 null）
        when(taskMapper.findTaskId(anyLong(), anyLong(), anyInt())).thenReturn(null);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(901L);
                            return 1;
                        })
                .when(taskMapper)
                .insertTask(any(FollowupTaskInsertRow.class));
    }

    /** 模拟调度框架：在租户上下文内执行作业体（runForEachTenant 的语义）。 */
    private void run(LocalDate logicalDate) {
        when(jobScheduler.runForEachTenant(anyString(), eq(logicalDate), any()))
                .thenAnswer(
                        invocation -> {
                            TenantJob job = invocation.getArgument(2, TenantJob.class);
                            TenantContext.runAsTenant(
                                    TENANT_ID, "demoa", () -> wrap(job, logicalDate));
                            return List.of();
                        });
        service.runForDate(logicalDate);
    }

    private static void wrap(TenantJob job, LocalDate logicalDate) {
        try {
            job.run(logicalDate);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- 验收：任务在开启日（较早一天）创建

    @Test
    void createsPendingTaskOnOpenDateAndBackfillsStartDate() {
        moduleOpen();
        annualEnrollment();
        insertSucceeds();

        run(LocalDate.of(2026, 3, 8));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        FollowupTaskInsertRow row = captor.getValue();
        assertEquals(ENROLLMENT_ID, row.getEnrollmentId());
        assertEquals(NODE_ID, row.getNodeId());
        assertEquals(1, row.getOccurrence());
        assertEquals(LocalDate.of(2026, 3, 15), row.getPlanDate());
        assertEquals(LocalDate.of(2026, 3, 8), row.getRemindStart());
        assertEquals(LocalDate.of(2026, 3, 8), row.getWindowStart());
        assertEquals(LocalDate.of(2026, 3, 22), row.getWindowEnd());
        assertEquals(LocalDate.of(2026, 3, 8), row.getOpenDate(), "开启日 = min(提醒开始日, 窗口开始日)");
        assertEquals("NORMAL", row.getCreateType());
        assertEquals("PENDING", row.getStatus(), "创建即待随访（A-FU-003）");
        assertEquals(LocalDate.of(2026, 3, 8), row.getCreatedLogicalDate());
        assertEquals(66L, row.getAssigneeId(), "WARD_DEFAULT → 病区默认负责人（A-FU-009）");
        verify(taskMapper).backfillStartDate(ENROLLMENT_ID, BASIS);
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, LocalDate.of(2027, 3, 15), LocalDate.of(2027, 3, 8));
        verify(outbox).publish(eq("TaskOpened"), contains("\"openDate\":\"2026-03-08\""));
        verify(audit).append(any());
    }

    @Test
    void nothingCreatedBeforeOpenDateButCacheTracksNext() {
        moduleOpen();
        annualEnrollment();

        run(LocalDate.of(2026, 3, 7));

        verify(taskMapper, never()).insertTask(any());
        verify(outbox, never()).publish(anyString(), anyString());
        verify(audit, never()).append(any());
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 8));
    }

    @Test
    void duplicateKeyHitCreatesNothingAndWritesNoEvent() {
        moduleOpen();
        annualEnrollment();
        when(taskMapper.findTaskId(ENROLLMENT_ID, NODE_ID, 1)).thenReturn(901L);

        run(LocalDate.of(2026, 3, 8));

        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
        verify(outbox, never()).publish(anyString(), anyString());
        verify(audit, never()).append(any());
    }

    @Test
    void concurrentUniqueKeyViolationIsTreatedAsExisting() {
        moduleOpen();
        annualEnrollment();
        when(taskMapper.findTaskId(ENROLLMENT_ID, NODE_ID, 1)).thenReturn(null);
        when(taskMapper.insertTask(any(FollowupTaskInsertRow.class)))
                .thenThrow(
                        new org.springframework.dao.DuplicateKeyException("uk_fu_task_occurrence"));

        run(LocalDate.of(2026, 3, 8));

        verify(outbox, never()).publish(anyString(), anyString());
        verify(audit, never()).append(any());
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, LocalDate.of(2027, 3, 15), LocalDate.of(2027, 3, 8));
    }

    // ---------------------------------------------------------------- 补跑 / 模块闸门 / 容错

    @Test
    void catchUpAdvancesFromLastSuccessDayPlusOne() {
        moduleOpen();
        annualEnrollment();
        insertSucceeds();
        when(jobScheduler.lastSuccessDate(anyString()))
                .thenReturn(Optional.of(LocalDate.of(2026, 3, 5)));

        run(LocalDate.of(2026, 3, 8));

        // 补跑区间 03-06..03-08 共 3 个逻辑日，逐日遍历入组
        verify(taskMapper, times(3)).findSchedulableEnrollments();
    }

    @Test
    void skipsTenantWhenFollowupModuleClosed() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT_ID))).thenReturn(0);

        run(LocalDate.of(2026, 3, 8));

        verify(taskMapper, never()).findSchedulableEnrollments();
        verify(jobScheduler, never()).lastSuccessDate(anyString());
        verify(statusService, never()).refreshOverdue(any()); // 模块关闭：不创建也不刷新（E15-T01）
    }

    @Test
    void dailyRunRefreshesOverdueAfterCreationLoop() {
        moduleOpen();
        annualEnrollment();
        insertSucceeds();
        when(statusService.refreshOverdue(any())).thenReturn(0);

        run(LocalDate.of(2026, 3, 8));

        // E15-T01 / A-FU-010（tech-plan §8 步骤 W）：创建循环后按本次运行目标逻辑日一次刷完逾期
        verify(statusService).refreshOverdue(LocalDate.of(2026, 3, 8));
    }

    @Test
    void singleEnrollmentFailureDoesNotStopOthers() {
        moduleOpen();
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        31L,
                                        77L,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null),
                                new FollowupTaskMapper.EnrollmentRow(
                                        32L,
                                        78L,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null)));
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow("WARD_DEFAULT")));
        when(taskMapper.findOwnerContext(77L)).thenThrow(new IllegalStateException("租户库抖动（示例）"));
        when(taskMapper.findOwnerContext(78L))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(null, null));
        insertSucceeds();

        run(LocalDate.of(2026, 3, 8));

        verify(taskMapper, times(1)).insertTask(any(FollowupTaskInsertRow.class));
        verify(taskMapper).updateNextCache(eq(32L), any(), any());
    }

    @Test
    void unresolvableStartDateSkipsEnrollmentWithoutBackfill() {
        moduleOpen();
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        null,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null)));
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(null);

        run(LocalDate.of(2026, 3, 8));

        verify(taskMapper, never()).insertTask(any());
        verify(taskMapper, never()).backfillStartDate(anyLong(), any());
    }

    // ---------------------------------------------------------------- 负责人回落链（A-FU-009）

    @Test
    void doctorOwnerFallsBackToWardDefaultOwner() {
        moduleOpen();
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null)));
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow("DOCTOR")));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(null, 66L));
        insertSucceeds();

        run(LocalDate.of(2026, 3, 8));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertEquals(66L, captor.getValue().getAssigneeId(), "责任医生缺失 → 病区默认负责人");
    }

    @Test
    void nobodyResolvableLeavesTaskUnassignedForPool() {
        moduleOpen();
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        null,
                                        null)));
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow("WARD_DEFAULT")));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(null, null));
        insertSucceeds();

        run(LocalDate.of(2026, 3, 8));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertNull(captor.getValue().getAssigneeId(), "两级都无人 → NULL 进病区待认领池（A-FU-009）");
    }

    // ---------------------------------------------------------------- E14-T05 入组即建（补录 / 当期）

    /** 医院 B 里程碑形态：FIXED×1 子计划 + 术后第 N 天节点（当天提醒、窗口前 0 后 1）。 */
    private static PlanMapper.NodeRow milestoneRow(
            long nodeId, int dayOffset, String passedWindowAction) {
        return new PlanMapper.NodeRow(
                nodeId,
                SUBPLAN_ID,
                "NODE",
                "术后第" + dayOffset + "天随访（示例）",
                null,
                null,
                null,
                null,
                "PLAN_START",
                dayOffset,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                1,
                "DAY",
                "FORM",
                "WARD_DEFAULT",
                "fu_postop_v1",
                passedWindowAction,
                null,
                20);
    }

    private static PlanMapper.NodeRow fixedOnceSubplanRow() {
        return new PlanMapper.NodeRow(
                SUBPLAN_ID,
                null,
                "SUBPLAN",
                "术后早期随访（示例）",
                "FIXED",
                1,
                null,
                null,
                null,
                0,
                "YEAR",
                0,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                null,
                null,
                null,
                null,
                null,
                10);
    }

    /** 手术起算 + 医院 B 里程碑（第 1/3/5 天 BACKFILL、第 4 天 SKIP）+ 3 个月节点。 */
    private void hospitalBMilestonePlan(LocalDate surgery) {
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(surgery);
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-B",
                                "示例 IT 术后随访计划",
                                1,
                                "PUBLISHED",
                                "SURGERY_DATE",
                                "ONGOING",
                                null,
                                false,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(
                        List.of(
                                fixedOnceSubplanRow(),
                                milestoneRow(201L, 1, "BACKFILL"),
                                milestoneRow(202L, 3, "BACKFILL"),
                                milestoneRow(203L, 5, "BACKFILL"),
                                milestoneRow(204L, 4, "SKIP"),
                                new PlanMapper.NodeRow(
                                        205L,
                                        SUBPLAN_ID,
                                        "NODE",
                                        "3 个月随访（示例）",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "PLAN_START",
                                        3,
                                        "MONTH",
                                        14,
                                        "DAY",
                                        14,
                                        "DAY",
                                        14,
                                        "DAY",
                                        "FORM",
                                        "WARD_DEFAULT",
                                        "fu_postop_v1",
                                        null,
                                        null,
                                        30)));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(55L, 66L));
    }

    @Test
    void enrollmentInitCreatesBackfillTasksForEndedWithoutEvents() {
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate surgery = today.minusDays(8);
        hospitalBMilestonePlan(surgery);
        when(taskMapper.findTaskId(anyLong(), anyLong(), anyInt())).thenReturn(null);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(901L);
                            return 1;
                        })
                .when(taskMapper)
                .insertTask(any(FollowupTaskInsertRow.class));

        FollowupTaskCreationService.EnrollmentInitSummary summary =
                service.initializeForNewEnrollment(ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL");

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper, times(3)).insertTask(captor.capture());
        List<FollowupTaskInsertRow> rows = captor.getAllValues();
        for (FollowupTaskInsertRow row : rows) {
            assertEquals("BACKFILL", row.getCreateType(), "已结束窗口 → 标补录（验收）");
            assertEquals("PENDING", row.getStatus(), "补录任务创建即待随访、可直接录入");
            assertEquals(today, row.getCreatedLogicalDate(), "建档当天创建");
            assertEquals("fu_postop_v1", row.getFormCode(), "使用完整随访记录表单");
            assertEquals(66L, row.getAssigneeId());
        }
        assertEquals(surgery.plusDays(1), rows.get(0).getPlanDate(), "第 1 天节点计划日期");
        assertEquals(surgery.plusDays(3), rows.get(1).getPlanDate());
        assertEquals(surgery.plusDays(5), rows.get(2).getPlanDate());
        verify(outbox, never()).publish(anyString(), anyString());
        assertEquals(3, summary.backfill());
        assertEquals(0, summary.normal());
        // 起算日期快照回填 + 下次缓存指向 3 个月节点（计划 06-01 −14 天开启）
        verify(taskMapper).backfillStartDate(ENROLLMENT_ID, surgery);
        verify(taskMapper)
                .updateNextCache(
                        ENROLLMENT_ID, surgery.plusMonths(3), surgery.plusMonths(3).minusDays(14));
    }

    @Test
    void enrollmentInitCreatesCurrentWindowTaskAsNormalWithEvent() {
        // 基准 10 天前：+1 天节点窗口已结束（补录）；+5 天节点窗口今天结束（当期 NORMAL）
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate basis = today.minusDays(10);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(basis);
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-B",
                                "示例 IT 术后随访计划",
                                1,
                                "PUBLISHED",
                                "SURGERY_DATE",
                                "ONGOING",
                                null,
                                false,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(
                        List.of(
                                fixedOnceSubplanRow(),
                                milestoneRow(201L, 1, "BACKFILL"),
                                new PlanMapper.NodeRow(
                                        202L,
                                        SUBPLAN_ID,
                                        "NODE",
                                        "窗口内节点（示例）",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "PLAN_START",
                                        5,
                                        "DAY",
                                        0,
                                        "DAY",
                                        5,
                                        "DAY",
                                        5,
                                        "DAY",
                                        "FORM",
                                        "WARD_DEFAULT",
                                        "fu_postop_v1",
                                        "BACKFILL",
                                        null,
                                        20)));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(55L, 66L));
        when(taskMapper.findTaskId(anyLong(), anyLong(), anyInt())).thenReturn(null);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(901L);
                            return 1;
                        })
                .when(taskMapper)
                .insertTask(any(FollowupTaskInsertRow.class));

        FollowupTaskCreationService.EnrollmentInitSummary summary =
                service.initializeForNewEnrollment(ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL");

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper, times(2)).insertTask(captor.capture());
        // due 按开启日排序：+5 天节点（窗口今天结束）先创建，+1 天节点（窗口已结束）补录
        assertEquals("NORMAL", captor.getAllValues().get(0).getCreateType(), "窗口已开启未结束 → 当天正常创建");
        assertEquals(202L, captor.getAllValues().get(0).getNodeId());
        assertEquals("BACKFILL", captor.getAllValues().get(1).getCreateType());
        assertEquals(201L, captor.getAllValues().get(1).getNodeId());
        assertEquals(1, summary.backfill());
        assertEquals(1, summary.normal());
        // 只有当期任务发 TaskOpened（补录不发即将逾期 / 已逾期 / 失访候选等通知）
        verify(outbox, times(1)).publish(eq("TaskOpened"), anyString());
    }

    @Test
    void enrollmentInitSkipsWhenBasisUnresolvableOrTasksExist() {
        // 起算日期不可解析：不建任务、不回填快照（与每日调度同口径）
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(null);
        FollowupTaskCreationService.EnrollmentInitSummary skipped =
                service.initializeForNewEnrollment(ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL");
        assertEquals(0, skipped.backfill());
        assertEquals(0, skipped.normal());
        verify(taskMapper, never()).insertTask(any());
        verify(taskMapper, never()).backfillStartDate(anyLong(), any());

        // 幂等：该次已存在（重复入组重放）不重复创建
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        hospitalBMilestonePlan(today.minusDays(8));
        when(taskMapper.findTaskId(anyLong(), anyLong(), anyInt())).thenReturn(901L);
        FollowupTaskCreationService.EnrollmentInitSummary existing =
                service.initializeForNewEnrollment(ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL");
        assertEquals(0, existing.backfill());
        verify(taskMapper, never()).insertTask(any());
    }

    // ---------------------------------------------------------------- E14-T06 停用 / 暂停补偿与恢复即建

    /** 可调入组行（带最近暂停区间，E14-T06 归属判定用）。 */
    private void pausedAnnualEnrollment(LocalDate pauseFrom, LocalDate pauseTo) {
        when(taskMapper.findSchedulableEnrollments())
                .thenReturn(
                        List.of(
                                new FollowupTaskMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1),
                                        pauseFrom,
                                        pauseTo)));
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-1",
                                "示例 IT 年度随访计划",
                                1,
                                "PUBLISHED",
                                "DISCHARGE_DATE",
                                "ONGOING",
                                null,
                                null,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(subplanRow(), nodeRow("WARD_DEFAULT")));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(55L, 66L));
    }

    @Test
    void catchUpMarksEndedWindowAsDisabledSkippedWithoutEvent() {
        moduleOpen();
        // 运行目标逻辑日 2026-04-01 > 窗口结束日 2026-03-22：第 1 次本应在开启日 2026-03-08 创建而未创建
        // （租户停用 / 作业未执行期间），补跑时补建为「停用期间未执行」（PLT-TEN-017）
        pausedAnnualEnrollment(null, null);
        insertSucceeds();

        run(LocalDate.of(2026, 4, 1));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        FollowupTaskInsertRow row = captor.getValue();
        assertEquals("DISABLED_SKIPPED", row.getCreateType(), "窗口已结束 → 停用期间未执行");
        assertEquals("SKIPPED_SUSPENDED", row.getStatus(), "待人工选择补做或关闭");
        verify(outbox, never()).publish(anyString(), anyString());
        verify(audit).append(any());
    }

    @Test
    void catchUpAttributesEndedWindowToPauseWhenIntervalCoversIt() {
        moduleOpen();
        // 窗口结束日 2026-03-22 落在此前暂停区间 [2026-03-01, 2026-03-25] 内 → 归「暂停期间未执行」（A 的 Q32）
        pausedAnnualEnrollment(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 25));
        insertSucceeds();

        run(LocalDate.of(2026, 4, 1));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertEquals("PAUSED_SKIPPED", captor.getValue().getCreateType());
        assertEquals("SKIPPED_SUSPENDED", captor.getValue().getStatus());
        verify(outbox, never()).publish(anyString(), anyString());
    }

    @Test
    void catchUpKeepsOpenWindowAsNormalWithEvent() {
        moduleOpen();
        // 运行目标逻辑日与窗口重叠（窗口 2026-03-08..03-22，运行 2026-03-15）：照常 NORMAL + 事件（既有口径不回归）
        pausedAnnualEnrollment(null, null);
        insertSucceeds();

        run(LocalDate.of(2026, 3, 15));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertEquals("NORMAL", captor.getValue().getCreateType());
        assertEquals("PENDING", captor.getValue().getStatus());
        verify(outbox).publish(eq("TaskOpened"), anyString());
    }

    @Test
    void resumeInitCreatesPausedSkippedAndCurrentNormalTasks() {
        // 恢复补偿：暂停始于 12 天前；+10 天节点窗口已结束（暂停区间内开启）→ 暂停期间未执行；
        // +20 天节点窗口未结束 → 当期 NORMAL（照常发任务开启事件）
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate basis = today.minusDays(20);
        when(taskMapper.resolveStartDate(PLAN_ID, PATIENT_ID)).thenReturn(basis);
        when(planMapper.findById(PLAN_ID))
                .thenReturn(
                        new PlanMapper.PlanRow(
                                PLAN_ID,
                                "PLAN-IT-B",
                                "示例 IT 术后随访计划",
                                1,
                                "PUBLISHED",
                                "SURGERY_DATE",
                                "ONGOING",
                                null,
                                false,
                                null,
                                0,
                                null,
                                1L));
        when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(
                        List.of(
                                fixedOnceSubplanRow(),
                                milestoneRow(201L, 10, "BACKFILL"),
                                milestoneRow(202L, 20, "BACKFILL"),
                                milestoneRow(203L, 30, "BACKFILL")));
        when(taskMapper.findOwnerContext(PATIENT_ID))
                .thenReturn(new FollowupTaskMapper.PatientOwnerRow(55L, 66L));
        when(taskMapper.findTaskId(anyLong(), anyLong(), anyInt())).thenReturn(null);
        doAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(901L);
                            return 1;
                        })
                .when(taskMapper)
                .insertTask(any(FollowupTaskInsertRow.class));

        FollowupTaskCreationService.ResumeInitSummary summary =
                service.initializeForResumedEnrollment(
                        ENROLLMENT_ID, PATIENT_ID, PLAN_ID, "NORMAL", today.minusDays(12));

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper, times(2)).insertTask(captor.capture());
        List<FollowupTaskInsertRow> rows = captor.getAllValues();
        // due 按开启日排序：+10 天节点（窗口已结束）先补建为暂停期间未执行，+20 天节点（窗口未结束）当期 NORMAL
        assertEquals(201L, rows.get(0).getNodeId());
        assertEquals("PAUSED_SKIPPED", rows.get(0).getCreateType(), "暂停期间到期的次数 → 暂停期间未执行（Q32）");
        assertEquals("SKIPPED_SUSPENDED", rows.get(0).getStatus());
        assertEquals(today.minusDays(9), rows.get(0).getWindowEnd());
        assertEquals(202L, rows.get(1).getNodeId());
        assertEquals("NORMAL", rows.get(1).getCreateType(), "窗口未结束 → 恢复后当期正常创建");
        assertEquals("PENDING", rows.get(1).getStatus());
        assertEquals(1, summary.pausedSkipped());
        assertEquals(1, summary.normal());
        verify(outbox, times(1)).publish(eq("TaskOpened"), anyString());
        // 下次缓存指向 +30 天节点（开启日 = 计划日期，当天提醒、零宽窗口）
        verify(taskMapper).updateNextCache(ENROLLMENT_ID, basis.plusDays(30), basis.plusDays(30));
    }
}
