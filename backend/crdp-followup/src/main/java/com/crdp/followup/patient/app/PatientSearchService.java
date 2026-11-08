package com.crdp.followup.patient.app;

import com.crdp.followup.patient.domain.AdvancedConditionCompiler;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientSearchMapper;
import com.crdp.followup.stats.app.CompletenessStatsService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 患者列表、检索与筛选用例（E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009；E13-T06 高级检索 A-PAT-014、
 * A-NFR-009、C-PAT-013；接口 docs/api/patient.md §1）。
 *
 * <p>验收口径：
 *
 * <ul>
 *   <li>按病区隔离——查询经 {@link PatientSearchGateway}（@DataScope patient），E07-T02 拦截器按登录快照改写，
 *       服务层不拼范围条件（高级检索的条件树同样不含病区条件， 拦截器对整条语句自动追加，A-NFR-009「自动追加病区条件」）；
 *   <li>按姓名精确检索——姓名 / 病案号列只存密文 + 盲索引（followup V37），检索词先算姓名与病案号两个盲索引， SQL 按盲索引相等匹配（A-PAT-002
 *       「病案号、姓名精确查找」），不做模糊匹配；
 *   <li>列表只输出脱敏值——姓名 / 病案号在应用层解密后经 {@link SensitiveValueMasker} 掩码（AGENTS.md §5.3 列表不提供逐行明文）；
 *   <li>高级检索——条件树只引用字段字典目录（{@link PatientSearchFieldService}，字典生成 + 列绑定白名单）， 经 {@link
 *       AdvancedConditionCompiler} 编译为参数化 SQL（取值全部参数位，无原始 SQL）。
 * </ul>
 *
 * <p>排序字段白名单（README §2.4：字段码映射白名单列，禁止拼接任意列名），默认下次随访日期升序（patient.md §1， 推断待确认）。C-PAT-009 沿用本口径，起搏器 /
 * ERI / 补录筛选随 E21 植入数据落地。
 */
@Service
public class PatientSearchService {

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 200;

    private static final int KEYWORD_MAX_LENGTH = 64;

    /** 随访状态（components.md §2 patient 域；与 pt_patient.followup_status 一致）。 */
    private static final Set<String> STATUS_VALUES =
            Set.of("ACTIVE", "PAUSED", "DEAD", "TERMINATED", "LOST", "FINISHED");

    /** 排序字段码 → 列（白名单；orderClause 只由这里的片段拼装，不透传请求原文）。 */
    private static final String DEFAULT_ORDER =
            "s.next_plan_date IS NULL ASC, s.next_plan_date ASC, p.id ASC";

    private final PatientSearchGateway gateway;
    private final PatientMapper patientMapper;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final PatientSearchFieldService searchFields;
    private final TenantViewFieldGuard viewGuard;
    private final CompletenessStatsService completenessStats;

    public PatientSearchService(
            PatientSearchGateway gateway,
            PatientMapper patientMapper,
            CryptoService crypto,
            AuditLogger audit,
            PatientSearchFieldService searchFields,
            TenantViewFieldGuard viewGuard,
            CompletenessStatsService completenessStats) {
        this.gateway = gateway;
        this.patientMapper = patientMapper;
        this.crypto = crypto;
        this.audit = audit;
        this.searchFields = searchFields;
        this.viewGuard = viewGuard;
        this.completenessStats = completenessStats;
    }

    // ---------------------------------------------------------------- DTO

    /** 检索请求（body 见 docs/api/README.md §2.4；filters / condition / completenessDrill 全部可空）。 */
    public record SearchRequest(
            Integer pageNo,
            Integer pageSize,
            List<SortSpec> sort,
            String keyword,
            Filters filters,
            JsonNode condition,
            CompletenessDrill completenessDrill) {

        /** 兼容旧调用方的构造器（无完整度钻取）。 */
        public SearchRequest(
                Integer pageNo,
                Integer pageSize,
                List<SortSpec> sort,
                String keyword,
                Filters filters,
                JsonNode condition) {
            this(pageNo, pageSize, sort, keyword, filters, condition, null);
        }
    }

    /**
     * 完整度「缺该字段的患者」钻取（E19-T05 / A-DASH-010、A-DASH-011「点击进入缺该字段的患者列表」）： kind = BASELINE 基线 / FOLLOWUP
     * 随访；fieldKey 为字段稳定键；其余为钻取时随卡片下发的完整度筛选（与卡片数字同一棵条件树—— ID 集由 {@link CompletenessStatsService}
     * 按同一计算给出，卡片缺失人数 = 列表 total）。
     */
    public record CompletenessDrill(
            String kind,
            String fieldKey,
            Long wardId,
            String patientType,
            LocalDate from,
            LocalDate to,
            String timeField,
            Integer year) {}

    /** 排序项：field 为白名单字段码，order 为 asc / desc。 */
    public record SortSpec(String field, String order) {}

    /** 简单筛选（A-PAT-002 中已有实体列承载的部分；术者 / 手术方式随 E13-T02 表单数据、逾期随 E15 任务）。 */
    public record Filters(
            List<Long> wardIds,
            List<Long> deptIds,
            List<String> status,
            Long doctorId,
            Integer dischargeStatus,
            String patientType,
            LocalDate surgeryFrom,
            LocalDate surgeryTo,
            LocalDate dischargeFrom,
            LocalDate dischargeTo,
            LocalDate nextVisitFrom,
            LocalDate nextVisitTo) {}

    /** 列表行（patient.md §1 PatientRow）：name / patientNo（本系统编号 = 病案号）只含脱敏值。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatientRowView(
            String id,
            String name,
            String patientNo,
            String legacyNo,
            String gender,
            Integer age,
            String wardName,
            String doctorName,
            LocalDate surgeryDate,
            LocalDate dischargeDate,
            LocalDate nextVisitDate,
            String status,
            Integer baselineCompleteness,
            BigDecimal followupCompleteness) {}

    /** 分页结果（统一分页口径）。 */
    public record PatientPage(List<PatientRowView> items, long total, int pageNo, int pageSize) {}

    /** 筛选候选：病区（启用项；行级隔离仍按数据范围，候选本身不敏感）。 */
    public record WardOptionView(long id, String name) {}

    public record FilterOptionsView(List<WardOptionView> wards) {}

    // ---------------------------------------------------------------- 检索（A-PAT-001、A-PAT-002）

    /** 条件检索一页（校验 → 盲索引 → 高级条件编译 → 范围内查询 → 脱敏 → 批量查询审计）。 */
    public PatientPage search(SearchRequest request) {
        SearchRequest safe =
                request == null
                        ? new SearchRequest(null, null, null, null, null, null, null)
                        : request;
        int pageNo = safe.pageNo() == null ? 1 : safe.pageNo();
        int pageSize = safe.pageSize() == null ? PAGE_SIZE_DEFAULT : safe.pageSize();
        if (pageNo < 1) {
            throw invalid("pageNo（从 1 开始）");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            throw invalid("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        Filters filters = safe.filters() == null ? emptyFilters() : safe.filters();
        SearchCriteriaBuilt built = criteriaOf(safe);

        int offset = (pageNo - 1) * pageSize;
        List<PatientRowView> items = new ArrayList<>();
        for (PatientSearchMapper.PatientRowRaw row :
                gateway.search(built.criteria(), offset, pageSize)) {
            items.add(toView(row));
        }
        long total = gateway.count(built.criteria());

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("PATIENT")
                        .description("检索患者列表")
                        .recordCount(total)
                        .queryDigest(digest(built.keyword(), filters, built.advanced()))
                        .build());
        return new PatientPage(items, total, pageNo, pageSize);
    }

    // ---------------------------------------------------------------- 导出行（E13-T09 / A-PAT-009）

    /**
     * 导出查询：与列表同一筛选面与高级检索条件树（A-PAT-009「按当前筛选导出」）。 不含检索词
     * keyword——检索词可能是患者姓名，与保存的筛选（fu_saved_filter）同口径不落库、不进导出条件。
     */
    public record ExportQuery(Filters filters, JsonNode condition) {}

    /** 提交导出时只校验不取数（非法筛选 / 目录外字段 / 越限条件树 40001），条件固化后审批重放。 */
    public void validateForExport(ExportQuery query) {
        criteriaOf(exportRequest(query));
    }

    /**
     * 导出行（生成时调用）：申请人数据范围内（调用线程会话承担数据范围改写）， 最多 {@code limit} 行，姓名 / 病案号脱敏输出（OQ-20：MASKED 字段导出脱敏值）。
     * 不写 BATCH_QUERY 审计——导出提交与下载已有 DATA_ACCESS 审计（EXPORT / DOWNLOAD）。
     */
    public List<PatientRowView> exportRows(ExportQuery query, int limit) {
        if (limit < 1) {
            throw invalid("limit");
        }
        SearchCriteriaBuilt built = criteriaOf(exportRequest(query));
        List<PatientRowView> items = new ArrayList<>();
        for (PatientSearchMapper.PatientRowRaw row : gateway.search(built.criteria(), 0, limit)) {
            items.add(toView(row));
        }
        return items;
    }

    private static SearchRequest exportRequest(ExportQuery query) {
        ExportQuery safe = query == null ? new ExportQuery(null, null) : query;
        // 导出不带完整度钻取（钻取是首页卡片的列表下钻，不进导出条件）
        return new SearchRequest(null, null, null, null, safe.filters(), safe.condition(), null);
    }

    /** 条件装配产物：检索条件 + 摘要要素（审计摘要用 keyword 布尔与条件行数，不落明文）。 */
    private record SearchCriteriaBuilt(
            PatientSearchMapper.SearchCriteria criteria,
            String keyword,
            AdvancedConditionCompiler.Compiled advanced) {}

    /** 列表与导出共用的条件装配与校验（检索词盲索引、筛选校验、高级条件树编译、排序白名单）。 */
    private SearchCriteriaBuilt criteriaOf(SearchRequest safe) {
        SearchRequest request =
                safe == null ? new SearchRequest(null, null, null, null, null, null, null) : safe;
        Filters filters = request.filters() == null ? emptyFilters() : request.filters();
        String keyword = normalizeKeyword(request.keyword());
        String orderClause = orderClauseOf(request.sort());

        String nameBlind = null;
        String mrnBlind = null;
        if (keyword != null) {
            // 同一检索词在姓名 / 病案号两个盲索引上精确匹配（SQL 内 OR）
            nameBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_NAME, keyword);
            mrnBlind = crypto.blindIndex(PatientRegistrationService.PURPOSE_MRN, keyword);
        }

        validateRange(filters.surgeryFrom(), filters.surgeryTo(), "surgeryFrom / surgeryTo");
        validateRange(
                filters.dischargeFrom(), filters.dischargeTo(), "dischargeFrom / dischargeTo");
        validateRange(
                filters.nextVisitFrom(), filters.nextVisitTo(), "nextVisitFrom / nextVisitTo");

        // 高级检索条件树：目录（字典生成）编译为参数化片段；无条件时 where=null 不追加
        AdvancedConditionCompiler.Compiled advanced =
                AdvancedConditionCompiler.compile(request.condition(), searchFields.specs());

        PatientSearchMapper.SearchCriteria criteria =
                new PatientSearchMapper.SearchCriteria(
                        nameBlind,
                        mrnBlind,
                        sanitizeIds(filters.wardIds(), "wardIds"),
                        sanitizeIds(filters.deptIds(), "deptIds"),
                        sanitizeStatus(filters.status()),
                        positiveOrNull(filters.doctorId(), "doctorId"),
                        sanitizeDischargeStatus(filters.dischargeStatus()),
                        sanitizePatientType(filters.patientType()),
                        filters.surgeryFrom(),
                        filters.surgeryTo(),
                        filters.dischargeFrom(),
                        filters.dischargeTo(),
                        filters.nextVisitFrom(),
                        filters.nextVisitTo(),
                        orderClause,
                        advanced.where(),
                        advanced.binds(),
                        missingPatientIdsOf(request.completenessDrill()));
        return new SearchCriteriaBuilt(criteria, keyword, advanced);
    }

    /**
     * 完整度钻取 ID 集（E19-T05）：按钻取筛选（卡片下发的同一棵条件树）重算缺该字段的患者； kind 非法 / fieldKey 空 40001；无钻取 → null
     * （不过滤）。空集表示范围内没有缺该字段的患者（列表返回空，不报错）。
     */
    private List<Long> missingPatientIdsOf(CompletenessDrill drill) {
        if (drill == null) {
            return null;
        }
        CompletenessStatsService.Query query =
                new CompletenessStatsService.Query(
                        drill.wardId(),
                        drill.patientType(),
                        drill.from(),
                        drill.to(),
                        drill.timeField(),
                        drill.year());
        if ("BASELINE".equals(drill.kind())) {
            return completenessStats.patientsMissingBaselineField(drill.fieldKey(), query);
        }
        if ("FOLLOWUP".equals(drill.kind())) {
            return completenessStats.patientsMissingFollowupField(drill.fieldKey(), query);
        }
        throw invalid("completenessDrill.kind（BASELINE / FOLLOWUP）");
    }

    /** 筛选候选（列表页病区下拉；随访状态等枚举文案由前端 enums 维护）。 */
    public FilterOptionsView filterOptions() {
        List<WardOptionView> wards =
                patientMapper.findWards().stream()
                        .map(item -> new WardOptionView(item.id(), item.name()))
                        .toList();
        return new FilterOptionsView(wards);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 行视图：姓名 / 病案号解密后脱敏（列表不提供逐行明文，明文走 E07-T05 专用接口）。 租户视角会话再经视角规则执行点（E20-T03 /
     * PLT-VIEW-010、011）：设盲替换「已设盲」、隐藏不返回，脱敏 / 明文规则保留脱敏值（视角不放宽列表明文）。
     */
    private PatientRowView toView(PatientSearchMapper.PatientRowRaw row) {
        String name = decrypt(row.nameCipher());
        String mrn = decrypt(row.mrnCipher());
        return new PatientRowView(
                String.valueOf(row.id()),
                viewGuard.apply(
                        "patient",
                        "name",
                        name == null ? "" : SensitiveValueMasker.mask("name", name)),
                viewGuard.apply(
                        "patient",
                        "medicalRecordNo",
                        mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn)),
                row.legacyNo(),
                row.gender(),
                row.age(),
                row.wardName(),
                row.doctorName(),
                row.surgeryDate(),
                row.dischargeDate(),
                row.nextVisitDate(),
                row.status(),
                row.baselineCompleteness(),
                row.followupCompleteness());
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }

    /** 检索词：去空白；超过姓名 / 病案号列上限（64）直接 40001，不做截断猜测。 */
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

    /** 排序子句：白名单字段码 + asc/desc；空 / 缺省用默认（下次随访升序）；p.id 恒为稳定次序。 */
    private static String orderClauseOf(List<SortSpec> sort) {
        if (sort == null || sort.isEmpty()) {
            return DEFAULT_ORDER;
        }
        SortSpec first = sort.get(0);
        if (first == null || first.field() == null || first.field().isBlank()) {
            return DEFAULT_ORDER;
        }
        String order =
                (first.order() == null || first.order().isBlank()
                                ? "asc"
                                : first.order().trim().toLowerCase())
                        .toUpperCase(java.util.Locale.ROOT);
        if (!"ASC".equals(order) && !"DESC".equals(order)) {
            throw invalid("sort.order（asc / desc）");
        }
        return switch (first.field().trim()) {
            case "nextVisitDate" ->
                    "s.next_plan_date IS NULL ASC, s.next_plan_date " + order + ", p.id " + order;
            case "dischargeDate" ->
                    "p.discharge_date IS NULL ASC, p.discharge_date " + order + ", p.id " + order;
            default -> throw invalid("sort.field（nextVisitDate / dischargeDate）");
        };
    }

    /** id 列表：去 null；出现非正值 40001；空列表视为不过滤（null）。 */
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

    private static List<String> sanitizeStatus(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String item : status) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String value = item.trim().toUpperCase();
            if (!STATUS_VALUES.contains(value)) {
                throw invalid("status（" + String.join(" / ", STATUS_VALUES) + "）");
            }
            cleaned.add(value);
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

    /** 出院状态口径同登记：A 规格字段 191——1=存活 / 2=病重自动出院 / 3=死亡。 */
    private static Integer sanitizeDischargeStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (status < 1 || status > 3) {
            throw invalid("dischargeStatus（1–3）");
        }
        return status;
    }

    private static String sanitizePatientType(String patientType) {
        if (patientType == null || patientType.isBlank()) {
            return null;
        }
        String trimmed = patientType.trim();
        if (trimmed.length() > 32) {
            throw invalid("patientType（最多 32 个字符）");
        }
        return trimmed;
    }

    /** 日期区间：开始晚于结束 40001。 */
    private static void validateRange(LocalDate from, LocalDate to, String field) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid(field + "（开始日期不能晚于结束日期）");
        }
    }

    private static Filters emptyFilters() {
        return new Filters(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 批量查询审计摘要：记录用了哪些筛选面与高级检索条件行数（不含检索词与条件取值明文—— 检索词可能是患者姓名，AGENTS.md §5.4 敏感值只存摘要）。 */
    private static String digest(
            String keyword, Filters filters, AdvancedConditionCompiler.Compiled advanced) {
        return "{\"keyword\":"
                + (keyword != null)
                + ",\"wardIds\":"
                + (filters.wardIds() != null && !filters.wardIds().isEmpty())
                + ",\"deptIds\":"
                + (filters.deptIds() != null && !filters.deptIds().isEmpty())
                + ",\"status\":"
                + (filters.status() != null && !filters.status().isEmpty())
                + ",\"doctorId\":"
                + (filters.doctorId() != null)
                + ",\"dischargeStatus\":"
                + (filters.dischargeStatus() != null)
                + ",\"patientType\":"
                + (filters.patientType() != null)
                + ",\"surgeryRange\":"
                + (filters.surgeryFrom() != null || filters.surgeryTo() != null)
                + ",\"dischargeRange\":"
                + (filters.dischargeFrom() != null || filters.dischargeTo() != null)
                + ",\"nextVisitRange\":"
                + (filters.nextVisitFrom() != null || filters.nextVisitTo() != null)
                + ",\"advancedRows\":"
                + advanced.rows()
                + "}";
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不正确：" + field);
    }
}
