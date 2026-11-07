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
 * 每日 00:10 的任务按需创建作业体（E14-T04 / A-FU-001、C-FU-004、A-NFR-006、C-NFR-007；验收：任务在提醒开始日与 窗口开始日较早一天创建）、
 * 入组即建入口（E14-T05 / Q44，验收：已结束窗口照常建任务并标补录）与恢复补偿入口（E14-T06 / A 的 Q32、A-FU-008）。
 *
 * <p>结构（tech-plan §8「每日调度」流程图；E15-T01 起创建循环后并做逾期刷新）：
 *
 * <ul>
 *   <li>扇出、执行记录与补跑区间（上次成功日 + 1 至逻辑日）由 E02-T06 的 {@link JobScheduler} 承担（job_tenant_run， jobCode =
 *       {@value #JOB_CODE}）；单租户失败不影响其他租户；
 *   <li>followup 模块未开通的租户跳过（OQ-46②：E14 起模块作业按 openedModules 扇出过滤）；
 *   <li>每逻辑日遍历「已启动」入组（暂停 / 移除的不建，恢复补偿由 {@link #initializeForResumedEnrollment} 在恢复当天同步补建； 计划 PAUSED
 *       不建、被顶替 DISABLED 版本在组沿用继续建，OQ-76①；患者死亡 / 终止不再建，A-FU-007）， 用 {@link TaskCreationPlanner} 排期，
 *       逐次创建 PENDING 任务——幂等由 uk（入组 + 节点 + 第 N 次）保证，重复执行与停机补跑重放不重复创建；
 *   <li>停用补偿（E14-T06 / PLT-TEN-017、tech-plan §8）：补跑时窗口已在本次运行目标逻辑日前结束、 又未被按时创建的次数（租户停用 /
 *       作业未执行期间本应执行）补建为「停用期间未执行」（DISABLED_SKIPPED + SKIPPED_SUSPENDED）， 不发通知，由人工选择补做或关闭；
 *   <li>逾期刷新（E15-T01 / A-FU-010，tech-plan §8 步骤 W「刷新状态：逾期」）：创建循环结束后按本次运行目标逻辑日调 {@link
 *       FollowupTaskStatusService#refreshOverdue}——窗口结束日次日仍未完成（待随访 / 进行中，补录豁免）的任务置 OVERDUE 并写
 *       TASK_OVERDUE 事件（通知规则引擎 E09-T04 消费）；
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

    /** 任务状态：停用 / 暂停期间未执行（PLT-TEN-017、A 的 Q32；补做或关闭的流转随 E15-T01）。 */
    static final String STATUS_SKIPPED_SUSPENDED = "SKIPPED_SUSPENDED";

    /** 入组即建结果（E14-T05）：补录（已结束窗口）与当期（窗口已开启未结束）任务数，供入组审计并入。 */
    public record EnrollmentInitSummary(int backfill, int normal) {}

    /** 恢复补偿结果（E14-T06）：「暂停期间未执行」与当期（窗口未结束）任务数，供恢复审计并入。 */
    public record ResumeInitSummary(int pausedSkipped, int normal) {}

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskCreationService.class);

    private final JobScheduler jobScheduler;
    private final FollowupTaskMapper taskMapper;
    private final PlanMapper planMapper;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;
    private final FollowupTaskStatusService statusService;

    public FollowupTaskCreationService(
            JobScheduler jobScheduler,
            FollowupTaskMapper taskMapper,
            PlanMapper planMapper,
            OutboxPublisher outbox,
            AuditLogger audit,
            JdbcTemplate jdbc,
            FollowupTaskStatusService statusService) {
        this.jobScheduler = jobScheduler;
        this.taskMapper = taskMapper;
        this.planMapper = planMapper;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
        this.statusService = statusService;
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
            created += createForDate(date, logicalDate);
        }
        // 逾期刷新（E15-T01 / A-FU-010，tech-plan §8 步骤 W）：按本次运行目标逻辑日一次刷完（补跑逐日推进后统一按最终日判定，
        // 晚标记同样只发一次 TASK_OVERDUE 事件——状态守卫幂等）
        int overdueMarked = statusService.refreshOverdue(logicalDate);
        // 失访候选刷新（E15-T05 / A-FU-012，tech-plan §8 步骤 W「刷新状态：逾期 · 失访候选 · 跟进任务 · 统计事实表」）：
        // 窗口内不同日期连续 N 次「未联系到 / 号码错误」且窗口已结束 → 失访候选 + LOST_CANDIDATE 事件（状态守卫幂等）
        int lostMarked = statusService.refreshLostCandidates(logicalDate);
        log.info(
                "任务创建完成 tenant={} from={} to={} created={} overdueMarked={} lostMarked={}",
                tenant.tenantCode(),
                from,
                logicalDate,
                created,
                overdueMarked,
                lostMarked);
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

    /**
     * 一个逻辑日：遍历可调入组，单条失败记 WARN 继续（返回新建数）。 runDate = 本次运行的目标逻辑日（补跑时晚于逐日推进的
     * date），停用补偿按它判定「窗口在本次运行时点已结束」（E14-T06）。
     */
    private int createForDate(LocalDate logicalDate, LocalDate runDate) {
        int created = 0;
        for (FollowupTaskMapper.EnrollmentRow enrollment :
                taskMapper.findSchedulableEnrollments()) {
            try {
                created += processEnrollment(enrollment, logicalDate, runDate);
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

    /** 每日调度口径（入组前已过窗口不建，OQ-78③）；返回新建任务数（含停用补偿，E14-T06）。 */
    private int processEnrollment(
            FollowupTaskMapper.EnrollmentRow enrollment, LocalDate logicalDate, LocalDate runDate) {
        CreationCounts counts = initializeInternal(enrollment, logicalDate, runDate, null, false);
        return counts.total();
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
        CreationCounts counts =
                initializeInternal(
                        new FollowupTaskMapper.EnrollmentRow(
                                enrollmentId, patientId, planId, null, mode, today, null, null),
                        today,
                        today,
                        null,
                        true);
        return new EnrollmentInitSummary(counts.backfill(), counts.normal());
    }

    // ---------------------------------------------------------------- 恢复补偿（E14-T06 / A 的
    // Q32、A-FU-008）

    /**
     * 恢复当天同步创建任务（与恢复同一事务，由 {@code PlanEnrollmentService.resume} 调用）：
     *
     * <ul>
     *   <li>暂停区间 [pausedFrom, 今天] 内本应开启且窗口已结束的次数：补建为「暂停期间未执行」（create_type = PAUSED_SKIPPED、状态
     *       SKIPPED_SUSPENDED，由医生选择补做或关闭，Q32），不写 TaskOpened 事件；
     *   <li>窗口已开启未结束的次数：当天正常创建（NORMAL，「恢复后剩余子计划重新开启」）并照常写 TaskOpened；
     *   <li>未来次数不预生成，只刷新下次计划日期 / 开启日缓存；开启日早于暂停起始且窗口已结束的次数按停用补偿口径 （DISABLED_SKIPPED）兜底，不属本次暂停。
     * </ul>
     *
     * <p>幂等由 uk（入组 + 节点 + 第 N 次）保证：恢复补偿与之后的每日调度重放不重复创建。
     */
    public ResumeInitSummary initializeForResumedEnrollment(
            long enrollmentId, long patientId, long planId, String mode, LocalDate pausedFrom) {
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        FollowupTaskMapper.EnrollmentRow row =
                new FollowupTaskMapper.EnrollmentRow(
                        enrollmentId, patientId, planId, null, mode, today, pausedFrom, today);
        CreationCounts counts = initializeInternal(row, today, today, pausedFrom, false);
        return new ResumeInitSummary(counts.skipped(), counts.normal());
    }

    /**
     * 单入组的排期与建任务（各同步入口与每日调度共用；runDate = 本次运行目标逻辑日，停用补偿判定用；atEnrollment = 入组即建口径；resumePausedFrom 非空
     * = 恢复补偿口径）。
     */
    private CreationCounts initializeInternal(
            FollowupTaskMapper.EnrollmentRow enrollment,
            LocalDate logicalDate,
            LocalDate runDate,
            LocalDate resumePausedFrom,
            boolean atEnrollment) {
        LocalDate startDate =
                enrollment.startDate() != null
                        ? enrollment.startDate()
                        : resolveAndBackfillStartDate(enrollment);
        if (startDate == null) {
            log.debug(
                    "起算日期不可解析（IMPLANT_DATE 随 E21、CUSTOM 未定义或字段值为空），入组暂不建任务 enrollment={}",
                    enrollment.id());
            return new CreationCounts(0, 0, 0);
        }
        PlanMapper.PlanRow plan = planMapper.findById(enrollment.planId());
        List<PlanMapper.NodeRow> rows =
                plan == null ? List.of() : planMapper.findNodesByPlanId(enrollment.planId());
        if (rows.isEmpty()) {
            return new CreationCounts(0, 0, 0); // 已发布计划必有节点（发布前置），防御跳过
        }
        LocalDate planEnd =
                "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                        ? startDate.plusYears(plan.cycleYears())
                        : null;
        PlanResult result;
        if (resumePausedFrom != null) {
            result =
                    TaskCreationPlanner.planForResume(
                            nodeDefs(rows),
                            enrollment.mode(),
                            startDate,
                            logicalDate,
                            resumePausedFrom,
                            planEnd);
        } else if (atEnrollment) {
            result =
                    TaskCreationPlanner.planForEnrollment(
                            nodeDefs(rows), enrollment.mode(), startDate, logicalDate, planEnd);
        } else {
            result =
                    TaskCreationPlanner.plan(
                            nodeDefs(rows),
                            enrollment.mode(),
                            startDate,
                            logicalDate,
                            enrollment.enrolledOn(),
                            planEnd);
        }
        return createPlannedTasks(enrollment, result, rows, logicalDate, runDate);
    }

    /**
     * 按排期结果落任务行（各入口共用）：
     *
     * <ul>
     *   <li>幂等：已存在（重复执行 / 补跑重放）跳过；并发撞 uk 按已存在处理；
     *   <li>停用补偿改判（E14-T06 / PLT-TEN-017）：runDate 非空时，排期为 NORMAL 但窗口在本次运行目标逻辑日前已结束的次数
     *       ——本应在开启日创建而未创建（租户停用 / 作业未执行期间）——改建为「停用期间未执行」（DISABLED_SKIPPED +
     *       SKIPPED_SUSPENDED）；窗口结束日落在此前暂停区间内的归「暂停期间未执行」（PAUSED_SKIPPED）。 入组即建口径（runDate
     *       与逻辑日同为入组日）不会有窗口已结束的 NORMAL 次数，改判天然不生效；
     *   <li>只有 NORMAL 写 TaskOpened 事件（补录 / 暂停期间未执行 / 停用期间未执行不进通知，A 规格第 5 章）。
     * </ul>
     */
    private CreationCounts createPlannedTasks(
            FollowupTaskMapper.EnrollmentRow enrollment,
            PlanResult result,
            List<PlanMapper.NodeRow> rows,
            LocalDate logicalDate,
            LocalDate runDate) {
        FollowupTaskMapper.PatientOwnerRow owner =
                taskMapper.findOwnerContext(enrollment.patientId());
        Map<Long, PlanMapper.NodeRow> templates =
                rows.stream()
                        .collect(Collectors.toMap(PlanMapper.NodeRow::id, Function.identity()));
        int normal = 0;
        int backfill = 0;
        int skipped = 0;
        for (PlannedTask task : result.due()) {
            // 幂等：已存在（重复执行 / 补跑重放）直接跳过；并发撞 uk 由唯一键兜底转已存在
            if (taskMapper.findTaskId(enrollment.id(), task.nodeId(), task.occurrence()) != null) {
                continue;
            }
            PlanMapper.NodeRow template = templates.get(task.nodeId());
            if (template == null) {
                continue;
            }
            String createType = effectiveCreateType(enrollment, task, runDate);
            String status =
                    TaskCreationPlanner.CREATE_NORMAL.equals(createType)
                            ? "PENDING"
                            : TaskCreationPlanner.CREATE_BACKFILL.equals(createType)
                                    ? "PENDING"
                                    : STATUS_SKIPPED_SUSPENDED;
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
                            createType,
                            status,
                            0, // followup_seq：调度创建的都是常规任务（未联系到跟进随 E15-T05 写沟通记录时创建）
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
            switch (createType) {
                case TaskCreationPlanner.CREATE_NORMAL -> {
                    normal++;
                    publishTaskOpened(enrollment, task, row.getId());
                }
                case TaskCreationPlanner.CREATE_BACKFILL -> backfill++;
                default -> skipped++; // 暂停 / 停用期间未执行：不发 TaskOpened（不进通知）
            }
        }
        taskMapper.updateNextCache(
                enrollment.id(),
                result.next() != null ? result.next().planDate() : null,
                result.next() != null ? result.next().openDate() : null);
        return new CreationCounts(normal, backfill, skipped);
    }

    /**
     * 停用 / 暂停补偿的改判（E14-T06）：排期为 NORMAL 但窗口已在本次运行目标逻辑日前结束 → 「未执行」补偿任务； 窗口结束日落在此前暂停区间内归
     * PAUSED_SKIPPED（患者暂停），否则 DISABLED_SKIPPED（租户停用 / 作业未执行，PLT-TEN-017）。 排期已给出的补偿 / 补录类型不改。
     */
    private static String effectiveCreateType(
            FollowupTaskMapper.EnrollmentRow enrollment, PlannedTask task, LocalDate runDate) {
        if (!TaskCreationPlanner.CREATE_NORMAL.equals(task.createType())) {
            return task.createType();
        }
        if (runDate == null || !task.windowEnd().isBefore(runDate)) {
            return task.createType();
        }
        return pauseCoversWindowEnd(enrollment, task.windowEnd())
                ? TaskCreationPlanner.CREATE_PAUSED_SKIPPED
                : TaskCreationPlanner.CREATE_DISABLED_SKIPPED;
    }

    /** 窗口结束日是否落在此前暂停区间 [pause_from, pause_to] 内（最近一次区间，V46 单区间口径；未暂停恒 false）。 */
    private static boolean pauseCoversWindowEnd(
            FollowupTaskMapper.EnrollmentRow enrollment, LocalDate windowEnd) {
        return enrollment.pauseFrom() != null
                && !windowEnd.isBefore(enrollment.pauseFrom())
                && (enrollment.pauseTo() == null || !windowEnd.isAfter(enrollment.pauseTo()));
    }

    /** 一次排期落库的分类计数（normal 当期 / backfill 补录 / skipped 暂停或停用期间未执行）。 */
    record CreationCounts(int normal, int backfill, int skipped) {

        int total() {
            return normal + backfill + skipped;
        }
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

    /**
     * 存储行 → 排期输入（null 列回落 PlanNodeRule 的默认值：基准起算、单位 YEAR/DAY、数值 0）； E14-T07
     * 重算入口（同包）与历史导入年度排期（E18-T05 importer app）共用。
     */
    public static List<NodeDef> nodeDefs(List<PlanMapper.NodeRow> rows) {
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
