package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.stats.app.DeviceDistService.BrandDistView;
import com.crdp.followup.stats.app.DeviceDistService.DeviceDistView;
import com.crdp.followup.stats.app.DeviceDistService.TypeDistView;
import com.crdp.followup.stats.infra.mapper.DeviceDistMapper;
import com.crdp.followup.stats.infra.mapper.DeviceDistMapper.DistCountRow;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E21-T19 / C-DASH-002 器械类型分布卡片单测（验收「按起搏器类型统计在随访患者数，可按品牌拆分」）： 类型 / 品牌两级聚合、字典行序与占位行、占比口径、 开关
 * 40301、参数清洗。全部虚构数据。
 *
 * <p>「在随访患者」的口径与指标卡同一条件树（mock Mapper；分母 = 聚合行合计），范围改写不在单测范围。
 */
class DeviceDistServiceTest {

    private final DeviceDistMapper mapper = mock(DeviceDistMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);
    private final DeviceDistService service = new DeviceDistService(mapper, features, dicts);

    {
        lenient().when(features.findEnabled("implant")).thenReturn(true);
        // 字典序（含停用项）：类型 SSI(10) → DDD(20) → ICD(30)；品牌 MED(10) → BIO(20)
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
    }

    @Test
    void groupsByTypeWithBrandBreakdownInDictOrder() {
        when(mapper.countByTypeAndBrand(null))
                .thenReturn(
                        List.of(
                                new DistCountRow("DDD", "MED", 3L),
                                new DistCountRow("SSI", null, 2L),
                                new DistCountRow("DDD", "BIO", 2L),
                                new DistCountRow(null, null, 1L)));

        DeviceDistView view = service.deviceDist(null);

        // 分母 = 在随访患者数 = 聚合行合计（8）；行按字典 sortNo 序（SSI → DDD）、「未登记在用器械」最后
        assertEquals(8L, view.followedCount());
        assertEquals(
                java.util.Arrays.asList("SSI", "DDD", null),
                view.rows().stream().map(TypeDistView::deviceType).toList());
        TypeDistView ssi = view.rows().get(0);
        assertEquals("单腔", ssi.label());
        assertEquals(2L, ssi.count());
        assertEquals(25, ssi.pct(), "2/8 = 25%");
        // 未填品牌归占位行（字典序之后）
        assertEquals(List.of(new BrandDistView(null, "未填品牌", 2L)), ssi.brands());
        TypeDistView ddd = view.rows().get(1);
        assertEquals(5L, ddd.count());
        assertEquals(63, ddd.pct(), "5/8 = 62.5 → 63");
        assertEquals(
                List.of(
                        new BrandDistView("MED", "示例品牌 M（示例）", 3L),
                        new BrandDistView("BIO", "示例品牌 B（示例）", 2L)),
                ddd.brands(),
                "品牌拆分按字典序（可按品牌拆分，C-DASH-002）");
        TypeDistView none = view.rows().get(2);
        assertEquals(DeviceDistService.NO_DEVICE_LABEL, none.label());
        assertEquals(1L, none.count());
    }

    @Test
    void unknownTypeCodeKeptAfterDictItemsWithCodeAsLabel() {
        when(mapper.countByTypeAndBrand(null))
                .thenReturn(
                        List.of(
                                new DistCountRow("XYZ", "MED", 1L),
                                new DistCountRow("DDD", "MED", 2L)));

        DeviceDistView view = service.deviceDist(null);

        // 字典外编码接在字典项之后（历史值照常显示，编码即显示名兜底）
        assertEquals(
                List.of("DDD", "XYZ"), view.rows().stream().map(TypeDistView::deviceType).toList());
        assertEquals("XYZ", view.rows().get(1).label());
    }

    @Test
    void emptyScopeReturnsZeroDenominatorWithoutError() {
        when(mapper.countByTypeAndBrand(null)).thenReturn(List.of());

        DeviceDistView view = service.deviceDist(null);

        assertEquals(0L, view.followedCount());
        assertEquals(List.of(), view.rows());
    }

    @Test
    void featureOffReturns40301() {
        when(features.findEnabled("implant")).thenReturn(false);

        ApiException off = assertThrows(ApiException.class, () -> service.deviceDist(null));
        assertEquals(40301, off.code());

        when(features.findEnabled("implant")).thenReturn(null);
        ApiException noRow = assertThrows(ApiException.class, () -> service.deviceDist(null));
        assertEquals(40301, noRow.code(), "无行视为默认关闭");
        verify(mapper, never()).countByTypeAndBrand(any());
    }

    @Test
    void wardIdPassedThroughAndInvalidRejected() {
        when(mapper.countByTypeAndBrand(7L)).thenReturn(List.of());

        service.deviceDist(7L);
        verify(mapper).countByTypeAndBrand(7L);

        ApiException zero = assertThrows(ApiException.class, () -> service.deviceDist(0L));
        assertEquals(40001, zero.code());
    }
}
