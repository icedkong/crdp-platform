package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.stats.infra.mapper.DoctorWorkloadMapper;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E19-T07 / A-DASH-009、C-DASH-010（医护负责患者数，验收「按责任人统计」）服务层单测： 按**患者责任医生**归口合并四路计数（负责患者数 /
 * 在随访、本月应随访、逾期、每年随访率）， 随访率 = 年度节点患者 × 年度去重汇总（口径 Q38，与每年随访率卡片同一事实表）； 每个数字内联钻取目标（患者列表 doctorId〔/
 * status〕、任务列表 tab=all + dueFrom/dueTo / status）。
 */
@ExtendWith(MockitoExtension.class)
class DoctorWorkloadStatsServiceTest {

    /** 「本月」按北京时间业务日计算（与指标卡同口径，DashboardKpiServiceTest 同做法）。 */
    private static final LocalDate TODAY = PlanDateCalculator.businessToday(Instant.now());

    private static final LocalDate MONTH_START = TODAY.withDayOfMonth(1);

    private static final LocalDate MONTH_END = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    @Mock private DoctorWorkloadMapper mapper;

    private DoctorWorkloadStatsService service() {
        return new DoctorWorkloadStatsService(mapper);
    }

    // ---------------------------------------------------------------- 合并与钻取

    @Test
    void workloadMergesPatientTaskAndRateCountsPerDoctor() {
        when(mapper.countPatientsByDoctor(isNull()))
                .thenReturn(
                        List.of(
                                new DoctorWorkloadMapper.DoctorPatientRow(11L, "张医生（示例）", 4, 3),
                                new DoctorWorkloadMapper.DoctorPatientRow(12L, "李医生（示例）", 2, 2)));
        when(mapper.countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), isNull()))
                .thenReturn(List.of(new DoctorWorkloadMapper.DoctorTaskRow(11L, 2)));
        when(mapper.countOverdueByDoctor(isNull()))
                .thenReturn(List.of(new DoctorWorkloadMapper.DoctorTaskRow(11L, 1)));
        when(mapper.aggregateYearlyRateByDoctor(isNull()))
                .thenReturn(
                        List.of(
                                new DoctorWorkloadMapper.DoctorRateRow(11L, 4, 3, AS_OF),
                                new DoctorWorkloadMapper.DoctorRateRow(
                                        12L, 2, 1, AS_OF.minusDays(1))));

        var view = service().workload(null);

        assertEquals(
                TODAY.getYear() + "-" + String.format("%02d", TODAY.getMonthValue()),
                view.asOfMonth(),
                "本月口径月（北京时间业务月）");
        assertEquals(AS_OF, view.asOf(), "随访率数据时点 = 事实表最近刷新日");
        assertEquals(2, view.rows().size(), "按责任医生一行");
        var zhang = view.rows().get(0);
        assertEquals("11", zhang.doctorId(), "doctorId 序列化为字符串");
        assertEquals("张医生（示例）", zhang.doctorName());
        assertEquals(4, zhang.totalPatients(), "负责患者数");
        assertEquals(3, zhang.activePatients(), "在随访");
        assertEquals(2, zhang.monthDue(), "本月应随访");
        assertEquals(1, zhang.overdue(), "当前逾期");
        assertEquals(4, zhang.rateDueCount(), "随访率分母 = 患者 × 年度去重");
        assertEquals(3, zhang.rateFollowedCount());
        assertEquals(75.0, zhang.followRatePct(), "每年随访率 = 3/4");
        var li = view.rows().get(1);
        assertEquals(0, li.monthDue(), "无任务的医生计数为 0（行保留）");
        assertEquals(0, li.overdue());
        assertEquals(50.0, li.followRatePct());
    }

    @Test
    void monthWindowFollowsBusinessTodayBounds() {
        when(mapper.countPatientsByDoctor(isNull()))
                .thenReturn(
                        List.of(new DoctorWorkloadMapper.DoctorPatientRow(11L, "张医生（示例）", 1, 1)));
        when(mapper.countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), isNull()))
                .thenReturn(List.of());
        when(mapper.countOverdueByDoctor(isNull())).thenReturn(List.of());
        when(mapper.aggregateYearlyRateByDoctor(isNull())).thenReturn(List.of());

        service().workload(null);

        // 与指标卡「本月应随访」同一业务月口径（DashboardKpiService 同做法）
        verify(mapper).countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), isNull());
    }

    @Test
    void drillsInlinePatientAndTaskListTargets() {
        when(mapper.countPatientsByDoctor(isNull()))
                .thenReturn(
                        List.of(new DoctorWorkloadMapper.DoctorPatientRow(11L, "张医生（示例）", 4, 3)));
        when(mapper.countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), isNull()))
                .thenReturn(List.of());
        when(mapper.countOverdueByDoctor(isNull())).thenReturn(List.of());
        when(mapper.aggregateYearlyRateByDoctor(isNull())).thenReturn(List.of());

        var view = service().workload(null);
        var row = view.rows().get(0);

        // 负责患者数 → 患者列表按责任医生筛选（列表检索 filters.doctorId 同条件树）
        assertEquals("PATIENT_LIST", row.totalDrill().target());
        assertEquals("11", row.totalDrill().query().get("doctorId"));
        assertEquals("张医生（示例）", row.totalDrill().query().get("doctorName"), "列表页钻取标签展示用");
        // 在随访 → 患者列表再按 status 筛选
        assertEquals("PATIENT_LIST", row.activeDrill().target());
        assertEquals("ACTIVE", row.activeDrill().query().get("status"));
        assertEquals("11", row.activeDrill().query().get("doctorId"));
        // 本月应随访 → 任务列表 doctorId + 计划日期窗口（与指标卡「本月应随访」同钻取形态）
        assertEquals("TASK_LIST", row.monthDueDrill().target());
        assertEquals("all", row.monthDueDrill().query().get("tab"));
        assertEquals(MONTH_START.toString(), row.monthDueDrill().query().get("dueFrom"));
        assertEquals(MONTH_END.toString(), row.monthDueDrill().query().get("dueTo"));
        assertEquals("11", row.monthDueDrill().query().get("doctorId"));
        // 逾期 → 任务列表 doctorId + status=OVERDUE
        assertEquals("TASK_LIST", row.overdueDrill().target());
        assertEquals("OVERDUE", row.overdueDrill().query().get("status"));
        assertEquals("11", row.overdueDrill().query().get("doctorId"));
    }

    // ---------------------------------------------------------------- 边界

    @Test
    void emptyScopeReturnsEmptyRowsWithoutAsOf() {
        when(mapper.countPatientsByDoctor(eq(5L))).thenReturn(List.of());
        lenient()
                .when(mapper.countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), eq(5L)))
                .thenReturn(List.of());
        lenient().when(mapper.countOverdueByDoctor(eq(5L))).thenReturn(List.of());
        lenient().when(mapper.aggregateYearlyRateByDoctor(eq(5L))).thenReturn(List.of());

        var view = service().workload(5L);

        assertEquals(List.of(), view.rows(), "范围内无患者 → 空行集，不报错");
        assertNull(view.asOf());
        verify(mapper).countPatientsByDoctor(eq(5L));
    }

    @Test
    void rateOnlyRowsWithoutPatientsAreDropped() {
        // 行锚点是「有负责患者的医生」：任务 / 随访率只统计责任医生的在册患者，
        // 理论上不会出现无患者锚点的计数行；防御性地丢弃而不是凭空造行。
        when(mapper.countPatientsByDoctor(isNull())).thenReturn(List.of());
        when(mapper.countMonthDueByDoctor(eq(MONTH_START), eq(MONTH_END), isNull()))
                .thenReturn(List.of());
        when(mapper.countOverdueByDoctor(isNull())).thenReturn(List.of());
        when(mapper.aggregateYearlyRateByDoctor(isNull()))
                .thenReturn(List.of(new DoctorWorkloadMapper.DoctorRateRow(99L, 3, 3, AS_OF)));

        var view = service().workload(null);
        assertEquals(List.of(), view.rows());
    }

    @Test
    void invalidWardIdRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service().workload(0L));
        assertEquals(40001, ex.code());
        verify(mapper, never()).countPatientsByDoctor(isNull());
    }
}
