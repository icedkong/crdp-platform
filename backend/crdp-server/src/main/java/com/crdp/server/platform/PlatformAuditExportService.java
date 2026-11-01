package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportCsv;
import com.crdp.server.system.audit.AuditExportRunner;
import com.crdp.server.system.audit.AuditExportStore;
import com.crdp.server.system.audit.AuditExportStore.Schema;
import com.crdp.server.system.audit.AuditTables.Kind;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportJobView;
import com.crdp.server.system.audit.AuditViews.ExportRequest;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
import com.crdp.server.system.audit.HospitalAuditExportService;
import com.crdp.server.system.audit.HospitalAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E08-T04 / PLT-AUD-007：平台端审计导出与审批（docs/api/audit.md §3）。 审批口径（platform-spec §9，已确认）：平台账号导出
 * 一律由平台管理员审批，且申请人不能审批自己的申请（即使是平台管理员本人）。 流程与租户侧一致：提交（恒待审批）→ 平台管理员审批 → 异步生成 → 限时下载（24 小时）；导出本身全程记审计。
 */
@Service
public class PlatformAuditExportService {

    private static final int REASON_MAX = 200;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final PlatformAuditService query;
    private final AuditExportStore store;
    private final AuditExportRunner runner;
    private final AuditLogger audit;
    private final DigestService digest;
    private final ObjectMapper json;

    public PlatformAuditExportService(
            JdbcTemplate jdbc,
            PlatformAuditService query,
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

    /** 提交导出申请：平台账号一律待审批（platform-spec §9 已确认）。 */
    public ExportSubmitted submit(ExportRequest request) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
        query.validate(request.type(), request.condition());
        String reason =
                request.reason() == null || request.reason().isBlank()
                        ? null
                        : request.reason().trim();
        if (reason != null && reason.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "申请理由过长（最多 200 字）");
        }
        String summary = HospitalAuditService.conditionSummary(request.condition());
        long exportId =
                store.insertJob(
                        Schema.PLATFORM,
                        request.condition() == null ? null : request.condition().tenantId(),
                        "AUDIT",
                        request.type(),
                        writeCondition(request.condition()),
                        reason,
                        principal.userId(),
                        principal.username(),
                        AuditExportStore.STATUS_PENDING);
        audit.append(
                AuditEntry.dataAccess("platform", "EXPORT")
                        .description("申请导出平台审计日志（待审批）：" + request.type())
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .queryDigest(summary)
                        .result(AuditResult.SUCCESS)
                        .build());
        return new ExportSubmitted(exportId, AuditExportStore.STATUS_PENDING);
    }

    /** 我的导出（申请人视角）。 */
    public ExportJobPage mine(Integer pageNo, Integer pageSize) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
        return page(
                "WHERE e.applicant_id = ? AND e.biz_type = 'AUDIT'",
                List.of(principal.userId()),
                pageNo,
                pageSize);
    }

    /** 待审批列表（platform:audit:export:approve，平台管理员）。 */
    public ExportJobPage pending(Integer pageNo, Integer pageSize) {
        CurrentSession.requirePlatform();
        requirePlatformContext();
        return page(
                "WHERE e.status = ? AND e.biz_type = 'AUDIT'",
                List.of(AuditExportStore.STATUS_PENDING),
                pageNo,
                pageSize);
    }

    /** 审批通过：申请人不能审批自己的申请（40901）；CAS 迁移后按固化条件异步生成。 */
    public ExportJobView approve(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
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
                AuditEntry.operation("platform", "AUDIT_EXPORT_APPROVE")
                        .description("审批通过平台审计日志导出申请")
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"GENERATING\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        // 水印记申请人（导出人），不是审批人：从 plt_account 取申请人显示名
        String applicantName = str(job.get("applicant_name"));
        String applicantDisplay =
                jdbc.queryForObject(
                        "SELECT display_name FROM plt_account WHERE username = ?",
                        String.class,
                        applicantName);
        if (applicantDisplay == null) {
            applicantDisplay = applicantName;
        }
        dispatch(exportId, requestType(job), readCondition(job), applicantDisplay, applicantName);
        return HospitalAuditExportService.view(requireJob(exportId));
    }

    /** 审批驳回（终态）。 */
    public ExportJobView reject(long exportId, ApprovalAction action) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
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
                AuditEntry.operation("platform", "AUDIT_EXPORT_REJECT")
                        .description("驳回平台审计日志导出申请")
                        .objectType("AUDIT_EXPORT")
                        .objectId(String.valueOf(exportId))
                        .beforeJson("{\"status\":\"PENDING_APPROVAL\"}")
                        .afterJson("{\"status\":\"REJECTED\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return HospitalAuditExportService.view(requireJob(exportId));
    }

    /** 下载：仅申请人，READY 且未过 24 小时；每次下载写审计。 */
    public DownloadFile download(long exportId) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
        Map<String, Object> job = requireJob(exportId);
        if (((Number) job.get("applicant_id")).longValue() != principal.userId()) {
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
                AuditEntry.dataAccess("platform", "DOWNLOAD")
                        .description("下载平台审计日志导出文件")
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

    /** 下载文件（Controller 直接以附件返回）。 */
    public record DownloadFile(String fileName, byte[] body) {}

    // ---------------------------------------------------------------- 内部

    private void dispatch(
            long exportId,
            String type,
            SearchRequest condition,
            String applicantDisplay,
            String applicant) {
        runner.generate(
                exportId,
                id -> {
                    try {
                        List<Map<String, Object>> rows = query.exportRows(type, condition);
                        Kind kind = com.crdp.server.system.audit.AuditTables.platformKind(type);
                        String watermark =
                                AuditExportCsv.watermark(
                                        applicantDisplay, applicant, null, LocalDateTime.now());
                        String note =
                                "平台审计日志导出（"
                                        + type
                                        + "）· 条件："
                                        + HospitalAuditService.conditionSummary(condition);
                        byte[] body =
                                AuditExportCsv.render(
                                        PlatformAuditService.platformColumns(kind),
                                        rows,
                                        watermark,
                                        note);
                        String fileName =
                                "platform-audit-"
                                        + type
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

    private static void requireNotSelf(Map<String, Object> job, SessionPrincipal principal) {
        if (((Number) job.get("applicant_id")).longValue() == principal.userId()) {
            throw new ApiException(409, 40901, "申请人不能审批自己的导出申请（PLT-AUD-007）");
        }
    }

    private Map<String, Object> requireJob(long exportId) {
        Map<String, Object> job = store.job(exportId);
        if (job == null || !"AUDIT".equals(str(job.get("biz_type")))) {
            throw new ApiException(404, 40400, "导出任务不存在");
        }
        return job;
    }

    private static String requestType(Map<String, Object> job) {
        return str(job.get("export_type"));
    }

    private ExportJobPage page(String where, List<Object> args, Integer pageNo, Integer pageSize) {
        int no = com.crdp.server.system.audit.AuditQuerySupport.pageNo(pageNo);
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM job_export e " + where, Long.class, args.toArray());
        int offset = (no - 1) * size;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.id, e.tenant_id, e.biz_type, e.export_type, e.condition_json,"
                                + " e.reason, e.applicant_id, e.applicant_name, e.status, e.approver_id,"
                                + " e.approver_name, e.approve_comment, e.row_count, e.file_name,"
                                + " e.file_hash, e.fail_reason, e.expires_at, e.created_at,"
                                + " a.display_name AS applicant_display_name FROM job_export e"
                                + " LEFT JOIN plt_account a ON a.id = e.applicant_id "
                                + where
                                + " ORDER BY e.id DESC LIMIT "
                                + size
                                + " OFFSET "
                                + offset,
                        args.toArray());
        return new ExportJobPage(
                rows.stream().map(r -> HospitalAuditExportService.view(r)).toList(),
                total == null ? 0 : total,
                no,
                size);
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
        String comment =
                action == null || action.comment() == null ? null : action.comment().trim();
        if (comment != null && comment.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "审批意见过长（最多 200 字）");
        }
        return comment;
    }

    private static void requirePlatformContext() {
        TenantContext.State state = TenantContext.require();
        if (!state.platform()) {
            throw new ApiException(403, 40300, "医院账号不能访问平台端审计中心");
        }
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
