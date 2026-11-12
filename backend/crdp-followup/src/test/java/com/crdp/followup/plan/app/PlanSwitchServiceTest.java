package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.app.PlanSwitchService.ImplantSwitchView;
import com.crdp.followup.plan.app.PlanSwitchService.RecordSwitchView;
import com.crdp.followup.plan.infra.mapper.DevicePlanMappingMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper.SwitchableEnrollmentRow;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.app.FollowupTaskTerminator;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E21-T06 / C-FU-007 单测（tasks.csv 验收「切换表每行一条用例通过」）。
 *
 * <p>C 规格第 5 章「计划切换触发事件」表 + docs/reviews/hospital-c-acceptance-review.md §3.3.1 逐行用例（SW-1…SW-10）：
 * 换计划各行（升级 / 更换 / 器械类型更正 / 取出）统一落法 = 原入组结束（SWITCHED + 原因）+ 未完成任务终止（原因入终止三列）+ 按新类型映射重新入组 （起算快照 =
 * 新植入日期；SW-6 仍按原植入日期）；电极修正不切换；ERI 标记 / 解除只翻 mode（eri_since = 该次随访日期）； 医生指定只登记
 * doctor_next_date（计划类型不变）。全部虚构数据。
 */
class PlanSwitchServiceTest {

    private static final long PATIENT_ID = 301L;
    private static final long IMPLANT_ID = 501L;
    private static final long PACER_ENROLLMENT_ID = 61L;
    private static final long PACER_PLAN_ID = 41L;
    private static final long ICD_PLAN_ID = 42L;
    private static final LocalDate IMPLANT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate SWITCH_DATE = LocalDate.of(2026, 9, 1);

    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final DevicePlanMappingMapper mappingMapper = mock(DevicePlanMappingMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentService enrollments = mock(PlanEnrollmentService.class);
    private final FollowupTaskTerminator taskTerminations = mock(FollowupTaskTerminator.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PlanSwitchService service =
            new PlanSwitchService(
                    enrollmentMapper,
                    mappingMapper,
                    planMapper,
                    enrollments,
                    taskTerminations,
                    audit);

    @BeforeEach
    void setUp() {
        // C-FU-002 类型划分（V86 预置口径）：DDD / CRT_P → 普通计划，CRT_D / ICD → ICD 计划
        lenient()
                .when(mappingMapper.findByDeviceType("DDD"))
                .thenReturn(
                        new DevicePlanMappingMapper.MappingRow(1L, "DDD", "C_PACER_FU_PLAN", true));
        lenient()
                .when(mappingMapper.findByDeviceType("CRT_P"))
                .thenReturn(
                        new DevicePlanMappingMapper.MappingRow(
                                2L, "CRT_P", "C_PACER_FU_PLAN", true));
        lenient()
                .when(mappingMapper.findByDeviceType("CRT_D"))
                .thenReturn(
                        new DevicePlanMappingMapper.MappingRow(3L, "CRT_D", "C_ICD_FU_PLAN", true));
        lenient()
                .when(planMapper.findPublishedByCode("C_PACER_FU_PLAN"))
                .thenReturn(plan(PACER_PLAN_ID, "C_PACER_FU_PLAN"));
        lenient()
                .when(planMapper.findPublishedByCode("C_ICD_FU_PLAN"))
                .thenReturn(plan(ICD_PLAN_ID, "C_ICD_FU_PLAN"));
        lenient().when(enrollmentMapper.switchEndEnrollment(anyLong(), anyString())).thenReturn(1);
        lenient()
                .when(enrollmentMapper.switchEnrollmentMode(anyLong(), anyString(), any()))
                .thenReturn(1);
    }

    private static PlanMapper.PlanRow plan(long id, String code) {
        return new PlanMapper.PlanRow(
                id,
                code,
                "示例计划（示例）",
                2,
                "PUBLISHED",
                "IMPLANT_DATE",
                "ONGOING",
                null,
                false,
                null,
                3,
                null,
                0L);
    }

    private static SwitchableEnrollmentRow pacerEnrollment(String mode) {
        return new SwitchableEnrollmentRow(
                PACER_ENROLLMENT_ID, PACER_PLAN_ID, "C_PACER_FU_PLAN", mode, "ACTIVE");
    }

    // ---------------------------------------------------------------- SW-1 首次植入：按器械类型入组（E21-T05
    // 路径）

    @Test
    void sw1FirstImplantDoesNotSwitchAndFallsBackToAutoEnroll() {
        ImplantSwitchView view =
                service.onActiveImplantAdded(PATIENT_ID, IMPLANT_ID, "FIRST", "DDD", IMPLANT_DATE);

        assertFalse(view.switched(), "首次植入不结束原入组（SW-1，按类型入组走 E21-T05 自动入组）");
        verifyNoInteractions(enrollmentMapper);
        verifyNoInteractions(taskTerminations);
    }

    // ---------------------------------------------------------------- SW-2 升级为 ICD / CRT-D：原入组结束、转
    // ICD 计划

    @Test
    void sw2UpgradeToIcdFamilyEndsOldEnrollmentAndReenrollsFromUpgradeDate() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));

        ImplantSwitchView view =
                service.onActiveImplantAdded(
                        PATIENT_ID, IMPLANT_ID, "UPGRADE", "CRT_D", SWITCH_DATE);

        assertTrue(view.switched(), "升级为 CRT-D 触发计划切换（SW-2）");
        assertEquals(1, view.endedEnrollments());
        assertEquals("C_ICD_FU_PLAN", view.toPlanCode(), "原入组结束，转入 ICD/CRT-D 计划");
        // 原入组结束：SWITCHED + 原因（升级）；未完成任务终止并记录原因
        verify(enrollmentMapper).switchEndEnrollment(eq(PACER_ENROLLMENT_ID), stringContains("升级"));
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("升级"), any());
        // 重新入组：ICD 计划、常规模式、从升级日期起算
        verify(enrollments).enroll(PATIENT_ID, ICD_PLAN_ID, "NORMAL", SWITCH_DATE);
    }

    // ---------------------------------------------------------------- SW-3 升级为 CRT-P /
    // 普通类型：留在普通计划、从升级日期重起算

    @Test
    void sw3UpgradeToCrtPStaysInPacerFamilyButRestartsFromUpgradeDate() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));

        ImplantSwitchView view =
                service.onActiveImplantAdded(
                        PATIENT_ID, IMPLANT_ID, "UPGRADE", "CRT_P", SWITCH_DATE);

        assertTrue(view.switched(), "升级为 CRT-P 同样触发切换（SW-3：转入或留在普通计划）");
        assertEquals("C_PACER_FU_PLAN", view.toPlanCode(), "CRT-P 归普通计划（CL-02 假设三）");
        verify(enrollmentMapper).switchEndEnrollment(eq(PACER_ENROLLMENT_ID), anyString());
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(eq(PACER_ENROLLMENT_ID), anyString(), any());
        // 同族重起算 = 结束旧行后以新起算日重新入组同族（从升级日期起算，术后节点重走）
        verify(enrollments).enroll(PATIENT_ID, PACER_PLAN_ID, "NORMAL", SWITCH_DATE);
    }

    // ---------------------------------------------------------------- SW-4 电池更换（同类型）：计划类型不变、ERI
    // 循环结束、从更换日期重走

    @Test
    void sw4BatteryReplacementKeepsFamilyEndsEriCycleAndRestartsFromReplaceDate() {
        // ERI 期（mode = ERI）的双腔患者换同类型器械：ERI 期循环结束（重新入组为常规模式）、从更换日期重走术后节点
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("ERI")));

        ImplantSwitchView view =
                service.onActiveImplantAdded(PATIENT_ID, IMPLANT_ID, "REPLACE", "DDD", SWITCH_DATE);

        assertTrue(view.switched(), "电池更换触发切换（SW-4）");
        assertEquals("C_PACER_FU_PLAN", view.toPlanCode(), "同类型更换计划类型不变");
        verify(enrollmentMapper).switchEndEnrollment(eq(PACER_ENROLLMENT_ID), stringContains("更换"));
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("更换"), any());
        // 重新入组为常规模式（ERI 期循环结束）、从更换日期重新起算
        verify(enrollments).enroll(PATIENT_ID, PACER_PLAN_ID, "NORMAL", SWITCH_DATE);
    }

    @Test
    void sw4UnmappedNewTypeKeepsOldEnrollment() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));
        // 新类型未映射（映射缺失）：保留原入组（不中断随访），配置补齐后再次保存触发
        when(mappingMapper.findByDeviceType("LEADLESS")).thenReturn(null);

        ImplantSwitchView view =
                service.onActiveImplantAdded(
                        PATIENT_ID, IMPLANT_ID, "REPLACE", "LEADLESS", SWITCH_DATE);

        assertFalse(view.switched(), "未映射类型不切换（防御：旧随访不中断）");
        verify(enrollmentMapper, never()).switchEndEnrollment(anyLong(), anyString());
    }

    // ---------------------------------------------------------------- SW-5 电极修正：不切换

    @Test
    void sw5LeadRevisionDoesNotSwitch() {
        ImplantSwitchView view =
                service.onActiveImplantAdded(
                        PATIENT_ID, IMPLANT_ID, "LEAD_REVISION", "DDD", SWITCH_DATE);

        assertFalse(view.switched(), "电极修正不切换计划（SW-5；新电极从修正日期起参与参数对比在记录侧）");
        verifyNoInteractions(enrollmentMapper);
        verifyNoInteractions(taskTerminations);
        verifyNoInteractions(enrollments);
    }

    // ---------------------------------------------------------------- SW-6
    // 器械类型更正：按新类型重新入组、起算仍按原植入日期

    @Test
    void sw6DeviceTypeCorrectionReenrollsByNewTypeFromOriginalImplantDate() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));

        ImplantSwitchView view =
                service.onDeviceTypeCorrected(PATIENT_ID, IMPLANT_ID, "DDD", "CRT_D", IMPLANT_DATE);

        assertTrue(view.switched(), "器械类型更正（录入纠错）触发重新入组（SW-6）");
        verify(enrollmentMapper).switchEndEnrollment(eq(PACER_ENROLLMENT_ID), stringContains("更正"));
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("更正"), any());
        // 起算仍按原植入日期（切换表「否，仍按原植入日期」），不是更正当天
        verify(enrollments).enroll(PATIENT_ID, ICD_PLAN_ID, "NORMAL", IMPLANT_DATE);
    }

    // ---------------------------------------------------------------- SW-9 取出且未再植入：结束入组、不再创建任务

    @Test
    void sw9ExplantEndsAllEnrollmentsWithoutReenrolling() {
        SwitchableEnrollmentRow paused =
                new SwitchableEnrollmentRow(62L, ICD_PLAN_ID, "C_ICD_FU_PLAN", "NORMAL", "PAUSED");
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL"), paused));

        ImplantSwitchView view = service.onImplantExplanted(PATIENT_ID, IMPLANT_ID, SWITCH_DATE);

        assertTrue(view.switched(), "取出触发入组结束（SW-9）");
        assertEquals(2, view.endedEnrollments(), "在组（含已暂停）入组全部结束");
        assertFalse(view.enrolled(), "取出且未再植入不重新入组（再植入走登记 / 新建）");
        verify(enrollmentMapper).switchEndEnrollment(eq(PACER_ENROLLMENT_ID), stringContains("取出"));
        verify(enrollmentMapper).switchEndEnrollment(eq(62L), stringContains("取出"));
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("取出"), any());
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(eq(62L), stringContains("取出"), any());
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    // ---------------------------------------------------------------- SW-7 接近 ERI：切换 ERI
    // 期循环、从该次随访日期起算

    @Test
    void sw7EriMarkedSwitchesModeWithEriSinceVisitDate() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));
        LocalDate visitDate = LocalDate.of(2026, 9, 10);

        RecordSwitchView view =
                service.onRecordSubmitted(PATIENT_ID, 701L, visitDate, Map.of("f323", "1"));

        assertEquals(1, view.eriSwitched(), "ERI 状态 = 接近ERI 切换 ERI 期（SW-7）");
        // mode → ERI、eri_since = 该次随访日期（切换表「从该次随访日期起算」）
        verify(enrollmentMapper).switchEnrollmentMode(PACER_ENROLLMENT_ID, "ERI", visitDate);
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("ERI"), any());
        // 不换入组行、不重新入组（循环任务创建随 E21-T07）
        verify(enrollmentMapper, never()).switchEndEnrollment(anyLong(), anyString());
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void sw7EriHitVariantsEoiCodeAndLowBatteryLife() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));
        // 已达 ERI(EOI)（编码 2）
        assertEquals(
                1,
                service.onRecordSubmitted(
                                PATIENT_ID, 701L, LocalDate.of(2026, 9, 10), Map.of("f323", "2"))
                        .eriSwitched(),
                "已达ERI(EOI) 同样切换");
        // 预计电池寿命 ≤ 1 年（数值与字符串两种形态）
        assertEquals(
                1,
                service.onRecordSubmitted(
                                PATIENT_ID, 702L, LocalDate.of(2026, 9, 11), Map.of("f324", 0.8))
                        .eriSwitched(),
                "电池寿命 0.8 年（数值）切换");
        assertEquals(
                1,
                service.onRecordSubmitted(
                                PATIENT_ID, 703L, LocalDate.of(2026, 9, 12), Map.of("f324", "1"))
                        .eriSwitched(),
                "电池寿命 1 年（字符串、恰为阈值）切换");
    }

    @Test
    void sw7AlreadyEriModeIsIdempotent() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("ERI")));

        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID, 701L, LocalDate.of(2026, 9, 10), Map.of("f323", "1"));

        assertEquals(0, view.eriSwitched(), "已在 ERI 期不重复切换（幂等）");
        verify(enrollmentMapper, never()).switchEnrollmentMode(anyLong(), anyString(), any());
    }

    // ---------------------------------------------------------------- E21-T07 / C-FU-006 滚动循环基准：
    // 上次实际随访日期快照（fu_enrollment.last_visit_date，V93）

    @Test
    void recordSubmissionAdvancesLastVisitDateSnapshot() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("ERI")));
        LocalDate visitDate = LocalDate.of(2026, 10, 20);

        service.onRecordSubmitted(PATIENT_ID, 705L, visitDate, Map.of("f323", "1"));

        // 滚动循环节点（ERI 期每 1–3 个月 / 常规随访）的排期基准：记录提交单调推进快照（只增不减，SQL 守卫）
        verify(enrollmentMapper).advanceLastVisitDate(PATIENT_ID, visitDate);
    }

    @Test
    void recordSubmissionWithoutActiveEnrollmentDoesNotAdvanceSnapshot() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID)).thenReturn(List.of());

        service.onRecordSubmitted(
                PATIENT_ID, 705L, LocalDate.of(2026, 10, 20), Map.of("f323", "1"));

        verify(enrollmentMapper, never()).advanceLastVisitDate(anyLong(), any());
    }

    @Test
    void unresolvableVisitDateFallsBackToTodayForSnapshot() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));

        service.onRecordSubmitted(PATIENT_ID, 705L, null, Map.of());

        // 随访日期不可解析（visit_date 与表单字段均空，防御）回落今天——与 eri_since 同口径
        verify(enrollmentMapper).advanceLastVisitDate(eq(PATIENT_ID), eq(LocalDate.now()));
    }

    // ---------------------------------------------------------------- SW-8 ERI 标记解除：恢复原计划的常规间隔

    @Test
    void sw8EriClearedRestoresNormalMode() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("ERI")));

        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID, 701L, LocalDate.of(2026, 10, 12), Map.of("f323", "0"));

        assertEquals(1, view.eriRestored(), "ERI 状态更正为正常 → 恢复常规（SW-8）");
        // mode 回 NORMAL、eri_since 清空
        verify(enrollmentMapper).switchEnrollmentMode(PACER_ENROLLMENT_ID, "NORMAL", null);
        verify(taskTerminations)
                .terminateUnfinishedByEnrollment(
                        eq(PACER_ENROLLMENT_ID), stringContains("解除"), any());
        verify(enrollmentMapper, never()).switchEndEnrollment(anyLong(), anyString());
    }

    @Test
    void sw8LowBatteryLifeStillHoldsEriCycle() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("ERI")));
        // ERI 状态正常但电池寿命仍 ≤ 1 年：不解除（电池仍接近更换，OQ-137）
        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID,
                        701L,
                        LocalDate.of(2026, 10, 12),
                        Map.of("f323", "0", "f324", 0.9));

        assertEquals(0, view.eriRestored(), "电池寿命仍偏低时不解除 ERI 期（OQ-137 暂行）");
        verify(enrollmentMapper, never()).switchEnrollmentMode(anyLong(), anyString(), any());
    }

    // ---------------------------------------------------------------- SW-10
    // 医生指定下次随访时间：不改变计划类型、只覆盖下一次任务计划日期

    @Test
    void sw10DoctorNextVisitDateOverridesNextPlanDateWithoutPlanChange() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));
        LocalDate next = LocalDate.now().plusMonths(3);

        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID, 701L, LocalDate.now(), Map.of("f333", next.toString()));

        assertTrue(view.doctorNextDateSet(), "登记医生指定下次随访日期（SW-10）");
        verify(enrollmentMapper).setDoctorNextDate(PATIENT_ID, next);
        // 不改变计划类型：不结束入组、不翻模式、不重新入组（窗口围绕展开与任务创建随 E21-T08）
        verify(enrollmentMapper, never()).switchEndEnrollment(anyLong(), anyString());
        verify(enrollmentMapper, never()).switchEnrollmentMode(anyLong(), anyString(), any());
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void sw10PastDoctorDateFromBackfilledRecordIsIgnored() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));

        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID, 701L, LocalDate.of(2025, 6, 1), Map.of("f333", "2025-07-01"));

        assertFalse(view.doctorNextDateSet(), "补录历史记录填写的下次随访时间只保存、不再生成任务（C 规格 §5）");
        verify(enrollmentMapper, never()).setDoctorNextDate(anyLong(), any());
    }

    // ---------------------------------------------------------------- 公共口径

    @Test
    void patientWithoutActiveEnrollmentFallsBackToAutoEnroll() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID)).thenReturn(List.of());

        assertFalse(
                service.onActiveImplantAdded(
                                PATIENT_ID, IMPLANT_ID, "UPGRADE", "CRT_D", SWITCH_DATE)
                        .switched(),
                "无在组入组时回落 E21-T05 自动入组路径");
        assertFalse(
                service.onDeviceTypeCorrected(PATIENT_ID, IMPLANT_ID, "DDD", "CRT_D", IMPLANT_DATE)
                        .switched(),
                "无在组入组时类型更正同样回落");
        assertFalse(
                service.onImplantExplanted(PATIENT_ID, IMPLANT_ID, SWITCH_DATE).switched(),
                "无在组入组时取出无事可做");
    }

    @Test
    void reenrollSkipKeepsEndedEnrollmentWhenPatientTerminal() {
        // 患者已死亡 / 已终止等业务性不可入组：原入组结束的事实保留（不回滚触发动作）
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID))
                .thenReturn(List.of(pacerEnrollment("NORMAL")));
        when(enrollments.enroll(anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new ApiException(409, 40901, "患者已死亡或已终止随访，不能入组"));

        ImplantSwitchView view =
                service.onActiveImplantAdded(
                        PATIENT_ID, IMPLANT_ID, "UPGRADE", "CRT_D", SWITCH_DATE);

        assertTrue(view.switched(), "切换事实（原入组结束）保留");
        assertFalse(view.enrolled(), "重新入组被跳过（患者终态）");
    }

    @Test
    void recordWithoutSwitchableEnrollmentIsNoOp() {
        when(enrollmentMapper.findSwitchableEnrollments(PATIENT_ID)).thenReturn(List.of());

        RecordSwitchView view =
                service.onRecordSubmitted(
                        PATIENT_ID,
                        701L,
                        LocalDate.now(),
                        Map.of("f323", "1", "f333", "2027-01-15"));

        assertEquals(0, view.eriSwitched());
        assertEquals(0, view.eriRestored());
        assertFalse(view.doctorNextDateSet());
        verify(enrollmentMapper, never()).switchEnrollmentMode(anyLong(), anyString(), any());
        verify(enrollmentMapper, never()).setDoctorNextDate(anyLong(), any());
    }

    /** assertThat(stringContains) 的 Mockito 等价：捕获原因参数并断言包含关键字。 */
    private static String stringContains(String keyword) {
        return org.mockito.ArgumentMatchers.argThat(
                reason -> reason != null && reason.contains(keyword));
    }
}
