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

import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJob;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.tenant.TenantContext;
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

    private final FollowupTaskCreationService service =
            new FollowupTaskCreationService(
                    jobScheduler, taskMapper, planMapper, outbox, audit, jdbc);

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
                                        LocalDate.of(2025, 4, 1))));
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
                                        LocalDate.of(2025, 4, 1)),
                                new FollowupTaskMapper.EnrollmentRow(
                                        32L,
                                        78L,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 4, 1))));
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
                                        LocalDate.of(2025, 4, 1))));
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
                                        LocalDate.of(2025, 4, 1))));
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
                                        LocalDate.of(2025, 4, 1))));
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
}
