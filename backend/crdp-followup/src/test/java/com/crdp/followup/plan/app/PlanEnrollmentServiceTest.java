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

    private final PlanEnrollmentService service =
            new PlanEnrollmentService(planMapper, mapper, audit, crypto);

    private static PlanMapper.PlanRow plan(String status, int version) {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                version,
                status,
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                null,
                0,
                null,
                "ACTIVE".equals(status) ? 5L : 0L);
    }

    private void patientAlive() {
        when(mapper.findPatientState(PATIENT_ID))
                .thenReturn(new PlanEnrollmentMapper.PatientStateRow(PATIENT_ID, "ACTIVE"));
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
