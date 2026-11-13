package com.crdp.followup.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.app.DeviceContextService;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.RecordParamMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E21-T14 / C-FU-014：补录时建议随访时间点（距植入日期天数，规则见 hospital-c-spec §5「业务规则与边界情况」）。
 *
 * <p>锁定：天数区间→选项编码（0–7 术后72小时/出院前 … 330–400 术后1年、其后 每6-12个月）；空档区间（8–24 等）无建议； 器械接近 ERI（在组 mode=ERI
 * 且随访日期 ≥ eri_since）优先建议「ERI期随访」；天数按随访日期匹配的当时器械； 越范围患者 40400；无 visit_timepoint 字段的表单（A /
 * B）不建议；区间经租户参数 followup.timepoint.ranges 可配（非法回落缺省）。 全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class TimepointSuggestionServiceTest {

    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final DeviceContextService deviceContext = mock(DeviceContextService.class);
    private final ImplantGateway implantGateway = mock(ImplantGateway.class);
    private final PlanEnrollmentMapper enrollmentMapper = mock(PlanEnrollmentMapper.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);
    private final FormService formService = mock(FormService.class);
    private final RecordParamMapper paramMapper = mock(RecordParamMapper.class);
    private final TimepointParamDefaults paramDefaults = mock(TimepointParamDefaults.class);

    private final TimepointSuggestionService service =
            new TimepointSuggestionService(
                    patientScope,
                    deviceContext,
                    implantGateway,
                    enrollmentMapper,
                    recordMapper,
                    formService,
                    paramMapper,
                    paramDefaults);

    private static final LocalDate IMPLANT = LocalDate.of(2021, 6, 14);

    private static final String FORM = "C_PROGRAM_FU";

    @Test
    void suggestsOneMonthAt31Days() {
        stubPatientWithDevice();

        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), FORM);

        assertEquals(31, view.daysSinceImplant(), "距植入 31 天");
        assertEquals(2, view.suggestedTimepoint(), "25–45 天建议「术后1个月」（选项 2）");
        assertEquals("术后1个月", view.suggestedLabel());
        assertEquals("双腔(DDD)", view.device().typeLabel(), "自动关联当时器械（C-FU-014）");
        assertEquals(false, view.eriFollowup());
    }

    @Test
    void dayRangeBoundariesFollowSpec() {
        stubPatientWithDevice();
        assertEquals(1, service.suggest(301L, IMPLANT, FORM).suggestedTimepoint(), "0 天 → 术后72小时");
        assertEquals(
                1, service.suggest(301L, IMPLANT.plusDays(7), FORM).suggestedTimepoint(), "7 天边界");
        assertNull(
                service.suggest(301L, IMPLANT.plusDays(8), FORM).suggestedTimepoint(),
                "8–24 天为空档，无建议（用户自选）");
        assertEquals(2, service.suggest(301L, IMPLANT.plusDays(45), FORM).suggestedTimepoint());
        assertNull(service.suggest(301L, IMPLANT.plusDays(46), FORM).suggestedTimepoint());
        assertEquals(3, service.suggest(301L, IMPLANT.plusDays(110), FORM).suggestedTimepoint());
        assertEquals(4, service.suggest(301L, IMPLANT.plusDays(215), FORM).suggestedTimepoint());
        assertNull(service.suggest(301L, IMPLANT.plusDays(216), FORM).suggestedTimepoint());
        assertEquals(5, service.suggest(301L, IMPLANT.plusDays(330), FORM).suggestedTimepoint());
        assertEquals(5, service.suggest(301L, IMPLANT.plusDays(400), FORM).suggestedTimepoint());
        assertEquals(
                6,
                service.suggest(301L, IMPLANT.plusDays(401), FORM).suggestedTimepoint(),
                "其后（> 400 天）→ 每6-12个月");
        assertEquals(6, service.suggest(301L, IMPLANT.plusDays(3000), FORM).suggestedTimepoint());
    }

    @Test
    void eriFollowupWinsWhenEnrollmentInEriModeAtVisitDate() {
        stubPatientWithDevice();
        LocalDate visit = IMPLANT.plusDays(31);
        when(enrollmentMapper.findEriMode(301L))
                .thenReturn(new PlanEnrollmentMapper.EriModeRow(91L, "ERI", IMPLANT.plusDays(20)));

        TimepointSuggestionService.SuggestionView view = service.suggest(301L, visit, FORM);

        assertTrue(view.eriFollowup(), "在组 ERI 且随访日期 ≥ eri_since");
        assertEquals(7, view.suggestedTimepoint(), "器械接近 ERI 时建议「ERI期随访」（优先于天数区间）");
        assertEquals("ERI期随访", view.suggestedLabel());

        // eri_since 晚于随访日期：当时还不是 ERI 期，回落天数区间
        when(enrollmentMapper.findEriMode(301L))
                .thenReturn(new PlanEnrollmentMapper.EriModeRow(91L, "ERI", visit.plusDays(1)));
        assertEquals(
                2, service.suggest(301L, visit, FORM).suggestedTimepoint(), "ERI 起算晚于随访日期 → 按天数建议");

        when(enrollmentMapper.findEriMode(301L)).thenReturn(null);
        assertEquals(
                2, service.suggest(301L, visit, FORM).suggestedTimepoint(), "无 ERI 入组 → 按天数建议");
    }

    @Test
    void noDeviceYieldsNoDayBasedSuggestion() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(deviceContext.resolve(301L, IMPLANT.plusDays(31))).thenReturn(null);
        when(implantGateway.implantsOf(301L)).thenReturn(List.of());
        stubTimepointField();

        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), FORM);

        assertNull(view.device(), "无匹配器械（implant 关闭 / 取出后 / 首次植入前）");
        assertNull(view.daysSinceImplant());
        assertNull(view.suggestedTimepoint(), "无器械无从算天数，不建议");
        assertNull(view.earliestImplantDate());
    }

    @Test
    void outOfScopePatientAndMissingDateAreRejected() {
        when(patientScope.patientIdInScope(301L)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () -> service.suggest(301L, IMPLANT.plusDays(31), FORM))
                        .code(),
                "越范围患者不暴露存在");
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.suggest(301L, null, FORM)).code(),
                "visitDate 必填");
    }

    @Test
    void formWithoutTimepointFieldHasNoSuggestion() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(deviceContext.resolve(eq(301L), any(LocalDate.class))).thenReturn(device());
        when(implantGateway.implantsOf(anyLong())).thenReturn(List.of(implant(IMPLANT)));
        when(formService.schema(eq(FORM), any()))
                .thenReturn(
                        new FormService.SchemaView(
                                FORM,
                                "随访（示例，无时间点字段）",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "overview",
                                                "随访概况",
                                                1,
                                                List.of(
                                                        new FormService.FieldView(
                                                                "f303",
                                                                "followup_date",
                                                                "随访日期",
                                                                "date",
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                List.of(),
                                                                true,
                                                                null,
                                                                null,
                                                                null,
                                                                false,
                                                                "json",
                                                                null)))),
                                List.of()));

        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), FORM);

        assertEquals(31, view.daysSinceImplant(), "器械与天数照常下发（表头展示）");
        assertNull(view.suggestedTimepoint(), "表单无 visit_timepoint 字段（A / B）→ 无建议");
        assertNull(view.suggestedLabel());
    }

    @Test
    void tenantOverrideOfRangesWinsAndInvalidFallsBack() {
        stubPatientWithDevice();
        // 租户覆盖：20–60 天都算「术后1个月」
        when(paramMapper.findOverride(TimepointSuggestionService.PARAM_RANGES))
                .thenReturn("{\"ranges\":[{\"code\":2,\"min\":20,\"max\":60}]}");
        assertEquals(2, service.suggest(301L, IMPLANT.plusDays(50), FORM).suggestedTimepoint());

        // 非法 JSON 回落缺省区间
        when(paramMapper.findOverride(TimepointSuggestionService.PARAM_RANGES))
                .thenReturn("not-json");
        assertNull(
                service.suggest(301L, IMPLANT.plusDays(50), FORM).suggestedTimepoint(),
                "50 天在缺省区间是空档");
        assertEquals(2, service.suggest(301L, IMPLANT.plusDays(31), FORM).suggestedTimepoint());
    }

    @Test
    void platformDefaultUsedWhenTenantNotOverriding() {
        stubPatientWithDevice();
        when(paramDefaults.param(TimepointSuggestionService.PARAM_RANGES))
                .thenReturn(
                        Optional.of(
                                new TimepointParamDefaults.ParamDefault(
                                        TimepointSuggestionService.PARAM_RANGES,
                                        "{\"ranges\":[{\"code\":2,\"min\":20,\"max\":60}]}",
                                        "TEXT")));
        assertEquals(2, service.suggest(301L, IMPLANT.plusDays(50), FORM).suggestedTimepoint());
    }

    @Test
    void earliestImplantDateComesFromAllImplants() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        // 当时器械是 2021-06-14 那条，但更早还有 2020 年的一条（更换史）：补录日期下界取最早
        when(deviceContext.resolve(301L, IMPLANT.plusDays(31))).thenReturn(device());
        when(implantGateway.implantsOf(301L))
                .thenReturn(List.of(implant(IMPLANT), implant(LocalDate.of(2020, 1, 1))));
        stubTimepointField();

        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), FORM);

        assertEquals(LocalDate.of(2020, 1, 1), view.earliestImplantDate());
        assertEquals(IMPLANT, view.device().implantDate(), "天数按当时器械（不是最早植入）");
    }

    @Test
    void formCodeFallsBackToEnrollmentFormNode() {
        stubPatientWithDevice();
        when(recordMapper.findEnrollmentFormCode(301L)).thenReturn(FORM);

        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), null);

        assertEquals(2, view.suggestedTimepoint(), "未显式指定表单时取在组计划 FORM 节点表单");
    }

    // ---------------------------------------------------------------- 补录创建带入（C-FU-013/014）

    @Test
    void initialDataSeedsTimepointFieldKeyWithSuggestedCode() {
        stubPatientWithDevice();
        TimepointSuggestionService.SuggestionView view =
                service.suggest(301L, IMPLANT.plusDays(31), FORM);

        Map<String, Object> seed = service.initialData(FORM, view);

        assertEquals(Map.of("f304", 2), seed, "按模板字段编码 visit_timepoint 定位稳定键（不硬编码键名）");
        // 无建议 / 无字段：空 seed，不动表单
        assertTrue(service.initialData(FORM, noSuggestion()).isEmpty());
    }

    private TimepointSuggestionService.SuggestionView noSuggestion() {
        return new TimepointSuggestionService.SuggestionView(
                "301", IMPLANT.plusDays(8), null, null, null, false, null, null);
    }

    // ---------------------------------------------------------------- 造数

    private void stubPatientWithDevice() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(deviceContext.resolve(eq(301L), any(LocalDate.class))).thenReturn(device());
        when(implantGateway.implantsOf(anyLong())).thenReturn(List.of(implant(IMPLANT)));
        stubTimepointField();
    }

    private static DeviceContextService.DeviceView device() {
        return new DeviceContextService.DeviceView(
                "501", "DDD", "双腔(DDD)", IMPLANT, true, 2, false);
    }

    private static ImplantRow implant(LocalDate date) {
        return new ImplantRow(
                501L,
                301L,
                "FIRST",
                date,
                "DDD",
                "MEDTRONIC",
                "示例型号 S-01",
                "SN-示例",
                "DDD",
                "虚构示例医院",
                "示例医生",
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                0,
                1L,
                1L);
    }

    /** 模板带随访时间点字段（编码 visit_timepoint → 稳定键 f304，选项集 c_visit_timepoint V90）。 */
    private void stubTimepointField() {
        when(formService.schema(eq(FORM), any()))
                .thenReturn(
                        new FormService.SchemaView(
                                FORM,
                                "随访程控记录（医院 C）",
                                "FOLLOWUP",
                                1,
                                List.of(
                                        new FormService.CategoryView(
                                                "overview",
                                                "随访概况",
                                                1,
                                                List.of(
                                                        new FormService.FieldView(
                                                                "f304",
                                                                "visit_timepoint",
                                                                "随访时间点",
                                                                "select",
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                List.of(
                                                                        timepointOption(
                                                                                1, "术后72小时/出院前"),
                                                                        timepointOption(2, "术后1个月"),
                                                                        timepointOption(3, "术后3个月"),
                                                                        timepointOption(4, "术后6个月"),
                                                                        timepointOption(5, "术后1年"),
                                                                        timepointOption(
                                                                                6, "每6-12个月"),
                                                                        timepointOption(
                                                                                7, "ERI期随访"),
                                                                        timepointOption(8, "其他")),
                                                                true,
                                                                null,
                                                                null,
                                                                null,
                                                                false,
                                                                "json",
                                                                null)))),
                                List.of()));
    }

    private static FormService.OptionItemView timepointOption(int code, String label) {
        return new FormService.OptionItemView(code, label, code * 10, false, false, false);
    }
}
