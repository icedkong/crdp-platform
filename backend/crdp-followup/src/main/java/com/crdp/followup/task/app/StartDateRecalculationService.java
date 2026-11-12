package com.crdp.followup.task.app;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 起算日期变更重算（E14-T07 / A-FU-011；tasks.csv 验收「改出院日期后未开始任务重算」）。
 *
 * <p>触发口径：患者出院日期（以手术日期起算的计划为手术日期，如医院 B）修改后调用本入口——HTTP 触发随患者基础信息编辑（PUT /patients/{id}，patient.md
 * §2）随 E13-T02 接入； 患者存在性按唯一性口径全院查（{@code @IgnoreScope}，与死亡终止同思路），操作者对该患者的数据范围校验由调用方承担（OQ-81①）。
 *
 * <p>一次调用的原子效果（同一事务，A-FU-011 逐句对应）：
 *
 * <ul>
 *   <li>下次计划日期按新日期<b>实时</b>重算：逐入组把起算日期快照（fu_enrollment.start_date）更新为按计划起算字段 解析的新值，并刷新
 *       next_plan_date / next_open_date 缓存（未来年度未预先创建、无需清理，A-FU-001）；
 *   <li>已创建但尚无联系记录的当期任务按新日期重算窗口：fu_task 的计划日期 / 提醒开始日 / 窗口与开启日五列整体重排（TaskCreationPlanner
 *       同一套次数推进口径），状态、负责人、创建方式与创建事实不变；「尚无联系记录」以 status = PENDING（待随访）判定（fu_contact_log 随 E15
 *       落地前的暂行口径，OQ-81②）——进行中（已有联系 / 录入）、已完成、逾期、失访、已终止与「期间未执行」补偿任务<b>不变</b>；
 *   <li>新窗口尚未开启的撤回为计划中：新开启日在今天之后的未开始任务删行（A-FU-003「计划中 / 待开始」只是时间轴显示态、不对应实际任务）， 每日调度到新开启日照常按新日期重建（uk
 *       幂等口径不破坏）；新计划日期超出管理周期（cycle_type=YEARS）边界的次数同样撤回（防御，配置口径漂移时兜底）；
 *   <li>已完成任务不变（历史事实，同 E14-T06 终止保留口径）。
 * </ul>
 *
 * <p>不创建新任务：日期改早后新开启的次数由每日调度（E14-T04）在下一个执行日按常规律创建（含停用 / 暂停补偿改判）， 本入口只重排既有行（OQ-81③）。
 * 起算字段不可解析（IMPLANT_DATE 随 E21、CUSTOM 未定义）或解析值与快照相同的入组跳过（幂等，不写审计）； 全部入组无变化时返回零计数。
 */
@Service
public class StartDateRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(StartDateRecalculationService.class);

    private final FollowupTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final PlanEnrollmentMapper enrollmentMapper;
    private final AuditLogger audit;

    public StartDateRecalculationService(
            FollowupTaskMapper taskMapper,
            PlanMapper planMapper,
            PlanEnrollmentMapper enrollmentMapper,
            AuditLogger audit) {
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.audit = audit;
    }

    /** 重算结果（enrollments = 起算快照更新的入组数；rescheduled / withdrawn = 未开始任务重排 / 撤回条数）。 */
    public record RecalculationView(
            String patientId, int enrollments, int rescheduled, int withdrawn) {}

    /**
     * 起算日期变更重算（A-FU-011）：在租户上下文内调用（患者编辑侧同一事务）。
     *
     * @param patientId 起算字段（出院 / 手术日期）刚被修改的患者
     * @param operatorId 触发操作人（患者编辑人），审计记录用（审计操作者由写入服务按会话补全，此处只进描述性日志）
     */
    @Transactional
    public RecalculationView recalculateOnBaseDateChange(long patientId, Long operatorId) {
        PlanEnrollmentMapper.PatientStateRow patient = enrollmentMapper.findPatientState(patientId);
        if (patient == null) {
            throw new ApiException(404, 40400, "患者不存在或已删除");
        }
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        int enrollments = 0;
        int rescheduled = 0;
        int withdrawn = 0;
        StringBuilder before = new StringBuilder("[");
        StringBuilder after = new StringBuilder("[");
        for (FollowupTaskMapper.RecalcEnrollmentRow enrollment :
                taskMapper.findInGroupEnrollmentsByPatient(patientId)) {
            LocalDate resolved =
                    taskMapper.resolveStartDate(enrollment.planId(), enrollment.patientId());
            if (resolved == null || resolved.equals(enrollment.startDate())) {
                continue; // 不可解析（IMPLANT / CUSTOM）或未变化：幂等跳过
            }
            PlanMapper.PlanRow plan = planMapper.findById(enrollment.planId());
            List<PlanMapper.NodeRow> rows =
                    plan == null ? List.of() : planMapper.findNodesByPlanId(enrollment.planId());
            if (rows.isEmpty()) {
                continue; // 已发布计划必有节点（发布前置），防御跳过
            }
            LocalDate planEnd =
                    "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                            ? resolved.plusYears(plan.cycleYears())
                            : null;
            int[] counts = recalcTasks(enrollment.id(), resolved, rows, planEnd, today);
            rescheduled += counts[0];
            withdrawn += counts[1];
            taskMapper.updateStartDate(enrollment.id(), resolved);
            refreshNextCache(enrollment, rows, resolved, planEnd, today);
            enrollments++;
            appendAuditFragment(before, enrollment.id(), enrollment.startDate(), -1, -1);
            appendAuditFragment(after, enrollment.id(), resolved, counts[0], counts[1]);
        }
        if (enrollments == 0) {
            log.debug("起算日期变更重算：无入组需要更新 patient={}", patientId);
            return new RecalculationView(String.valueOf(patientId), 0, 0, 0);
        }
        before.append(']');
        after.append(']');
        audit.append(
                AuditEntry.operation("followup", "PLAN_START_DATE_RECALC")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "起算日期变更重算（A-FU-011）：更新入组起算快照 "
                                        + enrollments
                                        + " 条，未开始任务重排窗口 "
                                        + rescheduled
                                        + " 条、撤回为计划中 "
                                        + withdrawn
                                        + " 条（已完成与已有联系记录的任务不变）")
                        .beforeJson(before.toString())
                        .afterJson(after.toString())
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "起算日期变更重算完成 patient={} operator={} enrollments={} rescheduled={} withdrawn={}",
                patientId,
                operatorId,
                enrollments,
                rescheduled,
                withdrawn);
        return new RecalculationView(
                String.valueOf(patientId), enrollments, rescheduled, withdrawn);
    }

    /**
     * 重排一个入组的未开始任务（返回 [重排数, 撤回数]）：新开启日在今天之后 → 撤回（删行，调度到点重建）； 新计划日期超出管理周期边界 → 同样撤回； 否则五列日期整体重排（状态守卫
     * PENDING，并发进入联系后不覆盖）。节点缺失（配置防御）跳过该行。
     */
    private int[] recalcTasks(
            long enrollmentId,
            LocalDate newStart,
            List<PlanMapper.NodeRow> rows,
            LocalDate planEnd,
            LocalDate today) {
        List<TaskCreationPlanner.NodeDef> defs = FollowupTaskCreationService.nodeDefs(rows);
        int rescheduled = 0;
        int withdrawn = 0;
        for (FollowupTaskMapper.UnstartedTaskRow task :
                taskMapper.findUnstartedTasks(enrollmentId)) {
            PlanDateCalculator.NodeSchedule schedule =
                    TaskCreationPlanner.occurrenceScheduleOf(
                            defs, task.nodeId(), task.occurrence(), newStart);
            if (schedule == null) {
                continue; // 节点已不在该版本配置里 / 滚动节点（E21-T07：次数日期派生自随访，不随起算日期重算）：不动历史行
            }
            if ((planEnd != null && schedule.planDate().isAfter(planEnd))
                    || schedule.openDate().isAfter(today)) {
                withdrawn += taskMapper.withdrawTask(task.id());
                continue;
            }
            rescheduled +=
                    taskMapper.rescheduleTask(
                            task.id(),
                            schedule.planDate(),
                            schedule.remindStart(),
                            schedule.windowStart(),
                            schedule.windowEnd(),
                            schedule.openDate());
        }
        return new int[] {rescheduled, withdrawn};
    }

    /** 下次计划日期 / 开启日缓存实时按新起算重算（与每日调度同口径：首个开启日在今天之后的次数；滚动循环输入 E21-T07 起同口径装配）。 */
    private void refreshNextCache(
            FollowupTaskMapper.RecalcEnrollmentRow enrollment,
            List<PlanMapper.NodeRow> rows,
            LocalDate newStart,
            LocalDate planEnd,
            LocalDate today) {
        List<TaskCreationPlanner.NodeDef> defs = FollowupTaskCreationService.nodeDefs(rows);
        List<Long> rollingNodeIds =
                defs.stream()
                        .filter(
                                node ->
                                        "NODE".equals(node.nodeType())
                                                && "LAST_VISIT".equalsIgnoreCase(node.basis()))
                        .map(TaskCreationPlanner.NodeDef::id)
                        .toList();
        TaskCreationPlanner.LoopBasis loopBasis =
                rollingNodeIds.isEmpty()
                        ? TaskCreationPlanner.LoopBasis.none()
                        : FollowupTaskCreationService.loopBasis(
                                taskMapper.findLastVisitDate(enrollment.id()),
                                taskMapper.findTaskOccurrences(enrollment.id()),
                                rollingNodeIds);
        TaskCreationPlanner.PlanResult result =
                TaskCreationPlanner.plan(
                        defs,
                        enrollment.mode(),
                        newStart,
                        today,
                        enrollment.enrolledOn(),
                        planEnd,
                        loopBasis);
        taskMapper.updateNextCache(
                enrollment.id(),
                result.next() != null ? result.next().planDate() : null,
                result.next() != null ? result.next().openDate() : null);
    }

    /** 审计前后片段（rescheduled / withdrawn 传 -1 表示不记，before 只含旧快照）。 */
    private static void appendAuditFragment(
            StringBuilder json,
            long enrollmentId,
            LocalDate startDate,
            int rescheduled,
            int withdrawn) {
        if (json.length() > 1) {
            json.append(',');
        }
        json.append("{\"enrollmentId\":")
                .append(enrollmentId)
                .append(",\"startDate\":\"")
                .append(startDate)
                .append('"');
        if (rescheduled >= 0) {
            json.append(",\"rescheduled\":")
                    .append(rescheduled)
                    .append(",\"withdrawn\":")
                    .append(withdrawn);
        }
        json.append('}');
    }
}
