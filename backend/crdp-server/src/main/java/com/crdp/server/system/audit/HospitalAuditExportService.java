package com.crdp.server.system.audit;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportStore.Schema;
import com.crdp.server.system.audit.AuditTables.Kind;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportJobView;
import com.crdp.server.system.audit.AuditViews.ExportRequest;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E08-T04 / PLT-AUD-007：医院端审计导出与审批（docs/api/audit.md §2、import-export.md §3）。 审批口径（platform-spec
 * §9，已确认）：租户管理员本人导出无需审批（照常记审计）， 租户普通用户导出由租户管理员审批。流程：提交 →（审批）→ 异步生成 → 限时下载（24 小时）。
 *
 * <p>导出条件在提交时校验（时间窗 ≤ 3 个月）并以 JSON 固化到 job_export.condition_json， 审批通过后按固化条件重放生成，不信任审批时的请求参数。
 *
 * <p>审计点：提交写 DATA_ACCESS/EXPORT；审批写 OPERATION/AUDIT_EXPORT_APPROVE（驳回 REJECT， 前后值为状态迁移）；每次下载写
 * DATA_ACCESS/DOWNLOAD（带文件哈希与行数）——「导出本身被记录」。
 */
@Service
public class HospitalAuditExportService {

    /** 内置租户管理员角色编码（审批人资格与免审批口径）。 */
    private static final String TENANT_ADMIN = "TENANT_ADMIN";

    private static final int REASON_MAX = 200;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final HospitalAuditService query;
    private final AuditExportStore store;
    private final AuditExportRunner runner;
    private final AuditLogger audit;
    private final DigestService digest;
    private final ObjectMapper json;

    public HospitalAuditExportService(
            JdbcTemplate jdbc,
            HospitalAuditService query,
            AuditExportStore store,
            AuditExportRunner runner,
            AuditLogger audit,
            DigestService digest,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.query = query;
        this.store = store;
        this.runner = runner;
        this.audit = audit;
        this.digest = digest;
        this.json = json;
    }

    /** 提交导出申请：租户管理员免审批直接生成，普通用户待租户管理员审批。 */
    public ExportSubmitted submit(ExportRequest request) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
        Kind kind = AuditTables.tenantKind(request.type());
        if (request.condition() == null) {
            throw new ApiException(400, 40001, "导出条件不能为空");
        }
        query.validate(kind, request.condition());
        String reason = trimToNull(request.reason());
        if (reason != null && reason.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "申请理由过长（最多 200 字）");
        }
        String summary = HospitalAuditService.conditionSummary(request.condition());
        boolean selfApproved =
                principal.roles() != null && principal.roles().contains(TENANT_ADMIN);
        String status =
                selfApproved ? AuditExportStore.STATUS_GENERATING : AuditExportStore.STATUS_PENDING;
        long exportId =
                store.insertJob(
                        Schema.TENANT,
                        null,
                        "AUDIT",
                        kind.name(),
                        writeCondition(request.condition()),
                        reason,
                        principal.userId(),
                        principal.username(),
                        status);
        audit.append(
                AuditEntry.dataAccess("system", "EXPORT")
                        .description(
                                (selfApproved ? "租户管理员导出审计日志（免审批）" : "申请导出审计日志（待审批）")
                                        + "："
                                        + typeLabel(kind))
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .queryDigest(summary)
                        .result(AuditResult.SUCCESS)
                        .build());
        if (selfApproved) {
            dispatch(exportId, kind, request.condition(), principal);
        }
        return new ExportSubmitted(exportId, status);
    }

    /** 我的导出（申请人视角）。 */
    public ExportJobPage mine(Integer pageNo, Integer pageSize) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
        return page(
                "WHERE e.applicant_id = ? AND e.biz_type = 'AUDIT'",
                List.of(principal.userId()),
                pageNo,
                pageSize);
    }

    /** 待审批列表（audit.md §2 / import-export.md §3：租户管理员）。 */
    public ExportJobPage pending(Integer pageNo, Integer pageSize) {
        CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
        return page(
                "WHERE e.status = ? AND e.biz_type = 'AUDIT'",
                List.of(AuditExportStore.STATUS_PENDING),
                pageNo,
                pageSize);
    }

    /** 审批通过：CAS 迁移 PENDING_APPROVAL → GENERATING 并按固化条件触发异步生成。 */
    public ExportJobView approve(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
        Map<String, Object> job = requireJob(exportId);
        Kind kind = Kind.valueOf(str(job.get("export_type")));
        SearchRequest condition = readCondition(job);
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
                AuditEntry.operation("system", "AUDIT_EXPORT_APPROVE")
                        .description("审批通过审计日志导出申请")
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"GENERATING\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        dispatch(exportId, kind, condition, principal);
        return view(requireJob(exportId));
    }

    /** 审批驳回（终态）。 */
    public ExportJobView reject(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
        requireJob(exportId);
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
                AuditEntry.operation("system", "AUDIT_EXPORT_REJECT")
                        .description("驳回审计日志导出申请")
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"REJECTED\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return view(requireJob(exportId));
    }

    /** 下载文件内容（PLT-AUD-007 限时下载）：仅申请人，READY 且未过 24 小时；每次下载写审计。 */
    public DownloadFile download(long exportId) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        HospitalAuditService.requireTenantContext();
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
            throw new ApiException(409, 40901, "导出任务已结束（" + statusText(status) + "），不能下载");
        }
        LocalDateTime expires = time(job.get("expires_at"));
        if (expires != null && expires.isBefore(LocalDateTime.now())) {
            throw new ApiException(409, 40901, "导出文件已超过 24 小时有效期，请重新申请");
        }
        byte[] body =
                jdbc.queryForObject(
                        "SELECT file_body FROM job_export WHERE id = ?", byte[].class, exportId);
        audit.append(
                AuditEntry.dataAccess("system", "DOWNLOAD")
                        .description("下载审计日志导出文件")
                        .objectType("AUDIT_EXPORT")
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

    /** 下载文件（Controller 直接以附件返回，不走统一 ApiResponse 包装）。 */
    public record DownloadFile(String fileName, byte[] body) {}

    // ---------------------------------------------------------------- 内部

    private void dispatch(
            long exportId, Kind kind, SearchRequest condition, SessionPrincipal principal) {
        runner.generate(
                exportId,
                id -> {
                    try {
                        List<Map<String, Object>> rows = query.exportRows(kind, condition);
                        String watermark =
                                AuditExportCsv.watermark(
                                        principal.displayName(),
                                        principal.username(),
                                        principal.tenantName(),
                                        LocalDateTime.now());
                        String note =
                                "审计日志导出（"
                                        + typeLabel(kind)
                                        + "）· 条件："
                                        + HospitalAuditService.conditionSummary(condition);
                        byte[] body =
                                AuditExportCsv.render(
                                        AuditTables.COLUMNS.get(kind), rows, watermark, note);
                        String fileName =
                                "audit-"
                                        + requestType(kind)
                                        + "-"
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
                    } catch (Exception e) {
                        store.markFailed(id, e.getMessage());
                    }
                });
    }

    private Map<String, Object> requireJob(long exportId) {
        Map<String, Object> job = store.job(exportId);
        if (job == null || !"AUDIT".equals(str(job.get("biz_type")))) {
            throw new ApiException(404, 40400, "导出任务不存在");
        }
        return job;
    }

    private ExportJobPage page(String where, List<Object> args, Integer pageNo, Integer pageSize) {
        int no = AuditQuerySupport.pageNo(pageNo);
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
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

    /** 作业行 → 视图（医院端与平台端共用装配）。 */
    public static ExportJobView view(Map<String, Object> r) {
        return new ExportJobView(
                ((Number) r.get("id")).longValue(),
                str(r.get("biz_type")),
                str(r.get("export_type")),
                str(r.get("condition_json")),
                str(r.get("reason")),
                ((Number) r.get("applicant_id")).longValue(),
                str(r.get("applicant_name")),
                str(r.get("applicant_display_name")),
                str(r.get("status")),
                str(r.get("approver_name")),
                str(r.get("approve_comment")),
                r.get("row_count") == null ? null : ((Number) r.get("row_count")).longValue(),
                str(r.get("file_name")),
                str(r.get("file_hash")),
                str(r.get("fail_reason")),
                AuditQuerySupport.format(time(r.get("expires_at"))),
                AuditQuerySupport.format(time(r.get("created_at"))));
    }

    private String writeCondition(SearchRequest condition) {
        try {
            String text = json.writeValueAsString(condition);
            return text.length() > 1000 ? text.substring(0, 1000) : text;
        } catch (Exception e) {
            throw new ApiException(400, 40001, "导出条件不合法");
        }
    }

    private SearchRequest readCondition(Map<String, Object> job) {
        try {
            return json.readValue(str(job.get("condition_json")), SearchRequest.class);
        } catch (Exception e) {
            throw new ApiException(500, 50000, "导出条件损坏，无法生成文件");
        }
    }

    private static String commentOf(ApprovalAction action) {
        String comment = action == null ? null : trimToNull(action.comment());
        if (comment != null && comment.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "审批意见过长（最多 200 字）");
        }
        return comment;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING_APPROVAL" -> "待审批";
            case "GENERATING" -> "生成中";
            case "READY" -> "可下载";
            case "REJECTED" -> "已驳回";
            case "FAILED" -> "生成失败";
            default -> status;
        };
    }

    static String typeLabel(Kind kind) {
        return switch (kind) {
            case OPERATION -> "操作日志";
            case LOGIN -> "登录日志";
            case DATA_ACCESS -> "数据访问日志";
            case PLATFORM_ACCESS -> "平台访问日志";
        };
    }

    static String requestType(Kind kind) {
        return switch (kind) {
            case OPERATION -> "operation";
            case LOGIN -> "login";
            case DATA_ACCESS -> "data-access";
            case PLATFORM_ACCESS -> "platform-access";
        };
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
