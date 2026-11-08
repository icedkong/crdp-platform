package com.crdp.followup.dashboard.app;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.followup.dashboard.app.DashboardKpiService.DrillView;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper.TableCountRow;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper.TaskCardCriteria;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper.WardOptionRow;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskQueryService;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper.TaskRowRaw;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 首页任务看板与任务表（E19-T03 /
 * A-DASH-003、A-DASH-004、A-DASH-005、A-DASH-008、C-DASH-003、C-DASH-008；docs/api/stats.md §3 cardCode =
 * task_board / task_table，页面 pages.md §2 首页任务区）。
 *
 * <p>验收「受病区隔离」：数据范围由 {@link DashboardTaskMapper} 语句上的 {@code @DataScope(resource = "patient")}
 * 出行（fu_task 在 ControlledTables，越范围患者的任务不可见；范围为空返回全 0 / 空列，不报错）；页面级病区筛选（A-DASH-005）只是范围内再收窄——wardId
 * 与数据范围条件叠加，选到可见范围之外的病区结果恒为空。 病区候选（{@link #wardOptions}）按登录快照的病区清单交集（全院 / 科室 / 本人快照回落全部启用病区——可见病区不止
 * snapshot.wardIds 一种来源，行级隔离仍由范围改写承担）。
 *
 * <p>「一个口径一个查询」（stats.md §1）：看板各列计数、任务表各页签计数与任务列表检索（{@link FollowupTaskQueryService}）共用同一条件树——
 * scope（A-DASH-004 我的 / 本病区 / 全部）、病区、负责人（assigneeId ↔ 列表 doctorId）、状态、窗口包含当日（windowOn ↔ 列表
 * windowFrom/windowTo）、完成时间窗口（doneFrom/doneTo ↔ 列表 completedFrom/completedTo）； 钻取 query
 * 内联在响应里（与指标卡同做法，前端不自行拼业务条件）。
 *
 * <p>每列前 20 张卡片（A 规格 §9「看板每列默认显示最紧急的 20 张卡片」）：未完成列按窗口结束日升序（最紧急在前）；
 * 已完成列按完成时间降序（最近完成在前——窗口结束日对已完成列无紧急语义，OQ-108）。 卡片显示脱敏姓名、病区、节点（第 N 年）、窗口、负责人、最近一次联系结果（A 规格 §9
 * 看板卡片口径）。已完成列口径 A 本月已完成 / C 本周完成（C-DASH-003，按 implant 开关判定，与指标卡指标集同做法，OQ-107③）。
 */
@Service
public class DashboardTaskService {

    /** 看板每列默认卡片数（A 规格 §9 业务规则「每列默认显示最紧急的 20 张卡片」，其余「查看全部」进任务列表）。 */
    static final int BOARD_CARD_LIMIT = 20;

    /** 任务表当前页签默认行数（首页预览行，其余「查看全部」进任务列表；表格整页 20 条在列表页）。 */
    static final int TABLE_ROW_LIMIT = 10;

    /** 看板筛选（A-DASH-004 我的 / 本病区 / 全部〔可见范围内〕；缺省我的——与任务列表默认页签一致，OQ-108）。 */
    private static final Set<String> SCOPE_VALUES = Set.of("MINE", "WARD", "ALL");

    /** 任务表页签（A-DASH-008 今日任务 / 超时任务 / 我负责的任务；缺省今日任务）。 */
    private static final Set<String> TAB_VALUES = Set.of("TODAY", "OVERDUE", "MINE");

    /**
     * 未完成五态（与任务列表 MINE 页签默认口径一致：PENDING / IN_PROGRESS / OVERDUE / LOST_CANDIDATE /
     * SKIPPED_SUSPENDED）。
     */
    private static final String OPEN_STATUSES_CSV =
            "PENDING,IN_PROGRESS,OVERDUE,LOST_CANDIDATE,SKIPPED_SUSPENDED";

    private final DashboardTaskMapper tasks;
    private final DashboardFeatureMapper features;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final ContactLogService contactLogs;

    public DashboardTaskService(
            DashboardTaskMapper tasks,
            DashboardFeatureMapper features,
            CryptoService crypto,
            AuditLogger audit,
            ContactLogService contactLogs) {
        this.tasks = tasks;
        this.features = features;
        this.crypto = crypto;
        this.audit = audit;
        this.contactLogs = contactLogs;
    }

    // ---------------------------------------------------------------- DTO

    /** 看板一列（A-DASH-003 按状态分列）：total 为该条件下总数（与任务列表同条件树），tasks 为前 20 张卡片。 */
    public record BoardColumnView(
            String status,
            String title,
            long total,
            List<FollowupTaskQueryService.TaskRowView> tasks,
            DrillView drill) {}

    /** 看板数据：scope 回显；doneColumn / doneFrom / doneTo 为已完成列口径（A 本月已完成 / C 本周完成）。 */
    public record TaskBoardView(
            String scope,
            String doneColumn,
            String doneFrom,
            String doneTo,
            List<BoardColumnView> columns) {}

    /** 任务表一个页签（A-DASH-008：页签上显示数量）。 */
    public record TableTabView(String tab, String title, long total, DrillView drill) {}

    /** 任务表数据：当前页签 tab + 三页签计数 + 当前页签前 10 行（可直接进入随访工作台）。 */
    public record TaskTableView(
            String tab,
            String today,
            long total,
            List<TableTabView> tabs,
            List<FollowupTaskQueryService.TaskRowView> items) {}

    /** 病区筛选候选（A-DASH-005：默认本人全部可见病区；字典值不敏感）。 */
    public record WardOptionView(String id, String name) {}

    /** 病区候选响应。 */
    public record WardOptionsView(List<WardOptionView> wards) {}

    // ---------------------------------------------------------------- 看板（A-DASH-003 / A-DASH-004）

    /** 看板：四列（待随访 / 进行中 / 逾期 / 本月已完成〔C 本周完成〕），每列前 20 张卡片。 */
    public TaskBoardView board(Long wardId, String scope, Long assigneeId) {
        Long ward = sanitizeId(wardId, "wardId");
        String normalizedScope = normalizeScope(scope);
        Long assignee = sanitizeId(assigneeId, "assigneeId");
        long userId = CurrentSession.requireHospital().userId();
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate doneFrom;
        LocalDate doneTo;
        String doneColumn;
        if (Boolean.TRUE.equals(features.findEnabled("implant"))) {
            // C-DASH-003：完成列为「本周完成」（周一 ~ 周日，含当日的自然周）
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            doneFrom = monday;
            doneTo = monday.plusDays(6);
            doneColumn = "本周完成";
        } else {
            doneFrom = today.withDayOfMonth(1);
            doneTo = today.withDayOfMonth(today.lengthOfMonth());
            doneColumn = "本月已完成";
        }

        List<BoardColumnView> columns = new ArrayList<>();
        long totalSum = 0;
        for (String[] open :
                new String[][] {{"PENDING", "待随访"}, {"IN_PROGRESS", "进行中"}, {"OVERDUE", "逾期"}}) {
            TaskCardCriteria criteria =
                    new TaskCardCriteria(
                            normalizedScope,
                            userId,
                            open[0],
                            false,
                            ward,
                            assignee,
                            null,
                            null,
                            null);
            long total = tasks.countByStatus(criteria);
            totalSum += total;
            columns.add(
                    new BoardColumnView(
                            open[0],
                            open[1],
                            total,
                            rowsOf(tasks.cardTasks(criteria, false, BOARD_CARD_LIMIT)),
                            openDrill(normalizedScope, open[0], assignee)));
        }
        TaskCardCriteria doneCriteria =
                new TaskCardCriteria(
                        normalizedScope,
                        userId,
                        "COMPLETED",
                        false,
                        ward,
                        assignee,
                        doneFrom,
                        doneTo,
                        null);
        long doneTotal = tasks.countByStatus(doneCriteria);
        totalSum += doneTotal;
        columns.add(
                new BoardColumnView(
                        "COMPLETED",
                        doneColumn,
                        doneTotal,
                        rowsOf(tasks.cardTasks(doneCriteria, true, BOARD_CARD_LIMIT)),
                        doneDrill(normalizedScope, assignee, doneFrom, doneTo)));

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("TASK")
                        .description("首页任务看板（筛选 " + normalizedScope + "，已完成列 " + doneColumn + "）")
                        .recordCount(totalSum)
                        .build());
        return new TaskBoardView(
                normalizedScope, doneColumn, doneFrom.toString(), doneTo.toString(), columns);
    }

    // ---------------------------------------------------------------- 任务表（A-DASH-008）

    /** 任务表：三页签（今日任务 / 超时任务 / 我负责的任务，页签显示数量）+ 当前页签前 10 行。 */
    public TaskTableView table(Long wardId, String tab) {
        Long ward = sanitizeId(wardId, "wardId");
        String normalizedTab = normalizeTab(tab);
        long userId = CurrentSession.requireHospital().userId();
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        TaskCardCriteria criteria =
                new TaskCardCriteria("ALL", userId, null, false, ward, null, null, null, today);

        TableCountRow counts = tasks.tableTabCounts(criteria);
        long todayTotal = counts == null || counts.today() == null ? 0 : counts.today();
        long overdueTotal = counts == null || counts.overdue() == null ? 0 : counts.overdue();
        long mineTotal = counts == null || counts.mine() == null ? 0 : counts.mine();
        List<TableTabView> tabs =
                List.of(
                        new TableTabView(
                                "TODAY",
                                "今日任务",
                                todayTotal,
                                new DrillView(
                                        "TASK_LIST",
                                        queryOf(
                                                "all",
                                                OPEN_STATUSES_CSV,
                                                Map.of(
                                                        "windowFrom", today.toString(),
                                                        "windowTo", today.toString())))),
                        new TableTabView(
                                "OVERDUE",
                                "超时任务",
                                overdueTotal,
                                new DrillView("TASK_LIST", queryOf("all", "OVERDUE", Map.of()))),
                        new TableTabView(
                                "MINE",
                                "我负责的任务",
                                mineTotal,
                                // 列表 MINE 页签默认未完成五态——恰为「负责人为本人、尚未完成的全部任务」
                                new DrillView("TASK_LIST", queryOf("mine", null, Map.of()))));
        long total =
                switch (normalizedTab) {
                    case "OVERDUE" -> overdueTotal;
                    case "MINE" -> mineTotal;
                    default -> todayTotal;
                };
        List<FollowupTaskQueryService.TaskRowView> items =
                rowsOf(tasks.tableTasks(criteria, normalizedTab, TABLE_ROW_LIMIT));

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("TASK")
                        .description("首页任务表（页签 " + normalizedTab + "）")
                        .recordCount(todayTotal + overdueTotal + mineTotal)
                        .build());
        return new TaskTableView(normalizedTab, today.toString(), total, tabs, items);
    }

    // ---------------------------------------------------------------- 病区候选（A-DASH-005 /
    // C-DASH-008）

    /** 病区筛选候选：默认本人全部可见病区——快照带病区清单时按清单交集，否则回落全部启用病区。 */
    public WardOptionsView wardOptions() {
        ScopeSnapshot snapshot = CurrentSession.requireHospital().scope();
        List<Long> wardIds = snapshot.wardIds().isEmpty() ? null : List.copyOf(snapshot.wardIds());
        List<WardOptionView> wards = new ArrayList<>();
        for (WardOptionRow row : tasks.findWardOptions(wardIds)) {
            wards.add(new WardOptionView(String.valueOf(row.id()), row.name()));
        }
        return new WardOptionsView(wards);
    }

    // ---------------------------------------------------------------- 内部

    /** 行装配：解密后脱敏（列表不提供逐行明文）+ 最近一次联系结果（A 规格 §9 看板卡片口径）。 */
    private List<FollowupTaskQueryService.TaskRowView> rowsOf(List<TaskRowRaw> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> patientIds = rows.stream().map(TaskRowRaw::patientId).distinct().toList();
        Map<Long, String> latestContacts = contactLogs.latestResultByPatients(patientIds);
        List<FollowupTaskQueryService.TaskRowView> items = new ArrayList<>();
        for (TaskRowRaw row : rows) {
            String name = crypto.decrypt(row.nameCipher());
            String mrn = crypto.decrypt(row.mrnCipher());
            items.add(
                    new FollowupTaskQueryService.TaskRowView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            name == null ? "" : SensitiveValueMasker.mask("name", name),
                            mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn),
                            row.legacyNo(),
                            row.wardName(),
                            row.nodeName(),
                            row.occurrence(),
                            row.taskType(),
                            row.planDate(),
                            row.windowStart(),
                            row.windowEnd(),
                            row.status(),
                            row.completeKind(),
                            row.createType(),
                            row.recordId() == null ? null : String.valueOf(row.recordId()),
                            row.assigneeName(),
                            row.nextPlanDate(),
                            latestContacts.get(row.patientId())));
        }
        return items;
    }

    /** 未完成列钻取：任务列表 tab + status（+ 负责人筛选延续）。 */
    private static DrillView openDrill(String scope, String status, Long assigneeId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (assigneeId != null) {
            extra.put("doctorId", assigneeId);
        }
        return new DrillView("TASK_LIST", queryOf(scopeQuery(scope), status, extra));
    }

    /** 已完成列钻取：另带完成时间窗口（列表 completedFrom / completedTo 同口径）。 */
    private static DrillView doneDrill(
            String scope, Long assigneeId, LocalDate from, LocalDate to) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("completedFrom", from.toString());
        extra.put("completedTo", to.toString());
        if (assigneeId != null) {
            extra.put("doctorId", assigneeId);
        }
        return new DrillView("TASK_LIST", queryOf(scopeQuery(scope), "COMPLETED", extra));
    }

    /** 钻取 query 装配（null 值不落键；与列表页 query 参数同名）。 */
    private static Map<String, Object> queryOf(
            String tab, String status, Map<String, Object> extra) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("tab", tab);
        if (status != null) {
            query.put("status", status);
        }
        for (Map.Entry<String, Object> entry : extra.entrySet()) {
            if (entry.getValue() != null) {
                query.put(entry.getKey(), entry.getValue());
            }
        }
        return query;
    }

    /** 看板 scope → 列表页 tab 值（mine / ward / all，与任务列表路由 query 一致）。 */
    private static String scopeQuery(String scope) {
        return switch (scope) {
            case "WARD" -> "ward";
            case "ALL" -> "all";
            default -> "mine";
        };
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "MINE";
        }
        String normalized = scope.trim().toUpperCase();
        if (!SCOPE_VALUES.contains(normalized)) {
            throw invalid("scope（MINE / WARD / ALL）");
        }
        return normalized;
    }

    private static String normalizeTab(String tab) {
        if (tab == null || tab.isBlank()) {
            return "TODAY";
        }
        String normalized = tab.trim().toUpperCase();
        if (!TAB_VALUES.contains(normalized)) {
            throw invalid("tab（TODAY / OVERDUE / MINE）");
        }
        return normalized;
    }

    private static Long sanitizeId(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw invalid(field + "（须为正整数）");
        }
        return value;
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, field + "不合法");
    }
}
