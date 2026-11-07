package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.importer.domain.ImportYearPlanner.YearPlan;
import com.crdp.followup.importer.domain.ImportYearPlanner.YearSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E18-T05 年度规划纯函数测试（tech-plan §12⑦ / Q06）：有数据年度 → 历史导入；窗口已结束无数据年度 → 历史未随访；
 * 窗口未结束年度不生成（当前窗口由每日调度建任务，A-FU-001）；排期覆盖不到的有数据年度仍按年度生成（unscheduled 回落）。
 */
class ImportYearPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 1, 10);

    @Test
    void dataYearsBecomeImportAndClosedEmptyYearsBecomeMissed() {
        // 出院 2024-01-10、今天 2027-01-10：第 1、2 年窗口已结束（±7 天），第 3 年窗口未结束（1-17 止）；第 1 年有数据
        List<YearSchedule> schedules =
                List.of(
                        new YearSchedule(1, d("2025-01-10"), d("2025-01-03"), d("2025-01-17")),
                        new YearSchedule(2, d("2026-01-10"), d("2026-01-03"), d("2026-01-17")),
                        new YearSchedule(3, d("2027-01-10"), d("2027-01-03"), d("2027-01-17")));
        List<YearPlan> plans = ImportYearPlanner.plan(schedules, Set.of(1), TODAY);
        assertEquals(2, plans.size());
        assertEquals(true, plans.get(0).hasData(), "第 1 年有数据 → 历史导入");
        assertEquals(false, plans.get(1).hasData(), "第 2 年窗口已结束且无数据 → 历史未随访（Q06）");
        assertEquals(d("2026-01-10"), plans.get(1).planDate());
    }

    @Test
    void openWindowYearWithoutDataIsNotGenerated() {
        List<YearSchedule> schedules =
                List.of(
                        new YearSchedule(1, d("2025-01-10"), d("2025-01-03"), d("2025-01-17")),
                        new YearSchedule(2, d("2027-01-10"), d("2027-01-03"), d("2027-01-17")));
        // 窗口未结束（windowEnd=2027-01-17 晚于 today=2027-01-10）：当前窗口由每日调度建任务，不生成记录
        List<YearSchedule> open =
                List.of(new YearSchedule(1, d("2027-12-10"), d("2027-12-03"), d("2027-12-17")));
        assertEquals(List.of(), ImportYearPlanner.plan(open, Set.of(), TODAY), "窗口未结束且无数据不生成");
        assertEquals(1, ImportYearPlanner.plan(schedules, Set.of(), TODAY).size());
    }

    @Test
    void dataYearBeyondScheduleStillPlannedByYear() {
        // 起算日期缺失（schedules 空）但文件里有第 1、3 年数据：unscheduled 回落按年度生成（窗口锚点留空）
        assertEquals(
                List.of(1, 3),
                ImportYearPlanner.unscheduledDataYears(List.of(), Set.of(1, 3, 0, -2)));
        // 排期已覆盖的年度不重复
        List<YearSchedule> schedules =
                List.of(new YearSchedule(1, d("2025-01-10"), d("2025-01-03"), d("2025-01-17")));
        assertEquals(List.of(3), ImportYearPlanner.unscheduledDataYears(schedules, Set.of(1, 3)));
    }

    @Test
    void emptySchedulesProduceOnlyNothing() {
        assertEquals(List.of(), ImportYearPlanner.plan(List.of(), Set.of(1, 2), TODAY));
    }

    private static LocalDate d(String text) {
        return LocalDate.parse(text);
    }
}
