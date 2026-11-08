package com.crdp.followup.stats.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.form.rule.app.FormProgressEngine.FieldProgress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E19-T05 / A-DASH-010、A-DASH-011、A-PAT-019（口径 Q39 / Q40）：完整度聚合纯函数单测。 输入 = 每个统计单位（基线 = 患者、随访 =
 * 已完成年度记录）的<b>字段级进度</b>（FormProgressEngine.fieldProgress，同一应填集合）； 输出 = 卡片全部聚合口径：
 *
 * <ul>
 *   <li>基线：患者完整度 = round(已填 × 100 ÷ 应填)（应填 0 视为 100，与 BaselineSectionService.refreshCompleteness
 *       同口径）； 汇总 = 各患者完整度的平均值 + 中位数（每名患者权重相同）；分布 5 段（100 / 90–99 / 70–89 / 50–69 / &lt;50）；
 *       分类明细按分类内同算法平均（该分类无应填字段的患者不计入该分类分母）；最常缺失字段按缺失人数降序前 10、缺失率 = 缺失人数 ÷ 范围患者数；
 *   <li>随访：单条记录完整度同上取整；患者值 = 其记录平均值（每条记录权重相同）；卡片汇总按患者平均（有已完成记录的患者）； 按随访年度一行 = 该年记录平均值 / 记录数 / 中位数 /
 *       记录级分布；最常缺失字段按缺失记录数降序前 10、缺失率 = 缺失记录数 ÷ 已统计记录数。
 * </ul>
 *
 * <p>全部虚构数据。
 */
class CompletenessAggregatorTest {

    private static FieldProgress field(String key, String category, boolean filled) {
        return new FieldProgress(key, key + "（示例）", category, filled);
    }

    // ---------------------------------------------------------------- 基线（A-DASH-010 / Q39）

    @Test
    void baselinePatientLevelRoundsPerPatientThenAverages() {
        // 甲 2/2=100、乙 1/2=50、丙 0/2=0 → 平均 50.0、中位数 50.0
        List<Integer> pcts =
                CompletenessAggregator.patientPcts(
                        List.of(
                                List.of(field("f1", "basic", true), field("f2", "basic", true)),
                                List.of(field("f1", "basic", true), field("f2", "basic", false)),
                                List.of(field("f1", "basic", false), field("f2", "basic", false))));

        assertEquals(List.of(100, 50, 0), pcts, "单患者 = round(已填×100÷应填)");

        var stats = CompletenessAggregator.statsOf(pcts);
        assertEquals(3, stats.unitCount());
        assertEquals(50.0, stats.averagePct(), "汇总 = 各患者平均值（每人权重相同）");
        assertEquals(50.0, stats.medianPct(), "中位数");
    }

    @Test
    void zeroDenominatorPatientCountsAsComplete() {
        // 应填为 0 视为 100（refreshCompleteness 同口径）
        List<Integer> pcts = CompletenessAggregator.patientPcts(List.of(List.of()));
        assertEquals(List.of(100), pcts);
    }

    @Test
    void baselineDistributionCoversFiveFixedBands() {
        // 100、100、95、80、60、49 → 五段各 2/1/1/1/1
        var stats = CompletenessAggregator.statsOf(List.of(100, 100, 95, 80, 60, 49));

        assertEquals(6, stats.unitCount());
        assertEquals(
                List.of("FULL", "90_99", "70_89", "50_69", "BELOW_50"),
                stats.distribution().stream().map(CompletenessAggregator.BandView::band).toList());
        assertEquals(
                List.of(2L, 1L, 1L, 1L, 1L),
                stats.distribution().stream().map(CompletenessAggregator.BandView::count).toList());
        assertEquals(33.3, stats.distribution().get(0).pct(), "占比一位小数");
        assertEquals(16.7, stats.distribution().get(1).pct());
    }

    @Test
    void categoryBreakdownExcludesPatientsWithoutDenominatorInCategory() {
        // 甲 basic 1/2=50、case 1/1=100；乙 basic 2/2=100、case 无应填字段（不计入 case 分母）
        List<List<FieldProgress>> units =
                List.of(
                        List.of(
                                field("f1", "basic", true),
                                field("f2", "basic", false),
                                field("f3", "case", true)),
                        List.of(field("f1", "basic", true), field("f2", "basic", true)));

        List<CompletenessAggregator.CategoryStatView> categories =
                CompletenessAggregator.categoryStats(
                        units, Map.of("basic", "基本信息", "case", "病例基本信息"));

        assertEquals(2, categories.size());
        assertEquals("basic", categories.get(0).categoryKey());
        assertEquals("基本信息", categories.get(0).label(), "label 取分类声明显示名");
        assertEquals(2, categories.get(0).unitCount(), "两名患者都有 basic 应填字段");
        assertEquals(75.0, categories.get(0).averagePct(), "basic = (50 + 100) / 2");
        assertEquals("case", categories.get(1).categoryKey());
        assertEquals(1, categories.get(1).unitCount(), "乙在 case 无应填字段不计入分母");
        assertEquals(100.0, categories.get(1).averagePct());
    }

    @Test
    void topMissingFieldsSortedByMissingCountDescLimitedToTen() {
        // 3 名患者：f1 缺 3、f2 缺 2、f3 缺 1、f4 缺 0（f5 只对甲计入且缺失）
        List<List<FieldProgress>> units =
                List.of(
                        List.of(
                                field("f1", "basic", false),
                                field("f2", "basic", false),
                                field("f3", "basic", false),
                                field("f4", "basic", true),
                                field("f5", "case", false)),
                        List.of(
                                field("f1", "basic", false), field("f2", "basic", false),
                                field("f3", "basic", true), field("f4", "basic", true)),
                        List.of(
                                field("f1", "basic", false), field("f2", "basic", true),
                                field("f3", "basic", true), field("f4", "basic", true)));

        List<CompletenessAggregator.MissingFieldView> top =
                CompletenessAggregator.topMissing(units, 3, 10);

        assertEquals(
                List.of("f1", "f2", "f3", "f5"),
                top.stream().map(CompletenessAggregator.MissingFieldView::fieldKey).toList(),
                "按缺失人数降序，无缺失字段不出现");
        assertEquals(3, top.get(0).missingCount());
        assertEquals(100.0, top.get(0).missingRatePct(), "缺失率 = 缺失人数 ÷ 范围患者数");
        assertEquals(2, top.get(1).missingCount());
        assertEquals(66.7, top.get(1).missingRatePct(), "缺失率一位小数");
        assertEquals("f1（示例）", top.get(0).label(), "label 供卡片缺失字段列显示");
        assertEquals("basic", top.get(0).categoryKey());
    }

    // ---------------------------------------------------------------- 随访（A-DASH-011 / Q40）

    @Test
    void followupPatientValueIsMeanOfRecordPcts() {
        // 甲两条记录 100、50 → 患者 75.0；乙一条 90 → 患者 90.0；卡片平均 = (75 + 90) / 2 = 82.5
        List<CompletenessAggregator.RecordProgress> records =
                List.of(
                        new CompletenessAggregator.RecordProgress(1L, 1, pct(100)),
                        new CompletenessAggregator.RecordProgress(1L, 2, pct(50)),
                        new CompletenessAggregator.RecordProgress(2L, 1, pct(90)));

        var card = CompletenessAggregator.followupCard(records);

        assertEquals(3, card.recordCount(), "已统计记录数 = 计入的已完成年度记录");
        assertEquals(2, card.patientCount(), "患者数 = 有已完成记录的患者");
        assertEquals(82.5, card.averagePct(), "卡片汇总按患者平均（每名患者权重相同）");
        assertEquals(82.5, card.medianPct());
    }

    @Test
    void followupYearRowsAverageRecordsWithinYear() {
        List<CompletenessAggregator.RecordProgress> records =
                List.of(
                        new CompletenessAggregator.RecordProgress(1L, 1, pct(100)),
                        new CompletenessAggregator.RecordProgress(2L, 1, pct(80)),
                        new CompletenessAggregator.RecordProgress(1L, 2, pct(40)));

        List<CompletenessAggregator.YearStatView> years =
                CompletenessAggregator.followupCard(records).years();

        assertEquals(2, years.size(), "只列有已完成记录的年度，按年度升序");
        assertEquals(1, years.get(0).year());
        assertEquals(2, years.get(0).recordCount());
        assertEquals(90.0, years.get(0).averagePct(), "第 N 年 = 该年记录完整度平均值");
        assertEquals(90.0, years.get(0).medianPct());
        assertEquals(1, years.get(0).distribution().get(0).count(), "年度分布为记录级（90–99 段 1 条）");
        assertEquals(1, years.get(1).recordCount());
        assertEquals(40.0, years.get(1).averagePct());
    }

    @Test
    void followupPatientLevelDistributionUsesPatientValues() {
        // 患者甲 75、患者乙 90 → 卡片分布按患者值分段
        List<CompletenessAggregator.RecordProgress> records =
                List.of(
                        new CompletenessAggregator.RecordProgress(1L, 1, pct(50)),
                        new CompletenessAggregator.RecordProgress(1L, 2, pct(100)),
                        new CompletenessAggregator.RecordProgress(2L, 1, pct(90)));

        var card = CompletenessAggregator.followupCard(records);

        assertEquals(
                List.of(0L, 1L, 1L, 0L, 0L),
                card.distribution().stream().map(CompletenessAggregator.BandView::count).toList(),
                "患者级分布按患者值分段：甲 = (50 + 100) / 2 = 75 → 70–89、乙 = 90 → 90–99");
    }

    @Test
    void topMissingRecordsCountRecordLevelAbsence() {
        List<List<FieldProgress>> units =
                List.of(
                        List.of(field("f1", "fu_contact", false), field("f2", "fu_contact", true)),
                        List.of(field("f1", "fu_contact", false), field("f2", "fu_contact", false)),
                        List.of(field("f1", "fu_contact", true), field("f2", "fu_contact", false)));

        List<CompletenessAggregator.MissingFieldView> top =
                CompletenessAggregator.topMissing(units, 3, 1);

        assertEquals(1, top.size(), "limit 截断（前 10 由调用方传）");
        assertEquals("f1", top.get(0).fieldKey());
        assertEquals(2, top.get(0).missingCount(), "缺失记录数");
        assertEquals(66.7, top.get(0).missingRatePct(), "缺失率 = 缺失记录数 ÷ 已统计记录数");
    }

    private static Integer pct(int value) {
        return value;
    }
}
