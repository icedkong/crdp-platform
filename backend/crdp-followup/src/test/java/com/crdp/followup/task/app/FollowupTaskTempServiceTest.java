package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskAssignMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E15-T06 / A-FU-013 临时添加任务单测（tasks.csv 验收「不占用年度任务」）。覆盖：缺省解析（在组入组 + FORM 节点 + 当前用户为负责人）与 FORM /
 * REMINDER 两形态；occurrence 负数计数器（-1 起、已有临时任务递减——不占用年度任务）； 窗口 = 计划日期 ± 窗口宽度、开启日 = 当天；负责人病区复核（与改派同口径
 * 40001）；TaskOpened 事件与 TASK_TEMP_CREATE 审计；校验（无在组入组 40001、计划日期必填 40001、窗口宽度 0–365、 REMINDER
 * 无表单、FORM 无可解析表单 40001）；越范围患者 40400；并发撞 uk 递减重试。全部虚构数据。
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class FollowupTaskTempServiceTest {

    private static final long PATIENT_ID = 301L;
    private static final long ENROLLMENT_ID = 501L;
    private static final long PLAN_ID = 11L;
    private static final long NODE_ID = 200L;
    private static final long OPERATOR = 55L;
    private static final long WARD_ID = 5L;
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 12, 1);

    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final FollowupTaskAssignMapper assignMapper = mock(FollowupTaskAssignMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final com.crdp.form.record.app.FormService formService =
            mock(com.crdp.form.record.app.FormService.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FollowupTaskTempService service =
            new FollowupTaskTempService(
                    taskMapper, assignMapper, planMapper, patientScope, formService, outbox, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                OPERATOR,
                "it.temp",
                "IT 用户（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:record:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
    }

    private void inScope() {
        lenient().when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
    }

    /** 计划节点：一个 FORM 节点（200）与一个 REMINDER 节点（201）。 */
    private void planNodes() {
        lenient()
                .when(planMapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(
                        List.of(
                                new PlanMapper.NodeRow(
                                        100L,
                                        null,
                                        "SUBPLAN",
                                        "持续随访（示例）",
                                        "CYCLE",
                                        null,
                                        1,
                                        "YEAR",
                                        null,
                                        null,
                                        null,
                                        1,
                                        "YEAR",
                                        0,
                                        "DAY",
                                        0,
                                        "DAY",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        1),
                                new PlanMapper.NodeRow(
                                        NODE_ID,
                                        100L,
                                        "NODE",
                                        "电话随访（示例）",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        1,
                                        "YEAR",
                                        7,
                                        "DAY",
                                        7,
                                        "DAY",
                                        7,
                                        "DAY",
                                        "FORM",
                                        "WARD_DEFAULT",
                                        "A_ANNUAL_FU",
                                        null,
                                        null,
                                        1),
                                new PlanMapper.NodeRow(
                                        201L,
                                        100L,
                                        "NODE",
                                        "核对联系方式（示例）",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        5,
                                        "YEAR",
                                        7,
                                        "DAY",
                                        7,
                                        "DAY",
                                        7,
                                        "DAY",
                                        "REMINDER",
                                        "WARD_DEFAULT",
                                        null,
                                        null,
                                        null,
                                        2)));
    }

    private void insertAnswers(long id) {
        when(taskMapper.insertTask(any(FollowupTaskInsertRow.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(id);
                            return 1;
                        });
    }

    // ---------------------------------------------------------------- 创建（验收：不占用年度任务）

    @Test
    void createsTempFormTaskWithDefaults() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        when(taskMapper.findTempOccurrenceFloor(ENROLLMENT_ID, NODE_ID)).thenReturn(0);
        insertAnswers(9101L);

        FollowupTaskTempService.TempTaskView view =
                service.create(
                        new FollowupTaskTempService.TempCommand(
                                PATIENT_ID,
                                null,
                                null,
                                null,
                                PLAN_DATE,
                                null,
                                null,
                                null,
                                "再住院后加访（示例）"));

        assertEquals("9101", view.taskId());
        assertEquals("FORM", view.taskType());
        assertEquals("TEMP", view.createType());
        assertEquals("PENDING", view.status());
        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        FollowupTaskInsertRow row = captor.getValue();
        assertEquals(-1, row.getOccurrence(), "occurrence 负数计数器从 -1 起，不占用年度任务（A-FU-013）");
        assertEquals("FORM", row.getTaskType());
        assertEquals("A_ANNUAL_FU", row.getFormCode(), "FORM 表单缺省取节点表单");
        assertEquals("TEMP", row.getCreateType());
        assertEquals(OPERATOR, row.getAssigneeId(), "负责人缺省当前用户");
        assertEquals(OPERATOR, row.getCreatedBy(), "临时任务记操作人（V48 注释）");
        assertEquals(PLAN_DATE.minusDays(7), row.getWindowStart(), "窗口 = 计划日期 ± 默认 7 天");
        assertEquals(PLAN_DATE.plusDays(7), row.getWindowEnd());
        verify(outbox).publish(eq("TaskOpened"), contains("\"taskId\":9101"));
        verify(audit).append(any());
    }

    @Test
    void createsTempReminderTaskWithoutForm() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        // REMINDER 缺省挂靠同类型首个节点（201 核对联系方式）
        when(taskMapper.findTempOccurrenceFloor(ENROLLMENT_ID, 201L)).thenReturn(-3);
        insertAnswers(9102L);

        FollowupTaskTempService.TempTaskView view =
                service.create(
                        new FollowupTaskTempService.TempCommand(
                                PATIENT_ID,
                                null,
                                null,
                                "REMINDER",
                                PLAN_DATE,
                                0,
                                null,
                                "A_ANNUAL_FU",
                                null));

        assertEquals("REMINDER", view.taskType());
        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertEquals(-4, captor.getValue().getOccurrence(), "已有临时任务（至 -3）时递减");
        assertEquals(null, captor.getValue().getFormCode(), "REMINDER 无表单（A-FU-026 无需填表）");
        assertEquals(PLAN_DATE, captor.getValue().getWindowStart(), "窗口宽度 0：窗口 = 计划日当天");
        assertEquals(PLAN_DATE, captor.getValue().getWindowEnd());
    }

    @Test
    void explicitEnrollmentAndNodeAreValidated() {
        inScope();
        when(taskMapper.findActiveEnrollment(502L, PATIENT_ID)).thenReturn(null);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        502L,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "指定入组不在组或不属于该患者 40001");
    }

    @Test
    void assigneeMustCoverPatientWard() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        when(taskMapper.findPatientWardId(PATIENT_ID)).thenReturn(WARD_ID);
        when(assignMapper.countEnabledAssignee(77L)).thenReturn(1);
        when(assignMapper.countHospitalWideScope(77L)).thenReturn(0);
        when(assignMapper.countWardMembership(77L, WARD_ID)).thenReturn(0);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        null,
                                                        77L,
                                                        null,
                                                        null)))
                        .code(),
                "负责人不负责患者病区（与改派 DOCTOR_NOT_IN_WARD 同口径）");
        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
    }

    @Test
    void hospitalWideAssigneeIsAccepted() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        when(taskMapper.findPatientWardId(PATIENT_ID)).thenReturn(WARD_ID);
        when(assignMapper.countEnabledAssignee(77L)).thenReturn(1);
        when(assignMapper.countHospitalWideScope(77L)).thenReturn(1);
        when(taskMapper.findTempOccurrenceFloor(ENROLLMENT_ID, NODE_ID)).thenReturn(0);
        insertAnswers(9103L);

        service.create(
                new FollowupTaskTempService.TempCommand(
                        PATIENT_ID, null, null, null, PLAN_DATE, null, 77L, null, null));

        verify(taskMapper).insertTask(any(FollowupTaskInsertRow.class));
    }

    // ---------------------------------------------------------------- 校验（AGENTS §6：校验失败）

    @Test
    void outOfScopePatientIs40400() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);

        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code());
        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
    }

    @Test
    void patientWithoutActiveEnrollmentIs40001() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID)).thenReturn(null);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "临时任务挂靠子计划，无在组入组不可添加");
    }

    @Test
    void planDateRequiredAndWindowBounded() {
        inScope();
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "计划日期必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        366,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "窗口宽度 0–365 天");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        null,
                                                        PLAN_DATE,
                                                        -1,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "窗口宽度不可为负");
    }

    @Test
    void unknownTaskTypeAndNodeAreRejected() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        null,
                                                        "PHONE",
                                                        PLAN_DATE,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "任务类型只收 FORM / REMINDER");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.create(
                                                new FollowupTaskTempService.TempCommand(
                                                        PATIENT_ID,
                                                        null,
                                                        999L,
                                                        null,
                                                        PLAN_DATE,
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                        .code(),
                "节点须属于挂靠计划的 NODE 行");
    }

    // ---------------------------------------------------------------- 并发

    @Test
    void duplicateKeyRetriesWithNextOccurrence() {
        inScope();
        when(taskMapper.findFirstActiveEnrollment(PATIENT_ID))
                .thenReturn(
                        new FollowupTaskMapper.EnrollmentRefRow(
                                ENROLLMENT_ID, PATIENT_ID, PLAN_ID));
        planNodes();
        when(taskMapper.findTempOccurrenceFloor(ENROLLMENT_ID, NODE_ID)).thenReturn(0, -1);
        when(taskMapper.insertTask(any(FollowupTaskInsertRow.class)))
                .thenThrow(new DuplicateKeyException("uk_fu_task_occurrence"))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(9104L);
                            return 1;
                        });

        FollowupTaskTempService.TempTaskView view =
                service.create(
                        new FollowupTaskTempService.TempCommand(
                                PATIENT_ID, null, null, null, PLAN_DATE, null, null, null, null));

        assertEquals("9104", view.taskId());
        verify(taskMapper, org.mockito.Mockito.times(2))
                .findTempOccurrenceFloor(ENROLLMENT_ID, NODE_ID);
        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).insertTask(captor.capture());
        assertEquals(-2, captor.getValue().getOccurrence(), "撞 uk 后按递减后的次数重试");
    }
}
