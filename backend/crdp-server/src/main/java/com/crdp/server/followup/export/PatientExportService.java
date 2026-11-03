package com.crdp.server.followup.export;

import com.crdp.followup.patient.app.PatientSearchService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportCsv;
import com.crdp.server.system.audit.AuditExportRunner;
import com.crdp.server.system.audit.AuditExportStore;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportJobView;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.HospitalAuditExportService;
import com.crdp.server.system.audit.HospitalAuditExportService.DownloadFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * E13-T09 / A-PAT-009、C-PAT-014 患者导出（审批、水印码）。验收原文：导出需审批。
 *
 * <p>复用 E08-T04 的导出作业框架（job_export / job_export_approval，租户库 base V19；biz_type = PATIENT，
 * scope_json 冻结申请人数据范围与租户名，base V20 新增列）。审批口径（platform-spec §9，已确认）：
 * 租户管理员本人导出无需审批（照常记审计），租户普通用户导出由租户管理员审批，申请人不能审批自己的申请。
 *
 * <p>数据口径：
 *
 * <ul>
 *   <li>按当前筛选导出（A-PAT-009）——条件树与患者列表同一套（filters + 高级检索 condition）， 不含检索词
 *       keyword（检索词可能是患者姓名，与保存的筛选同口径不落库）；提交时校验并固化到 condition_json，
 *       审批通过后按固化条件重放生成，不信任审批时的请求参数（与审计导出同口径）；
 *   <li>按导出人的数据范围（import-export.md §3）——提交时冻结申请人 ScopeSnapshot，异步生成线程以申请人身份 重建会话执行同一 @DataScope
 *       查询（拦截器按快照改写，越范围患者不进入导出文件）；
 *   <li>脱敏（OQ-20）——姓名 / 病案号导出脱敏值，明文不导出；证件号 / 住址 / 电话不在患者列表列内，不导出；
 *   <li>可选编码或显示名（A-PAT-009 valueMode）——CODE 出库内编码，LABEL 出中文显示名；
 *   <li>水印（OQ-22）——每行首列 {@code 姓名（账号）· 医院名称 · 时间（到分钟）}，并附水印码 （SHA-256 经 DigestService 绑定导出作业 + 申请人
 *       + 行数的前 12 位十六进制，tech-plan §「导出水印码」）， 泄露文件可凭码对账定位导出作业。
 * </ul>
 *
 * <p>审计：提交写 DATA_ACCESS/EXPORT（module=followup，触发 BATCH_EXPORT 安全告警 PLT-AUD-012）； 审批写
 * OPERATION/PATIENT_EXPORT_APPROVE（驳回 REJECT）；每次下载写 DATA_ACCESS/DOWNLOAD（带文件哈希与行数）。
 *
 * <p>范围口径：本任务只落 scope=PATIENT（患者列表列）；BASELINE / FOLLOWUP / IMPLANT 随 E13-T02 / E15 / E21
 * 数据落地在同一框架内扩展（import-export.md §3）。
 */
@Service
public class PatientExportService {

    /** 内置租户管理员角色编码（免审批与审批人资格口径，platform-spec §9）。 */
    private static final String TENANT_ADMIN = "TENANT_ADMIN";

    private static final String BIZ_TYPE = "PATIENT";

    /** 当前开放的导出范围（A-PAT-009 基线与随访数据随 E13-T02 / E15 接入同框架）。 */
    private static final String SCOPE_PATIENT = "PATIENT";

    private static final String VALUE_MODE_CODE = "CODE";
    private static final String VALUE_MODE_LABEL = "LABEL";

    private static final int REASON_MAX = 200;

    /** 患者随访状态显示名（components.md §2 patient 域，与前端 PATIENT_STATUS_TEXT 同口径）。 */
    private static final Map<String, String> STATUS_LABELS =
            Map.of(
                    "ACTIVE", "随访中",
                    "PAUSED", "已暂停",
                    "DEAD", "死亡",
                    "TERMINATED", "停止",
                    "LOST", "失访",
                    "FINISHED", "随访结束");

    /** 导出列白名单（序号即列序；fields 只能从中选取，默认全集）。 */
    private static final LinkedHashMap<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("id", "系统编号");
        COLUMNS.put("name", "姓名（脱敏）");
        COLUMNS.put("patientNo", "病案号（脱敏）");
        COLUMNS.put("legacyNo", "原系统编号");
        COLUMNS.put("gender", "性别");
        COLUMNS.put("age", "手术年龄");
        COLUMNS.put("wardName", "病区");
        COLUMNS.put("doctorName", "责任医生");
        COLUMNS.put("surgeryDate", "手术日期");
        COLUMNS.put("dischargeDate", "出院日期");
        COLUMNS.put("nextVisitDate", "下次随访日期");
        COLUMNS.put("status", "随访状态");
        COLUMNS.put("baselineCompleteness", "基线资料完整度(%)");
        COLUMNS.put("followupCompleteness", "随访资料完整度(%)");
    }

    private final JdbcTemplate jdbc;
    private final PatientSearchService patientSearch;
    private final AuditExportStore store;
    private final AuditExportRunner runner;
    private final AuditLogger audit;
    private final DigestService digest;
    private final ObjectMapper json;

    public PatientExportService(
            JdbcTemplate jdbc,
            PatientSearchService patientSearch,
            AuditExportStore store,
            AuditExportRunner runner,
            AuditLogger audit,
            DigestService digest,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.patientSearch = patientSearch;
        this.store = store;
        this.runner = runner;
        this.audit = audit;
        this.digest = digest;
        this.json = json;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * 提交导出申请（import-export.md §3）：scope 当前只支持 PATIENT；condition 与列表检索同构（filters +
     * 高级检索条件树，不含检索词）；valueMode 可选编码或显示名；fields 从列白名单选取（缺省全集）。
     */
    public record ExportRequest(
            String scope,
            String valueMode,
            List<String> fields,
            PatientSearchService.ExportQuery condition,
            String reason) {}

    /** 固化到 job_export.condition_json 的条件（审批后按此重放，不信任审批时请求参数）。 */
    record StoredCondition(
            String scope,
            String valueMode,
            List<String> fields,
            PatientSearchService.Filters filters,
            JsonNode condition) {

        PatientSearchService.ExportQuery query() {
            return new PatientSearchService.ExportQuery(filters, condition);
        }
    }

    /** 固化到 job_export.scope_json 的生成上下文（提交时的租户名与申请人数据范围）。 */
    record FrozenContext(String tenantName, ScopeSnapshot scope) {}

    // ---------------------------------------------------------------- 提交（A-PAT-009）

    /** 提交导出申请：租户管理员免审批直接生成，普通用户待租户管理员审批（验收「导出需审批」）。 */
    public ExportSubmitted submit(ExportRequest request) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        requireTenantContext();
        ExportRequest safe =
                request == null ? new ExportRequest(null, null, null, null, null) : request;
        StoredCondition condition = sanitize(safe);
        String reason = trimToNull(safe.reason());
        if (reason != null && reason.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "申请理由过长（最多 200 字）");
        }
        // 条件提交时校验并固化（非法筛选 / 目录外条件树 40001）
        patientSearch.validateForExport(condition.query());

        boolean selfApproved =
                principal.roles() != null && principal.roles().contains(TENANT_ADMIN);
        String status =
                selfApproved ? AuditExportStore.STATUS_GENERATING : AuditExportStore.STATUS_PENDING;
        long exportId =
                store.insertJob(
                        AuditExportStore.Schema.TENANT,
                        null,
                        BIZ_TYPE,
                        condition.scope(),
                        writeJson(condition),
                        reason,
                        principal.userId(),
                        principal.username(),
                        status);
        // 冻结申请人数据范围与租户名（生成线程按导出人范围取数，import-export.md §3）
        jdbc.update(
                "UPDATE job_export SET scope_json = ? WHERE id = ?",
                writeJson(new FrozenContext(principal.tenantName(), principal.scope())),
                exportId);

        audit.append(
                AuditEntry.dataAccess("followup", "EXPORT")
                        .description((selfApproved ? "租户管理员导出患者数据（免审批）" : "申请导出患者数据（待审批）"))
                        .objectType("PATIENT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .queryDigest(conditionDigest(condition))
                        .result(AuditResult.SUCCESS)
                        .build());
        if (selfApproved) {
            dispatch(exportId);
        }
        return new ExportSubmitted(exportId, status);
    }

    /** 我的导出（申请人视角）。 */
    public ExportJobPage mine(Integer pageNo, Integer pageSize) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        requireTenantContext();
        return page(
                "WHERE e.applicant_id = ? AND e.biz_type = 'PATIENT'",
                List.of(principal.userId()),
                pageNo,
                pageSize);
    }

    /** 待审批列表（import-export.md §3：租户管理员，followup:patient:export:approve）。 */
    public ExportJobPage pending(Integer pageNo, Integer pageSize) {
        CurrentSession.requireHospital();
        requireTenantContext();
        return page(
                "WHERE e.status = ? AND e.biz_type = 'PATIENT'",
                List.of(AuditExportStore.STATUS_PENDING),
                pageNo,
                pageSize);
    }

    // ---------------------------------------------------------------- 审批（platform-spec §9）

    /** 审批通过：CAS 迁移 PENDING_APPROVAL → GENERATING 并按固化条件触发异步生成。 */
    public ExportJobView approve(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        requireTenantContext();
        Map<String, Object> job = requireJob(exportId);
        requireNotSelf(job, principal);
        String comment = commentOf(action);
        if (!store.decide(
                exportId,
                AuditExportStore.STATUS_PENDING,
                AuditExportStore.STATUS_GENERATING,
                principal.userId(),
                principal.username(),
                comment)) {
            throw new ApiException(409, 40901, "该申请不在待审批状态，不能审批");
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_EXPORT_APPROVE")
                        .description("审批通过患者数据导出申请")
                        .objectType("PATIENT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"GENERATING\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        dispatch(exportId);
        return HospitalAuditExportService.view(requireJob(exportId));
    }

    /** 审批驳回（终态）。 */
    public ExportJobView reject(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        requireTenantContext();
        Map<String, Object> job = requireJob(exportId);
        requireNotSelf(job, principal);
        String comment = commentOf(action);
        if (!store.decide(
                exportId,
                AuditExportStore.STATUS_PENDING,
                AuditExportStore.STATUS_REJECTED,
                principal.userId(),
                principal.username(),
                comment)) {
            throw new ApiException(409, 40901, "该申请不在待审批状态，不能驳回");
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_EXPORT_REJECT")
                        .description("驳回患者数据导出申请")
                        .objectType("PATIENT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"REJECTED\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return HospitalAuditExportService.view(requireJob(exportId));
    }

    // ---------------------------------------------------------------- 下载（限时）

    /** 下载文件内容（PLT-AUD-007 限时下载，与审计导出同表同口径）：仅申请人，READY 且未过 24 小时； 每次下载写审计（带文件哈希与行数）。 */
    public DownloadFile download(long exportId) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        requireTenantContext();
        Map<String, Object> job = requireJob(exportId);
        if (((Number) job.get("applicant_id")).longValue() != principal.userId()) {
            // 不暴露他人作业的存在性（AGENTS 第 5 节数据范围口径）
            throw new ApiException(404, 40400, "导出任务不存在");
        }
        String status = str(job.get("status"));
        if (AuditExportStore.STATUS_PENDING.equals(status)) {
            throw new ApiException(409, 40901, "导出申请尚未审批，不能下载");
        }
        if (AuditExportStore.STATUS_GENERATING.equals(status)) {
            throw new ApiException(409, 40901, "导出文件生成中，请稍后下载");
        }
        if (!AuditExportStore.STATUS_READY.equals(status)) {
            throw new ApiException(
                    409,
                    40901,
                    "导出任务已结束（" + HospitalAuditExportService.statusText(status) + "），不能下载");
        }
        LocalDateTime expires = time(job.get("expires_at"));
        if (expires != null && expires.isBefore(LocalDateTime.now())) {
            throw new ApiException(409, 40901, "导出文件已超过 24 小时有效期，请重新申请");
        }
        byte[] body =
                jdbc.queryForObject(
                        "SELECT file_body FROM job_export WHERE id = ?", byte[].class, exportId);
        audit.append(
                AuditEntry.dataAccess("followup", "DOWNLOAD")
                        .description("下载患者数据导出文件")
                        .objectType("PATIENT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .fileHash(str(job.get("file_hash")))
                        .recordCount(
                                job.get("row_count") == null
                                        ? null
                                        : ((Number) job.get("row_count")).longValue())
                        .result(AuditResult.SUCCESS)
                        .build());
        return new DownloadFile(str(job.get("file_name")), body == null ? new byte[0] : body);
    }

    // ---------------------------------------------------------------- 内部：校验与装配

    private StoredCondition sanitize(ExportRequest request) {
        String scope =
                request.scope() == null || request.scope().isBlank()
                        ? SCOPE_PATIENT
                        : request.scope().trim().toUpperCase();
        if (!SCOPE_PATIENT.equals(scope)) {
            throw new ApiException(400, 40001, "导出范围暂只支持 PATIENT（基线 / 随访 / 植入数据随后续任务开放）");
        }
        String valueMode =
                request.valueMode() == null || request.valueMode().isBlank()
                        ? VALUE_MODE_LABEL
                        : request.valueMode().trim().toUpperCase();
        if (!VALUE_MODE_CODE.equals(valueMode) && !VALUE_MODE_LABEL.equals(valueMode)) {
            throw new ApiException(400, 40001, "valueMode（CODE / LABEL）");
        }
        return new StoredCondition(
                scope,
                valueMode,
                sanitizeFields(request.fields()),
                filtersOf(request),
                nodeOf(request));
    }

    private static PatientSearchService.Filters filtersOf(ExportRequest request) {
        PatientSearchService.ExportQuery condition = request.condition();
        return condition == null ? null : condition.filters();
    }

    private static JsonNode nodeOf(ExportRequest request) {
        PatientSearchService.ExportQuery condition = request.condition();
        return condition == null ? null : condition.condition();
    }

    /** 列选择：白名单内保序去重；空 / 缺省 = 全集；白名单外 40001。 */
    private static List<String> sanitizeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.copyOf(COLUMNS.keySet());
        }
        List<String> cleaned = new ArrayList<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String name = field.trim();
            if (!COLUMNS.containsKey(name)) {
                throw new ApiException(
                        400, 40001, "fields（导出列只允许：" + String.join(" / ", COLUMNS.keySet()) + "）");
            }
            if (!cleaned.contains(name)) {
                cleaned.add(name);
            }
        }
        return cleaned.isEmpty() ? List.copyOf(COLUMNS.keySet()) : List.copyOf(cleaned);
    }

    private void requireTenantContext() {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            throw new ApiException(403, 40300, "平台账号不能访问医院端患者导出");
        }
    }

    /** 申请人不能审批自己的申请（platform-spec §9 已确认；医院侧同口径防御）。 */
    private static void requireNotSelf(Map<String, Object> job, SessionPrincipal principal) {
        if (((Number) job.get("applicant_id")).longValue() == principal.userId()) {
            throw new ApiException(409, 40901, "申请人不能审批自己的导出申请");
        }
    }

    private static String commentOf(ApprovalAction action) {
        String comment = action == null ? null : trimToNull(action.comment());
        if (comment != null && comment.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "审批意见过长（最多 200 字）");
        }
        return comment;
    }

    private Map<String, Object> requireJob(long exportId) {
        Map<String, Object> job = store.job(exportId);
        if (job == null || !BIZ_TYPE.equals(str(job.get("biz_type")))) {
            throw new ApiException(404, 40400, "导出任务不存在");
        }
        return job;
    }

    // ---------------------------------------------------------------- 内部：异步生成

    /** 触发生成（提交免审批或审批通过后）：失败写 FAILED（终态），成功写 READY + 24 小时时限。 */
    private void dispatch(long exportId) {
        Map<String, Object> job = requireJob(exportId);
        FrozenContext context = readContext(job);
        StoredCondition condition = readCondition(job);
        long applicantId = ((Number) job.get("applicant_id")).longValue();
        String username = str(job.get("applicant_name"));
        String displayName = displayNameOf(applicantId, username);
        runner.generate(
                exportId,
                id -> {
                    try {
                        if (context == null || context.scope() == null) {
                            // 冻结范围缺失：不盲目放行（默认拒绝），作业失败可重新申请
                            store.markFailed(id, "导出上下文缺失（申请人数据范围未冻结），请重新申请");
                            return;
                        }
                        List<PatientSearchService.PatientRowView> rows =
                                withApplicantSession(
                                        context,
                                        applicantId,
                                        username,
                                        displayName,
                                        () ->
                                                patientSearch.exportRows(
                                                        condition.query(),
                                                        AuditExportCsv.MAX_ROWS));
                        String code = watermarkCode(id, applicantId, rows.size());
                        String watermark =
                                AuditExportCsv.watermark(
                                                displayName,
                                                username,
                                                context.tenantName(),
                                                LocalDateTime.now())
                                        + " · 水印码 "
                                        + code;
                        String note =
                                "患者列表导出 · 值模式："
                                        + (VALUE_MODE_LABEL.equals(condition.valueMode())
                                                ? "显示名"
                                                : "编码")
                                        + " · 列："
                                        + condition.fields().size()
                                        + " · 共 "
                                        + rows.size()
                                        + " 行（姓名 / 病案号为脱敏值）";
                        byte[] body =
                                AuditExportCsv.render(
                                        headersOf(condition.fields()),
                                        rowMaps(rows, condition),
                                        watermark,
                                        note);
                        String fileName =
                                "patient-export-"
                                        + LocalDateTime.now()
                                                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                                        + ".csv";
                        store.markReady(
                                id,
                                rows.size(),
                                fileName,
                                digest.hashHex(new String(body, StandardCharsets.UTF_8)),
                                body,
                                LocalDateTime.now().plusHours(AuditExportStore.DOWNLOAD_HOURS));
                    } catch (Exception failure) {
                        store.markFailed(id, failure.getMessage());
                    }
                });
    }

    /**
     * 以申请人身份执行生成取数：异步线程无登录会话，用冻结的租户名 + 数据范围快照重建 会话（令牌为一次性作业标记，不对应真实会话），执行完清理（AGENTS 第 5
     * 节「异步任务必须显式设置并清理上下文」；租户上下文由 TenantContextTaskDecorator 复制）。
     * 数据范围改写以快照为准——审批人（通常全院范围）不放大导出内容，范围收紧也不缩小（提交时已固化）。
     */
    private <T> T withApplicantSession(
            FrozenContext context,
            long applicantId,
            String username,
            String displayName,
            java.util.function.Supplier<T> action) {
        TenantContext.State tenant = TenantContext.require();
        SessionPrincipal applicant =
                new SessionPrincipal(
                        "patient-export:" + context.hashCode(),
                        "HOSPITAL",
                        tenant.tenantId(),
                        tenant.tenantCode(),
                        context.tenantName(),
                        applicantId,
                        username,
                        displayName,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0L,
                        context.scope(),
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(applicant, null, List.of()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 水印码（tech-plan §「导出水印码」）：绑定导出作业 + 申请人 + 行数的摘要前 12 位，泄露文件可对账定位作业。 */
    private String watermarkCode(long exportId, long applicantId, long rowCount) {
        return digest.hashHex("PATIENT-EXPORT:" + exportId + ":" + applicantId + ":" + rowCount)
                .substring(0, 12);
    }

    /** 列头（按白名单序）。 */
    private static List<String> headersOf(List<String> fields) {
        return fields.stream().map(COLUMNS::get).toList();
    }

    /**
     * 行装配：view → 列值（键 = 列头，与 {@link AuditExportCsv#render} 的取数口径一致）； LABEL 模式把性别 / 随访状态转显示名，CODE
     * 模式出库内编码。
     */
    private static List<Map<String, Object>> rowMaps(
            List<PatientSearchService.PatientRowView> rows, StoredCondition condition) {
        boolean label = VALUE_MODE_LABEL.equals(condition.valueMode());
        List<Map<String, Object>> maps = new ArrayList<>(rows.size());
        for (PatientSearchService.PatientRowView row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(COLUMNS.get("id"), row.id());
            map.put(COLUMNS.get("name"), row.name());
            map.put(COLUMNS.get("patientNo"), row.patientNo());
            map.put(COLUMNS.get("legacyNo"), row.legacyNo());
            map.put(COLUMNS.get("gender"), label ? genderLabel(row.gender()) : row.gender());
            map.put(COLUMNS.get("age"), row.age());
            map.put(COLUMNS.get("wardName"), row.wardName());
            map.put(COLUMNS.get("doctorName"), row.doctorName());
            map.put(COLUMNS.get("surgeryDate"), row.surgeryDate());
            map.put(COLUMNS.get("dischargeDate"), row.dischargeDate());
            map.put(COLUMNS.get("nextVisitDate"), row.nextVisitDate());
            map.put(
                    COLUMNS.get("status"),
                    label ? STATUS_LABELS.getOrDefault(row.status(), row.status()) : row.status());
            map.put(COLUMNS.get("baselineCompleteness"), row.baselineCompleteness());
            map.put(COLUMNS.get("followupCompleteness"), row.followupCompleteness());
            maps.add(map);
        }
        return maps;
    }

    private static String genderLabel(String gender) {
        return "M".equals(gender) ? "男" : "F".equals(gender) ? "女" : gender;
    }

    private String displayNameOf(long applicantId, String fallback) {
        List<String> names =
                jdbc.queryForList(
                        "SELECT display_name FROM sys_user WHERE id = ?",
                        String.class,
                        applicantId);
        return names.isEmpty() || names.get(0) == null || names.get(0).isBlank()
                ? fallback
                : names.get(0);
    }

    // ---------------------------------------------------------------- 内部：视图与序列化

    private ExportJobPage page(String where, List<Object> args, Integer pageNo, Integer pageSize) {
        int no = pageNoOf(pageNo);
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM job_export e " + where, Long.class, args.toArray());
        int offset = (no - 1) * size;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.id, e.biz_type, e.export_type, e.condition_json, e.reason,"
                                + " e.applicant_id, e.applicant_name, e.status, e.approver_id,"
                                + " e.approver_name, e.approve_comment, e.row_count, e.file_name,"
                                + " e.file_hash, e.fail_reason, e.expires_at, e.created_at,"
                                + " u.display_name AS applicant_display_name FROM job_export e"
                                + " LEFT JOIN sys_user u ON u.id = e.applicant_id "
                                + where
                                + " ORDER BY e.id DESC LIMIT "
                                + size
                                + " OFFSET "
                                + offset,
                        args.toArray());
        return new ExportJobPage(
                rows.stream().map(HospitalAuditExportService::view).toList(),
                total == null ? 0 : total,
                no,
                size);
    }

    private static int pageNoOf(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private StoredCondition readCondition(Map<String, Object> job) {
        try {
            return json.readValue(str(job.get("condition_json")), StoredCondition.class);
        } catch (Exception failure) {
            throw new ApiException(500, 50000, "导出条件损坏，无法生成文件");
        }
    }

    private FrozenContext readContext(Map<String, Object> job) {
        String text = str(job.get("scope_json"));
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readValue(text, FrozenContext.class);
        } catch (Exception failure) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            String text = json.writeValueAsString(value);
            return text.length() > 1000 ? text.substring(0, 1000) : text;
        } catch (Exception failure) {
            throw new ApiException(400, 40001, "导出条件不合法");
        }
    }

    /** 提交审计摘要：只记范围 / 值模式 / 列数等条件面，不含患者数据。 */
    private static String conditionDigest(StoredCondition condition) {
        PatientSearchService.Filters filters = condition.filters();
        boolean hasFilters = filters != null;
        return "{\"scope\":\""
                + condition.scope()
                + "\",\"valueMode\":\""
                + condition.valueMode()
                + "\",\"fields\":"
                + condition.fields().size()
                + ",\"filters\":"
                + hasFilters
                + ",\"advanced\":"
                + (hasFilters || condition.condition() != null)
                + "}";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
