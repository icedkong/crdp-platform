package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.plan.domain.EriEval;
import com.crdp.followup.stats.app.EriListService.EriItemView;
import com.crdp.followup.stats.app.EriListService.EriListView;
import com.crdp.followup.stats.infra.mapper.EriListMapper;
import com.crdp.followup.stats.infra.mapper.EriListMapper.LatestEriRecordRow;
import com.crdp.followup.stats.infra.mapper.EriListMapper.PatientDeviceRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E21-T19 / C-DASH-005 接近 ERI 器械列表卡片单测（验收「列出最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命低于阈值的患者，含器械、
 * 最近随访日期、下次计划日期」）： 命中判定与切换 SW-7 / SW-8 共享 EriEval、器械与摘要装配、姓名脱敏、排序与行数上限、开关 40301、参数清洗。 全部虚构数据。
 *
 * <p>数据范围由 Mapper 语句上的 {@code @DataScope} 出行（本测 mock Mapper，与 DashboardKpiServiceTest 同口径）； 「最近一条 /
 * 随访中收窄」在 SQL（窗口函数 + followup_status 条件），本测锁定服务层装配。
 */
class EriListServiceTest {

    private final EriListMapper mapper = mock(EriListMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final TenantViewFieldGuard viewGuard = mock(TenantViewFieldGuard.class);
    private final EriListService service =
            new EriListService(mapper, features, dicts, crypto, viewGuard);

    {
        lenient().when(features.findEnabled("eri_cycle")).thenReturn(true);
        // 字典（含停用项照常显示，C-FLD-009）：类型两项、品牌两项，按 sortNo 序
        lenient()
                .when(dicts.items(ImplantDictPort.DICT_DEVICE_TYPE))
                .thenReturn(
                        List.of(
                                new ImplantDictPort.DictItem("SSI", "单腔", 10, true),
                                new ImplantDictPort.DictItem("DDD", "双腔", 20, true),
                                new ImplantDictPort.DictItem("ICD", "ICD", 30, false)));
        lenient()
                .when(dicts.items(ImplantDictPort.DICT_BRAND))
                .thenReturn(
                        List.of(
                                new ImplantDictPort.DictItem("MED", "示例品牌 M（示例）", 10, true),
                                new ImplantDictPort.DictItem("BIO", "示例品牌 B（示例）", 20, true)));
        // 脱敏链：解密 → 字段目录掩码 → 视图守卫直通
        lenient().when(crypto.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(viewGuard.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    }

    private static LatestEriRecordRow record(
            long patientId, long recordId, LocalDate visitDate, String dataJson) {
        return new LatestEriRecordRow(patientId, recordId, dataJson, visitDate);
    }

    @Test
    void marksNearEoiAndLowBatteryButNotNormal() {
        when(mapper.findLatestEriRecords(null))
                .thenReturn(
                        List.of(
                                // 接近 ERI（f323=1）
                                record(11L, 101L, LocalDate.of(2026, 11, 2), "{\"f323\":\"1\"}"),
                                // 已达 ERI(EOI)（f323=2）
                                record(12L, 102L, LocalDate.of(2026, 11, 5), "{\"f323\":\"2\"}"),
                                // 电池寿命 ≤ 1 年（数字形态）
                                record(
                                        13L,
                                        103L,
                                        LocalDate.of(2026, 11, 8),
                                        "{\"f323\":\"0\",\"f324\":0.6}"),
                                // 正常且寿命不低 → 不进列表
                                record(
                                        14L,
                                        104L,
                                        LocalDate.of(2026, 11, 9),
                                        "{\"f323\":\"0\",\"f324\":3.5}"),
                                // 两值皆缺 → 不进列表
                                record(15L, 105L, LocalDate.of(2026, 11, 9), "{\"f301\":1}")));
        when(mapper.findPatientDevices(any())).thenReturn(List.of());

        EriListView view = service.eriList(null);

        assertEquals(3L, view.total(), "接近 / 已达 / 电池寿命低三种命中，正常与缺值不计");
        assertEquals(1d, view.batteryLifeThreshold());
        // 命中行按证据随访日期倒序（最新在前）
        assertEquals(
                List.of("13", "12", "11"),
                view.items().stream().map(EriItemView::patientId).toList());
        EriItemView battery = view.items().get(0);
        // 电池寿命命中（f324=0.6 ≤ 1）但 ERI 状态本身为正常——状态列照实显示 0 / 正常
        assertEquals("0", battery.eriStatusCode());
        assertEquals(0.6d, battery.batteryLife());
        assertEquals("已达ERI(EOI)", view.items().get(1).eriStatusName());
        assertEquals("接近ERI", view.items().get(2).eriStatusName());
    }

    @Test
    void assemblesDeviceSummaryAndMaskedName() {
        when(mapper.findLatestEriRecords(null))
                .thenReturn(
                        List.of(record(21L, 201L, LocalDate.of(2026, 10, 30), "{\"f323\":\"1\"}")));
        when(mapper.findPatientDevices(List.of(21L)))
                .thenReturn(
                        List.of(
                                new PatientDeviceRow(
                                        21L,
                                        "cipher-张三丰",
                                        "C-2026-0001",
                                        LocalDate.of(2026, 10, 30),
                                        LocalDate.of(2026, 12, 1),
                                        "DDD",
                                        "MED",
                                        "示例型号 DDD-M（示例）")));
        when(crypto.decrypt("cipher-张三丰")).thenReturn("张三丰");

        EriListView view = service.eriList(null);

        EriItemView item = view.items().getFirst();
        // 姓名脱敏（张*丰）：列表不提供逐行明文（AGENTS §5.3）
        assertEquals("张*丰", item.name());
        assertEquals("C-2026-0001", item.visitNo());
        assertEquals("DDD", item.deviceType());
        assertEquals("双腔", item.deviceTypeLabel());
        assertEquals("MED", item.brand());
        assertEquals("示例品牌 M（示例）", item.brandLabel());
        assertEquals("示例型号 DDD-M（示例）", item.model());
        assertEquals(LocalDate.of(2026, 10, 30), item.lastVisitDate());
        assertEquals(LocalDate.of(2026, 12, 1), item.nextPlanDate());
    }

    @Test
    void patientWithoutDeviceRowFallsBackToPlaceholders() {
        when(mapper.findLatestEriRecords(null))
                .thenReturn(
                        List.of(record(31L, 301L, LocalDate.of(2026, 9, 1), "{\"f324\":\"0.9\"}")));
        // 摘要行存在但无在用器械（器械列 NULL）；最近随访日期缺失回落证据随访日期
        when(mapper.findPatientDevices(List.of(31L)))
                .thenReturn(
                        List.of(
                                new PatientDeviceRow(
                                        31L,
                                        "cipher-李四",
                                        "C-2026-0002",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));

        EriListView view = service.eriList(null);

        EriItemView item = view.items().getFirst();
        assertEquals(EriListService.NO_DEVICE_LABEL, item.deviceTypeLabel());
        assertNull(item.model());
        assertEquals(LocalDate.of(2026, 9, 1), item.lastVisitDate(), "摘要缺失回落证据随访日期");
        assertNull(item.nextPlanDate());
        // 电池寿命字符串形态可判定（f324="0.9" ≤ 1）
        assertEquals(0.9d, item.batteryLife());
    }

    @Test
    void itemsCappedAtLimitWhileTotalKeepsFullCount() {
        List<LatestEriRecordRow> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= EriListService.ITEM_LIMIT + 5; i++) {
            rows.add(record(1000L + i, 9000L + i, LocalDate.of(2026, 10, 1), "{\"f323\":\"1\"}"));
        }
        when(mapper.findLatestEriRecords(null)).thenReturn(List.copyOf(rows));
        when(mapper.findPatientDevices(any())).thenReturn(List.of());

        EriListView view = service.eriList(null);

        assertEquals(EriListService.ITEM_LIMIT + 5L, view.total());
        assertEquals(EriListService.ITEM_LIMIT, view.items().size());
        // 只装配前 limit 行的器械 / 摘要信息
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();
        verify(mapper).findPatientDevices(ids.capture());
        assertEquals(EriListService.ITEM_LIMIT, ids.getValue().size());
    }

    @Test
    void nearEriCountSharesQueryWithCardTotal() {
        when(mapper.findLatestEriRecords(null))
                .thenReturn(
                        List.of(
                                record(41L, 401L, LocalDate.of(2026, 11, 1), "{\"f323\":\"2\"}"),
                                record(42L, 402L, LocalDate.of(2026, 11, 2), "{\"f323\":\"0\"}")));

        assertEquals(1L, service.nearEriCount(null), "指标卡「接近 ERI 器械数」与本卡 total 同一查询");
        verify(mapper, never()).findPatientDevices(any());
    }

    @Test
    void featureOffReturns40301() {
        when(features.findEnabled("eri_cycle")).thenReturn(false);

        ApiException off = assertThrows(ApiException.class, () -> service.eriList(null));
        assertEquals(40301, off.code());

        when(features.findEnabled("eri_cycle")).thenReturn(null);
        ApiException noRow = assertThrows(ApiException.class, () -> service.eriList(null));
        assertEquals(40301, noRow.code(), "无行视为默认关闭");
        verify(mapper, never()).findLatestEriRecords(any());
    }

    @Test
    void wardIdPassedThroughAndInvalidRejected() {
        when(mapper.findLatestEriRecords(7L)).thenReturn(List.of());

        service.eriList(7L);
        verify(mapper).findLatestEriRecords(7L);

        ApiException zero = assertThrows(ApiException.class, () -> service.eriList(0L));
        assertEquals(40001, zero.code());
        ApiException negative = assertThrows(ApiException.class, () -> service.eriList(-3L));
        assertEquals(40001, negative.code());
    }

    @Test
    void dirtyRecordDataIgnoredDefensively() {
        when(mapper.findLatestEriRecords(null))
                .thenReturn(List.of(record(51L, 501L, LocalDate.of(2026, 11, 1), "not-json")));
        when(mapper.findPatientDevices(any())).thenReturn(List.of());

        EriListView view = service.eriList(null);

        assertEquals(0L, view.total(), "脏数据不阻断卡片（值缺失不命中）");
    }

    @Test
    void eriStatusNameMapsDictCodes() {
        assertEquals("接近ERI", EriListService.eriStatusName(EriEval.STATUS_NEAR));
        assertEquals("已达ERI(EOI)", EriListService.eriStatusName(EriEval.STATUS_EOI));
        assertEquals("正常", EriListService.eriStatusName(EriEval.STATUS_NORMAL));
        assertNull(EriListService.eriStatusName(null));
    }
}
