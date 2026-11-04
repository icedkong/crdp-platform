package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.infra.mapper.PlanEnrollmentInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * E14-T01 / A-FU-019 入组单测（验收「新入组用新版本」）：入组只允许已发布（或派生随访中） 版本——旧版本发布新版本时已自动停用，入组必然落在新版本； 未发布 / 已暂停 /
 * 已停用 40901； 患者死亡 / 终止 40901；同族已在组 40901（唯一键兜底同口径）。
 *
 * <p>E14-T08 / A-FU-022 计划患者列表：姓名 / 病案号只输出脱敏值（解密后 SensitiveValueMasker）、随访完成次数聚合并入、 分页参数容错；计划不存在
 * 40400。数据范围由 Mapper 的 @DataScope 承担（拦截器改写，集成测试覆盖）。全部虚构数据。
 */
class PlanEnrollmentServiceTest {

    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;

    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentMapper mapper = mock(PlanEnrollmentMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final com.crdp.followup.task.app.FollowupTaskCreationService taskCreation =
            mock(com.crdp.followup.task.app.FollowupTaskCreationService.class);

    private final PlanEnrollmentService service =
            new PlanEnrollmentService(planMapper, mapper, audit, crypto, taskCreation);

    private static PlanMapper.PlanRow plan(String status, int version) {
        return plan(status, version, false);
    }

    private static PlanMapper.PlanRow plan(String status, int version, boolean autoEnroll) {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                version,
                status,
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                autoEnroll,
                null,
                0,
                null,
                "ACTIVE".equals(status) ? 5L : 0L);
    }

    private void patientAlive() {
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));
        // E14-T05：入组同一事务建任务；单测默认「0 补录 / 0 当期」（入组即建细节由
        // FollowupTaskCreationServiceTest 与 EnrollmentBackfillIT 锁定）
        when(taskCreation.initializeForNewEnrollment(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(
                        new com.crdp.followup.task.app.FollowupTaskCreationService
                                .EnrollmentInitSummary(0, 0));
    }

    @Test
    void enrollPinsPublishedPlanVersion() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 2));
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));

        PlanEnrollmentService.EnrollmentView view = service.enroll(PATIENT_ID, PLAN_ID, " normal ");

        assertEquals(String.valueOf(PLAN_ID), view.planId(), "入组行固定在当前发布版本 v2（A-FU-019）");
        assertEquals("NORMAL", view.mode());
        ArgumentCaptor<PlanEnrollmentInsertRow> captor =
                ArgumentCaptor.forClass(PlanEnrollmentInsertRow.class);
        verify(mapper).insertEnrollment(captor.capture());
        assertEquals("PLAN-IT-1", captor.getValue().getPlanCode());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        verify(audit).append(any(com.crdp.framework.audit.AuditEntry.class));
    }

    @Test
    void enrollRejectedForNonPublishedOrDeadOrDuplicate() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("UNPUBLISHED", 1));
        ApiException unpublished =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, unpublished.code());

        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PAUSED", 1));
        ApiException paused =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, paused.code());
        assertTrue(paused.getMessage().contains("暂停"));

        when(planMapper.findById(PLAN_ID)).thenReturn(plan("DISABLED", 1));
        ApiException disabled =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, disabled.code());
        assertTrue(disabled.getMessage().contains("新版本"), "已停用不再允许入组 → 引导用新版本");

        // 患者死亡（A-PAT-016 批量入组「已死亡跳过」同口径）
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "DEAD"));
        ApiException dead =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, dead.code());

        // 同族已在组
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(31L);
        ApiException dup =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, dup.code());
        assertTrue(dup.getMessage().contains("已在"));

        // 唯一键兜底（并发重复入组）
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_fu_enrollment_active"))
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        ApiException race =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40901, race.code());
    }

    @Test
    void enrollValidatesModeAndMissingPatient() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.findPatientState(anyLong())).thenReturn(null);
        ApiException missing =
                assertThrows(ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
        assertEquals(40400, missing.code());

        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(anyLong(), anyString())).thenReturn(null);
        ApiException badMode =
                assertThrows(
                        ApiException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, "monthly"));
        assertEquals(40001, badMode.code());
    }

    // ---------------------------------------------------------------- E14-T05 入组即建 + 自动入组

    @Test
    void enrollInitializesTasksAtEnrollmentAndAuditsCounts() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        when(taskCreation.initializeForNewEnrollment(31L, PATIENT_ID, PLAN_ID, "NORMAL"))
                .thenReturn(
                        new com.crdp.followup.task.app.FollowupTaskCreationService
                                .EnrollmentInitSummary(3, 1));

        service.enroll(PATIENT_ID, PLAN_ID, null);

        // 入组同一事务内创建任务：已结束窗口标补录 + 窗口内当期任务（E14-T05）
        verify(taskCreation).initializeForNewEnrollment(31L, PATIENT_ID, PLAN_ID, "NORMAL");
        ArgumentCaptor<com.crdp.framework.audit.AuditEntry> captor =
                ArgumentCaptor.forClass(com.crdp.framework.audit.AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().afterJson().contains("\"backfillTasks\":3"),
                "审计并入补录任务数：" + captor.getValue().afterJson());
        assertTrue(captor.getValue().afterJson().contains("\"currentTasks\":1"));
    }

    @Test
    void enrollFailsWholeEnrollmentWhenTaskInitFails() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(PATIENT_ID, "PLAN-IT-1")).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        when(taskCreation.initializeForNewEnrollment(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("租户库抖动（示例）"));

        // 补录任务建不出来 → 整个入组失败（同一事务回滚；不留在组无任务的半状态）
        assertThrows(IllegalStateException.class, () -> service.enroll(PATIENT_ID, PLAN_ID, null));
    }

    @Test
    void autoEnrollSkipsWhenDischargeStatusIsDeath() {
        // Q35：出院状态为死亡的不加入
        int enrolled = service.autoEnrollOnRegistration(PATIENT_ID, 3);

        assertEquals(0, enrolled);
        verify(planMapper, never()).findAutoEnrollPlans();
        verify(mapper, never()).insertEnrollment(any(PlanEnrollmentInsertRow.class));
    }

    @Test
    void autoEnrollJoinsEveryPublishedAutoPlanAndCreatesTasks() {
        PlanMapper.PlanRow first = plan("PUBLISHED", 1, true);
        PlanMapper.PlanRow second =
                new PlanMapper.PlanRow(
                        12L,
                        "PLAN-IT-2",
                        "示例 IT 专病随访计划",
                        1,
                        "PUBLISHED",
                        "SURGERY_DATE",
                        "ONGOING",
                        null,
                        true,
                        null,
                        0,
                        null,
                        0L);
        when(planMapper.findAutoEnrollPlans()).thenReturn(List.of(first, second));
        patientAlive();
        when(mapper.findActiveIdByPatientAndPlanCode(anyLong(), anyString())).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        when(planMapper.findById(PLAN_ID)).thenReturn(first);
        when(planMapper.findById(12L)).thenReturn(second);
        when(taskCreation.initializeForNewEnrollment(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(
                        new com.crdp.followup.task.app.FollowupTaskCreationService
                                .EnrollmentInitSummary(0, 0));

        int enrolled = service.autoEnrollOnRegistration(PATIENT_ID, 1);

        assertEquals(2, enrolled, "每个自动入组的已发布计划各入一条（Q35）");
        verify(mapper, org.mockito.Mockito.times(2)).insertEnrollment(any());
        verify(taskCreation).initializeForNewEnrollment(31L, PATIENT_ID, PLAN_ID, "NORMAL");
        verify(taskCreation).initializeForNewEnrollment(31L, PATIENT_ID, 12L, "NORMAL");
    }

    @Test
    void autoEnrollContinuesWhenSinglePlanFails() {
        PlanMapper.PlanRow broken = plan("PUBLISHED", 1, true);
        PlanMapper.PlanRow healthy =
                new PlanMapper.PlanRow(
                        12L,
                        "PLAN-IT-2",
                        "示例 IT 专病随访计划",
                        1,
                        "PUBLISHED",
                        "SURGERY_DATE",
                        "ONGOING",
                        null,
                        true,
                        null,
                        0,
                        null,
                        0L);
        when(planMapper.findAutoEnrollPlans()).thenReturn(List.of(broken, healthy));
        // 第一个计划：患者状态不可入组（已终止）→ 跳过继续
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "TERMINATED"));
        when(planMapper.findById(PLAN_ID)).thenReturn(broken);
        when(planMapper.findById(12L)).thenReturn(healthy);
        when(mapper.findActiveIdByPatientAndPlanCode(anyLong(), anyString())).thenReturn(null);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanEnrollmentInsertRow.class).setId(31L);
                            return null;
                        })
                .when(mapper)
                .insertEnrollment(any(PlanEnrollmentInsertRow.class));
        when(taskCreation.initializeForNewEnrollment(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(
                        new com.crdp.followup.task.app.FollowupTaskCreationService
                                .EnrollmentInitSummary(0, 0));

        // 前置：第二个计划可入组（状态改回在册）——findPatientState 按 plan 顺序依次打桩改为两次调用
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "TERMINATED"))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));

        int enrolled = service.autoEnrollOnRegistration(PATIENT_ID, 2);

        assertEquals(1, enrolled, "单个计划失败不影响其余计划（病重自动出院 2 同样入组）");
        verify(mapper, org.mockito.Mockito.times(1)).insertEnrollment(any());
    }

    // ---------------------------------------------------------------- 计划患者列表（E14-T08 / A-FU-022）

    private static PlanEnrollmentMapper.EnrollmentPatientRow enrollmentRow(
            long id, long patientId, String status, String name, String mrn) {
        return new PlanEnrollmentMapper.EnrollmentPatientRow(
                id,
                patientId,
                "name-cipher-" + id,
                "mrn-cipher-" + id,
                "LEGACY-" + id,
                "M",
                "虚构病区一",
                "NORMAL",
                status,
                "PAUSED".equals(status) ? "外地随访（示例）" : null,
                "REMOVED".equals(status) ? "拒访（示例）" : null,
                LocalDateTime.of(2026, 3, 1, 9, 0));
    }

    @Test
    void patientsMasksNameAndMrnAndJoinsCompletedCount() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.countPatientsByPlanId(PLAN_ID)).thenReturn(2L);
        when(mapper.findPatientsByPlanId(anyLong(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                enrollmentRow(31L, 77L, "ACTIVE", "赵小同（示例）", "IT-E14T08-1"),
                                enrollmentRow(32L, 78L, "REMOVED", "钱大二（示例）", "IT-E14T08-2")));
        when(crypto.decrypt("name-cipher-31")).thenReturn("赵小同（示例）");
        when(crypto.decrypt("mrn-cipher-31")).thenReturn("IT-E14T08-1");
        when(crypto.decrypt("name-cipher-32")).thenReturn("钱大二（示例）");
        when(crypto.decrypt("mrn-cipher-32")).thenReturn("IT-E14T08-2");
        when(mapper.countCompletedByEnrollmentIds(anyList()))
                .thenReturn(List.of(new PlanEnrollmentMapper.CompletedCountRow(31L, 3L)));

        PlanEnrollmentService.EnrollmentPatientPage page = service.patients(PLAN_ID, 1, 20);

        assertEquals(2, page.total());
        assertEquals(1, page.pageNo());
        assertEquals(20, page.pageSize());
        assertEquals(2, page.items().size());
        PlanEnrollmentService.EnrollmentPatientView active = page.items().get(0);
        assertEquals("赵*）", active.name(), "姓名只输出脱敏值（首字 + * + 尾字，AGENTS §5.3）");
        assertEquals("IT*******-1", active.mrn(), "病案号前 2 后 2、中段全掩");
        assertFalse(active.name().contains("小同"), "不透出姓名明文");
        assertEquals(3, active.completedCount(), "随访完成次数并入（fu_task COMPLETED 聚合）");
        assertEquals("ACTIVE", active.status());
        assertTrue(active.enrolledAt().startsWith("2026-03-01T"), "加入时间 ISO-8601 带时区");
        PlanEnrollmentService.EnrollmentPatientView removed = page.items().get(1);
        assertEquals("钱*）", removed.name());
        assertEquals("REMOVED", removed.status(), "已移除患者仍在列表（A-FU-022 三态）");
        assertEquals("拒访（示例）", removed.removeReason());
        assertEquals(0, removed.completedCount(), "无完成任务计 0");
    }

    @Test
    void patientsNormalizesPagingAndRejectsMissingPlan() {
        when(planMapper.findById(PLAN_ID)).thenReturn(plan("PUBLISHED", 1));
        when(mapper.countPatientsByPlanId(PLAN_ID)).thenReturn(0L);

        PlanEnrollmentService.EnrollmentPatientPage defaulted =
                service.patients(PLAN_ID, null, null);
        assertEquals(1, defaulted.pageNo(), "pageNo 缺省 1");
        assertEquals(20, defaulted.pageSize(), "pageSize 缺省 20");

        when(mapper.countPatientsByPlanId(PLAN_ID)).thenReturn(150L);
        PlanEnrollmentService.EnrollmentPatientPage capped = service.patients(PLAN_ID, 0, 500);
        assertEquals(1, capped.pageNo(), "pageNo 下限 1");
        assertEquals(100, capped.pageSize(), "pageSize 上限 100");
        // 第 2 页：offset = (2 - 1) × 100
        service.patients(PLAN_ID, 2, 100);
        verify(mapper).findPatientsByPlanId(PLAN_ID, 100, 100);

        when(planMapper.findById(PLAN_ID)).thenReturn(null);
        ApiException missing =
                assertThrows(ApiException.class, () -> service.patients(PLAN_ID, 1, 20));
        assertEquals(40400, missing.code());
    }
}
