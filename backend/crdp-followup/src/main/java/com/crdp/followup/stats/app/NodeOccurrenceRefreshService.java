package com.crdp.followup.stats.app;

import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.EnrollmentInput;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.FactRow;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.FloatingRecord;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.PlanVersion;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.RecordInput;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.StatusPoint;
import com.crdp.followup.stats.domain.NodeOccurrenceFactCalculator.TaskInput;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceInsertRow;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 节点随访事实表每日刷新作业体（E19-T01 / A-DASH-012；tasks.csv 验收「按节点出应随访、已随访数」）。
 *
 * <p>tech-plan §8 步骤 W「刷新状态：……统计事实表」与 §13：实时查询决策下，真正难算的是随访率分母
 * （窗口开始日仍符合条件），因此在写入侧维护结构化事实表、看板只做聚合。本任务落<b>每日刷新</b>：
 *
 * <ul>
 *   <li>由 {@code FollowupTaskScheduler}（crdp-server）在每日 00:10 任务创建与状态刷新之后顺序调用 （事实行依赖当天新建 /
 *       刷新的任务），作业编码 {@value #JOB_CODE}（job_tenant_run 按日幂等， 重复执行无副作用）；
 *   <li>扇出与单租户失败隔离由 {@link JobScheduler} 承担；followup 模块未开通的租户跳过（OQ-46②）；
 *   <li>每租户整表重建（DELETE + 批量 INSERT 同一事务）：数据量小（约 5000 患者 × 各节点次数）， 全量重算天然覆盖任务创建、窗口结束、记录提交 /
 *       删除、补录、状态与死亡日期修正等全部变化来源 （tech-plan §13「何时维护」列的各触发点），并修复任何漂移；
 *   <li>事实行计算是纯函数 {@link NodeOccurrenceFactCalculator}（口径 Q38，单测锁定）；写入的行 供 {@link
 *       NodeOccurrenceStatsService} 按节点聚合（应随访 / 已随访数）。
 * </ul>
 *
 * <p>统计时点：每日刷新到当日逻辑日；当日完成 / 补录在次日 00:10 前以最近一次刷新为准（E19-T04 卡片 如需实时再按需加单患者重算入口，暂行口径见 OQ-106）。刷新写一条汇总
 * OPERATION 审计（派生数据重建， 不逐行写审计）。
 */
@Service
public class NodeOccurrenceRefreshService {

    /** job_tenant_run 作业编码（按日幂等键成分）。 */
    static final String JOB_CODE = "st-node-refresh";

    private static final Logger log = LoggerFactory.getLogger(NodeOccurrenceRefreshService.class);

    private final JobScheduler jobScheduler;
    private final NodeOccurrenceMapper occurrenceMapper;
    private final PlanMapper planMapper;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public NodeOccurrenceRefreshService(
            JobScheduler jobScheduler,
            NodeOccurrenceMapper occurrenceMapper,
            PlanMapper planMapper,
            AuditLogger audit,
            JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this.jobScheduler = jobScheduler;
        this.occurrenceMapper = occurrenceMapper;
        this.planMapper = planMapper;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    // ---------------------------------------------------------------- 作业入口（定时注册在 crdp-server）

    /** 对全部启用 / 试用 / 宽限期租户刷新到逻辑日（每日重建幂等，补跑区间无额外意义——整表按最新数据重算）。 单租户失败不影响其他租户（框架保证）。 */
    public List<TenantJobResult> refreshForDate(LocalDate logicalDate) {
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(JOB_CODE, logicalDate, this::refreshCurrentTenant);
        long failed = results.stream().filter(result -> !result.success()).count();
        if (failed > 0) {
            log.warn("节点事实表刷新作业有失败租户 count={} 详情={}", failed, results);
        }
        return results;
    }

    /** 租户上下文内的作业体：模块闸门 → 整表重算（同一事务）→ 汇总审计。 */
    private void refreshCurrentTenant(LocalDate asOf) {
        TenantContext.State tenant = TenantContext.require();
        if (!followupModuleOpen(tenant.tenantId())) {
            log.info("followup 模块未开通，本租户跳过节点事实表刷新 tenant={}", tenant.tenantCode());
            return;
        }
        Integer written =
                transactions.execute(
                        status -> {
                            List<NodeOccurrenceInsertRow> computed = computeAll(asOf);
                            occurrenceMapper.deleteAll();
                            computed.forEach(occurrenceMapper::insertFact);
                            return computed.size();
                        });
        int rows = written != null ? written : 0;
        log.info("节点事实表刷新完成 tenant={} asOf={} rows={}", tenant.tenantCode(), asOf, rows);
        if (rows > 0) {
            audit.append(
                    AuditEntry.operation("followup", "STATS_NODE_REFRESH")
                            .objectType("STATS")
                            .description("每日刷新节点随访事实表 " + rows + " 行（自动）")
                            .afterJson("{\"rows\":" + rows + ",\"asOf\":\"" + asOf + "\"}")
                            .result(AuditResult.SUCCESS)
                            .build());
        }
    }

    // ---------------------------------------------------------------- 整表重算

    /** 全部入组逐条计算（计划节点按计划版本缓存；数据量级见类注释，整租户一次载入）。 */
    private List<NodeOccurrenceInsertRow> computeAll(LocalDate asOf) {
        List<NodeOccurrenceMapper.EnrollmentRow> enrollments =
                occurrenceMapper.findAllEnrollments();
        if (enrollments.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TaskInput>> tasksByEnrollment = new HashMap<>();
        for (NodeOccurrenceMapper.TaskRow task : occurrenceMapper.findRegularTasks()) {
            tasksByEnrollment
                    .computeIfAbsent(task.enrollmentId(), key -> new ArrayList<>())
                    .add(
                            new TaskInput(
                                    task.taskId(),
                                    task.recordId(),
                                    task.nodeId(),
                                    task.occurrence(),
                                    task.planDate(),
                                    task.windowStart(),
                                    task.windowEnd(),
                                    task.createType(),
                                    task.status(),
                                    task.completeKind()));
        }
        Map<Long, List<RecordInput>> recordsByEnrollment = new HashMap<>();
        for (NodeOccurrenceMapper.AnchoredRecordRow record :
                occurrenceMapper.findAnchoredRecords()) {
            recordsByEnrollment
                    .computeIfAbsent(record.enrollmentId(), key -> new ArrayList<>())
                    .add(
                            new RecordInput(
                                    record.recordId(),
                                    record.nodeId(),
                                    record.occurrence(),
                                    record.source(),
                                    record.status(),
                                    record.visitDate()));
        }
        Map<Long, List<FloatingRecord>> floatingByPatient = new HashMap<>();
        for (NodeOccurrenceMapper.FloatingRecordRow floating :
                occurrenceMapper.findFloatingBackfills()) {
            floatingByPatient
                    .computeIfAbsent(floating.patientId(), key -> new ArrayList<>())
                    .add(new FloatingRecord(floating.recordId(), floating.visitDate()));
        }
        Map<Long, List<StatusPoint>> statusByPatient = new HashMap<>();
        for (NodeOccurrenceMapper.StatusPointRow point : occurrenceMapper.findStatusHistory()) {
            statusByPatient
                    .computeIfAbsent(point.patientId(), key -> new ArrayList<>())
                    .add(new StatusPoint(point.status(), point.effectiveDate()));
        }
        Map<Long, PlanMapper.PlanRow> planById = new HashMap<>();
        Map<Long, List<NodeDef>> nodesByPlan = new HashMap<>();
        List<NodeOccurrenceInsertRow> result = new ArrayList<>();
        for (NodeOccurrenceMapper.EnrollmentRow enrollment : enrollments) {
            if (enrollment.startDate() == null) {
                continue; // 起算日期不可解析：与任务调度同口径跳过
            }
            PlanMapper.PlanRow plan =
                    planById.computeIfAbsent(
                            enrollment.planId(), planId -> planMapper.findById(planId));
            List<NodeDef> nodes =
                    nodesByPlan.computeIfAbsent(
                            enrollment.planId(),
                            planId ->
                                    FollowupTaskCreationService.nodeDefs(
                                            planMapper.findNodesByPlanId(planId)));
            if (plan == null || nodes.isEmpty()) {
                continue; // 防御：计划版本或节点缺失（配置异常）跳过该入组
            }
            LocalDate planEnd =
                    "YEARS".equals(plan.cycleType()) && plan.cycleYears() != null
                            ? enrollment.startDate().plusYears(plan.cycleYears())
                            : null;
            for (FactRow fact :
                    NodeOccurrenceFactCalculator.compute(
                            nodes,
                            new PlanVersion(plan.id(), plan.code(), plan.version()),
                            new EnrollmentInput(
                                    enrollment.id(),
                                    enrollment.patientId(),
                                    enrollment.planId(),
                                    enrollment.startDate(),
                                    enrollment.mode(),
                                    enrollment.enrolledOn()),
                            tasksByEnrollment.getOrDefault(enrollment.id(), List.of()),
                            recordsByEnrollment.getOrDefault(enrollment.id(), List.of()),
                            floatingByPatient.getOrDefault(enrollment.patientId(), List.of()),
                            statusByPatient.getOrDefault(enrollment.patientId(), List.of()),
                            planEnd,
                            asOf)) {
                result.add(toInsertRow(fact, asOf));
            }
        }
        return result;
    }

    /** 领域行 → 插入行（字段一一对应，V76）。 */
    private static NodeOccurrenceInsertRow toInsertRow(FactRow fact, LocalDate asOf) {
        return new NodeOccurrenceInsertRow(
                fact.patientId(),
                fact.enrollmentId(),
                fact.planId(),
                fact.planCode(),
                fact.planVersion(),
                fact.nodeId(),
                fact.occurrence(),
                fact.followupYear(),
                fact.planDate(),
                fact.windowStart(),
                fact.windowEnd(),
                fact.cohortYear(),
                fact.due(),
                fact.dueReason(),
                fact.taskId(),
                fact.recordId(),
                fact.followed(),
                fact.completeKind(),
                fact.inWindow(),
                fact.lost(),
                fact.deathConfirmed(),
                asOf);
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
}
