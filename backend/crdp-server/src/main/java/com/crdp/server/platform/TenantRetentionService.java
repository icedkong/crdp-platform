package com.crdp.server.platform;

import com.crdp.framework.crypto.ArchiveCipher;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 法定保留数据封存（E05-T08 / PLT-TEN-021）。
 *
 * <p>发起注销时列出需依法或按合同保留的数据（如 RCT 资料、审计日志），登记范围、依据与保留期满日； 销毁前单独封存——按登记表导出 {@link TenantPackageBuilder}
 * 子包，用**独立解压密码**加密封存（与迁出包密码分离），期满后按销毁流程处理并补充出具销毁证明。 封存包访问（下载）须两名平台管理员到场（操作人 +
 * 复核人，规格「访问须两名平台管理员同意」）。
 */
@Service
public class TenantRetentionService {

    /** 封存包独立解压密码的信封加密用途。 */
    static final String PASSWORD_PURPOSE = "plt-retain-password";

    private static final int TEXT_MAX = 200;
    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final TenantCancelService cancelService;
    private final TenantDestroyService destroyService;
    private final TenantPackageBuilder packages;
    private final ArchiveCipher archiveCipher;
    private final CryptoService crypto;
    private final ObjectStorage storage;

    public TenantRetentionService(
            JdbcTemplate platform,
            TenantRoutingDataSource routing,
            TenantCancelService cancelService,
            TenantDestroyService destroyService,
            TenantPackageBuilder packages,
            ArchiveCipher archiveCipher,
            CryptoService crypto,
            ObjectStorage storage) {
        this.platform = platform;
        this.routing = routing;
        this.cancelService = cancelService;
        this.destroyService = destroyService;
        this.packages = packages;
        this.archiveCipher = archiveCipher;
        this.crypto = crypto;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- DTO

    /** 保留项登记（保存整体替换未封存的登记项）。tablesCsv：封存时导出的租户库表（逗号分隔，可空=仅登记清单）。 */
    public record RetentionSaveItem(
            String scope, String legalBasis, String tablesCsv, String retainUntil) {}

    public record RetentionSaveRequest(List<RetentionSaveItem> items) {}

    public record RetentionView(
            long id,
            String scope,
            String legalBasis,
            List<String> tables,
            String retainUntil,
            String status,
            String sealedAt,
            String sealedSha256,
            String destroyedAt,
            Long certificateId) {}

    public record SealRequest(String reason) {}

    public record SealResult(long tenantId, String stage, int sealedCount) {}

    public record DestroyDueRequest(Long reviewerId, String reason) {}

    public record DestroyDueResult(long tenantId, long itemId, String status, long certificateId) {}

    public record SealedDownload(String fileName, byte[] body) {}

    // ---------------------------------------------------------------- 登记 / 调整

    /** 整体替换未封存（REGISTERED）的登记项；已封存项不可改（销毁前的事实已固定）。 */
    public RetentionSaveRequest save(long tenantId, RetentionSaveRequest request) {
        if (request == null || request.items() == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    String code = requireTenantCode(tenantId);
                    Map<String, Object> cancel = cancelService.requireCancel(tenantId);
                    String stage = TenantCancelService.str(cancel.get("stage"));
                    if (!TenantCancelService.STAGE_PENDING_APPROVAL.equals(stage)
                            && !TenantCancelService.STAGE_COOLING.equals(stage)
                            && !TenantCancelService.STAGE_EXPORTED.equals(stage)
                            && !TenantCancelService.STAGE_ACKED.equals(stage)) {
                        throw new ApiException(409, 40901, "注销流程已结束（" + stage + "），法定保留清单不能再调整");
                    }
                    for (RetentionSaveItem item : request.items()) {
                        validateInput(item.scope(), item.legalBasis(), item.retainUntil());
                        if (item.tablesCsv() != null && !item.tablesCsv().isBlank()) {
                            for (String table : item.tablesCsv().split(",")) {
                                requireTable(tenantId, code, table.trim());
                            }
                        }
                    }
                    platform.update(
                            "DELETE FROM plt_tenant_retention WHERE tenant_id = ?"
                                    + " AND status = 'REGISTERED'",
                            tenantId);
                    for (RetentionSaveItem item : request.items()) {
                        platform.update(
                                "INSERT INTO plt_tenant_retention"
                                        + " (tenant_id, scope, legal_basis, tables_csv, retain_until,"
                                        + " status, created_by) VALUES (?, ?, ?, ?, ?, 'REGISTERED', ?)",
                                tenantId,
                                item.scope().trim(),
                                item.legalBasis().trim(),
                                item.tablesCsv() == null || item.tablesCsv().isBlank()
                                        ? null
                                        : item.tablesCsv().trim(),
                                java.sql.Date.valueOf(item.retainUntil().trim()),
                                actor);
                    }
                    cancelService.recordEvent(
                            actor,
                            "RETENTION_SAVE",
                            code,
                            "登记法定保留数据 " + request.items().size() + " 条（未封存项整体替换）");
                    return request;
                });
    }

    // ---------------------------------------------------------------- 封存（PLT-TEN-021）

    /** 封存：把全部未封存项按登记表导出并独立加密；销毁前必须完成（销毁入口校验）。 */
    public SealResult seal(long tenantId, SealRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = TenantCancelService.text(request.reason(), "封存原因", 500);
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    String code = requireTenantCode(tenantId);
                    Map<String, Object> cancel = cancelService.requireCancel(tenantId);
                    String stage = TenantCancelService.str(cancel.get("stage"));
                    if (!TenantCancelService.STAGE_EXPORTED.equals(stage)
                            && !TenantCancelService.STAGE_ACKED.equals(stage)) {
                        throw new ApiException(409, 40901, "封存在迁出包生成之后、销毁之前进行（当前 " + stage + "）");
                    }
                    List<Map<String, Object>> pending =
                            platform.queryForList(
                                    "SELECT * FROM plt_tenant_retention WHERE tenant_id = ?"
                                            + " AND status = 'REGISTERED' ORDER BY id",
                                    tenantId);
                    if (pending.isEmpty()) {
                        throw new ApiException(409, 40901, "没有待封存的登记项");
                    }
                    String tenantName = tenantName(tenantId);
                    int sealed = 0;
                    for (Map<String, Object> item : pending) {
                        long itemId = TenantCancelService.longOf(item.get("id"));
                        List<String> tables =
                                tablesOf(TenantCancelService.str(item.get("tables_csv")));
                        String password = archiveCipher.randomPassword();
                        byte[] sealedBody;
                        if (tables.isEmpty()) {
                            // 仅登记清单不导表：封存包只含说明清单（登记行本身就是封存事实）
                            sealedBody =
                                    archiveCipher.seal(
                                            ("retention-record:%d:%s:%s:%s"
                                                            .formatted(
                                                                    itemId,
                                                                    TenantCancelService.str(
                                                                            item.get("scope")),
                                                                    TenantCancelService.str(
                                                                            item.get(
                                                                                    "legal_basis")),
                                                                    TenantCancelService.str(
                                                                            item.get(
                                                                                    "retain_until"))))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8),
                                            password.toCharArray());
                        } else {
                            TenantPackageBuilder.BuiltPackage built =
                                    TenantContext.callAsTenant(
                                            tenantId,
                                            code,
                                            () ->
                                                    packages.build(
                                                            tenantId, code, tenantName, tables));
                            sealedBody = archiveCipher.seal(built.zip(), password.toCharArray());
                        }
                        String relativeKey =
                                "cancel/%d/retention/%d-%s.crdpa"
                                        .formatted(
                                                tenantId,
                                                itemId,
                                                java.time.LocalDateTime.now().format(NO_FORMAT));
                        String objectKey =
                                TenantContext.callAsPlatform(
                                        () ->
                                                storage.put(
                                                        relativeKey,
                                                        sealedBody,
                                                        "application/octet-stream"));
                        platform.update(
                                "UPDATE plt_tenant_retention SET status = 'SEALED', sealed_by = ?,"
                                        + " sealed_at = CURRENT_TIMESTAMP(3), sealed_object_key = ?,"
                                        + " sealed_key_cipher = ?, sealed_sha256 = ? WHERE id = ?"
                                        + " AND status = 'REGISTERED'",
                                actor,
                                objectKey,
                                crypto.encrypt(PASSWORD_PURPOSE, password),
                                TenantCancelService.sha256Hex(sealedBody),
                                itemId);
                        sealed++;
                    }
                    cancelService.recordEvent(
                            actor,
                            "RETENTION_SEAL",
                            code,
                            "封存法定保留数据 " + sealed + " 条（独立密码加密，访问须两名平台管理员到场）；原因：" + reason);
                    return new SealResult(tenantId, stage, sealed);
                });
    }

    // ----------------------------------------------------------------
    // 期满销毁（PLT-TEN-021：期满后按销毁流程处理并补充出具证明）

    public DestroyDueResult destroyDue(long tenantId, long itemId, DestroyDueRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = TenantCancelService.text(request.reason(), "销毁原因", 500);
        long executor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    String code = requireTenantCode(tenantId);
                    String tenantName = tenantName(tenantId);
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT * FROM plt_tenant_retention WHERE id = ? AND tenant_id = ?",
                                    itemId,
                                    tenantId);
                    if (rows.isEmpty()) {
                        throw new ApiException(404, 40400, "保留项不存在");
                    }
                    Map<String, Object> item = rows.get(0);
                    String status = TenantCancelService.str(item.get("status"));
                    if (!"SEALED".equals(status)) {
                        throw new ApiException(409, 40901, "只有已封存的保留项可以期满销毁（当前 " + status + "）");
                    }
                    LocalDate retainUntil =
                            ((java.sql.Date) item.get("retain_until")).toLocalDate();
                    if (TenantDestroyService.today().isBefore(retainUntil)) {
                        throw new ApiException(409, 40901, "保留期未满（至 " + retainUntil + "），不能销毁");
                    }
                    cancelService.requireAnotherAdmin(request.reviewerId(), executor, "保留数据销毁");
                    String objectKey = TenantCancelService.str(item.get("sealed_object_key"));
                    if (objectKey != null) {
                        TenantContext.runAsPlatform(
                                () -> storage.delete(TenantExportService.relativeOf(objectKey)));
                    }
                    String verify =
                            "封存对象已删除（"
                                    + TenantCancelService.str(item.get("scope"))
                                    + "）；独立解压密码密文随登记行作废；校验时间 "
                                    + java.time.LocalDateTime.now().withNano(0);
                    long certId =
                            destroyService.issueRetentionCertificate(
                                    tenantId,
                                    code,
                                    tenantName,
                                    TenantCancelService.str(item.get("scope"))
                                            + "；依据 "
                                            + TenantCancelService.str(item.get("legal_basis"))
                                            + "；保留期满日 "
                                            + retainUntil,
                                    verify,
                                    executor,
                                    request.reviewerId(),
                                    reason);
                    platform.update(
                            "UPDATE plt_tenant_retention SET status = 'DESTROYED', destroyed_by = ?,"
                                    + " destroyed_at = CURRENT_TIMESTAMP(3), certificate_id = ?,"
                                    + " sealed_key_cipher = 'DESTROYED' WHERE id = ?",
                            executor,
                            certId,
                            itemId);
                    cancelService.recordEvent(
                            actor(),
                            "RETENTION_DESTROY",
                            code,
                            "保留期满销毁："
                                    + TenantCancelService.str(item.get("scope"))
                                    + "；补充销毁证明 DEST-"
                                    + String.format("%06d", certId)
                                    + "；原因："
                                    + reason);
                    return new DestroyDueResult(tenantId, itemId, "DESTROYED", certId);
                });
    }

    // ---------------------------------------------------------------- 封存包访问（两名平台管理员到场）

    /** 下载封存包（解密为 zip）：操作人 + 复核人（另一名平台管理员）双方到场。 */
    public SealedDownload downloadSealed(long tenantId, long itemId, Long reviewerId) {
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    String code = requireTenantCode(tenantId);
                    Map<String, Object> item = requireItem(tenantId, itemId);
                    if (!"SEALED".equals(TenantCancelService.str(item.get("status")))) {
                        throw new ApiException(409, 40901, "只有已封存的保留项可以访问封存包");
                    }
                    cancelService.requireAnotherAdmin(reviewerId, actor, "封存包访问");
                    String objectKey = TenantCancelService.str(item.get("sealed_object_key"));
                    byte[] sealedBody =
                            objectKey == null
                                    ? null
                                    : TenantContext.callAsPlatform(
                                            () ->
                                                    storage.get(
                                                            TenantExportService.relativeOf(
                                                                    objectKey)));
                    if (sealedBody == null) {
                        throw new ApiException(404, 40400, "封存包对象缺失");
                    }
                    String password =
                            crypto.decrypt(TenantCancelService.str(item.get("sealed_key_cipher")));
                    byte[] zip = archiveCipher.open(sealedBody, password.toCharArray());
                    cancelService.recordEvent(
                            actor,
                            "RETENTION_ACCESS",
                            code,
                            "访问封存保留数据（复核人 "
                                    + cancelService.accountName(reviewerId)
                                    + "）："
                                    + TenantCancelService.str(item.get("scope")));
                    return new SealedDownload(
                            "保留数据封存包-"
                                    + TenantPackageBuilder.safeName(
                                            TenantCancelService.str(item.get("scope")))
                                    + ".zip",
                            zip);
                });
    }

    // ---------------------------------------------------------------- 查询

    public List<RetentionView> list(long tenantId) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT * FROM plt_tenant_retention WHERE tenant_id = ? ORDER BY id",
                                (rs, rowNum) ->
                                        new RetentionView(
                                                rs.getLong("id"),
                                                rs.getString("scope"),
                                                rs.getString("legal_basis"),
                                                rs.getString("tables_csv") == null
                                                        ? List.of()
                                                        : List.of(
                                                                rs.getString("tables_csv")
                                                                        .split(",")),
                                                rs.getDate("retain_until").toString(),
                                                rs.getString("status"),
                                                rs.getTimestamp("sealed_at") == null
                                                        ? null
                                                        : rs.getTimestamp("sealed_at")
                                                                .toLocalDateTime()
                                                                .withNano(0)
                                                                .toString(),
                                                rs.getString("sealed_sha256"),
                                                rs.getTimestamp("destroyed_at") == null
                                                        ? null
                                                        : rs.getTimestamp("destroyed_at")
                                                                .toLocalDateTime()
                                                                .withNano(0)
                                                                .toString(),
                                                rs.getObject("certificate_id") == null
                                                        ? null
                                                        : Long.valueOf(
                                                                rs.getLong("certificate_id"))),
                                tenantId));
    }

    // ---------------------------------------------------------------- 校验（发起申请复用）

    /** 登记项字段校验（申请与保存共用）。 */
    static void validateInput(String scope, String legalBasis, String retainUntil) {
        TenantCancelService.text(scope, "保留数据范围", TEXT_MAX);
        TenantCancelService.text(legalBasis, "法律或合同依据", TEXT_MAX);
        if (retainUntil == null || retainUntil.isBlank()) {
            throw new ApiException(400, 40001, "必须填写保留期满日（yyyy-MM-dd）");
        }
        try {
            LocalDate parsed = LocalDate.parse(retainUntil.trim());
            if (parsed.isBefore(LocalDate.now(ZoneIdOf.SHANGHAI))) {
                throw new ApiException(400, 40001, "保留期满日不能早于当天");
            }
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ApiException(400, 40001, "保留期满日格式应为 yyyy-MM-dd");
        }
    }

    // ---------------------------------------------------------------- 内部

    private static final class ZoneIdOf {

        static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");
    }

    private long actor() {
        return CurrentSession.requirePlatform().userId();
    }

    private Map<String, Object> requireItem(long tenantId, long itemId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT * FROM plt_tenant_retention WHERE id = ? AND tenant_id = ?",
                        itemId,
                        tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "保留项不存在");
        }
        return rows.get(0);
    }

    private String requireTenantCode(long tenantId) {
        List<String> codes =
                platform.queryForList(
                        "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
        if (codes.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return codes.get(0);
    }

    private String tenantName(long tenantId) {
        return TenantCancelService.str(
                platform.queryForObject(
                        "SELECT name FROM plt_tenant WHERE id = ?", String.class, tenantId));
    }

    /** 登记时校验表在租户库存在（库不可达时由封存时的构建校验兜底）。 */
    private void requireTable(long tenantId, String code, String table) {
        if (table.isEmpty()) {
            return;
        }
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new ApiException(400, 40001, "表名只允许字母数字下划线：" + table);
        }
        if (!routing.hasTenant(tenantId)) {
            return;
        }
        Integer count =
                TenantContext.callAsTenant(
                        tenantId,
                        code,
                        () ->
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                                                + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                                        Integer.class,
                                        table));
        if (count == null || count == 0) {
            throw new ApiException(400, 40001, "封存表在租户库中不存在：" + table);
        }
    }

    private static List<String> tablesOf(String tablesCsv) {
        if (tablesCsv == null || tablesCsv.isBlank()) {
            return List.of();
        }
        List<String> tables = new ArrayList<>();
        for (String table : tablesCsv.split(",")) {
            if (!table.isBlank()) {
                tables.add(table.trim());
            }
        }
        return tables;
    }
}
