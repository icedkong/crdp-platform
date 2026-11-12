package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.plan.app.DevicePlanMappingService.AutoEnrollOutcome;
import com.crdp.followup.plan.app.DevicePlanMappingService.MappingInput;
import com.crdp.followup.plan.app.DevicePlanMappingService.MappingView;
import com.crdp.followup.plan.infra.mapper.DevicePlanMappingMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E21-T05 / C-FU-003 单测（tasks.csv 验收「按器械类型进入对应计划」）。
 *
 * <p>锁定口径：保存植入记录后按「起搏器类型 → 计划」映射自动入组，起算日 = 植入日期（enroll 4 参重载的快照）； 未映射 / 映射停用、族无已发布版本、同族已在组、
 * 他族在组（切换随 E21-T06）、患者不可入组为业务性跳过（不抛错、不阻断植入保存）； 映射配置整体替换保存（字典外类型 / 重复类型 / 不存在的计划族 40001）；配置接口
 * implant 开关关闭 40301；写 PLAN_MAPPING_SAVE 审计。全部虚构数据。
 */
class DevicePlanMappingServiceTest {

    private static final long PATIENT_ID = 301L;
    private static final long PLAN_ID = 41L;
    private static final LocalDate IMPLANT_DATE = LocalDate.of(2026, 3, 2);

    private final DevicePlanMappingMapper mappingMapper = mock(DevicePlanMappingMapper.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final PlanEnrollmentService enrollments = mock(PlanEnrollmentService.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);
    private final ImplantFeatureMapper features = mock(ImplantFeatureMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final DevicePlanMappingService service =
            new DevicePlanMappingService(
                    mappingMapper,
                    planMapper,
                    enrollmentMapper,
                    enrollments,
                    dicts,
                    features,
                    audit);

    @BeforeEach
    void setUp() {
        // 平台库 V70 预置口径（含停用项；C-FLD-009 保存校验接受全部已知项）
        when(dicts.items(ImplantDictPort.DICT_DEVICE_TYPE))
                .thenReturn(
                        List.of(
                                new ImplantDictPort.DictItem("SSI", "单腔起搏器(SSI)", 10, true),
                                new ImplantDictPort.DictItem("DDD", "双腔起搏器(DDD)", 20, true),
                                new ImplantDictPort.DictItem("CRT_D", "CRT-D", 50, true)));
        lenient().when(features.findEnabled(anyString())).thenReturn(true);
        lenient().when(planMapper.findMaxVersionByCode("C_PACER_FU_PLAN")).thenReturn(2);
        lenient()
                .when(planMapper.findLatestByCode(anyString()))
                .thenReturn(plan("C_PACER_FU_PLAN", "普通起搏器术后随访（示例）", 2, "PUBLISHED"));
    }

    private static PlanMapper.PlanRow plan(String code, String name, int version, String status) {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                code,
                name,
                version,
                status,
                "IMPLANT_DATE",
                "ONGOING",
                null,
                false,
                null,
                0,
                null,
                0L);
    }

    private static DevicePlanMappingMapper.MappingRow mapping(String deviceType, boolean enabled) {
        return new DevicePlanMappingMapper.MappingRow(1L, deviceType, "C_PACER_FU_PLAN", enabled);
    }

    // ---------------------------------------------------------------- 自动入组（C-FU-003 验收）

    @Test
    void mappedDeviceEnrollsWithImplantDateAsStartDate() {
        when(mappingMapper.findByDeviceType("DDD")).thenReturn(mapping("DDD", true));
        when(planMapper.findPublishedByCode("C_PACER_FU_PLAN"))
                .thenReturn(plan("C_PACER_FU_PLAN", "普通起搏器术后随访（示例）", 2, "PUBLISHED"));
        when(enrollmentMapper.findActivePlanCodes(PATIENT_ID)).thenReturn(List.of());

        AutoEnrollOutcome outcome = service.autoEnrollByDeviceType(PATIENT_ID, "DDD", IMPLANT_DATE);

        assertEquals(AutoEnrollOutcome.ENROLLED, outcome, "按器械类型进入对应计划");
        // 起算日 = 植入日期（C-FU-003）：入组起算日期快照走 enroll 4 参重载
        verify(enrollments)
                .enroll(PATIENT_ID, PLAN_ID, PlanEnrollmentService.MODE_NORMAL, IMPLANT_DATE);
    }

    @Test
    void unmappedOrDisabledMappingSkipsEnrollment() {
        when(mappingMapper.findByDeviceType("SSI")).thenReturn(null);
        assertEquals(
                AutoEnrollOutcome.NOT_MAPPED,
                service.autoEnrollByDeviceType(PATIENT_ID, "SSI", IMPLANT_DATE),
                "未映射类型跳过");
        when(mappingMapper.findByDeviceType("SSI")).thenReturn(mapping("SSI", false));
        assertEquals(
                AutoEnrollOutcome.NOT_MAPPED,
                service.autoEnrollByDeviceType(PATIENT_ID, "SSI", IMPLANT_DATE),
                "映射停用跳过");
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void familyWithoutPublishedVersionSkipsEnrollment() {
        when(mappingMapper.findByDeviceType("DDD")).thenReturn(mapping("DDD", true));
        when(planMapper.findPublishedByCode("C_PACER_FU_PLAN")).thenReturn(null);

        assertEquals(
                AutoEnrollOutcome.PLAN_NOT_PUBLISHED,
                service.autoEnrollByDeviceType(PATIENT_ID, "DDD", IMPLANT_DATE),
                "计划族未发布（如 E23-T20 未落地）跳过");
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void alreadyInSameFamilyOrOtherFamilySkipsEnrollment() {
        when(mappingMapper.findByDeviceType("DDD")).thenReturn(mapping("DDD", true));
        when(planMapper.findPublishedByCode("C_PACER_FU_PLAN"))
                .thenReturn(plan("C_PACER_FU_PLAN", "普通起搏器术后随访（示例）", 2, "PUBLISHED"));

        // 同族已在组（如电池更换：同类型 → 同计划）：跳过，不重复入组
        when(enrollmentMapper.findActivePlanCodes(PATIENT_ID))
                .thenReturn(List.of("C_PACER_FU_PLAN"));
        assertEquals(
                AutoEnrollOutcome.ALREADY_IN_PLAN,
                service.autoEnrollByDeviceType(PATIENT_ID, "DDD", IMPLANT_DATE));

        // 他族在组（如 DDD 已入普通计划，升级 CRT-D → ICD 计划）：跳过——原入组结束与任务终止随 E21-T06（C-FU-007）
        when(enrollmentMapper.findActivePlanCodes(PATIENT_ID)).thenReturn(List.of("OTHER_FAMILY"));
        assertEquals(
                AutoEnrollOutcome.OTHER_PLAN_ACTIVE,
                service.autoEnrollByDeviceType(PATIENT_ID, "DDD", IMPLANT_DATE));
        verify(enrollments, never()).enroll(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void patientNotEnrollableIsBusinessSkipNotError() {
        when(mappingMapper.findByDeviceType("DDD")).thenReturn(mapping("DDD", true));
        when(planMapper.findPublishedByCode("C_PACER_FU_PLAN"))
                .thenReturn(plan("C_PACER_FU_PLAN", "普通起搏器术后随访（示例）", 2, "PUBLISHED"));
        when(enrollmentMapper.findActivePlanCodes(PATIENT_ID)).thenReturn(List.of());
        when(enrollments.enroll(anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new ApiException(409, 40901, "患者已死亡或已终止随访，不能入组"));

        assertEquals(
                AutoEnrollOutcome.PATIENT_NOT_ENROLLABLE,
                service.autoEnrollByDeviceType(PATIENT_ID, "DDD", IMPLANT_DATE),
                "患者不可入组（已死亡 / 已终止）为业务性跳过，不阻断植入保存");
    }

    // ---------------------------------------------------------------- 映射配置（implant.md §2）

    @Test
    void mappingMergesDictTypesWithConfiguredRows() {
        when(mappingMapper.findAll())
                .thenReturn(List.of(mapping("DDD", true), mapping("CRT_D", false)));

        MappingView view = service.mapping();

        assertEquals(3, view.items().size(), "字典类型全量下发（含未映射项）");
        assertEquals("DDD", view.items().get(0).deviceType());
        assertEquals("双腔起搏器(DDD)", view.items().get(0).deviceTypeLabel());
        assertEquals("C_PACER_FU_PLAN", view.items().get(0).planCode());
        assertEquals("普通起搏器术后随访（示例）", view.items().get(0).planName(), "计划族名一并下发");
        assertEquals("CRT_D", view.items().get(1).deviceType());
        assertEquals(false, view.items().get(1).enabled(), "停用映射如实下发");
        assertNull(view.items().get(2).planCode(), "未映射类型 planCode 为 null");
    }

    @Test
    void saveReplacesMappingAndWritesAudit() {
        when(mappingMapper.findAll())
                .thenReturn(List.of(mapping("DDD", true)))
                .thenReturn(List.of(mapping("DDD", true)));
        when(dicts.items(ImplantDictPort.DICT_DEVICE_TYPE))
                .thenReturn(
                        List.of(
                                new ImplantDictPort.DictItem("DDD", "双腔起搏器(DDD)", 20, true),
                                new ImplantDictPort.DictItem("CRT_D", "CRT-D", 50, true)));

        MappingView view =
                service.save(
                        List.of(
                                new MappingInput("DDD", "C_PACER_FU_PLAN", true),
                                new MappingInput("CRT_D", " ", false)));

        verify(mappingMapper).deleteAll();
        // planCode 留空 = 删除映射：只落 DDD 一行
        ArgumentCaptor<String> planCode = ArgumentCaptor.forClass(String.class);
        verify(mappingMapper).insert(eq("DDD"), planCode.capture(), eq(true), any());
        assertEquals("C_PACER_FU_PLAN", planCode.getValue());
        verify(mappingMapper, never()).insert(eq("CRT_D"), anyString(), any(Boolean.class), any());
        verify(audit).append(any(com.crdp.framework.audit.AuditEntry.class));
        assertEquals(1, view.items().stream().filter(item -> item.planCode() != null).count());
    }

    @Test
    void saveRejectsUnknownOrDuplicateDeviceTypeAndUnknownPlanCode() {
        // 字典外类型
        ApiException unknownType =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.save(
                                        List.of(new MappingInput("XXX", "C_PACER_FU_PLAN", true))));
        assertEquals(40001, unknownType.code());

        // 重复类型
        ApiException duplicate =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.save(
                                        List.of(
                                                new MappingInput("DDD", "C_PACER_FU_PLAN", true),
                                                new MappingInput(
                                                        "DDD", "C_PACER_FU_PLAN", false))));
        assertEquals(40001, duplicate.code());

        // 不存在的计划族
        when(planMapper.findMaxVersionByCode("NO_SUCH_PLAN")).thenReturn(0);
        ApiException unknownPlan =
                assertThrows(
                        ApiException.class,
                        () -> service.save(List.of(new MappingInput("DDD", "NO_SUCH_PLAN", true))));
        assertEquals(40001, unknownPlan.code());

        verify(mappingMapper, never()).deleteAll();
        verify(mappingMapper, never()).insert(anyString(), anyString(), any(Boolean.class), any());
    }

    // ---------------------------------------------------------------- 开关门控（implant.md §2：仅医院 C）

    @Test
    void configEndpointsRequireImplantFeature() {
        when(features.findEnabled(anyString())).thenReturn(null);

        ApiException disabled = assertThrows(ApiException.class, () -> service.mapping());
        assertEquals(40301, disabled.code(), "implant 开关关闭（含无行）→ 40301");
        ApiException saveDisabled =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.save(
                                        List.of(new MappingInput("DDD", "C_PACER_FU_PLAN", true))));
        assertEquals(40301, saveDisabled.code());
        verify(mappingMapper, never()).deleteAll();
    }
}
