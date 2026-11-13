package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardStatsMapper;
import com.crdp.followup.stats.app.AbnormalRecordsService.AbnormalItemView;
import com.crdp.followup.stats.app.AbnormalRecordsService.AbnormalRecordsView;
import com.crdp.followup.stats.app.AbnormalRecordsService.HighlightHitView;
import com.crdp.followup.stats.infra.mapper.AbnormalRecordsMapper;
import com.crdp.followup.stats.infra.mapper.AbnormalRecordsMapper.HighlightRecordRow;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E21-T19 / C-DASH-006 异常标红记录卡片单测（验收「近 N 天（默认 30 天）提交的含标红参数的随访记录，显示标红项目与变化率，点击进入记录；只做查看， 不需处理」）：
 * 窗口口径（北京业务日 N 天、days 参数与指标卡 30 天同条件树）、命中数组解析（标红项目与变化率）、姓名脱敏、开关 40301、参数清洗。 全部虚构数据。
 */
class AbnormalRecordsServiceTest {

    private final AbnormalRecordsMapper mapper = mock(AbnormalRecordsMapper.class);
    private final DashboardStatsMapper stats = mock(DashboardStatsMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final TenantViewFieldGuard viewGuard = mock(TenantViewFieldGuard.class);
    private final AbnormalRecordsService service =
            new AbnormalRecordsService(mapper, stats, features, crypto, viewGuard);

    {
        lenient().when(features.findEnabled("implant")).thenReturn(true);
        lenient().when(crypto.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(viewGuard.apply(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    void defaultWindowIsLast30BusinessDaysAndCountsShareCaliberWithKpi() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDateTime since = today.minusDays(29L).atStartOfDay();
        when(stats.countRecentHighlightRecords(eq(since), any())).thenReturn(3L);
        when(mapper.findRecentHighlights(eq(since), any(), anyInt())).thenReturn(List.of());

        AbnormalRecordsView view = service.abnormalRecords(null, null);

        assertEquals(AbnormalRecordsService.DEFAULT_DAYS, view.days());
        assertEquals(today.minusDays(29L), view.since());
        assertEquals(3L, view.total(), "total 与指标卡「近 30 天含标红参数的记录数」同一条件树");
        assertEquals(AbnormalRecordsService.ITEM_LIMIT, view.limit());
        // days 缺省 30 → 窗口起点与指标卡 HIGHLIGHT_WINDOW_DAYS 一致（OQ-107④ 同近似列）
        verify(stats).countRecentHighlightRecords(eq(since), any());
        verify(mapper).findRecentHighlights(eq(since), any(), anyInt());
    }

    @Test
    void daysParameterWidensOrShrinksWindow() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(stats.countRecentHighlightRecords(any(), any())).thenReturn(0L);
        when(mapper.findRecentHighlights(any(), any(), anyInt())).thenReturn(List.of());

        service.abnormalRecords(null, 7);

        LocalDateTime since = today.minusDays(6L).atStartOfDay();
        verify(stats).countRecentHighlightRecords(eq(since), any());
        verify(mapper).findRecentHighlights(eq(since), any(), anyInt());
    }

    @Test
    void parsesHitsWithChangeAndMasksName() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDateTime since = today.minusDays(29L).atStartOfDay();
        when(stats.countRecentHighlightRecords(eq(since), any())).thenReturn(1L);
        when(mapper.findRecentHighlights(eq(since), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new HighlightRecordRow(
                                        551L,
                                        61L,
                                        "cipher-王五",
                                        "C-2026-0061",
                                        LocalDate.of(2026, 11, 2),
                                        LocalDateTime.of(2026, 11, 3, 9, 30),
                                        "[{\"ruleId\":\"ef_drop\",\"key\":\"f201\","
                                                + "\"categoryKey\":\"echo\",\"message\":\"射血分数下降\","
                                                + "\"previousValue\":56,\"change\":-11,"
                                                + "\"changeText\":\"下降 11 个百分点\"}]")));
        when(crypto.decrypt("cipher-王五")).thenReturn("王五");

        AbnormalRecordsView view = service.abnormalRecords(null, null);

        AbnormalItemView item = view.items().getFirst();
        assertEquals("551", item.recordId(), "recordId 字符串化（点击进入记录的定位键）");
        assertEquals("61", item.patientId());
        assertEquals("王*", item.name(), "姓名脱敏（两字保留首字，列表不提供逐行明文）");
        assertEquals(LocalDate.of(2026, 11, 2), item.visitDate());
        assertEquals(
                List.of(new HighlightHitView("f201", "射血分数下降", 56, -11, "下降 11 个百分点")),
                item.hits(),
                "标红项目（key）与变化率（change / changeText）来自 E17-T04 命中数组");
    }

    @Test
    void dirtyHighlightJsonDegradesToEmptyHits() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDateTime since = today.minusDays(29L).atStartOfDay();
        when(stats.countRecentHighlightRecords(eq(since), any())).thenReturn(1L);
        when(mapper.findRecentHighlights(eq(since), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new HighlightRecordRow(
                                        552L,
                                        62L,
                                        null,
                                        null,
                                        null,
                                        LocalDateTime.of(2026, 11, 3, 9, 30),
                                        "not-json")));

        AbnormalRecordsView view = service.abnormalRecords(null, null);

        assertEquals(List.of(), view.items().getFirst().hits(), "脏数据不阻断卡片");
    }

    @Test
    void featureOffReturns40301() {
        when(features.findEnabled("implant")).thenReturn(false);

        ApiException off =
                assertThrows(ApiException.class, () -> service.abnormalRecords(null, null));
        assertEquals(40301, off.code());

        when(features.findEnabled("implant")).thenReturn(null);
        ApiException noRow =
                assertThrows(ApiException.class, () -> service.abnormalRecords(null, null));
        assertEquals(40301, noRow.code(), "无行视为默认关闭");
        verify(mapper, never()).findRecentHighlights(any(), any(), anyInt());
    }

    @Test
    void invalidParametersRejected() {
        ApiException zeroDays =
                assertThrows(ApiException.class, () -> service.abnormalRecords(null, 0));
        assertEquals(40001, zeroDays.code());
        ApiException tooLarge =
                assertThrows(ApiException.class, () -> service.abnormalRecords(null, 366));
        assertEquals(40001, tooLarge.code());
        ApiException zeroWard =
                assertThrows(ApiException.class, () -> service.abnormalRecords(0L, null));
        assertEquals(40001, zeroWard.code());
    }

    @Test
    void wardIdPassedThroughToCountAndList() {
        when(stats.countRecentHighlightRecords(any(), eq(7L))).thenReturn(0L);
        when(mapper.findRecentHighlights(any(), eq(7L), anyInt())).thenReturn(List.of());

        service.abnormalRecords(7L, null);

        verify(stats).countRecentHighlightRecords(any(), eq(7L));
        verify(mapper).findRecentHighlights(any(), eq(7L), anyInt());
    }
}
