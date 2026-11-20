package com.crdp.followup.stats.app;

import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.stats.infra.mapper.StatsDrillMapper;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本月应随访：实际任务与尚未生成的排期按（入组、节点、次数）合并，查询不创建任务。 */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class MonthlyDueService {
    private final StatsDrillMapper mapper;
    private final PlanMapper plans;
    private final com.crdp.followup.plan.app.CurrentDeviceScheduleService schedule;

    public MonthlyDueService(
            StatsDrillMapper mapper,
            PlanMapper plans,
            com.crdp.followup.plan.app.CurrentDeviceScheduleService schedule) {
        this.schedule = schedule;
        this.mapper = mapper;
        this.plans = plans;
    }

    public record Unit(
            long patientId,
            long enrollmentId,
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            Long taskId,
            Long recordId,
            Long doctorId,
            long planId,
            String planName,
            String nodeName) {}

    @Transactional(readOnly = true)
    public List<Unit> units(LocalDate from, LocalDate to, Long wardId) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || !java.time.YearMonth.from(from).equals(java.time.YearMonth.from(to)))
            throw new ApiException(400, 40001, "月度应随访查询须为同一月份的有效日期范围");
        var enrollments = mapper.enrollments(wardId);
        var tasks = mapper.tasks(wardId);
        Map<Long, List<StatsDrillMapper.Task>> byEnrollment = new HashMap<>();
        tasks.forEach(
                t -> byEnrollment.computeIfAbsent(t.enrollmentId(), k -> new ArrayList<>()).add(t));
        Map<Long, PlanMapper.PlanRow> planCache = new HashMap<>();
        Map<Long, List<PlanMapper.NodeRow>> nodeCache = new HashMap<>();
        List<Unit> result = new ArrayList<>();
        for (var e : enrollments) {
            var plan = planCache.computeIfAbsent(e.planId(), plans::findById);
            var rows = nodeCache.computeIfAbsent(e.planId(), plans::findNodesByPlanId);
            Map<Long, String> labels = new HashMap<>();
            rows.forEach(n -> labels.put(n.id(), n.name()));
            var existing = byEnrollment.getOrDefault(e.id(), List.of());
            Set<String> present = new HashSet<>();
            for (var task : existing) {
                present.add(key(task.nodeId(), task.occurrence()));
                if (inRange(task.planDate(), from, to))
                    result.add(
                            new Unit(
                                    e.patientId(),
                                    e.id(),
                                    task.nodeId(),
                                    task.occurrence(),
                                    task.planDate(),
                                    task.windowStart(),
                                    task.windowEnd(),
                                    task.id(),
                                    task.recordId(),
                                    e.doctorId(),
                                    e.planId(),
                                    plan == null ? null : plan.name(),
                                    labels.get(task.nodeId())));
            }
            if (e.startDate() == null) continue;
            if (plan == null) continue;
            var defs = FollowupTaskCreationService.nodeDefs(rows);
            Map<Long, TaskCreationPlanner.LoopBasis.NodeLoopState> states = new HashMap<>();
            Set<Long> rolling = new HashSet<>();
            defs.stream()
                    .filter(n -> "LAST_VISIT".equals(n.basis()))
                    .forEach(n -> rolling.add(n.id()));
            for (var task : existing)
                if (rolling.contains(task.nodeId())) {
                    var old = states.get(task.nodeId());
                    if (old == null || task.occurrence() >= old.nextOccurrence())
                        states.put(
                                task.nodeId(),
                                new TaskCreationPlanner.LoopBasis.NodeLoopState(
                                        task.occurrence() + 1, task.planDate()));
                }
            LocalDate end =
                    "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                            ? e.startDate().plusYears(plan.cycleYears())
                            : null;
            var scheduled =
                    TaskCreationPlanner.plan(
                            defs,
                            e.mode(),
                            e.startDate(),
                            to,
                            e.enrolledOn(),
                            end,
                            new TaskCreationPlanner.LoopBasis(e.lastVisitDate(), states),
                            null);
            scheduled = schedule.apply(e.id(), scheduled, defs, to, false);
            for (var future : scheduled.due())
                if (!present.contains(key(future.nodeId(), future.occurrence()))
                        && inRange(future.planDate(), from, to)) {
                    result.add(
                            new Unit(
                                    e.patientId(),
                                    e.id(),
                                    future.nodeId(),
                                    future.occurrence(),
                                    future.planDate(),
                                    future.windowStart(),
                                    future.windowEnd(),
                                    null,
                                    null,
                                    e.doctorId(),
                                    e.planId(),
                                    plan.name(),
                                    labels.get(future.nodeId())));
                }
        }
        return result.stream()
                .sorted(
                        Comparator.comparing(Unit::planDate)
                                .thenComparingLong(Unit::enrollmentId)
                                .thenComparingLong(Unit::nodeId)
                                .thenComparingInt(Unit::occurrence))
                .toList();
    }

    public long count(LocalDate from, LocalDate to, Long wardId) {
        return units(from, to, wardId).size();
    }

    public Map<Long, Long> byDoctor(LocalDate from, LocalDate to, Long wardId) {
        Map<Long, Long> counts = new HashMap<>();
        for (var u : units(from, to, wardId))
            if (u.doctorId() != null) counts.merge(u.doctorId(), 1L, Long::sum);
        return counts;
    }

    public List<com.crdp.followup.stats.infra.mapper.DoctorWorkloadMapper.DoctorTaskRow> doctorRows(
            LocalDate from, LocalDate to, Long wardId) {
        return byDoctor(from, to, wardId).entrySet().stream()
                .map(
                        e ->
                                new com.crdp.followup.stats.infra.mapper.DoctorWorkloadMapper
                                        .DoctorTaskRow(e.getKey(), e.getValue()))
                .toList();
    }

    private static String key(long node, int occurrence) {
        return node + ":" + occurrence;
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }
}
