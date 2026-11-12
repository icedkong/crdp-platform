package com.crdp.followup.implant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E21-T03 单元测试（tasks.csv 验收「表单按当前器械显示字段」；C-PAT-007、C-FLD-005、C-FU-009）。
 *
 * <p>锁定口径（C 规格 §2「按器械条件显示」表与 §4 C-PAT-007）：
 *
 * <ul>
 *   <li>当前器械 = 状态「在用」的植入记录；随访记录按随访日期匹配当时在用的器械——区间 [implant_date,
 *       end_date)（更换当日旧器械已结束，归新器械），补录旧记录同样按日期匹配；
 *   <li>器械上下文（条件 DSL 的 ctx，共享用例 form-condition-cases.json 锁定的形状）： device.type
 *       起搏器类型编码、device.hasRaLead 有在用 RA 电极（心房参数组）、 device.chambers 在用电极去重腔室数（AV 间期「双腔及以上」用 gte 2）；
 *   <li>废弃留置 / 已拔除电极不计入；沿用电极（同一行挂新记录）计入新器械；
 *   <li>功能开关 implant 关闭（A / B 租户）→ 无器械上下文，查询不出发；
 *   <li>无匹配器械（取出后、植入前）→ 无器械上下文（ctx 断链 → 条件不成立 → 隐藏，OQ-65①）。
 * </ul>
 *
 * <p>全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class DeviceContextServiceTest {

    private static final long PATIENT_ID = 301L;

    private final ImplantGateway gateway = mock(ImplantGateway.class);
    private final ImplantFeatureMapper features = mock(ImplantFeatureMapper.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);

    private final DeviceContextService service = new DeviceContextService(gateway, features, dicts);

    /** 2024-06-01 首植入的 DDD（RA + RV），2026-03-10 升级换为 CRT_D 后状态 REPLACED。 */
    private static final ImplantRow OLD_DDD =
            implant(501L, "DDD", "2024-06-01", "REPLACED", "2026-03-10");

    /** 2026-03-10 升级后的 CRT_D（RA / RV 沿用 + LV 新植），在用。 */
    private static final ImplantRow NEW_CRT_D =
            implant(701L, "CRT_D", "2026-03-10", "ACTIVE", null);

    @BeforeEach
    void dictsAndFeature() {
        lenient().when(features.findEnabled(anyString())).thenReturn(true);
        lenient()
                .when(dicts.items(anyString()))
                .thenAnswer(
                        invocation ->
                                ImplantServiceTest.presetDict(
                                        invocation.getArgument(0, String.class)));
    }

    // ---------------------------------------------------------------- C-PAT-007 当前器械判定

    @Test
    void currentDateNullResolvesActiveImplantAsCurrentDevice() {
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(NEW_CRT_D);
        when(gateway.leadsOfImplant(701L)).thenReturn(leads("RA", "RV", "LV"));

        DeviceContextService.DeviceView view = service.resolve(PATIENT_ID, null);

        assertEquals("701", view.implantId());
        assertEquals("CRT_D", view.type());
        assertEquals("CRT-D", view.typeLabel());
        assertTrue(view.current(), "在用即当前器械");
        assertEquals(LocalDate.of(2026, 3, 10), view.implantDate());
    }

    @Test
    void visitDateBeforeReplacementMatchesOldDevice() {
        // C 规格验收示例：2026-03 更换器械后，2025 年记录仍关联旧器械
        when(gateway.implantAt(PATIENT_ID, LocalDate.of(2025, 6, 15))).thenReturn(OLD_DDD);
        when(gateway.leadsOfImplant(501L)).thenReturn(leads("RA", "RV"));

        DeviceContextService.DeviceView view =
                service.resolve(PATIENT_ID, LocalDate.of(2025, 6, 15));

        assertEquals("501", view.implantId());
        assertEquals("DDD", view.type());
        assertFalse(view.current(), "按日期匹配到的是历史器械，不是当前器械");
        assertTrue(view.hasRaLead());
        assertEquals(2, view.chambers());
    }

    @Test
    void replacementDayBelongsToNewDevice() {
        // 旧器械结束日期 = 新植入日期，区间右开：更换当日归新器械
        when(gateway.implantAt(PATIENT_ID, LocalDate.of(2026, 3, 10))).thenReturn(NEW_CRT_D);
        when(gateway.leadsOfImplant(701L)).thenReturn(leads("RA", "RV", "LV"));

        DeviceContextService.DeviceView view =
                service.resolve(PATIENT_ID, LocalDate.of(2026, 3, 10));

        assertEquals("701", view.implantId(), "更换当日已由新器械在用");
    }

    @Test
    void noMatchingDeviceYieldsNull() {
        // 取出后 / 首次植入前：无在用区间覆盖该日期
        when(gateway.implantAt(PATIENT_ID, LocalDate.of(2024, 1, 1))).thenReturn(null);
        assertNull(service.resolve(PATIENT_ID, LocalDate.of(2024, 1, 1)));

        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        assertNull(service.resolve(PATIENT_ID, null), "取出后无当前器械");
    }

    @Test
    void featureDisabledResolvesNothingWithoutQueries() {
        when(features.findEnabled(anyString())).thenReturn(false);

        assertNull(service.resolve(PATIENT_ID, null));
        assertTrue(service.conditionContext(PATIENT_ID, null).isEmpty());
        verify(gateway, never()).activeImplantOf(anyLong());
        verify(gateway, never()).implantAt(anyLong(), any());
    }

    // ---------------------------------------------------------------- C-FLD-005 / C-FU-009 器械上下文

    @Test
    void conditionContextCarriesDocumentedDeviceKeys() {
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(NEW_CRT_D);
        when(gateway.leadsOfImplant(701L)).thenReturn(leads("RA", "RV", "LV"));

        Map<String, Object> context = service.conditionContext(PATIENT_ID, null);

        assertEquals(1, context.size(), "只带 device 一个根键");
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) context.get("device");
        assertEquals("CRT_D", device.get("type"));
        assertEquals(Boolean.TRUE, device.get("hasRaLead"));
        assertEquals(3, device.get("chambers"), "CRT-D 三腔（RA / RV / LV）");
    }

    @Test
    void singleChamberDeviceHasNoAtrialContext() {
        // C 规格验收：录入 VVI 单腔起搏器患者的程控记录时看不到心房参数
        ImplantRow ssi = implant(601L, "SSI", "2025-09-01", "ACTIVE", null);
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(ssi);
        when(gateway.leadsOfImplant(601L)).thenReturn(List.of(lead("RV", "ACTIVE")));

        DeviceContextService.DeviceView view = service.resolve(PATIENT_ID, null);

        assertFalse(view.hasRaLead(), "单腔只有心室电极");
        assertEquals(1, view.chambers());
        Map<String, Object> context = service.conditionContext(PATIENT_ID, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) context.get("device");
        assertEquals(Boolean.FALSE, device.get("hasRaLead"));
    }

    @Test
    void inactiveLeadsExcludedAndKeptLeadsCountTowardNewDevice() {
        // 沿用 RA（同一行挂新记录）计入；旧记录下废弃留置 / 已拔除的电极不计入
        when(gateway.implantAt(PATIENT_ID, LocalDate.of(2025, 6, 15))).thenReturn(OLD_DDD);
        when(gateway.leadsOfImplant(501L))
                .thenReturn(List.of(lead("RA", "ACTIVE"), lead("RV", "ABANDONED")));

        DeviceContextService.DeviceView view =
                service.resolve(PATIENT_ID, LocalDate.of(2025, 6, 15));

        assertTrue(view.hasRaLead());
        assertEquals(1, view.chambers(), "废弃留置电极不计腔室");
    }

    @Test
    void leadlessDeviceHasNoChambers() {
        ImplantRow leadless = implant(602L, "LEADLESS", "2025-10-01", "ACTIVE", null);
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(leadless);
        when(gateway.leadsOfImplant(602L)).thenReturn(List.of());

        DeviceContextService.DeviceView view = service.resolve(PATIENT_ID, null);

        assertFalse(view.hasRaLead());
        assertEquals(0, view.chambers(), "无导线起搏器不录电极");
    }

    // ---------------------------------------------------------------- 构造

    private static ImplantRow implant(
            long id, String deviceType, String implantDate, String status, String endDate) {
        return new ImplantRow(
                id,
                PATIENT_ID,
                "FIRST",
                LocalDate.parse(implantDate),
                deviceType,
                "MEDTRONIC",
                "示例型号 S-01",
                "SN-" + id,
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                null,
                status,
                endDate == null ? null : LocalDate.parse(endDate),
                null,
                0,
                9L,
                null);
    }

    private static List<LeadRow> leads(String... chambers) {
        return java.util.Arrays.stream(chambers).map(ch -> lead(ch, "ACTIVE")).toList();
    }

    private static LeadRow lead(String chamber, String status) {
        return new LeadRow(90L, PATIENT_ID, chamber, "RA_APPENDAGE", "示例电极", null, status, 0);
    }
}
