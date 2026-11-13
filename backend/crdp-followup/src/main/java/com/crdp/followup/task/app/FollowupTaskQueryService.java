package com.crdp.followup.task.app;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 我的任务 / 任务列表与队列（E15-T02 / A-FU-004、C-FU-020；docs/api/followup.md §2，页面 /followup/task，pages.md
 * §7）。
 *
 * <p>验收口径：
 *
 * <ul>
 *   <li>按状态、病区、负责人、随访年度（窗口日期）筛选；默认显示「我的待办」（A-FU-004）——scope = MINE 且未完成四态；
 *   <li>数据范围由 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行（fu_task 在
 *       ControlledTables，E07-T02 改写；「仅本人负责的患者」快照另含任务负责人分支，A-WARD-003）；范围为空返回空列表；
 *   <li>行内姓名 / 病案号只输出脱敏值（解密后 {@link SensitiveValueMasker}，AGENTS §5.3；列表不提供逐行明文）；
 *   <li>检索词 keyword 按姓名 / 病案号盲索引精确匹配（与患者列表 E13-T04 同口径，不做模糊）；
 *   <li>页签徽标（我的待办 / 本病区 / 全部）与「上一条 / 下一条」队列共用同一条件树（顺当前列表走，pages.md §5）；
 *   <li>批量查询写 BATCH_QUERY 数据访问审计（AGENTS §5.4）。
 * </ul>
 *
 * <p>C 侧（C-FU-020 沿用 A 的任务列表，E21-T23 起三列落地）：{@code implant} 开关开启时行附 当前器械类型
 * deviceType（在用植入记录的字典显示名）、 随访时间点 timepoint（任务节点名——C 预置节点名即时间点，C-FU-008「来自任务的子计划」）、 上次随访标红项数
 * lastHighlightCount（该患者最近一条 已提交记录的 fu_record.highlight 命中数，无已提交记录 null）； 开关关闭（A / B）三列全部 null
 * 且不触发植入 / 标红查询，行为与 E21 前完全一致 （AGENTS §5.7）；{@code compare_highlight} 关闭时标红计数按读取侧过滤（followup.md
 * §4）。 认领 / 改派 / 逾期沿用 A 的链路（E15-T03 / E15-T05，C-FU-020「沿用」）。「最近联系」列已随 E15-T04 沟通记录接入：每患者最新一条
 * fu_contact_log 的结果编码， 中文映射在前端 packages/api（components.md §2 contact 域）。
 */
@Service
public class FollowupTaskQueryService {

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 100;

    private static final int KEYWORD_MAX_LENGTH = 64;

    /** 任务状态（fu_task.status，E15-T01 状态机 + E15-T05 失访候选；components.md §2 task 枚举的展示派生在前端）。 */
    private static final Set<String> STATUS_VALUES =
            Set.of(
                    "PENDING",
                    "IN_PROGRESS",
                    "COMPLETED",
                    "OVERDUE",
                    "LOST_CANDIDATE",
                    "LOST",
                    "TERMINATED",
                    "SKIPPED_SUSPENDED");

    /** 页签（A-FU-004：我的待办 / 本病区 / 全部）。 */
    private static final Set<String> SCOPE_VALUES = Set.of("MINE", "WARD", "ALL");

    /** C 三列的功能开关（E21-T23 / C-FU-020：关闭〔A / B〕三列不出，AGENTS §5.7）。 */
    private static final String FEATURE_IMPLANT = "implant";

    /** 对比标红的功能开关（E21-T23：上次随访标红项数的读取侧过滤；无行视为默认开，tech-plan §10）。 */
    private static final String FEATURE_COMPARE_HIGHLIGHT = "compare_highlight";

    private final FollowupTaskQueryMapper mapper;
    private final PatientMapper patientMapper;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final ContactLogService contactLogs;
    private final DashboardFeatureMapper features;
    private final ImplantGateway implantGateway;
    private final ImplantDictPort implantDicts;
    private final FollowupRecordMapper recordMapper;

    public FollowupTaskQueryService(
            FollowupTaskQueryMapper mapper,
            PatientMapper patientMapper,
            CryptoService crypto,
            AuditLogger audit,
            ContactLogService contactLogs,
            DashboardFeatureMapper features,
            ImplantGateway implantGateway,
            ImplantDictPort implantDicts,
            FollowupRecordMapper recordMapper) {
        this.mapper = mapper;
        this.patientMapper = patientMapper;
        this.crypto = crypto;
        this.audit = audit;
        this.contactLogs = contactLogs;
        this.features = features;
        this.implantGateway = implantGateway;
        this.implantDicts = implantDicts;
        this.recordMapper = recordMapper;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 检索请求（docs/api/followup.md §2 tasks/search body；scope 对应页签，缺省 MINE 我的待办）。 windowFrom /
     * windowTo（窗口包含区间， E19-T03 首页任务表「今日任务」钻取）与 completedFrom / completedTo（完成时间区间，看板已完成列钻取）为钻取专用筛选，
     * 页面筛选条暂不提供。
     */
    public record SearchRequest(
            String scope,
            List<String> status,
            List<Long> wardIds,
            Long doctorId,
            LocalDate dueFrom,
            LocalDate dueTo,
            LocalDate windowFrom,
            LocalDate windowTo,
            LocalDate completedFrom,
            LocalDate completedTo,
            String nodeName,
            String keyword,
            Boolean unassigned,
            Integer pageNo,
            Integer pageSize) {}

    /**
     * 列表行（姓名 / 病案号只含脱敏值；最近联系随 E15-T04；E21-T23 起 {@code implant} 开启时附 C 三列—— deviceType
     * 当前器械类型（在用植入记录的 字典显示名，无在用器械 null）、timepoint 随访时间点（任务节点名，C 预置节点即时间点）、 lastHighlightCount
     * 上次随访标红项数（无已提交记录 null；compare_highlight 关闭 null——读取侧过滤））。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskRowView(
            String id,
            String patientId,
            String patientName,
            String mrn,
            String legacyNo,
            String wardName,
            String nodeName,
            Integer occurrence,
            String taskType,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String status,
            String completeKind,
            String createType,
            String recordId,
            String assigneeName,
            LocalDate nextPlanDate,
            String lastContactResult,
            String deviceType,
            String timepoint,
            Integer lastHighlightCount) {}

    /** 页签徽标计数（同一非 scope 筛选下三态各计一次，MINE 恒按未完成口径）。 */
    public record TabCountsView(long mine, long ward, long total) {}

    /** 分页结果（统一分页口径 + 页签计数 + 病区候选，病区候选不敏感）。 */
    public record TaskPage(
            List<TaskRowView> items,
            long total,
            int pageNo,
            int pageSize,
            TabCountsView tabCounts,
            List<WardOptionView> wards) {}

    /** 病区候选（列表页病区下拉，启用项；行级隔离仍按数据范围）。 */
    public record WardOptionView(long id, String name) {}

    /** 队列结果（表单页「上一条 / 下一条」；prev / next 为任务 id，尽头为 null）。 */
    public record QueueView(String prevId, String nextId) {}

    // ---------------------------------------------------------------- 检索（A-FU-004）

    /** 条件检索一页（校验 → 盲索引 → 范围内查询 → 脱敏 → 批量查询审计）。 */
    public TaskPage search(SearchRequest request) {
        SearchRequest safe = request == null ? emptyRequest() : request;
        int pageNo = safe.pageNo() == null ? 1 : safe.pageNo();
        int pageSize = safe.pageSize() == null ? PAGE_SIZE_DEFAULT : safe.pageSize();
        if (pageNo < 1) {
            throw invalid("pageNo（从 1 开始）");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            throw invalid("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        String scope = normalizeScope(safe.scope());
        List<String> status = sanitizeStatus(safe.status());
        // 我的待办默认只看未完成（A-FU-004「默认显示我的待办」）；显式选状态则按所选（含已完成，便于回看）
        boolean openOnly = "MINE".equals(scope) && status == null;
        FollowupTaskQueryMapper.TaskCriteria criteria =
                criteriaOf(scope, status, openOnly, safe, currentUserId());

        List<FollowupTaskQueryMapper.TaskRowRaw> rows =
                mapper.search(criteria, (pageNo - 1) * pageSize, pageSize);
        // 「最近联系」列（E15-T04 接入，A 规格 §主要页面 1）：每患者最新一条沟通记录的结果，
        // 与行查询同一数据范围（ContactLogMapper 语句上的 @DataScope 出行，越范围患者不可见）
        Map<Long, String> latestContacts = contactLogs.latestResultByPatients(patientIdsOf(rows));
        // C 三列（E21-T23 / C-FU-020）：implant 开关关闭 → 空 Map（三列 null，不触发植入 / 标红查询）
        Map<Long, TaskCColumns> cColumns = cColumnsOf(rows);
        List<TaskRowView> items = new ArrayList<>();
        for (FollowupTaskQueryMapper.TaskRowRaw row : rows) {
            items.add(
                    toView(
                            row,
                            latestContacts.get(row.patientId()),
                            cColumns.get(row.patientId())));
        }
        long total = mapper.count(criteria);
        FollowupTaskQueryMapper.TabCountRow counts = mapper.tabCounts(criteria);

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("TASK")
                        .description("检索随访任务列表（页签 " + scope + "）")
                        .recordCount(total)
                        .queryDigest(digest(safe))
                        .build());
        return new TaskPage(
                items,
                total,
                pageNo,
                pageSize,
                new TabCountsView(
                        counts == null || counts.mine() == null ? 0 : counts.mine(),
                        counts == null || counts.ward() == null ? 0 : counts.ward(),
                        counts == null || counts.total() == null ? 0 : counts.total()),
                wardOptions());
    }

    /** 队列相邻任务（cursor 任务在当前筛选顺序中的前一条 / 后一条；顺当前列表走，pages.md §5）。 */
    public QueueView queue(long cursorTaskId, SearchRequest request) {
        SearchRequest safe = request == null ? emptyRequest() : request;
        String scope = normalizeScope(safe.scope());
        List<String> status = sanitizeStatus(safe.status());
        boolean openOnly = "MINE".equals(scope) && status == null;
        long userId = currentUserId();
        FollowupTaskQueryMapper.TaskCriteria criteria =
                criteriaOf(scope, status, openOnly, safe, userId);
        LocalDate cursorPlanDate = cursorPlanDateOf(cursorTaskId, criteria);
        if (cursorPlanDate == null) {
            // cursor 不在当前筛选结果内（或越范围不可见）：两头都无——前端按钮置灰
            return new QueueView(null, null);
        }
        FollowupTaskQueryMapper.TaskRowRaw prev =
                mapper.neighbor(criteria, cursorPlanDate, cursorTaskId, false);
        FollowupTaskQueryMapper.TaskRowRaw next =
                mapper.neighbor(criteria, cursorPlanDate, cursorTaskId, true);
        return new QueueView(
                prev == null ? null : String.valueOf(prev.id()),
                next == null ? null : String.valueOf(next.id()));
    }

    /** 病区候选（列表页病区下拉；启用项，候选本身不敏感）。 */
    public List<WardOptionView> wardOptions() {
        return patientMapper.findWards().stream()
                .map(item -> new WardOptionView(item.id(), item.name()))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private static SearchRequest emptyRequest() {
        return new SearchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    /** 条件装配（检索词盲索引、筛选校验；scope 与 userId 由调用方解析）。 */
    private FollowupTaskQueryMapper.TaskCriteria criteriaOf(
            String scope, List<String> status, boolean openOnly, SearchRequest safe, long userId) {
        String keyword = normalizeKeyword(safe.keyword());
        String nameBlind = null;
        String mrnBlind = null;
        if (keyword != null) {
            nameBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_NAME, keyword);
            mrnBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_MRN, keyword);
        }
        validateRange(safe.dueFrom(), safe.dueTo(), "dueFrom / dueTo");
        validateRange(safe.windowFrom(), safe.windowTo(), "windowFrom / windowTo");
        validateRange(safe.completedFrom(), safe.completedTo(), "completedFrom / completedTo");
        return new FollowupTaskQueryMapper.TaskCriteria(
                scope,
                userId,
                status,
                openOnly,
                sanitizeIds(safe.wardIds(), "wardIds"),
                positiveOrNull(safe.doctorId(), "doctorId"),
                safe.dueFrom(),
                safe.dueTo(),
                safe.windowFrom(),
                safe.windowTo(),
                safe.completedFrom(),
                safe.completedTo(),
                normalizeNodeName(safe.nodeName()),
                nameBlind,
                mrnBlind,
                Boolean.TRUE.equals(safe.unassigned()));
    }

    /** cursor 任务的计划日期（keyset 队列定位）；越范围 / 不存在时 null（队列两头置空）。 */
    private LocalDate cursorPlanDateOf(
            long cursorTaskId, FollowupTaskQueryMapper.TaskCriteria criteria) {
        // 不按筛选判断 cursor 是否命中——提交后当前任务已离开「未完成」筛选，仍按 (plan_date, id) 顺序定位前后任务；
        // 可见性（数据范围）由 mapper 语句上的 @DataScope 出行（越范围 null）。
        return mapper.cursorPlanDate(cursorTaskId);
    }

    /** 行视图：姓名 / 病案号解密后脱敏（列表不提供逐行明文，明文走 E07-T05 专用接口）。 */
    private TaskRowView toView(
            FollowupTaskQueryMapper.TaskRowRaw row,
            String lastContactResult,
            TaskCColumns cColumns) {
        String name = decrypt(row.nameCipher());
        String mrn = decrypt(row.mrnCipher());
        return new TaskRowView(
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
                // E15-T04「最近联系」：该患者最新一条沟通记录的结果编码（前端映射中文），无联系时 null（列显示「—」）
                lastContactResult,
                // E21-T23 / C-FU-020 C 三列（implant 关闭 → null 占位）：随访时间点 = 任务节点名（数据驱动，不映射写死）
                cColumns == null ? null : cColumns.deviceType(),
                cColumns == null ? null : row.nodeName(),
                cColumns == null ? null : cColumns.lastHighlightCount());
    }

    /** C 三列的每患者取数结果（deviceType 无在用器械 null；lastHighlightCount 无已提交记录 null、无命中 0）。 */
    private record TaskCColumns(String deviceType, Integer lastHighlightCount) {}

    /**
     * 本页各患者的 C 列取数（E21-T23 / C-FU-020）：implant 开关关闭返回空 Map（三列 null， 不触发植入 / 标红查询——A / B 行为与 E21
     * 前一致）；标红计数经批量查询（同「最近联系」模式），compare_highlight 关闭按读取侧过滤为 null。
     */
    private Map<Long, TaskCColumns> cColumnsOf(List<FollowupTaskQueryMapper.TaskRowRaw> rows) {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_IMPLANT)) || rows.isEmpty()) {
            return Map.of();
        }
        List<Long> patientIds = patientIdsOf(rows);
        Map<Long, Integer> highlights =
                Boolean.FALSE.equals(features.findEnabled(FEATURE_COMPARE_HIGHLIGHT))
                        ? Map.of()
                        : lastHighlightCounts(patientIds);
        Map<Long, TaskCColumns> result = new LinkedHashMap<>();
        for (Long patientId : patientIds) {
            ImplantRow active = implantGateway.activeImplantOf(patientId);
            // 无已提交记录 → 不在 Map（null，列显示「—」）；最近记录无命中 → 0
            result.put(
                    patientId,
                    new TaskCColumns(
                            active == null ? null : deviceTypeLabel(active.deviceType()),
                            highlights.get(patientId)));
        }
        return result;
    }

    /** 各患者最近一条已提交记录的标红命中数（JSON_LENGTH(highlight)，无命中行 null；命中数 null 归 0）。 */
    private Map<Long, Integer> lastHighlightCounts(List<Long> patientIds) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (FollowupRecordMapper.PatientHighlightRow row :
                recordMapper.latestSubmittedHighlightCounts(patientIds)) {
            result.put(row.patientId(), row.hitCount() == null ? 0 : row.hitCount());
        }
        return result;
    }

    /** 起搏器类型显示名（字典 pacemaker_type 含停用项，C-FLD-009；未知编码回落原码，与 PatientOverviewService 同口径）。 */
    private String deviceTypeLabel(String deviceType) {
        if (deviceType == null) {
            return null;
        }
        return implantDicts.items(ImplantDictPort.DICT_DEVICE_TYPE).stream()
                .filter(item -> deviceType.equals(item.itemCode()))
                .map(ImplantDictPort.DictItem::label)
                .findFirst()
                .orElse(deviceType);
    }

    /** 本页去重患者 id（「最近联系」批量取数）。 */
    private static List<Long> patientIdsOf(List<FollowupTaskQueryMapper.TaskRowRaw> rows) {
        return rows.stream().map(FollowupTaskQueryMapper.TaskRowRaw::patientId).distinct().toList();
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }

    private static long currentUserId() {
        return CurrentSession.requireHospital().userId();
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

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > KEYWORD_MAX_LENGTH) {
            throw invalid("检索词（最多 " + KEYWORD_MAX_LENGTH + " 个字符）");
        }
        return trimmed;
    }

    private static String normalizeNodeName(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            return null;
        }
        String trimmed = nodeName.trim();
        if (trimmed.length() > 64) {
            throw invalid("节点名称（最多 64 个字符）");
        }
        return trimmed;
    }

    /** 状态白名单（fu_task.status；未知值 40001，不静默吞掉）。 */
    private static List<String> sanitizeStatus(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String item : status) {
            if (item == null) {
                continue;
            }
            String normalized = item.trim().toUpperCase();
            if (!STATUS_VALUES.contains(normalized)) {
                throw invalid("status（" + String.join(" / ", STATUS_VALUES) + "）");
            }
            cleaned.add(normalized);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static List<Long> sanitizeIds(List<Long> ids, String field) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<Long> cleaned = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (id <= 0) {
                throw invalid(field + "（须为正整数）");
            }
            cleaned.add(id);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static Long positiveOrNull(Long value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw invalid(field + "（须为正整数）");
        }
        return value;
    }

    private static void validateRange(LocalDate from, LocalDate to, String field) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid(field + "（from 不能晚于 to）");
        }
    }

    /** 审计摘要（不落检索词——可能是患者姓名，与患者列表检索同口径）。 */
    private static String digest(SearchRequest safe) {
        return "scope="
                + (safe.scope() == null ? "MINE" : safe.scope())
                + ",status="
                + (safe.status() == null ? 0 : safe.status().size())
                + ",wardIds="
                + (safe.wardIds() == null ? 0 : safe.wardIds().size())
                + ",doctorId="
                + (safe.doctorId() == null ? "-" : safe.doctorId())
                + ",due=["
                + safe.dueFrom()
                + ","
                + safe.dueTo()
                + "]"
                + ",window=["
                + safe.windowFrom()
                + ","
                + safe.windowTo()
                + "]"
                + ",completed=["
                + safe.completedFrom()
                + ","
                + safe.completedTo()
                + "]"
                + ",unassigned="
                + Boolean.TRUE.equals(safe.unassigned())
                + ",keyword="
                + (safe.keyword() == null || safe.keyword().isBlank() ? "no" : "yes");
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, field + "不合法");
    }
}
