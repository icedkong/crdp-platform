package com.crdp.followup.report.app;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.ScanRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 模板调整后的位置退回作业（E16-T15 / A-ATT-021、C-SCAN-021）。
 *
 * <p>验收原文：分节删除后位置退回到分类或整条记录，锚点不存在时移除并提示「原位置已调整」，均写操作日志； 重复执行结果不变。
 *
 * <ul>
 *   <li>巡检口径（与 E16-T02/T13 的锚点校验同一依据）：基线分类对基线表单<b>当前发布版</b>、随访分组对<b>该记录固化版本</b>的模板分类清单。
 *       当前数据模型里位置粒度三种（基线分类、随访整条、随访分组——表单模板只有分类一级，基线分类即基线的 最细粒度），所以退回链是：随访分组 →
 *       整条记录（有上一级可退）；基线分类已不在当前发布版 / 随访记录不存在（无上一级可退）→ 移除该位置， 失去全部位置的报告单进入「未指定位置」（A-ATT-020）。
 *   <li>退回：位置行锚点清空（anchor NULL = 整条）并置 adjusted = 1（位置级「原位置已调整」，V63 列， 前端 reportLocationText
 *       追加提示）；分组退回时整条位置已在册（同一报告单同一位置唯一）则移除分组行、既有整条行置 adjusted = 1。移除与退回都置报告单级 location_adjusted =
 *       1（V66，患者内清单行带出「原位置已调整」）—— 用户经修改位置 add 重新指定后清 0（E16-T13 接口）。
 *   <li>操作日志：每个有变更的报告单写一条 {@code RPT_LOCATION_FALLBACK}（module=followup，objectType=REPORT， 谁 =
 *       作业自动执行、何时、退回 / 移除了哪个位置，before / after 留位置四元组快照，与 RPT_LOCATION_CHANGE 同形）。
 *   <li>幂等（重复执行结果不变）：巡检按「锚点当前是否仍存在」判定，退回后的整条行与移除后的空位不再命中； 乐观锁 version 只在有变更的报告单上 +1（与 E16-T13
 *       同口径，作业无操作人）。基线模板或记录固化版本快照读不出（防御：正常运营不会发生）时 该批行跳过并 WARN，不做无依据的退回。
 *   <li>扇出与执行记录（job_tenant_run，ADR 0003）由 {@link JobScheduler} 承担（jobCode = {@value
 *       #JOB_CODE}）；followup 模块未开通的租户跳过（OQ-46②）。定时注册在 crdp-server（每日 00:25 + 启动各一次，避开 00:10 任务创建与
 *       00:20 租户到期）。
 * </ul>
 */
@Service
public class ReportLocationFallbackService {

    static final String JOB_CODE = "rpt-location-fallback";

    private static final Logger log = LoggerFactory.getLogger(ReportLocationFallbackService.class);

    private final JobScheduler jobScheduler;
    private final ReportMapper mapper;
    private final FormService formService;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ReportLocationFallbackService(
            JobScheduler jobScheduler,
            ReportMapper mapper,
            FormService formService,
            AuditLogger audit,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jobScheduler = jobScheduler;
        this.mapper = mapper;
        this.formService = formService;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 单租户巡检结果（观测 / 测试用；fallbacks 含与既有整条位置合并的分组行）。 */
    public record TenantSummary(String tenantCode, int reports, int fallbacks, int removals) {}

    /** 对全部启用 / 试用 / 宽限期租户执行（单租户失败不影响其他租户，框架保证）。 */
    public List<TenantJobResult> runForDate(LocalDate logicalDate) {
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(JOB_CODE, logicalDate, this::runForCurrentTenant);
        long failed = results.stream().filter(result -> !result.success()).count();
        if (failed > 0) {
            log.warn("报告单位置退回作业有失败租户 count={}", failed);
        }
        return results;
    }

    /** 租户上下文内的作业体：模块闸门 → 整库巡检。 */
    void runForCurrentTenant(LocalDate logicalDate) {
        TenantContext.State tenant = TenantContext.require();
        if (!followupModuleOpen(tenant.tenantId())) {
            log.info("followup 模块未开通，本租户跳过位置退回 tenant={}", tenant.tenantCode());
            return;
        }
        TenantSummary summary = reconcileCurrentTenant();
        log.info(
                "报告单位置退回完成 tenant={} 报告单={} 退回={} 移除={}",
                summary.tenantCode(),
                summary.reports(),
                summary.fallbacks(),
                summary.removals());
    }

    // ---------------------------------------------------------------- 巡检与退回

    /** 巡检当前租户全部在册位置（须在租户上下文内）：逐报告单判定并应用，单报告单失败记 WARN 继续（一条坏行 不拖垮整院）。无陈旧锚点时零写入——重复执行结果不变。 */
    public TenantSummary reconcileCurrentTenant() {
        TenantContext.State tenant = TenantContext.require();
        List<ScanRow> rows = mapper.scanActiveLinks();
        Map<Long, List<ScanRow>> byReport = new LinkedHashMap<>();
        for (ScanRow row : rows) {
            byReport.computeIfAbsent(row.reportId(), id -> new ArrayList<>()).add(row);
        }
        if (byReport.isEmpty()) {
            return new TenantSummary(tenant.tenantCode(), 0, 0, 0);
        }
        Caches caches = new Caches();
        int reports = 0;
        int fallbacks = 0;
        int removals = 0;
        for (Map.Entry<Long, List<ScanRow>> entry : byReport.entrySet()) {
            long reportId = entry.getKey();
            try {
                ReportPlan plan = planReport(entry.getValue(), caches);
                if (plan.isEmpty()) {
                    continue;
                }
                transactions.executeWithoutResult(status -> applyReport(plan, caches));
                reports++;
                fallbacks += plan.fallbackCount();
                removals += plan.removeCount();
            } catch (RuntimeException ex) {
                log.warn("报告单位置退回失败，跳过该报告单继续 reportId={} 原因={}", reportId, ex.getMessage());
            }
        }
        return new TenantSummary(tenant.tenantCode(), reports, fallbacks, removals);
    }

    /** 单报告单的处理计划：对每条位置行给出退回 / 移除 / 标记动作，或保持不动。 */
    private record ReportPlan(
            long reportId,
            String typeName,
            String nameSnapshot,
            List<ScanRow> before,
            List<PlannedAction> actions) {

        boolean isEmpty() {
            return actions.isEmpty();
        }

        int fallbackCount() {
            return (int) actions.stream().filter(action -> action.kind() == Kind.FALLBACK).count();
        }

        int removeCount() {
            return (int) actions.stream().filter(action -> action.kind() == Kind.REMOVE).count();
        }
    }

    private enum Kind {
        /** 分组退回到整条记录（锚点清空 + adjusted = 1）。 */
        FALLBACK,

        /** 移除位置行（基线分类已不在当前发布版 / 记录不存在，无上一级可退）。 */
        REMOVE,

        /** 既有整条行补退回标记（分组退回时与整条位置合并的场景）。 */
        MARK_ADJUSTED
    }

    private record PlannedAction(ScanRow row, Kind kind, String reason) {}

    /** 逐行判定（巡检口径见类注释）：记录与分组清单按租户缓存，减少逐报告单重复查询。 */
    private ReportPlan planReport(List<ScanRow> rows, Caches caches) {
        ScanRow first = rows.get(0);
        List<PlannedAction> actions = new ArrayList<>();
        // 该报告单已有的整条记录位置（分组退回的合并判定：四元组中 anchor 为空即整条）
        Set<Long> wholeRecordIds = new LinkedHashSet<>();
        for (ScanRow row : rows) {
            if ("followup_record".equals(row.linkType()) && row.anchorType() == null) {
                wholeRecordIds.add(row.linkRecordId());
            }
        }
        for (ScanRow row : rows) {
            if ("baseline".equals(row.linkType())) {
                if (!"category".equals(row.anchorType())) {
                    continue; // 防御：不认识的形态不动（校验口不会产出）
                }
                Map<String, String> categories = caches.baselineCategories();
                if (categories == null) {
                    continue; // 基线模板读不出：跳过，不做无依据的移除
                }
                if (!categories.containsKey(row.anchorKey())) {
                    actions.add(
                            new PlannedAction(
                                    row, Kind.REMOVE, "基线模板当前发布版已不含分类 " + row.anchorKey()));
                }
                continue;
            }
            if (!"followup_record".equals(row.linkType())) {
                continue; // implant_record 等（E21-T32）不在此处理
            }
            RecordRow record = caches.record(row.linkRecordId());
            if (record == null) {
                actions.add(
                        new PlannedAction(row, Kind.REMOVE, "随访记录 " + row.linkRecordId() + " 不存在"));
                continue;
            }
            if (row.anchorType() == null) {
                continue; // 整条记录：记录在即有效
            }
            if (!"section".equals(row.anchorType())) {
                continue; // 防御：不认识的形态不动
            }
            Map<String, String> sections = caches.sections(record);
            if (sections == null) {
                continue; // 记录固化版本快照读不出：跳过，不做无依据的退回
            }
            if (sections.containsKey(row.anchorKey())) {
                continue;
            }
            if (wholeRecordIds.contains(row.linkRecordId())) {
                // 同一记录的整条位置已在册：分组行移除、既有整条行置退回标记（同一位置唯一，V63 uk）
                actions.add(new PlannedAction(row, Kind.REMOVE, "分组退回整条记录（与既有整条位置合并）"));
                for (ScanRow whole : rows) {
                    if (whole.anchorType() == null
                            && row.linkRecordId().equals(whole.linkRecordId())
                            && whole.adjusted() != null
                            && whole.adjusted() == 0) {
                        actions.add(new PlannedAction(whole, Kind.MARK_ADJUSTED, "分组退回合并"));
                    }
                }
            } else {
                actions.add(
                        new PlannedAction(row, Kind.FALLBACK, "记录固化版本已不含分组 " + row.anchorKey()));
                wholeRecordIds.add(row.linkRecordId());
            }
        }
        return new ReportPlan(
                first.reportId(), first.typeName(), first.nameSnapshot(), rows, actions);
    }

    /** 应用一个报告单的计划（独立事务：变更 + 操作日志 + 版本推进同生共死）。 */
    private void applyReport(ReportPlan plan, Caches caches) {
        for (PlannedAction action : plan.actions()) {
            switch (action.kind()) {
                case FALLBACK -> mapper.fallbackToWholeRecord(action.row().linkId());
                case REMOVE -> mapper.deleteLinkById(action.row().linkId());
                case MARK_ADJUSTED -> mapper.markLinkAdjusted(action.row().linkId());
            }
        }
        mapper.markReportLocationAdjusted(plan.reportId());
        mapper.bumpVersionByJob(plan.reportId());
        audit.append(
                AuditEntry.operation("followup", "RPT_LOCATION_FALLBACK")
                        .objectType("REPORT")
                        .objectId(String.valueOf(plan.reportId()))
                        .description(descriptionOf(plan, caches))
                        .beforeJson(writeJson(refsOf(plan.before())))
                        .afterJson(writeJson(refsOf(afterRows(plan))))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "报告单位置退回（E16-T15）：reportId={} 退回={} 移除={}",
                plan.reportId(),
                plan.fallbackCount(),
                plan.removeCount());
    }

    /** 日志描述：「退回 N 个（标签 → 标签）、移除 M 个（标签，原因）」——标签与 tagText 同口径。 */
    private String descriptionOf(ReportPlan plan, Caches caches) {
        StringBuilder text = new StringBuilder("模板调整退回位置：");
        if (plan.fallbackCount() > 0) {
            String items =
                    plan.actions().stream()
                            .filter(action -> action.kind() == Kind.FALLBACK)
                            .map(action -> labelOf(action.row(), caches) + " → 整条记录")
                            .collect(Collectors.joining("、"));
            text.append("退回 ").append(plan.fallbackCount()).append(" 个（").append(items).append("）");
        }
        if (plan.removeCount() > 0) {
            if (plan.fallbackCount() > 0) {
                text.append("、");
            }
            String items =
                    plan.actions().stream()
                            .filter(action -> action.kind() == Kind.REMOVE)
                            .map(action -> labelOf(action.row(), caches) + "，" + action.reason())
                            .collect(Collectors.joining("、"));
            text.append("移除 ").append(plan.removeCount()).append(" 个（").append(items).append("）");
        }
        text.append("；报告单：")
                .append(plan.typeName())
                .append(plan.nameSnapshot() == null ? "" : " · " + plan.nameSnapshot());
        return text.toString();
    }

    /** 位置标签（与 ReportService.tagText 同口径：基线 · 分类 / 记录标题 · 分组；读不出的锚点回落键值）。 */
    private String labelOf(ScanRow row, Caches caches) {
        if ("baseline".equals(row.linkType())) {
            Map<String, String> categories = caches.baselineCategories();
            String label = categories == null ? null : categories.get(row.anchorKey());
            return "基线 · " + (label != null ? label : row.anchorKey());
        }
        RecordRow record = caches.record(row.linkRecordId());
        String recordLabel =
                record != null ? ReportService.recordTitle(record) : "随访记录 " + row.linkRecordId();
        if (row.anchorType() == null) {
            return recordLabel;
        }
        Map<String, String> sections = record == null ? null : caches.sections(record);
        String sectionLabel = sections == null ? null : sections.get(row.anchorKey());
        return recordLabel + " · " + (sectionLabel != null ? sectionLabel : row.anchorKey());
    }

    /** 应用后的位置清单（before 投影：FALLBACK 行锚点清空、REMOVE 行剔除）——afterJson 用。 */
    private static List<ScanRow> afterRows(ReportPlan plan) {
        Set<Long> removed =
                plan.actions().stream()
                        .filter(action -> action.kind() == Kind.REMOVE)
                        .map(action -> action.row().linkId())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ScanRow> after = new ArrayList<>();
        for (ScanRow row : plan.before()) {
            if (removed.contains(row.linkId())) {
                continue;
            }
            boolean converted =
                    plan.actions().stream()
                            .anyMatch(
                                    action ->
                                            action.kind() == Kind.FALLBACK
                                                    && action.row().linkId() == row.linkId());
            after.add(
                    converted
                            ? new ScanRow(
                                    row.linkId(),
                                    row.reportId(),
                                    row.patientId(),
                                    row.typeName(),
                                    row.nameSnapshot(),
                                    row.linkType(),
                                    row.linkRecordId(),
                                    null,
                                    null,
                                    row.templateVersion(),
                                    1)
                            : row);
        }
        return after;
    }

    /** 位置四元组快照（与 RPT_LOCATION_CHANGE 的 before / after 同形）。 */
    private static List<Map<String, Object>> refsOf(List<ScanRow> rows) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (ScanRow row : rows) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("linkType", row.linkType());
            ref.put("linkRecordId", row.linkRecordId());
            ref.put("anchorType", row.anchorType());
            ref.put("anchorKey", row.anchorKey());
            ref.put("templateVersion", row.templateVersion());
            ref.put("adjusted", row.adjusted() != null && row.adjusted() > 0 ? 1 : 0);
            refs.add(ref);
        }
        return refs;
    }

    // ---------------------------------------------------------------- 巡检缓存（按租户一趟）

    /** 基线当前发布版分类 / 记录行 / 记录固化版本分组清单的懒加载缓存；读不出记 null + WARN（该批行跳过）。 */
    private final class Caches {

        private Map<String, String> baselineCategories;
        private boolean baselineLoaded;
        private final Map<Long, RecordRow> records = new LinkedHashMap<>();
        private final Map<Long, Boolean> recordMisses = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        private final Map<String, Boolean> sectionMisses = new LinkedHashMap<>();

        Map<String, String> baselineCategories() {
            if (!baselineLoaded) {
                baselineLoaded = true;
                try {
                    String code = BaselineSectionService.BASELINE_FORM_CODE;
                    int version = formService.publishedVersion(code);
                    baselineCategories = categoryMapOf(code, version);
                } catch (RuntimeException ex) {
                    log.warn("基线模板读取失败，本租户基线位置跳过退回判定：{}", ex.getMessage());
                    baselineCategories = null;
                }
            }
            return baselineCategories;
        }

        RecordRow record(Long id) {
            if (id == null) {
                return null;
            }
            if (records.containsKey(id)) {
                return records.get(id);
            }
            boolean missing = recordMisses.getOrDefault(id, false);
            if (missing) {
                return null;
            }
            RecordRow row = mapper.findRecordById(id);
            if (row == null) {
                recordMisses.put(id, true);
                return null;
            }
            records.put(id, row);
            return row;
        }

        Map<String, String> sections(RecordRow record) {
            String cacheKey = record.formCode() + "|" + record.templateVersion();
            if (sections.containsKey(cacheKey)) {
                return sections.get(cacheKey);
            }
            if (sectionMisses.getOrDefault(cacheKey, false)) {
                return null;
            }
            try {
                Map<String, String> map =
                        categoryMapOf(record.formCode(), record.templateVersion());
                sections.put(cacheKey, map);
                return map;
            } catch (RuntimeException ex) {
                log.warn(
                        "记录模板版本快照读取失败，该记录分组位置跳过退回判定 form={} v{}：{}",
                        record.formCode(),
                        record.templateVersion(),
                        ex.getMessage());
                sectionMisses.put(cacheKey, true);
                return null;
            }
        }

        private Map<String, String> categoryMapOf(String formCode, int version) {
            Map<String, String> map = new LinkedHashMap<>();
            for (FormService.CategoryView category :
                    formService.schema(formCode, version).categories()) {
                map.put(category.key(), category.label());
            }
            return map;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("退回日志 JSON 序列化失败", e);
        }
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
