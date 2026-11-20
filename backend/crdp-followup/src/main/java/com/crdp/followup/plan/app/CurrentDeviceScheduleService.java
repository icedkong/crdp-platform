package com.crdp.followup.plan.app;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.CurrentDeviceScheduleMapper;
import com.crdp.followup.plan.infra.mapper.CurrentDeviceScheduleMapper.*;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.record.app.EffectiveVisitDateResolver;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskCreationPlanner.*;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.*;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OQR-06：当前器械日期派生、指定次数绑定和消费；与记录修改 / 调度同事务。 */
@Service
public class CurrentDeviceScheduleService {
    private final CurrentDeviceScheduleMapper mapper;
    private final FollowupTaskMapper tasks;
    private final PlanMapper plans;
    private final EffectiveVisitDateResolver dates;
    private final ObjectMapper json;
    private final AuditLogger audit;
    private final TransactionTemplate tx;

    public CurrentDeviceScheduleService(
            CurrentDeviceScheduleMapper mapper,
            FollowupTaskMapper tasks,
            PlanMapper plans,
            EffectiveVisitDateResolver dates,
            ObjectMapper json,
            AuditLogger audit,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.tasks = tasks;
        this.plans = plans;
        this.dates = dates;
        this.json = json;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    public void lockPatient(long patientId) {
        mapper.lockPatient(patientId);
    }

    public FollowupTaskMapper.EnrollmentRow currentEnrollment(
            FollowupTaskMapper.EnrollmentRow original) {
        return mapper.schedulable(original.id());
    }

    /** 调度读取的入组可能已经过时；在同一事务内先按患者加锁，再锁入组、创建 / 重排及消费。 */
    public <T> T inEnrollmentTransaction(long id, long patientId, Supplier<T> action) {
        return tx.execute(
                ignored -> {
                    lockPatient(patientId);
                    mapper.enrollments(patientId);
                    return action.get();
                });
    }

    public boolean isLatest(long patientId, long recordId) {
        var device = mapper.lockDevice(patientId);
        var latest = latest(patientId, device);
        return latest != null && latest.id() == recordId;
    }

    private record Dated(long id, LocalDate date) {}

    private Dated latest(long patientId, Device device) {
        return latest(patientId, device, true);
    }

    private Dated latest(long patientId, Device device, boolean locking) {
        if (device == null) return null;
        Dated latest = null;
        var schemas = new HashMap<String, com.crdp.form.record.app.FormService.SchemaView>();
        for (var row :
                locking
                        ? mapper.submitted(patientId, device.id())
                        : mapper.readSubmitted(patientId, device.id())) {
            var date =
                    dates.resolve(
                            row.visitDate(),
                            row.formCode(),
                            row.templateVersion(),
                            read(row.data()),
                            schemas);
            if (date == null || date.isBefore(device.implantDate())) continue;
            if (latest == null
                    || date.isAfter(latest.date())
                    || (date.equals(latest.date()) && row.id() > latest.id()))
                latest = new Dated(row.id(), date);
        }
        return latest;
    }

    /** 更正 / 删除可使派生值回退或清空；不自动重放旧记录中的 ERI / 医生指令。 */
    public void refresh(long patientId) {
        lockPatient(patientId);
        var device = mapper.lockDevice(patientId);
        var latest = latest(patientId, device);
        LocalDate date = latest == null ? null : latest.date();
        for (var e : mapper.enrollments(patientId)) {
            var plan = plans.findById(e.planId());
            var rows = plans.findNodesByPlanId(e.planId());
            if (plan == null || rows.stream().noneMatch(n -> "LAST_VISIT".equals(n.basis())))
                continue;
            if (Objects.equals(date, e.lastVisitDate())) continue;
            mapper.setLastVisit(e.id(), date);
            if ("ACTIVE".equals(e.status())) reschedule(e, rows, date);
            audit("LAST_VISIT_RECALC", e.id(), "按当前器械有效已提交记录重算最近随访日期（允许回退 / 清空）");
        }
    }

    private void reschedule(Enrollment e, List<PlanMapper.NodeRow> rows, LocalDate date) {
        var defs = FollowupTaskCreationService.nodeDefs(rows);
        Map<Long, NodeDef> nodes = new HashMap<>();
        defs.forEach(n -> nodes.put(n.id(), n));
        Set<Long> movable = new HashSet<>();
        tasks.findUnstartedTasks(e.id()).forEach(t -> movable.add(t.id()));
        Map<Long, LocalDate> previous = new HashMap<>();
        var today = today();
        for (var task : mapper.lockTasks(e.id())) {
            var node = nodes.get(task.nodeId());
            if (node == null || !"LAST_VISIT".equals(node.basis())) continue;
            boolean bound =
                    Objects.equals(e.nodeId(), task.nodeId())
                            && Objects.equals(e.occurrence(), task.occurrence())
                            && e.doctorNextDate() != null;
            if (!movable.contains(task.id())
                    || task.planDate().isBefore(today)
                    || task.doctorOverride()
                    || bound) {
                previous.merge(task.nodeId(), task.planDate(), CurrentDeviceScheduleService::later);
                continue;
            }
            LocalDate base = later(date, previous.get(task.nodeId()));
            if (base == null) base = e.startDate();
            if (base == null) continue;
            var schedule =
                    PlanDateCalculator.computeNodeSchedule(
                            TaskCreationPlanner.timingOf(node), base);
            int changed =
                    schedule.openDate().isAfter(today)
                            ? tasks.withdrawTask(task.id())
                            : tasks.rescheduleTask(
                                    task.id(),
                                    schedule.planDate(),
                                    schedule.remindStart(),
                                    schedule.windowStart(),
                                    schedule.windowEnd(),
                                    schedule.openDate());
            if (changed != 1) throw conflict();
            if (!schedule.openDate().isAfter(today))
                previous.put(task.nodeId(), schedule.planDate());
        }
        refreshCache(e, defs, date, today);
    }

    private void refreshCache(Enrollment e, List<NodeDef> defs, LocalDate date, LocalDate today) {
        if (e.startDate() == null) return;
        var result =
                TaskCreationPlanner.plan(
                        defs,
                        e.mode(),
                        e.startDate(),
                        today,
                        e.enrolledOn(),
                        planEnd(e),
                        basis(e.id(), defs, date));
        tasks.updateNextCache(
                e.id(),
                result.next() == null ? null : result.next().planDate(),
                result.next() == null ? null : result.next().openDate());
    }

    /** 新指令按已有最早开启规则选择一次并持久绑定；历史补录中的过去日期只保留记录。 */
    public boolean register(
            long patientId,
            long recordId,
            LocalDate visitDate,
            Map<String, Object> data,
            boolean reselect) {
        Object raw = data.get("f333");
        if (raw == null || raw instanceof String s && s.isBlank()) {
            if (reselect) throw new ApiException(400, 40001, "请填写当前有效的下次随访时间后重新确认任务");
            mapper.revoke(patientId, recordId);
            return false;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(String.valueOf(raw).trim());
        } catch (java.time.DateTimeException invalid) {
            throw new ApiException(400, 40001, "下次随访时间必须是有效日期");
        }
        if (visitDate == null || !date.isAfter(visitDate))
            throw new ApiException(400, 40001, "下次随访时间必须晚于本次实际随访日期");
        if (date.isBefore(today())) {
            // C-FU-005：历史补录中已过去的下次日期仅归档，不发出当前计划命令。
            if (visitDate.isBefore(today())
                    && mapper.historicalBackfill(patientId, recordId, today())) return false;
            throw new ApiException(400, 40001, "下次随访时间不能早于今天");
        }
        var device = mapper.lockDevice(patientId);
        if (device == null) throw conflict();
        boolean bound = false;
        for (var e : mapper.enrollments(patientId)) {
            if (!"ACTIVE".equals(e.status())) continue;
            var rows = plans.findNodesByPlanId(e.planId());
            if (e.startDate() == null) continue;
            var plan = plans.findById(e.planId());
            if (plan == null
                    || !("IMPLANT_DATE".equals(plan.startDateField())
                            || rows.stream().anyMatch(n -> "LAST_VISIT".equals(n.basis()))))
                continue;
            var defs = FollowupTaskCreationService.nodeDefs(rows);
            var actual = mapper.lockTasks(e.id());
            var result =
                    TaskCreationPlanner.plan(
                            defs,
                            e.mode(),
                            e.startDate(),
                            today(),
                            e.enrolledOn(),
                            planEnd(e),
                            basis(e.id(), defs, e.lastVisitDate()));
            List<PlannedTask> candidates = new ArrayList<>(result.due());
            if (result.next() != null) candidates.add(result.next());
            // 滚动次数已物化时，默认 planner 只返回再下一次；尚未结束的一次仍须作为覆盖候选。
            for (var t : actual) {
                if (!Set.of("PENDING", "IN_PROGRESS", "OVERDUE").contains(t.status())) continue;
                var node =
                        defs.stream()
                                .filter(
                                        n ->
                                                n.id() == t.nodeId()
                                                        && TaskCreationPlanner.activeInMode(
                                                                n.enrollMode(), e.mode()))
                                .findFirst()
                                .orElse(null);
                if (node == null) continue;
                var s =
                        PlanDateCalculator.scheduleFromPlanDate(
                                TaskCreationPlanner.timingOf(node), t.planDate());
                candidates.add(
                        new PlannedTask(
                                t.nodeId(),
                                t.occurrence(),
                                s.planDate(),
                                s.remindStart(),
                                s.windowStart(),
                                s.windowEnd(),
                                s.openDate(),
                                "NORMAL",
                                false));
            }
            candidates.removeIf(
                    c ->
                            actual.stream()
                                    .anyMatch(
                                            t ->
                                                    t.nodeId() == c.nodeId()
                                                            && t.occurrence() == c.occurrence()
                                                            && (!Set.of(
                                                                                    "PENDING",
                                                                                    "IN_PROGRESS",
                                                                                    "OVERDUE")
                                                                            .contains(t.status())
                                                                    || reselect
                                                                            && (t.protectedTask()
                                                                                    || !"PENDING"
                                                                                            .equals(
                                                                                                    t
                                                                                                            .status())))));
            candidates.sort(
                    Comparator.comparing(PlannedTask::openDate)
                            .thenComparing(PlannedTask::planDate)
                            .thenComparingLong(PlannedTask::nodeId)
                            .thenComparingInt(PlannedTask::occurrence));
            if (candidates.isEmpty()) throw conflict();
            var target = candidates.getFirst();
            if (actual.stream()
                    .anyMatch(
                            t ->
                                    t.nodeId() == target.nodeId()
                                            && t.occurrence() == target.occurrence()
                                            && (!"PENDING".equals(t.status())
                                                    || t.protectedTask()))) throw conflict();
            if (mapper.bind(
                            e.id(),
                            date,
                            target.nodeId(),
                            target.occurrence(),
                            device.id(),
                            recordId)
                    != 1) throw conflict();
            audit("DOCTOR_NEXT_BIND", e.id(), "医生指定日期已绑定当前器械及具体节点次数");
            var existing =
                    actual.stream()
                            .filter(
                                    t ->
                                            t.nodeId() == target.nodeId()
                                                    && t.occurrence() == target.occurrence())
                            .findFirst()
                            .orElse(null);
            if (existing != null) {
                var node =
                        defs.stream()
                                .filter(n -> n.id() == target.nodeId())
                                .findFirst()
                                .orElseThrow();
                var schedule =
                        PlanDateCalculator.scheduleFromPlanDate(
                                TaskCreationPlanner.timingOf(node), date);
                if (tasks.rescheduleTask(
                                existing.id(),
                                schedule.planDate(),
                                schedule.remindStart(),
                                schedule.windowStart(),
                                schedule.windowEnd(),
                                schedule.openDate())
                        != 1) throw conflict();
                consume(
                        e.id(),
                        new PlannedTask(
                                target.nodeId(),
                                target.occurrence(),
                                schedule.planDate(),
                                schedule.remindStart(),
                                schedule.windowStart(),
                                schedule.windowEnd(),
                                schedule.openDate(),
                                "NORMAL",
                                true));
            }
            bound = true;
        }
        if (!bound) throw conflict();
        return true;
    }

    /** 调度和只读统计使用同一绑定；不能每天重新猜测另一次。 */
    public PlanResult apply(
            long id,
            PlanResult original,
            List<NodeDef> defs,
            LocalDate logicalDate,
            boolean strict) {
        var e = mapper.enrollment(id);
        if (e == null || e.doctorNextDate() == null) return original;
        var device = mapper.device(e.patientId());
        var source = latest(e.patientId(), device, false);
        var node =
                e.nodeId() == null
                        ? null
                        : defs.stream().filter(n -> n.id() == e.nodeId()).findFirst().orElse(null);
        boolean invalid =
                e.nodeId() == null
                        || e.occurrence() == null
                        || e.recordId() == null
                        || source == null
                        || !Objects.equals(e.recordId(), source.id())
                        || device == null
                        || !Objects.equals(e.implantId(), device.id())
                        || !Objects.equals(e.mode(), e.commandMode())
                        || e.lastVisitDate() != null
                                && !e.doctorNextDate().isAfter(e.lastVisitDate())
                        || node == null
                        || !"ACTIVE".equals(e.status());
        if (!invalid)
            invalid =
                    mapper.tasks(id).stream()
                            .anyMatch(
                                    t ->
                                            t.nodeId() == e.nodeId()
                                                    && t.occurrence() == e.occurrence()
                                                    && (!"PENDING".equals(t.status())
                                                            || t.protectedTask()));
        if (invalid) {
            if (strict) throw conflict();
            return original;
        }
        var schedule =
                PlanDateCalculator.scheduleFromPlanDate(
                        TaskCreationPlanner.timingOf(node), e.doctorNextDate());
        List<PlannedTask> due = new ArrayList<>(original.due());
        due.removeIf(
                t ->
                        t.nodeId() == e.nodeId()
                                && (t.occurrence() == e.occurrence()
                                        || "LAST_VISIT".equals(node.basis())
                                                && t.occurrence() > e.occurrence()));
        var next = original.next();
        if (next != null
                && next.nodeId() == e.nodeId()
                && (next.occurrence() == e.occurrence()
                        || "LAST_VISIT".equals(node.basis()) && next.occurrence() > e.occurrence()))
            next = null;
        var override =
                new PlannedTask(
                        e.nodeId(),
                        e.occurrence(),
                        schedule.planDate(),
                        schedule.remindStart(),
                        schedule.windowStart(),
                        schedule.windowEnd(),
                        schedule.openDate(),
                        "NORMAL",
                        true);
        if (!schedule.openDate().isAfter(logicalDate)) due.add(override);
        else if (next == null || schedule.openDate().isBefore(next.openDate())) next = override;
        return new PlanResult(List.copyOf(due), next);
    }

    public boolean isBoundTarget(long id, long nodeId, int occurrence) {
        var e = mapper.enrollment(id);
        return e != null
                && (e.doctorNextDate() != null
                                && Objects.equals(e.nodeId(), nodeId)
                                && Objects.equals(e.occurrence(), occurrence)
                        || mapper.doctorTask(id, nodeId, occurrence));
    }

    public void consume(long id, PlannedTask task) {
        var e = mapper.enrollment(id);
        if (e == null
                || e.recordId() == null
                || mapper.markDoctorTask(id, task.nodeId(), task.occurrence(), e.recordId()) != 1)
            throw conflict();
        if (mapper.consume(id, task.nodeId(), task.occurrence(), task.planDate()) != 1)
            throw conflict();
        audit("DOCTOR_NEXT_CONSUME", id, "指定日期已在绑定次数创建或重排成功，保留任务来源并同事务消费");
    }

    public void revoke(long patientId, long recordId) {
        if (mapper.revoke(patientId, recordId) > 0)
            audit.append(
                    AuditEntry.operation("followup", "DOCTOR_NEXT_REVOKE")
                            .objectType("FU_RECORD")
                            .objectId(String.valueOf(recordId))
                            .description("来源记录删除或清空指定日期，撤销尚未消费的指令")
                            .result(AuditResult.SUCCESS)
                            .build());
    }

    public record CommandView(
            String enrollmentId,
            String nodeId,
            Integer occurrence,
            String implantId,
            String sourceRecordId,
            LocalDate date,
            String state) {}

    /** 仅随已授权患者详情返回绑定元数据，不含临床字段或姓名。 */
    public List<CommandView> commands(long patientId) {
        var result = new ArrayList<CommandView>();
        // 详情读取不加行锁，使用独立只读查询。
        for (var id : mapper.enrollmentIds(patientId)) {
            var e = mapper.enrollment(id);
            if (e == null || e.doctorNextDate() == null) continue;
            String state = "PENDING";
            try {
                apply(
                        e.id(),
                        new PlanResult(List.of(), null),
                        FollowupTaskCreationService.nodeDefs(plans.findNodesByPlanId(e.planId())),
                        today(),
                        true);
            } catch (ApiException changed) {
                state = "CONFLICT";
            }
            result.add(
                    new CommandView(
                            String.valueOf(e.id()),
                            e.nodeId() == null ? null : String.valueOf(e.nodeId()),
                            e.occurrence(),
                            e.implantId() == null ? null : String.valueOf(e.implantId()),
                            e.recordId() == null ? null : String.valueOf(e.recordId()),
                            e.doctorNextDate(),
                            state));
        }
        return result;
    }

    private LoopBasis basis(long id, List<NodeDef> defs, LocalDate date) {
        return FollowupTaskCreationService.loopBasis(
                date,
                mapper.lockTasks(id).stream()
                        .map(
                                t ->
                                        new FollowupTaskMapper.TaskOccurrenceRow(
                                                t.nodeId(), t.occurrence(), t.planDate()))
                        .toList(),
                defs.stream()
                        .filter(n -> "LAST_VISIT".equals(n.basis()))
                        .map(NodeDef::id)
                        .toList());
    }

    private LocalDate planEnd(Enrollment e) {
        var p = plans.findById(e.planId());
        return p != null && "YEARS".equals(p.cycleType()) && p.cycleYears() != null
                ? e.startDate().plusYears(p.cycleYears())
                : null;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static LocalDate today() {
        return LocalDate.now(PlanDateCalculator.BUSINESS_ZONE);
    }

    public static ApiException conflict() {
        return new ApiException(409, 40901, "指定排期的器械、模式或目标次数已变化，或任务已开始；请重新确认并选择下一次任务，原指令未消费");
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("当前器械记录解析失败", ex);
        }
    }

    private void audit(String action, long id, String description) {
        audit.append(
                AuditEntry.operation("followup", action)
                        .objectType("ENROLLMENT")
                        .objectId(String.valueOf(id))
                        .description(description)
                        .result(AuditResult.SUCCESS)
                        .build());
    }
}
