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
import com.crdp.followup.stats.app.ScanProgressService.ScanProgressView;
import com.crdp.followup.stats.app.ScanProgressService.UserOptionView;
import com.crdp.followup.stats.infra.mapper.ScanProgressMapper;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E21-T15 / C-DASH-007 补录进度卡片单测（验收「待录入与已电子化扫描件的数量与比例、可按科室病区 / 上传人 / 文档年份查看；
 * 另显示补录随访记录条数」）：计数装配、比例口径（无需录入不进分母）、筛选透传、开关 40301、参数清洗。全部虚构数据。
 *
 * <p>数据范围由 Mapper 语句上的 {@code @DataScope} 出行（本测 mock Mapper，范围改写不在单测范围—— 与 DashboardKpiServiceTest
 * 同口径）。
 */
class ScanProgressServiceTest {

    private final ScanProgressMapper mapper = mock(ScanProgressMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final ScanProgressService service = new ScanProgressService(mapper, features);

    {
        lenient().when(features.findEnabled("scan_progress")).thenReturn(true);
    }

    @Test
    void countsPendingDoneNoEntryWithRatioExcludingNoEntry() {
        when(mapper.countByStatus(null, null, null))
                .thenReturn(
                        List.of(
                                new ScanProgressMapper.StatusCountRow("PENDING", 40L),
                                new ScanProgressMapper.StatusCountRow("DONE", 60L),
                                new ScanProgressMapper.StatusCountRow("NO_ENTRY", 25L)));
        when(mapper.countBackfillRecords(null)).thenReturn(7L);
        lenient().when(mapper.uploaderOptions(null)).thenReturn(List.of());
        lenient().when(mapper.yearOptions(null)).thenReturn(List.of());

        ScanProgressView view = service.scanProgress(null, null, null);

        assertEquals(40L, view.pendingCount());
        assertEquals(60L, view.doneCount());
        assertEquals(25L, view.noEntryCount());
        // 比例 = 已电子化 ÷（待录入 + 已电子化）= 60/100 = 60%；「无需录入」25 份不进分母
        assertEquals(60, view.donePct());
        assertEquals(7L, view.backfillRecordCount());
    }

    @Test
    void emptyScopeReturnsAllZerosWithoutError() {
        when(mapper.countByStatus(null, null, null)).thenReturn(List.of());
        when(mapper.countBackfillRecords(null)).thenReturn(0L);
        lenient().when(mapper.uploaderOptions(null)).thenReturn(List.of());
        lenient().when(mapper.yearOptions(null)).thenReturn(List.of());

        ScanProgressView view = service.scanProgress(null, null, null);

        assertEquals(0L, view.pendingCount());
        assertEquals(0L, view.doneCount());
        assertEquals(0, view.donePct(), "分母为 0 → 0（不报错，stats.md §1）");
        assertEquals(0L, view.backfillRecordCount());
    }

    @Test
    void unknownStatusValuesIgnoredDefensively() {
        when(mapper.countByStatus(null, null, null))
                .thenReturn(List.of(new ScanProgressMapper.StatusCountRow("WEIRD", 3L)));
        when(mapper.countBackfillRecords(null)).thenReturn(0L);
        lenient().when(mapper.uploaderOptions(null)).thenReturn(List.of());
        lenient().when(mapper.yearOptions(null)).thenReturn(List.of());

        ScanProgressView view = service.scanProgress(null, null, null);

        assertEquals(0L, view.pendingCount() + view.doneCount() + view.noEntryCount());
    }

    @Test
    void filtersPassedThroughToStatusCountAndCandidatesFollowWardOnly() {
        when(mapper.countByStatus(5L, 9L, 2026)).thenReturn(List.of());
        when(mapper.countBackfillRecords(5L)).thenReturn(2L);
        when(mapper.uploaderOptions(5L))
                .thenReturn(List.of(new ScanProgressMapper.UserOptionRow(9L, "王医生（示例）")));
        when(mapper.yearOptions(5L)).thenReturn(List.of(2026, 2025));

        ScanProgressView view = service.scanProgress(5L, 9L, 2026);

        verify(mapper).countByStatus(5L, 9L, 2026);
        // 补录记录条数只收病区筛选（上传人 / 年份是扫描件侧筛选，记录无对应语义）
        verify(mapper).countBackfillRecords(5L);
        // 候选不叠加上传人 / 年份自身，id 序列化为字符串
        assertEquals(List.of(new UserOptionView("9", "王医生（示例）")), view.uploaders());
        assertEquals(List.of(2026, 2025), view.years());
    }

    @Test
    void featureOffReturns40301() {
        when(features.findEnabled("scan_progress")).thenReturn(false);

        ApiException off =
                assertThrows(ApiException.class, () -> service.scanProgress(null, null, null));
        assertEquals(40301, off.code());

        when(features.findEnabled("scan_progress")).thenReturn(null);
        ApiException noRow =
                assertThrows(ApiException.class, () -> service.scanProgress(null, null, null));
        assertEquals(40301, noRow.code(), "无行视为默认关闭");
        verify(mapper, never()).countByStatus(any(), any(), any());
    }

    @Test
    void invalidParametersRejected() {
        ApiException ward =
                assertThrows(ApiException.class, () -> service.scanProgress(0L, null, null));
        assertEquals(40001, ward.code());
        ApiException uploader =
                assertThrows(ApiException.class, () -> service.scanProgress(null, -2L, null));
        assertEquals(40001, uploader.code());
        ApiException year =
                assertThrows(ApiException.class, () -> service.scanProgress(null, null, 1800));
        assertEquals(40001, year.code());
    }

    @Test
    void ratioRoundsHalfUp() {
        assertEquals(50, ScanProgressService.donePct(1, 1));
        assertEquals(67, ScanProgressService.donePct(1, 2), "2/3 = 66.67 → 67");
        assertEquals(0, ScanProgressService.donePct(0, 0), "分母 0 → 0");
        assertEquals(100, ScanProgressService.donePct(0, 8));
    }
}
