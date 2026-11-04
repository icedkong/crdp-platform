package com.crdp.followup.task.app;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlanResult;
import com.crdp.followup.task.domain.TaskCreationPlanner.PlannedTask;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 每日 00:10 的任务按需创建作业体（E14-T04 / A-FU-001、C-FU-004、A-NFR-006、C-NFR-007；验收：任务在提醒开始日与 窗口开始日较早一天创建）
 * 与入组即建入口（E14-T05 / Q44，验收：已结束窗口照常建任务并标补录）。
 *
 * <p>结构（tech-plan §8「每日调度」流程图）：
 *
 * <ul>
 *   <li>扇出、执行记录与补跑区间（上次成功日 + 1 至逻辑日）由 E02-T06 的 {@link JobScheduler} 承担（job_tenant_run， jobCode =
 *       {@value #JOB_CODE}）；单租户失败不影响其他租户；
 *   <li>followup 模块未开通的租户跳过（OQ-46②：E14 起模块作业按 openedModules 扇出过滤）；
 *   <li>每逻辑日遍历「已启动」入组（暂停 / 移除的不建，恢复补偿随 E14-T06；计划 PAUSED 不建、被顶替 DISABLED 版本在组沿用继续建，OQ-76①；患者死亡 /
 *       终止不再建，A-FU-007），用 {@link TaskCreationPlanner} 排期， 逐次创建 PENDING 任务——幂等由 uk（入组 + 节点 + 第 N
 *       次）保证，重复执行与停机补跑重放不重复创建；
 *   <li>新建任务写 {@code TaskOpened} 事件到租户库 outbox（消费方随 E15 通知落地，无人订阅保持 PENDING，OQ-60③）；
 *   <li>刷新入组的下次计划日期 / 开启日缓存（fu_enrollment.next_plan_date / next_open_date，V46 预留）。
 * </ul>
 *
 * <p>{@link #initializeForNewEnrollment}（E14-T05）：入组当天同步创建补录（BACKFILL）与当期（NORMAL）任务，
 * 与入组同一事务（建不出来就整个入组回滚，不留「在组无任务」的半状态）；补录任务<b>不写</b> {@code TaskOpened} 事件（不进即将逾期 / 已逾期 / 失访候选通知，A
 * 规格第 5 章；状态流转侧 E15-T01 按 create_type 同样豁免）。
 *
 * <p>单条入组失败只记 WARN 并继续（起算日期不可解析等配置问题不应拖垮整院当日调度）； 全租户汇总创建数写一条 OPERATION 审计（任务行本身即业务记录，不逐条写审计）。负责人解析按
 * A-FU-009 回落链： 责任医生 → 病区默认负责人 （OQ-60）→ NULL 进病区待认领池。
 */
@Service
public class FollowupTaskCreationService {

    /** job_tenant_run 作业编码（按日幂等键成分）。 */
    static final String JOB_CODE = "fu-task-create";

    /** 任务开启 outbox 事件类型（E15 通知规则引擎消费，OQ-60②）。 */
    static final String EVENT_TASK_OPENED = "TaskOpened";

    /** 入组即建结果（E14-T05）：补录（已结束窗口）与当期（窗口已开启未结束）任务数，供入组审计并入。 */
    public record EnrollmentInitSummary(int backfill, int normal) {}

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskCreationService.class);

    private final JobScheduler jobScheduler;
    private final FollowupTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;

    public FollowupTaskCreationService(
            JobScheduler jobScheduler,
            FollowupTaskMapper taskMapper,
            PlanMapper planMapper,
            OutboxPublisher outbox,
            AuditLogger audit,
            JdbcTemplate jdbc) {
        this.jobScheduler = jobScheduler;
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 作业入口（定时注册在 crdp-server）

    /** 对全部启用 / 试用 / 宽限期租户执行到逻辑日（含补跑：每租户从上次成功日 + 1 逐日推进到逻辑日）。 单租户失败不影响其他租户（框架保证）。 */
    public List<TenantJobResult> runForDate(LocalDate logicalDate) {
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(JOB_CODE, logicalDate, this::runForCurrentTenant);
        long failed = results.stream().filter(result -> !result.success()).count();
        if (failed > 0) {
            log.warn("任务创建作业有失败租户 count={} 详情={}", failed, results);
        }
        return results;
    }

    /** 租户上下文内的作业体：模块闸门 → 补跑区间逐日创建 → 汇总审计。 */
    private void runForCurrentTenant(LocalDate logicalDate) {
        TenantContext.State tenant = TenantContext.require();
        if (!followupModuleOpen(tenant.tenantId())) {
            log.info("followup 模块未开通，本租户跳过任务创建 tenant={}", tenant.tenantCode());
            return;
        }
        // job_tenant_run 属租户库（ADR 0003），lastSuccessDate 须在租户上下文里读；当天已写 RUNNING 不影响取上次成功日
        LocalDate from =
                jobScheduler
                        .lastSuccessDate(JOB_CODE)
                        .map(lastSuccess -> lastSuccess.plusDays(1))
                        .orElse(logicalDate);
        int created = 0;
        for (LocalDate date = from; !date.isAfter(logicalDate); date = date.plusDays(1)) {
            created += createForDate(date);
        }
        log.info(
                "任务创建完成 tenant={} from={} to={} created={}",
                tenant.tenantCode(),
                from,
                logicalDate,
                created);
        if (created > 0) {
            audit.append(
                    AuditEntry.operation("followup", "TASK_CREATE")
                            .objectType("TASK")
                            .description(
                                    "每日调度按需创建随访任务 "
                                            + created
                                            + " 条（补跑区间 "
                                            + from
                                            + " 至 "
                                            + logicalDate
                                            + "，自动）")
                            .afterJson("{\"created\":" + created + ",\"from\":\"" + from + "\"}")
                            .result(AuditResult.SUCCESS)
                            .build());
        }
    }

    /** 一个逻辑日：遍历可调入组，单条失败记 WARN 继续（返回新建数）。 */
    private int createForDate(LocalDate logicalDate) {
        int created = 0;
        for (FollowupTaskMapper.EnrollmentRow enrollment :
                taskMapper.findSchedulableEnrollments()) {
            try {
                created += processEnrollment(enrollment, logicalDate);
            } catch (RuntimeException ex) {
                log.warn(
                        "入组任务创建失败，跳过该入组继续 enrollment={} plan={} 原因={}",
                        enrollment.id(),
                        enrollment.planId(),
                        ex.getMessage());
            }
        }
        return created;
    }

    // ---------------------------------------------------------------- 单入组处理

    /** 每日调度口径（入组前已过窗口不建，OQ-78③）；返回新建任务数（创建方式恒 NORMAL）。 */
    private int processEnrollment(
            FollowupTaskMapper.EnrollmentRow enrollment, LocalDate logicalDate) {
        EnrollmentInitSummary summary = initializeForLogicalDate(enrollment, logicalDate, false);
        return summary.backfill() + summary.normal();
    }

    // ---------------------------------------------------------------- 入组即建（E14-T05 / Q44）

    /**
     * 入组当天同步创建任务（与入组同一事务，由 {@code PlanEnrollmentService.enroll} 调用）：
     *
     * <ul>
     *   <li>窗口在入组日前已结束的次数：按节点 passed_window_action 补建为「补录」任务（create_type = BACKFILL， Q44）或跳过（SKIP /
     *       未配置，医院 C 的 Q14）；补录任务不写 TaskOpened 事件（不发通知）；
     *   <li>窗口已开启未结束的次数：当天正常创建（NORMAL）并写 TaskOpened（A 规格第 5 章「窗口已开启未结束的节点当天正常创建」）；
     *   <li>未来次数不预生成，只刷新下次计划日期 / 开启日缓存；起算日期快照为空时解析并回填（与每日调度同口径）。
     * </ul>
     *
     * <p>历史导入（E18）不走本入口（导入按自身规则生成历史记录，不建补录任务）。任务创建失败直接抛出：整个入组回滚，
     * 避免留下「在组但缺补录任务」的半状态（每日调度不补入组前已过窗口，OQ-78③）。
     */
    public EnrollmentInitSummary initializeForNewEnrollment(
            long enrollmentId, long patientId, long planId, String mode) {
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        return initializeForLogicalDate(
                new FollowupTaskMapper.EnrollmentRow(
                        enrollmentId, patientId, planId, null, mode, today),
                today,
                true);
    }

    /** 单入组的排期与建任务（每日调度与入组即建共用；atEnrollment = true 用入组即建口径分类补录 / 当期）。 */
    private EnrollmentInitSummary initializeForLogicalDate(
            FollowupTaskMapper.EnrollmentRow enrollment,
            LocalDate logicalDate,
            boolean atEnrollment) {
        LocalDate startDate =
                enrollment.startDate() != null
                        ? enrollment.startDate()
                        : resolveAndBackfillStartDate(enrollment);
        if (startDate == null) {
            log.debug(
                    "起算日期不可解析（IMPLANT_DATE 随 E21、CUSTOM 未定义或字段值为空），入组暂不建任务 enrollment={}",
                    enrollment.id());
            return new EnrollmentInitSummary(0, 0);
        }
        PlanMapper.PlanRow plan = planMapper.findById(enrollment.planId());
        List<PlanMapper.NodeRow> rows =
                plan == null ? List.of() : planMapper.findNodesByPlanId(enrollment.planId());
        if (rows.isEmpty()) {
            return new EnrollmentInitSummary(0, 0); // 已发布计划必有节点（发布前置），防御跳过
        }
        LocalDate planEnd =
                "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                        ? startDate.plusYears(plan.cycleYears())
                        : null;
        PlanResult result =
                atEnrollment
                        ? TaskCreationPlanner.planForEnrollment(
                                nodeDefs(rows), enrollment.mode(), startDate, logicalDate, planEnd)
                        : TaskCreationPlanner.plan(
                                nodeDefs(rows),
                                enrollment.mode(),
                                startDate,
                                logicalDate,
                                enrollment.enrolledOn(),
                                planEnd);
        FollowupTaskMapper.PatientOwnerRow owner =
                taskMapper.findOwnerContext(enrollment.patientId());
        Map<Long, PlanMapper.NodeRow> templates =
                rows.stream()
                        .collect(Collectors.toMap(PlanMapper.NodeRow::id, Function.identity()));
        int backfill = 0;
        int normal = 0;
        for (PlannedTask task : result.due()) {
            // 幂等：已存在（重复执行 / 补跑重放）直接跳过；并发撞 uk 由唯一键兜底转已存在
            if (taskMapper.findTaskId(enrollment.id(), task.nodeId(), task.occurrence()) != null) {
                continue;
            }
            PlanMapper.NodeRow template = templates.get(task.nodeId());
            if (template == null) {
                continue;
            }
            FollowupTaskInsertRow row =
                    new FollowupTaskInsertRow(
                            enrollment.id(),
                            enrollment.patientId(),
                            enrollment.planId(),
                            task.nodeId(),
                            task.occurrence(),
                            template.taskType(),
                            template.formCode(),
                            template.ownerType(),
                            resolveAssignee(template.ownerType(), owner),
                            task.planDate(),
                            task.remindStart(),
                            task.windowStart(),
                            task.windowEnd(),
                            task.openDate(),
                            task.createType(),
                            "PENDING",
                            logicalDate);
            try {
                taskMapper.insertTask(row);
            } catch (DuplicateKeyException race) {
                // 并发重复（同一逻辑日重入执行）：uk 兜底，按已存在处理
                log.debug(
                        "任务并发重复创建，按已存在处理 enrollment={} node={} occurrence={}",
                        enrollment.id(),
                        task.nodeId(),
                        task.occurrence());
                continue;
            }
            if (TaskCreationPlanner.CREATE_BACKFILL.equals(task.createType())) {
                backfill++; // 补录不发 TaskOpened（不进即将逾期 / 已逾期 / 失访候选通知）
            } else {
                normal++;
                publishTaskOpened(enrollment, task, row.getId());
            }
        }
        taskMapper.updateNextCache(
                enrollment.id(),
                result.next() != null ? result.next().planDate() : null,
                result.next() != null ? result.next().openDate() : null);
        return new EnrollmentInitSummary(backfill, normal);
    }

    /** 起算日期快照为空：按计划起算字段解析并回填（只补空值；解析不到返回 null 跳过该入组）。 */
    private LocalDate resolveAndBackfillStartDate(FollowupTaskMapper.EnrollmentRow enrollment) {
        LocalDate resolved =
                taskMapper.resolveStartDate(enrollment.planId(), enrollment.patientId());
        if (resolved != null) {
            taskMapper.backfillStartDate(enrollment.id(), resolved);
        }
        return resolved;
    }

    /** A-FU-009 回落链：责任医生 → 病区默认负责人（OQ-60）→ NULL（病区待认领池）。 */
    private static Long resolveAssignee(
            String ownerType, FollowupTaskMapper.PatientOwnerRow owner) {
        if (owner == null) {
            return null;
        }
        if ("DOCTOR".equals(ownerType)) {
            return owner.responsibleUserId() != null
                    ? owner.responsibleUserId()
                    : owner.wardDefaultOwnerId();
        }
        return owner.wardDefaultOwnerId();
    }

    private void publishTaskOpened(
            FollowupTaskMapper.EnrollmentRow enrollment, PlannedTask task, long taskId) {
        // 消费方（E15 通知规则引擎）落地前事件保持 PENDING（OQ-60③：无人订阅不消费、不丢失）
        outbox.publish(
                EVENT_TASK_OPENED,
                "{\"taskId\":"
                        + taskId
                        + ",\"enrollmentId\":"
                        + enrollment.id()
                        + ",\"patientId\":"
                        + enrollment.patientId()
                        + ",\"planId\":"
                        + enrollment.planId()
                        + ",\"nodeId\":"
                        + task.nodeId()
                        + ",\"occurrence\":"
                        + task.occurrence()
                        + ",\"openDate\":\""
                        + task.openDate()
                        + "\",\"windowStart\":\""
                        + task.windowStart()
                        + "\",\"windowEnd\":\""
                        + task.windowEnd()
                        + "\"}");
    }

    /** followup 模块是否对该租户开通（plt_tenant_module，平台库；OQ-46② 扇出过滤）。 */
    private boolean followupModuleOpen(long tenantId) {
        Integer open =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM plt_tenant_module"
                                                + " WHERE tenant_id = ? AND module_code = 'followup'"
                                                + " AND status = 'OPEN'",
                                        Integer.class,
                                        tenantId));
        return open != null && open > 0;
    }

    /** 存储行 → 排期输入（null 列回落 PlanNodeRule 的默认值：基准起算、单位 YEAR/DAY、数值 0）。 */
    private static List<NodeDef> nodeDefs(List<PlanMapper.NodeRow> rows) {
        return rows.stream()
                .map(
                        row ->
                                new NodeDef(
                                        row.id(),
                                        row.parentId(),
                                        row.nodeType(),
                                        row.repeatMode(),
                                        row.repeatCount(),
                                        row.cycleValue(),
                                        row.cycleUnit(),
                                        defaultIfBlank(row.basis(), "PLAN_START"),
                                        row.enrollMode(),
                                        row.passedWindowAction(),
                                        orZero(row.offsetValue()),
                                        defaultIfBlank(row.offsetUnit(), "YEAR"),
                                        orZero(row.remindValue()),
                                        defaultIfBlank(row.remindUnit(), "DAY"),
                                        orZero(row.windowBeforeValue()),
                                        defaultIfBlank(row.windowBeforeUnit(), "DAY"),
                                        orZero(row.windowAfterValue()),
                                        defaultIfBlank(row.windowAfterUnit(), "DAY")))
                .toList();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
