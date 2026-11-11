package com.crdp.server.platform;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.server.auth.TenantLookupCache;
import com.crdp.server.config.ProvisioningDatabase;
import com.crdp.server.config.TenantDataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据销毁与销毁证明（E05-T08 / PLT-TEN-020，验收：销毁出证明）。
 *
 * <p>签收后执行（规格「迁出包交付后须医院确认校验无误并签收，才能进入销毁」；法定保留项须全部封存）： 删除租户库与专属账号（特权连接
 * DDL，与开通回收同款）、清理对象存储租户前缀、吊销会话（Redis 键）并注销连接池； 平台库保留租户档案（状态 → 已注销）与平台审计，资源行标记已销毁。 KMS
 * 主密钥计划删除与云备份自然失效是云侧人工步骤（开通编排同款 E01 口径），写入证明的范围清单与销毁方式中列明。 执行人与复核人双人复核（复核人为另一名平台管理员，规格「关键字段 ·
 * 数据销毁证明」）； 证明 PDF 平台存档并交付医院（下载接口）。
 */
@Service
public class TenantDestroyService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate platform;
    private final ProvisioningDatabase provisioning;
    private final TenantDataSourceRegistry registry;
    private final TenantRoutingDataSource routing;
    private final TenantCancelService cancelService;
    private final SessionStore sessions;
    private final TenantLookupCache tenantLookup;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public TenantDestroyService(
            JdbcTemplate platform,
            ProvisioningDatabase provisioning,
            TenantDataSourceRegistry registry,
            TenantRoutingDataSource routing,
            TenantCancelService cancelService,
            SessionStore sessions,
            TenantLookupCache tenantLookup,
            ObjectStorage storage,
            ObjectMapper json) {
        this.platform = platform;
        this.provisioning = provisioning;
        this.registry = registry;
        this.routing = routing;
        this.cancelService = cancelService;
        this.sessions = sessions;
        this.tenantLookup = tenantLookup;
        this.storage = storage;
        this.json = json;
    }

    // ---------------------------------------------------------------- DTO

    /** 执行销毁：复核人（另一名平台管理员）+ 原因。 */
    public record DestroyRequest(Long reviewerId, String reason) {}

    public record DestroyResult(
            long tenantId,
            String tenantCode,
            String stage,
            String tenantStatus,
            long certificateId,
            String certNo) {}

    /** 销毁证明行。 */
    public record CertificateView(
            long id,
            String certNo,
            String kind,
            String tenantCode,
            String tenantName,
            String executedAt,
            String executedByName,
            String reviewedByName) {}

    public record CertificateFile(String fileName, byte[] body) {}

    // ---------------------------------------------------------------- 执行销毁（PLT-TEN-020）

    public DestroyResult destroy(long tenantId, DestroyRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = TenantCancelService.text(request.reason(), "销毁原因", 500);
        long executor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    String code = TenantCancelService.str(tenant.get("code"));
                    String name = TenantCancelService.str(tenant.get("name"));
                    Map<String, Object> cancel = cancelService.requireCancel(tenantId);
                    String stage = TenantCancelService.str(cancel.get("stage"));
                    if (!TenantCancelService.STAGE_ACKED.equals(stage)) {
                        throw new ApiException(
                                409,
                                40901,
                                "只有医院已签收迁出包的流程可以执行销毁（当前 " + stage + "）；顺序：冷静期 → 迁出包 → 签收 → 销毁");
                    }
                    Integer unsealed =
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM plt_tenant_retention WHERE tenant_id = ?"
                                            + " AND status = 'REGISTERED'",
                                    Integer.class,
                                    tenantId);
                    if (unsealed != null && unsealed > 0) {
                        throw new ApiException(
                                409, 40901, "有 " + unsealed + " 条法定保留数据未封存；须先封存或从清单移除才能销毁");
                    }
                    cancelService.requireAnotherAdmin(request.reviewerId(), executor, "数据销毁");

                    String database = "t_" + code;
                    // 1. 持有对象键清单（库删除前；对象体随后清理）
                    List<String> objectKeys = objectKeysOf(tenantId, code);
                    // 2. 会话（Redis 键）与连接池
                    sessions.revokeAllForTenant(tenantId);
                    registry.unregister(tenantId);
                    // 3. 租户库与专属账号
                    provisioning.execute("DROP DATABASE IF EXISTS `" + database + "`");
                    provisioning.execute("DROP USER IF EXISTS `" + database + "`@'%'");
                    // 4. 对象存储租户前缀内容（逐对象删除；桶由云侧管理，见证明范围清单）
                    int objectsDeleted = deleteObjects(tenantId, code, objectKeys);
                    // 5. 资源行标记已销毁（保留作凭据）
                    platform.update(
                            "UPDATE plt_tenant_resource SET password_cipher = 'DESTROYED',"
                                    + " destroyed_at = CURRENT_TIMESTAMP(3) WHERE tenant_id = ?",
                            tenantId);
                    // 6. 状态 → 已注销（平台库保留档案与审计）
                    int updated =
                            platform.update(
                                    "UPDATE plt_tenant SET status = 'CANCELLED' WHERE id = ?"
                                            + " AND status = 'CANCELLING'",
                                    tenantId);
                    if (updated == 0) {
                        throw new ApiException(409, 40901, "租户状态已变化，请刷新后重试");
                    }
                    tenantLookup.evict(code);
                    cancelService.advanceStage(
                            TenantCancelService.longOf(cancel.get("id")),
                            TenantCancelService.STAGE_ACKED,
                            TenantCancelService.STAGE_DESTROYED);

                    // 7. 销毁后校验（证明「校验结果」字段：检查资源已不存在）
                    List<String> verification = new ArrayList<>();
                    verification.add(
                            "租户库 " + database + "：" + (schemaExists(database) ? "仍存在（异常）" : "已删除"));
                    verification.add("专属账号：" + (dbUserExists(database) ? "仍存在（异常）" : "已删除"));
                    verification.add("连接池与会话：已注销并吊销（" + code + " 不再路由；医院端登录被拒）");
                    verification.add(
                            "对象存储：租户前缀对象已清理 "
                                    + objectsDeleted
                                    + " 个（缺失对象 "
                                    + Math.max(0, objectKeys.size() - objectsDeleted)
                                    + " 个）");
                    verification.add("KMS 主密钥：计划删除为云侧人工步骤，已列入交付清单");
                    verification.add("云备份：按保留期自然过期（密钥删除后不可解密），证明中注明");

                    long certId =
                            issueCertificate(
                                    tenantId,
                                    code,
                                    name,
                                    "TENANT",
                                    scopeItems(objectsDeleted, objectKeys.size()),
                                    methodText(),
                                    String.join("；", verification),
                                    retentionNote(tenantId),
                                    executor,
                                    request.reviewerId(),
                                    reason);
                    cancelService.recordEvent(
                            executor,
                            "DESTROY",
                            code,
                            "执行数据销毁（复核人 "
                                    + cancelService.accountName(request.reviewerId())
                                    + "）："
                                    + String.join("；", verification)
                                    + "；原因："
                                    + reason
                                    + "；销毁证明 "
                                    + certNoOf(certId));
                    return new DestroyResult(
                            tenantId,
                            code,
                            TenantCancelService.STAGE_DESTROYED,
                            "CANCELLED",
                            certId,
                            certNoOf(certId));
                });
    }

    // ---------------------------------------------------------------- 证明（PLT-TEN-020）

    /** 法定保留数据期满销毁补充证明（PLT-TEN-021，由封存服务回调）。 */
    long issueRetentionCertificate(
            long tenantId,
            String code,
            String name,
            String scope,
            String verifyDetail,
            long executor,
            Long reviewerId,
            String reason) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resource", "封存保留数据（独立加密封存包）");
        item.put("handling", "删除封存对象，销毁独立密钥");
        item.put("result", "已删除");
        items.add(item);
        return issueCertificate(
                tenantId,
                code,
                name,
                "RETENTION",
                items,
                "保留数据期满销毁：删除加密封存包并作废其独立解压密码（密文随包删除）；范围：" + scope,
                verifyDetail,
                null,
                executor,
                reviewerId,
                reason);
    }

    public List<CertificateView> certificatesOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT c.id, c.cert_no, c.kind, c.tenant_code, c.tenant_name,"
                                        + " c.executed_at, c.executed_by_name, c.reviewed_by_name"
                                        + " FROM plt_destroy_certificate c WHERE c.tenant_id = ?"
                                        + " ORDER BY c.id DESC",
                                (rs, rowNum) ->
                                        new CertificateView(
                                                rs.getLong("id"),
                                                rs.getString("cert_no"),
                                                rs.getString("kind"),
                                                rs.getString("tenant_code"),
                                                rs.getString("tenant_name"),
                                                rs.getTimestamp("executed_at")
                                                        .toLocalDateTime()
                                                        .withNano(0)
                                                        .toString(),
                                                rs.getString("executed_by_name"),
                                                rs.getString("reviewed_by_name")),
                                tenantId));
    }

    /** 下载证明 PDF（平台存档交付医院；敏感操作须二次认证）。 */
    public CertificateFile downloadCertificate(long tenantId, long certificateId) {
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireCertificate(tenantId, certificateId);
                    String objectKey = TenantCancelService.str(row.get("object_key"));
                    byte[] body =
                            TenantContext.callAsPlatform(
                                    () -> storage.get(TenantExportService.relativeOf(objectKey)));
                    if (body == null) {
                        throw new ApiException(404, 40400, "销毁证明文件缺失");
                    }
                    cancelService.recordEvent(
                            actor,
                            "CERT_DOWNLOAD",
                            TenantCancelService.str(row.get("tenant_code")),
                            "下载数据销毁证明 " + TenantCancelService.str(row.get("cert_no")));
                    return new CertificateFile(
                            "销毁证明-" + TenantCancelService.str(row.get("cert_no")) + ".pdf", body);
                });
    }

    // ---------------------------------------------------------------- 内部

    private long issueCertificate(
            long tenantId,
            String code,
            String name,
            String kind,
            List<Map<String, Object>> scopeItems,
            String methodText,
            String verification,
            String retentionNote,
            long executor,
            Long reviewerId,
            String reason) {
        try {
            String tempNo = "TMP-" + java.util.UUID.randomUUID();
            platform.update(
                    "INSERT INTO plt_destroy_certificate"
                            + " (cert_no, kind, tenant_id, tenant_code, tenant_name, scope_items,"
                            + " method_text, verification, retention_note, executed_by,"
                            + " executed_by_name, reviewed_by, reviewed_by_name, object_key,"
                            + " sha256, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " '', '', 0)",
                    tempNo,
                    kind,
                    tenantId,
                    code,
                    name,
                    json.writeValueAsString(scopeItems),
                    methodText,
                    verification,
                    retentionNote,
                    executor,
                    cancelService.accountName(executor),
                    reviewerId,
                    cancelService.accountName(reviewerId == null ? 0 : reviewerId));
            Long id =
                    platform.queryForObject(
                            "SELECT id FROM plt_destroy_certificate WHERE cert_no = ?",
                            Long.class,
                            tempNo);
            String certNo = "DEST-%06d".formatted(id == null ? 0 : id);
            List<String> lines =
                    certificateLines(
                            certNo,
                            kind,
                            code,
                            name,
                            scopeItems,
                            methodText,
                            verification,
                            retentionNote,
                            executor,
                            reviewerId,
                            reason);
            byte[] pdf = CancelCertificatePdf.build("数据销毁证明", lines);
            String relativeKey = "cancel/%d/certificate/%s.pdf".formatted(tenantId, certNo);
            String objectKey =
                    TenantContext.callAsPlatform(
                            () -> storage.put(relativeKey, pdf, "application/pdf"));
            platform.update(
                    "UPDATE plt_destroy_certificate SET cert_no = ?, object_key = ?, sha256 = ?,"
                            + " size_bytes = ? WHERE id = ?",
                    certNo,
                    objectKey,
                    TenantCancelService.sha256Hex(pdf),
                    pdf.length,
                    id);
            return id == null ? 0 : id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ApiException(500, 50000, "生成销毁证明失败");
        }
    }

    private List<String> certificateLines(
            String certNo,
            String kind,
            String code,
            String name,
            List<Map<String, Object>> scopeItems,
            String methodText,
            String verification,
            String retentionNote,
            long executor,
            Long reviewerId,
            String reason) {
        List<String> lines = new ArrayList<>();
        lines.add("证明编号：" + certNo);
        lines.add("类型：" + ("RETENTION".equals(kind) ? "法定保留数据期满销毁" : "租户数据整体销毁"));
        lines.add("医院：" + name + "（租户编码 " + code + "）");
        lines.add("执行时间：" + LocalDateTime.now(ZONE).withNano(0));
        lines.add("执行人：" + cancelService.accountName(executor));
        lines.add("复核人：" + cancelService.accountName(reviewerId == null ? 0 : reviewerId));
        lines.add("");
        lines.add("销毁范围清单：");
        for (Map<String, Object> item : scopeItems) {
            lines.addAll(
                    CancelCertificatePdf.wrap(
                            "· "
                                    + item.get("resource")
                                    + "——"
                                    + item.get("handling")
                                    + "（"
                                    + item.get("result")
                                    + "）",
                            84));
        }
        lines.add("");
        lines.add("销毁方式：");
        lines.addAll(CancelCertificatePdf.wrap(methodText, 84));
        lines.add("");
        lines.add("校验结果：");
        lines.addAll(CancelCertificatePdf.wrap(verification, 84));
        if (retentionNote != null && !retentionNote.isBlank()) {
            lines.add("");
            lines.add("保留数据说明：");
            lines.addAll(CancelCertificatePdf.wrap(retentionNote, 84));
        }
        lines.add("");
        lines.add("销毁原因：" + reason);
        lines.add("");
        lines.add("本证明由临床科研数据一体化平台出具，平台留存原件并交付医院。");
        lines.add("KMS 密钥进入计划删除后，残留在云备份中的数据无法解密；云备份按保留期自然过期。");
        return lines;
    }

    private List<Map<String, Object>> scopeItems(int objectsDeleted, int objectsTotal) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(scopeItem("租户数据库（t_{租户编码}）与专属账号", "DROP DATABASE / DROP USER（特权连接）", "已删除"));
        items.add(
                scopeItem(
                        "对象存储租户前缀对象（原始文件）",
                        "逐对象删除（桶由云侧管理）",
                        "已清理 " + objectsDeleted + "/" + objectsTotal));
        items.add(scopeItem("缓存（Redis 键 / 在线会话）", "吊销全部会话、注销连接池", "已清理"));
        items.add(scopeItem("KMS 主密钥", "计划删除（云侧人工执行）", "列入交付清单"));
        items.add(scopeItem("云备份", "按保留期自然过期（不可解密）", "自然失效"));
        return items;
    }

    private static Map<String, Object> scopeItem(String resource, String handling, String result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resource", resource);
        item.put("handling", handling);
        item.put("result", result);
        return item;
    }

    private static String methodText() {
        return "删除租户库与专属账号、清理对象存储租户前缀、吊销会话并注销连接池；KMS 主密钥进入计划删除（云侧人工执行）；"
                + "云备份按保留期自然失效。平台库保留租户档案与平台审计（不含医院业务数据）。";
    }

    private String retentionNote(long tenantId) {
        List<Map<String, Object>> items =
                platform.queryForList(
                        "SELECT scope, legal_basis, retain_until, status FROM plt_tenant_retention"
                                + " WHERE tenant_id = ? ORDER BY id",
                        tenantId);
        if (items.isEmpty()) {
            return null;
        }
        StringBuilder note = new StringBuilder();
        for (Map<String, Object> item : items) {
            if (note.length() > 0) {
                note.append('；');
            }
            note.append(TenantCancelService.str(item.get("scope")))
                    .append("（依据 ")
                    .append(TenantCancelService.str(item.get("legal_basis")))
                    .append("，保留期满日 ")
                    .append(String.valueOf(item.get("retain_until")))
                    .append("，")
                    .append(TenantCancelService.str(item.get("status")))
                    .append("）");
        }
        return note.toString();
    }

    private String certNoOf(long certId) {
        return "DEST-%06d".formatted(certId);
    }

    private Map<String, Object> requireCertificate(long tenantId, long certificateId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT * FROM plt_destroy_certificate WHERE id = ? AND tenant_id = ?",
                        certificateId,
                        tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "销毁证明不存在");
        }
        return rows.get(0);
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

    private List<String> objectKeysOf(long tenantId, String code) {
        if (!routing.hasTenant(tenantId)) {
            return List.of();
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    code,
                    () -> {
                        Integer present =
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                                                + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME ="
                                                + " 'sys_file'",
                                        Integer.class);
                        if (present == null || present == 0) {
                            return List.<String>of();
                        }
                        return platform.queryForList(
                                "SELECT object_key FROM sys_file WHERE status <> 'DELETED'",
                                String.class);
                    });
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private int deleteObjects(long tenantId, String code, List<String> objectKeys) {
        int deleted = 0;
        for (String key : objectKeys) {
            try {
                TenantContext.runAsTenant(tenantId, code, () -> storage.delete(key));
                deleted++;
            } catch (RuntimeException ignored) {
                // 对象缺失或存储不可达：不阻塞销毁（已在证明校验结果中计数）
            }
        }
        return deleted;
    }

    private boolean schemaExists(String database) {
        return provisioning.query(
                        "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '"
                                + database
                                + "'")
                > 0;
    }

    private boolean dbUserExists(String user) {
        return provisioning.query("SELECT COUNT(*) FROM mysql.user WHERE user = '" + user + "'")
                > 0;
    }

    /** 今天（北京时间），保留期满判断用。 */
    static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
