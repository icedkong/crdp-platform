package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.auth.TenantLookupCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 租户注销流程主表（E05-T08 / PLT-TEN-018，验收：注销需审批）。
 *
 * <p>按平台规格第 2 章「注销流程」编排：发起申请（原因 + 医院书面确认 + 法定保留清单）→ 另一名平台管理员审批（通过 → 注销中 CANCELLING、不可登录；驳回 →
 * 回到停用可重新发起）→ 冷静期（默认 30 天，已确认）内可撤销回停用。 迁出与签收（{@link TenantExportService}）、封存（{@link
 * TenantRetentionService}）、销毁与证明（{@link TenantDestroyService}）在本流程的 stage 之上推进： COOLING → EXPORTED
 * → ACKED → DESTROYED。每一步写 TENANT_CANCEL 审计；审批人必须不是发起人（双人复核，规格 §1「平台管理员至少 2
 * 人」），发起人可以是平台管理员或实施人员（权限码只约束动作，不区分角色）。
 */
@Service
public class TenantCancelService {

    /** 注销流程阶段（plt_tenant_cancel.stage）。 */
    static final String STAGE_PENDING_APPROVAL = "PENDING_APPROVAL";

    static final String STAGE_COOLING = "COOLING";
    static final String STAGE_EXPORTED = "EXPORTED";
    static final String STAGE_ACKED = "ACKED";
    static final String STAGE_DESTROYED = "DESTROYED";
    static final String STAGE_REJECTED = "REJECTED";
    static final String STAGE_REVOKED = "REVOKED";

    /** 冷静期天数（规格已确认默认 30 天）。 */
    static final int COOLING_DAYS = 30;

    /** 迁出包下载链接有效期与次数上限（规格「关键字段 · 标准数据迁出包」，已确认）。 */
    static final int EXPORT_LINK_DAYS = 7;

    static final int EXPORT_MAX_DOWNLOADS = 5;

    private static final int REASON_MAX = 500;
    private static final long FILE_MAX_BYTES = 20L * 1024 * 1024;
    private static final List<String> FILE_EXTENSIONS = List.of("pdf", "jpg", "jpeg", "png");
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate platform;
    private final SessionStore sessions;
    private final AuditLogger audit;
    private final TenantLookupCache tenantLookup;
    private final ObjectStorage storage;

    public TenantCancelService(
            JdbcTemplate platform,
            SessionStore sessions,
            AuditLogger audit,
            TenantLookupCache tenantLookup,
            ObjectStorage storage) {
        this.platform = platform;
        this.sessions = sessions;
        this.audit = audit;
        this.tenantLookup = tenantLookup;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- DTO

    /** 法定保留清单条目（PLT-TEN-021 登记口径）。 */
    public record RetentionItemInput(String scope, String legalBasis, String retainUntil) {}

    /** 发起注销申请：原因 + 医院书面确认 + 需依法或按合同保留的数据清单。 */
    public record ApplyRequest(
            String reason, Long confirmationFileId, List<RetentionItemInput> retentionItems) {}

    /** 审批：APPROVE 通过（→ 注销中 + 冷静期）、REJECT 驳回（回到停用，可重新发起）。 */
    public record ApproveRequest(String decision, String note) {}

    /** 冷静期内撤销。 */
    public record RevokeRequest(String reason) {}

    /** 附件（书面确认 / 签收单）。 */
    public record FileView(
            long id, String kind, String fileName, long sizeBytes, LocalDateTime uploadedAt) {}

    /** 注销流程行（注销与数据处理页总览的注销主体部分；流程总览由控制器拼装四块）。 */
    public record CancelFlowView(
            long tenantId,
            String tenantCode,
            String tenantName,
            String tenantStatus,
            String stage,
            String reason,
            FileView confirmationFile,
            String appliedByName,
            LocalDateTime appliedAt,
            String approvedByName,
            LocalDateTime approvedAt,
            String approveNote,
            LocalDateTime coolingUntil,
            String revokedByName,
            LocalDateTime revokedAt,
            String revokeReason) {}

    public record ApplyResult(long tenantId, String stage, int retentionItemCount) {}

    public record StageResult(
            long tenantId,
            String tenantCode,
            String previousStage,
            String stage,
            String tenantStatus) {}

    // ---------------------------------------------------------------- 查询

    /** 注销流程视图：无流程行时 stage 为 null（页面据此展示发起表单）。 */
    public CancelFlowView flowOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    Map<String, Object> row = cancelRow(tenantId);
                    return new CancelFlowView(
                            tenantId,
                            str(tenant.get("code")),
                            str(tenant.get("name")),
                            str(tenant.get("status")),
                            row == null ? null : str(row.get("stage")),
                            row == null ? null : str(row.get("reason")),
                            row == null ? null : fileView(row.get("confirmation_file_id")),
                            row == null ? null : accountName(longOf(row.get("applied_by"))),
                            row == null ? null : timeOf(row.get("applied_at")),
                            row.get("approved_by") == null
                                    ? null
                                    : accountName(longOf(row.get("approved_by"))),
                            timeOf(row.get("approved_at")),
                            str(row.get("approve_note")),
                            timeOf(row.get("cooling_until")),
                            row.get("revoked_by") == null
                                    ? null
                                    : accountName(longOf(row.get("revoked_by"))),
                            timeOf(row.get("revoked_at")),
                            str(row.get("revoke_reason")));
                });
    }

    // ---------------------------------------------------------------- 发起申请（PLT-TEN-018）

    public ApplyResult apply(long tenantId, ApplyRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = text(request.reason(), "注销原因", REASON_MAX);
        if (request.confirmationFileId() == null || request.confirmationFileId() <= 0) {
            throw new ApiException(400, 40001, "必须上传医院书面确认（先上传再发起）");
        }
        List<RetentionItemInput> items =
                request.retentionItems() == null ? List.of() : request.retentionItems();
        for (RetentionItemInput item : items) {
            TenantRetentionService.validateInput(
                    item.scope(), item.legalBasis(), item.retainUntil());
        }
        long actor = CurrentSession.requirePlatform().userId();

        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    String status = str(tenant.get("status"));
                    if (!"DISABLED".equals(status)) {
                        throw new ApiException(
                                409, 40901, "只有停用状态的租户可以发起注销（当前 " + status + "）；请先停用");
                    }
                    String code = str(tenant.get("code"));
                    requireConfirmationFile(tenantId, request.confirmationFileId());
                    Map<String, Object> existing = cancelRow(tenantId);
                    String previousStage = null;
                    if (existing == null) {
                        platform.update(
                                "INSERT INTO plt_tenant_cancel"
                                        + " (tenant_id, stage, reason, confirmation_file_id, applied_by)"
                                        + " VALUES (?, ?, ?, ?, ?)",
                                tenantId,
                                STAGE_PENDING_APPROVAL,
                                reason,
                                request.confirmationFileId(),
                                actor);
                    } else {
                        previousStage = str(existing.get("stage"));
                        if (!STAGE_REJECTED.equals(previousStage)
                                && !STAGE_REVOKED.equals(previousStage)) {
                            throw new ApiException(
                                    409, 40901, "该租户已有进行中的注销流程（" + previousStage + "），不能重复发起");
                        }
                        platform.update(
                                "UPDATE plt_tenant_cancel SET stage = ?, reason = ?,"
                                        + " confirmation_file_id = ?, applied_by = ?, applied_at ="
                                        + " CURRENT_TIMESTAMP(3), approved_by = NULL, approved_at = NULL,"
                                        + " approve_note = NULL, cooling_until = NULL, revoked_by = NULL,"
                                        + " revoked_at = NULL, revoke_reason = NULL WHERE tenant_id = ?",
                                STAGE_PENDING_APPROVAL,
                                reason,
                                request.confirmationFileId(),
                                actor,
                                tenantId);
                        platform.update(
                                "DELETE FROM plt_tenant_retention WHERE tenant_id = ?"
                                        + " AND status = 'REGISTERED'",
                                tenantId);
                    }
                    for (RetentionItemInput item : items) {
                        platform.update(
                                "INSERT INTO plt_tenant_retention"
                                        + " (tenant_id, scope, legal_basis, retain_until, status, created_by)"
                                        + " VALUES (?, ?, ?, ?, 'REGISTERED', ?)",
                                tenantId,
                                item.scope().trim(),
                                item.legalBasis().trim(),
                                java.sql.Date.valueOf(item.retainUntil().trim()),
                                actor);
                    }
                    audit.record(
                            auditEvent(
                                    actor,
                                    "APPLY",
                                    code,
                                    "发起注销申请；原因："
                                            + reason
                                            + "；书面确认文件 #"
                                            + request.confirmationFileId()
                                            + "；法定保留清单 "
                                            + items.size()
                                            + " 条"
                                            + (previousStage == null
                                                    ? ""
                                                    : "（重新发起，前次阶段 " + previousStage + "）")));
                    return new ApplyResult(tenantId, STAGE_PENDING_APPROVAL, items.size());
                });
    }

    // ---------------------------------------------------------------- 审批（PLT-TEN-018：另一名平台管理员）

    public StageResult approve(long tenantId, ApproveRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String decision = request.decision() == null ? "" : request.decision().trim();
        boolean approve = "APPROVE".equals(decision);
        boolean reject = "REJECT".equals(decision);
        if (!approve && !reject) {
            throw new ApiException(400, 40001, "decision 只能是 APPROVE 或 REJECT");
        }
        String note =
                request.note() == null || request.note().isBlank()
                        ? null
                        : text(request.note(), "审批意见", REASON_MAX);
        long approver = CurrentSession.requirePlatform().userId();

        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    String code = str(tenant.get("code"));
                    Map<String, Object> row = requireCancel(tenantId);
                    String stage = str(row.get("stage"));
                    if (!STAGE_PENDING_APPROVAL.equals(stage)) {
                        throw new ApiException(409, 40901, "只有待审批的注销申请可以审批（当前 " + stage + "）");
                    }
                    long applicant = longOf(row.get("applied_by"));
                    if (applicant == approver) {
                        throw new ApiException(409, 40901, "注销审批须由另一名平台管理员完成，发起人不能自审");
                    }
                    if (approve) {
                        int updated =
                                platform.update(
                                        "UPDATE plt_tenant SET status = 'CANCELLING' WHERE id = ?"
                                                + " AND status = 'DISABLED'",
                                        tenantId);
                        if (updated == 0) {
                            throw new ApiException(409, 40901, "租户状态已变化，请刷新后重试");
                        }
                        tenantLookup.evict(code);
                        // 注销中不可登录（登录闸门按状态拒绝）；审批通过即吊销在线会话（与停用同口径）
                        sessions.revokeAllForTenant(tenantId);
                        platform.update(
                                "UPDATE plt_tenant_cancel SET stage = ?, approved_by = ?,"
                                        + " approved_at = CURRENT_TIMESTAMP(3), approve_note = ?,"
                                        + " cooling_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL "
                                        + COOLING_DAYS
                                        + " DAY) WHERE id = ?",
                                STAGE_COOLING,
                                approver,
                                note,
                                longOf(row.get("id")));
                        audit.record(
                                auditEvent(
                                        approver,
                                        "APPROVE",
                                        code,
                                        "注销审批通过（发起人 "
                                                + accountName(applicant)
                                                + "）；租户进入注销中，冷静期 "
                                                + COOLING_DAYS
                                                + " 天，期内可撤销"
                                                + (note == null ? "" : "；意见：" + note)));
                        return new StageResult(tenantId, code, stage, STAGE_COOLING, "CANCELLING");
                    }
                    platform.update(
                            "UPDATE plt_tenant_cancel SET stage = ?, approved_by = ?, approved_at ="
                                    + " CURRENT_TIMESTAMP(3), approve_note = ? WHERE id = ?",
                            STAGE_REJECTED,
                            approver,
                            note,
                            longOf(row.get("id")));
                    audit.record(
                            auditEvent(
                                    approver,
                                    "REJECT",
                                    code,
                                    "注销申请被驳回（发起人 "
                                            + accountName(applicant)
                                            + "）；租户保持停用，可重新发起"
                                            + (note == null ? "" : "；意见：" + note)));
                    return new StageResult(
                            tenantId, code, stage, STAGE_REJECTED, str(tenant.get("status")));
                });
    }

    // ---------------------------------------------------------------- 冷静期内撤销（PLT-TEN-018）

    public StageResult revoke(long tenantId, RevokeRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = text(request.reason(), "撤销原因", REASON_MAX);
        long actor = CurrentSession.requirePlatform().userId();

        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    String code = str(tenant.get("code"));
                    Map<String, Object> row = requireCancel(tenantId);
                    String stage = str(row.get("stage"));
                    if (!STAGE_COOLING.equals(stage)) {
                        throw new ApiException(409, 40901, "只有注销中（冷静期）的流程可以撤销（当前 " + stage + "）");
                    }
                    LocalDateTime coolingUntil = timeOf(row.get("cooling_until"));
                    if (coolingUntil != null && LocalDateTime.now().isAfter(coolingUntil)) {
                        throw new ApiException(409, 40901, "冷静期已满，不能撤销；请继续迁出与销毁流程");
                    }
                    int updated =
                            platform.update(
                                    "UPDATE plt_tenant SET status = 'DISABLED' WHERE id = ?"
                                            + " AND status = 'CANCELLING'",
                                    tenantId);
                    if (updated == 0) {
                        throw new ApiException(409, 40901, "租户状态已变化，请刷新后重试");
                    }
                    tenantLookup.evict(code);
                    platform.update(
                            "UPDATE plt_tenant_cancel SET stage = ?, revoked_by = ?, revoked_at ="
                                    + " CURRENT_TIMESTAMP(3), revoke_reason = ? WHERE id = ?",
                            STAGE_REVOKED,
                            actor,
                            reason,
                            longOf(row.get("id")));
                    audit.record(auditEvent(actor, "REVOKE", code, "冷静期内撤销注销，租户回到停用；原因：" + reason));
                    return new StageResult(tenantId, code, stage, STAGE_REVOKED, "DISABLED");
                });
    }

    // ---------------------------------------------------------------- 附件上传（书面确认 / 签收单）

    /** 上传注销流程附件（kind=CONFIRMATION 书面确认 / ACK 签收单），存 platform/ 前缀（不随租户销毁）。 */
    public FileView upload(long tenantId, String kind, MultipartFile file) {
        if (!"CONFIRMATION".equals(kind) && !"ACK".equals(kind)) {
            throw new ApiException(400, 40001, "附件类型只能是 CONFIRMATION 或 ACK");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, 40001, "必须选择要上传的文件");
        }
        byte[] body = read(file);
        if (body.length > FILE_MAX_BYTES) {
            throw new ApiException(400, 40001, "附件最大 20MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String extension = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!FILE_EXTENSIONS.contains(extension)) {
            throw new ApiException(400, 40001, "附件只支持 PDF / JPG / PNG（书面确认与签收单）");
        }
        long actor = CurrentSession.requirePlatform().userId();
        String safeName =
                (original.isBlank()
                        ? "attachment"
                        : original.replaceAll("[\\\\/:*?\"<>|\\s]+", "_"));
        String relativeKey =
                ("cancel/%d/%s-%s-%s.%s"
                        .formatted(
                                tenantId,
                                kind.toLowerCase(Locale.ROOT),
                                LocalDateTime.now().format(FILE_TIME),
                                UUID.randomUUID().toString().substring(0, 8),
                                extension));

        String objectKey =
                TenantContext.callAsPlatform(
                        () -> storage.put(relativeKey, body, "application/octet-stream"));
        return TenantContext.callAsPlatform(
                () -> {
                    String code = str(requireTenant(tenantId).get("code"));
                    platform.update(
                            "INSERT INTO plt_cancel_file"
                                    + " (tenant_id, kind, file_name, object_key, sha256, size_bytes,"
                                    + " uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            tenantId,
                            kind,
                            safeName,
                            objectKey,
                            sha256Hex(body),
                            body.length,
                            actor);
                    Long id =
                            platform.queryForObject(
                                    "SELECT id FROM plt_cancel_file WHERE object_key = ?",
                                    Long.class,
                                    objectKey);
                    audit.record(
                            auditEvent(
                                    actor,
                                    "UPLOAD_" + kind,
                                    code,
                                    "上传"
                                            + ("CONFIRMATION".equals(kind) ? "医院书面确认" : "迁出包签收单")
                                            + "："
                                            + safeName
                                            + "（"
                                            + body.length
                                            + " 字节）"));
                    return new FileView(
                            id == null ? 0 : id,
                            kind,
                            safeName,
                            body.length,
                            LocalDateTime.now().withNano(0));
                });
    }

    // ---------------------------------------------------------------- 供兄弟服务复用（同包）

    /** 当前行（可能为 null）。 */
    Map<String, Object> cancelRow(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT * FROM plt_tenant_cancel WHERE tenant_id = ?", tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> requireCancel(long tenantId) {
        Map<String, Object> row = cancelRow(tenantId);
        if (row == null) {
            throw new ApiException(404, 40400, "该租户没有注销流程");
        }
        return row;
    }

    /** stage 推进（兄弟服务回调；乐观：fromStage 不匹配 40901）。 */
    void advanceStage(long cancelId, String fromStage, String toStage) {
        int updated =
                platform.update(
                        "UPDATE plt_tenant_cancel SET stage = ? WHERE id = ? AND stage = ?",
                        toStage,
                        cancelId,
                        fromStage);
        if (updated == 0) {
            throw new ApiException(409, 40901, "注销流程阶段已变化，请刷新后重试");
        }
    }

    void recordEvent(Long actorId, String action, String tenantCode, String detail) {
        audit.record(auditEvent(actorId, action, tenantCode, detail));
    }

    /** 复核校验：reviewerId 必须是另一名启用中的平台管理员（销毁、封存访问双人复核）。 */
    void requireAnotherAdmin(Long reviewerId, long executorId, String what) {
        if (reviewerId == null || reviewerId <= 0) {
            throw new ApiException(400, 40001, "必须填写复核人（另一名平台管理员）");
        }
        if (reviewerId == executorId) {
            throw new ApiException(409, 40901, what + "须由另一名平台管理员复核，不能自己复核自己");
        }
        Integer count =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_account a JOIN plt_account_role ar ON"
                                + " ar.account_id = a.id JOIN plt_role r ON r.id = ar.role_id"
                                + " WHERE a.id = ? AND a.status = 'ENABLED' AND r.code = 'PLATFORM_ADMIN'",
                        Integer.class,
                        reviewerId);
        if (count == null || count == 0) {
            throw new ApiException(404, 40400, "复核人不存在或不是启用中的平台管理员");
        }
    }

    String accountName(long accountId) {
        List<String> names =
                platform.queryForList(
                        "SELECT display_name FROM plt_account WHERE id = ?",
                        String.class,
                        accountId);
        return names.isEmpty() ? "账号#" + accountId : names.get(0);
    }

    static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    static String text(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, 40001, "必须填写" + what);
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ApiException(400, 40001, what + "过长（最多 " + max + " 个字符）");
        }
        return trimmed;
    }

    static long longOf(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static LocalDateTime timeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value));
    }

    // ---------------------------------------------------------------- 内部

    private AuditEvent auditEvent(Long actorId, String action, String tenantCode, String detail) {
        return new AuditEvent("TENANT_CANCEL", actorId, action, "plt_tenant:" + tenantCode, detail);
    }

    private void requireConfirmationFile(long tenantId, long fileId) {
        Integer count =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_cancel_file WHERE id = ? AND tenant_id = ?"
                                + " AND kind = 'CONFIRMATION'",
                        Integer.class,
                        fileId,
                        tenantId);
        if (count == null || count == 0) {
            throw new ApiException(404, 40400, "医院书面确认文件不存在（须先上传本租户的 CONFIRMATION 附件）");
        }
    }

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private FileView fileView(Object fileId) {
        if (fileId == null) {
            return null;
        }
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, kind, file_name, size_bytes, uploaded_at FROM plt_cancel_file"
                                + " WHERE id = ?",
                        longOf(fileId));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new FileView(
                longOf(row.get("id")),
                str(row.get("kind")),
                str(row.get("file_name")),
                longOf(row.get("size_bytes")),
                timeOf(row.get("uploaded_at")));
    }

    private static byte[] read(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(500, 50000, "读取上传文件失败");
        }
    }
}
