package com.crdp.server.platform;

import com.crdp.framework.crypto.ArchiveCipher;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据迁出与交付（E05-T08 / PLT-TEN-019，验收链路：注销 → 审批 → 冷静期 → 迁出包 → 签收 → 销毁）。
 *
 * <p>冷静期满后按标准迁出包结构（{@link TenantPackageBuilder}）导出租户库全部表 + 原始文件 + 数据字典 + 清单校验和， 整包 AES-256
 * 加密（.crdpa 容器），限时下载（7 天 / 5 次，规格已确认），解压密码信封加密存平台库、 经短信另行发送医院联系人（短信通道随 E09，与到期提醒同款 PENDING
 * 派发口径——先存密文，通道接入后补发）。 医院校验无误后在线确认并上传签收单（平台账号代录），签收后才能进入销毁（规格「迁出包交付后须医院确认校验无误并签收，才能进入销毁」）。
 */
@Service
public class TenantExportService {

    /** 迁出包解压密码的信封加密用途（短信通道接入前仅密文存档）。 */
    static final String PASSWORD_PURPOSE = "plt-export-password";

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final TenantCancelService cancelService;
    private final TenantPackageBuilder packages;
    private final ArchiveCipher archiveCipher;
    private final CryptoService crypto;
    private final ObjectStorage storage;

    public TenantExportService(
            JdbcTemplate platform,
            TenantRoutingDataSource routing,
            TenantCancelService cancelService,
            TenantPackageBuilder packages,
            ArchiveCipher archiveCipher,
            CryptoService crypto,
            ObjectStorage storage) {
        this.platform = platform;
        this.routing = routing;
        this.cancelService = cancelService;
        this.packages = packages;
        this.archiveCipher = archiveCipher;
        this.crypto = crypto;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- DTO

    /** 迁出包行（页面与总览用）。 */
    public record ExportView(
            long id,
            String packageNo,
            String sha256,
            long sizeBytes,
            int tableCount,
            long totalRows,
            int fileCount,
            String expiresAt,
            int maxDownloads,
            int downloadCount,
            String generatedAt,
            String ackedAt,
            String ackNote,
            String ackFileName) {}

    public record GenerateResult(
            long tenantId,
            long exportId,
            String packageNo,
            int tableCount,
            long totalRows,
            int fileCount,
            long sizeBytes,
            String expiresAt,
            int maxDownloads,
            boolean reissued) {}

    /** 签收：医院校验无误后在线确认（平台账号代录）+ 上传签收单。 */
    public record AckRequest(String note, Long ackFileId) {}

    public record AckResult(long tenantId, long exportId, String packageNo, String stage) {}

    /** 下载产物（.crdpa 加密容器，文件名带租户编码与包号）。 */
    public record DownloadFile(String fileName, byte[] body) {}

    // ---------------------------------------------------------------- 生成（PLT-TEN-019）

    /** 生成迁出包；下载链接过期或次数用完可重新生成（规格），每次生成与下载均记录。 */
    public GenerateResult generate(long tenantId) {
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    String code = TenantCancelService.str(tenant.get("code"));
                    String name = TenantCancelService.str(tenant.get("name"));
                    Map<String, Object> cancel = cancelService.requireCancel(tenantId);
                    String stage = TenantCancelService.str(cancel.get("stage"));
                    boolean reissue;
                    if (TenantCancelService.STAGE_COOLING.equals(stage)) {
                        LocalDateTime coolingUntil =
                                TenantCancelService.timeOf(cancel.get("cooling_until"));
                        if (coolingUntil == null || LocalDateTime.now().isBefore(coolingUntil)) {
                            throw new ApiException(
                                    409,
                                    40901,
                                    "冷静期未满（至 "
                                            + (coolingUntil == null ? "未登记" : coolingUntil)
                                            + "），不能生成迁出包");
                        }
                        reissue = false;
                    } else if (TenantCancelService.STAGE_EXPORTED.equals(stage)) {
                        reissue = true;
                    } else {
                        throw new ApiException(
                                409, 40901, "只有注销中（冷静期满）或已生成过迁出包的流程可以生成迁出包（当前 " + stage + "）");
                    }
                    if (!routing.hasTenant(tenantId)) {
                        throw new ApiException(409, 40901, "租户库不可达，无法导出数据");
                    }
                    String packageNo =
                            "EXP-%d-%s-%04d"
                                    .formatted(
                                            tenantId,
                                            LocalDateTime.now().format(NO_FORMAT),
                                            ThreadLocalRandom.current().nextInt(10000));
                    TenantPackageBuilder.BuiltPackage built =
                            TenantContext.callAsTenant(
                                    tenantId,
                                    code,
                                    () -> packages.build(tenantId, code, name, null));
                    String password = archiveCipher.randomPassword();
                    byte[] sealed = archiveCipher.seal(built.zip(), password.toCharArray());
                    String relativeKey = "cancel/%d/export/%s.crdpa".formatted(tenantId, packageNo);
                    String objectKey =
                            TenantContext.callAsPlatform(
                                    () ->
                                            storage.put(
                                                    relativeKey,
                                                    sealed,
                                                    "application/octet-stream"));
                    platform.update(
                            "INSERT INTO plt_tenant_export"
                                    + " (tenant_id, package_no, object_key, sha256, size_bytes,"
                                    + " table_count, total_rows, file_count, password_cipher,"
                                    + " expires_at, max_downloads, generated_by) VALUES (?, ?, ?, ?,"
                                    + " ?, ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL "
                                    + TenantCancelService.EXPORT_LINK_DAYS
                                    + " DAY), "
                                    + TenantCancelService.EXPORT_MAX_DOWNLOADS
                                    + ", ?)",
                            tenantId,
                            packageNo,
                            objectKey,
                            TenantCancelService.sha256Hex(sealed),
                            sealed.length,
                            built.tableCount(),
                            built.totalRows(),
                            built.fileCount(),
                            crypto.encrypt(PASSWORD_PURPOSE, password),
                            actor);
                    if (!reissue) {
                        cancelService.advanceStage(
                                TenantCancelService.longOf(cancel.get("id")),
                                TenantCancelService.STAGE_COOLING,
                                TenantCancelService.STAGE_EXPORTED);
                    }
                    cancelService.recordEvent(
                            actor,
                            reissue ? "EXPORT_REISSUE" : "EXPORT_GENERATE",
                            code,
                            "生成迁出包 "
                                    + packageNo
                                    + "：表 "
                                    + built.tableCount()
                                    + " 张、行 "
                                    + built.totalRows()
                                    + "、原始文件 "
                                    + built.fileCount()
                                    + " 个、加密包 "
                                    + sealed.length
                                    + " 字节；下载链接 "
                                    + TenantCancelService.EXPORT_LINK_DAYS
                                    + " 天内有效、最多 "
                                    + TenantCancelService.EXPORT_MAX_DOWNLOADS
                                    + " 次");
                    return new GenerateResult(
                            tenantId,
                            packageIdOf(packageNo),
                            packageNo,
                            built.tableCount(),
                            built.totalRows(),
                            built.fileCount(),
                            sealed.length,
                            LocalDateTime.now()
                                    .plusDays(TenantCancelService.EXPORT_LINK_DAYS)
                                    .withNano(0)
                                    .toString(),
                            TenantCancelService.EXPORT_MAX_DOWNLOADS,
                            reissue);
                });
    }

    // ---------------------------------------------------------------- 限时下载（PLT-TEN-019）

    public DownloadFile download(long tenantId, long exportId) {
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireExport(tenantId, exportId);
                    String packageNo = TenantCancelService.str(row.get("package_no"));
                    LocalDateTime expiresAt = TenantCancelService.timeOf(row.get("expires_at"));
                    if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
                        throw new ApiException(409, 40901, "下载链接已过期（" + expiresAt + "）；请重新生成迁出包");
                    }
                    int count = ((Number) row.get("download_count")).intValue();
                    int max = ((Number) row.get("max_downloads")).intValue();
                    if (count >= max) {
                        throw new ApiException(
                                409, 40901, "下载次数已用完（" + count + "/" + max + "）；请重新生成迁出包");
                    }
                    platform.update(
                            "UPDATE plt_tenant_export SET download_count = download_count + 1"
                                    + " WHERE id = ? AND download_count = ?",
                            exportId,
                            count);
                    String objectKey = TenantCancelService.str(row.get("object_key"));
                    byte[] body =
                            TenantContext.callAsPlatform(() -> storage.get(relativeOf(objectKey)));
                    if (body == null) {
                        throw new ApiException(404, 40400, "迁出包对象缺失，请联系平台管理员");
                    }
                    cancelService.recordEvent(
                            actor,
                            "EXPORT_DOWNLOAD",
                            TenantCancelService.str(
                                    platform.queryForObject(
                                            "SELECT code FROM plt_tenant WHERE id = ?",
                                            String.class,
                                            tenantId)),
                            "下载迁出包 " + packageNo + "（第 " + (count + 1) + "/" + max + " 次）");
                    return new DownloadFile(
                            TenantCancelService.str(
                                            platform.queryForObject(
                                                    "SELECT code FROM plt_tenant WHERE id = ?",
                                                    String.class,
                                                    tenantId))
                                    + "-"
                                    + packageNo
                                    + ".crdpa",
                            body);
                });
    }

    // ---------------------------------------------------------------- 签收（PLT-TEN-019）

    /** 医院校验无误后在线确认（平台账号代录）并上传签收单；签收后进入销毁阶段。 */
    public AckResult ack(long tenantId, long exportId, AckRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String note = TenantCancelService.text(request.note(), "签收说明", 500);
        long actor = CurrentSession.requirePlatform().userId();
        return TenantContext.callAsPlatform(
                () -> {
                    String code =
                            TenantCancelService.str(
                                    platform.queryForObject(
                                            "SELECT code FROM plt_tenant WHERE id = ?",
                                            String.class,
                                            tenantId));
                    Map<String, Object> row = requireExport(tenantId, exportId);
                    String packageNo = TenantCancelService.str(row.get("package_no"));
                    if (TenantCancelService.longOf(row.get("acked_by")) != 0) {
                        throw new ApiException(409, 40901, "该迁出包已签收，不能重复签收");
                    }
                    Map<String, Object> cancel = cancelService.requireCancel(tenantId);
                    String stage = TenantCancelService.str(cancel.get("stage"));
                    if (!TenantCancelService.STAGE_EXPORTED.equals(stage)) {
                        throw new ApiException(409, 40901, "只有已生成迁出包的流程可以签收（当前 " + stage + "）");
                    }
                    String ackFileName = null;
                    if (request.ackFileId() != null) {
                        Map<String, Object> file =
                                platform.queryForMap(
                                        "SELECT id, file_name FROM plt_cancel_file WHERE id = ?"
                                                + " AND tenant_id = ? AND kind = 'ACK'",
                                        request.ackFileId(),
                                        tenantId);
                        ackFileName = TenantCancelService.str(file.get("file_name"));
                    }
                    platform.update(
                            "UPDATE plt_tenant_export SET acked_by = ?, acked_at ="
                                    + " CURRENT_TIMESTAMP(3), ack_note = ?, ack_file_id = ? WHERE id = ?",
                            actor,
                            note,
                            request.ackFileId(),
                            exportId);
                    cancelService.advanceStage(
                            TenantCancelService.longOf(cancel.get("id")),
                            TenantCancelService.STAGE_EXPORTED,
                            TenantCancelService.STAGE_ACKED);
                    cancelService.recordEvent(
                            actor,
                            "EXPORT_ACK",
                            code,
                            "医院签收迁出包 "
                                    + packageNo
                                    + "；说明："
                                    + note
                                    + (ackFileName == null ? "" : "；签收单：" + ackFileName));
                    return new AckResult(
                            tenantId, exportId, packageNo, TenantCancelService.STAGE_ACKED);
                });
    }

    // ---------------------------------------------------------------- 查询

    public List<ExportView> exportsOf(long tenantId) {
        return platform.query(
                "SELECT e.*, f.file_name AS ack_file_name FROM plt_tenant_export e LEFT JOIN"
                        + " plt_cancel_file f ON f.id = e.ack_file_id WHERE e.tenant_id = ?"
                        + " ORDER BY e.id DESC",
                (rs, rowNum) ->
                        new ExportView(
                                rs.getLong("id"),
                                rs.getString("package_no"),
                                rs.getString("sha256"),
                                rs.getLong("size_bytes"),
                                rs.getInt("table_count"),
                                rs.getLong("total_rows"),
                                rs.getInt("file_count"),
                                rs.getTimestamp("expires_at")
                                        .toLocalDateTime()
                                        .withNano(0)
                                        .toString(),
                                rs.getInt("max_downloads"),
                                rs.getInt("download_count"),
                                rs.getTimestamp("generated_at")
                                        .toLocalDateTime()
                                        .withNano(0)
                                        .toString(),
                                rs.getTimestamp("acked_at") == null
                                        ? null
                                        : rs.getTimestamp("acked_at")
                                                .toLocalDateTime()
                                                .withNano(0)
                                                .toString(),
                                rs.getString("ack_note"),
                                rs.getString("ack_file_name")),
                tenantId);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireExport(long tenantId, long exportId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT * FROM plt_tenant_export WHERE id = ? AND tenant_id = ?",
                        exportId,
                        tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "迁出包不存在");
        }
        return rows.get(0);
    }

    private long packageIdOf(String packageNo) {
        Long id =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant_export WHERE package_no = ?",
                        Long.class,
                        packageNo);
        return id == null ? 0 : id;
    }

    /** 存库的是完整对象键（platform/ 前缀）；读取走相对键。 */
    static String relativeOf(String objectKey) {
        return objectKey.startsWith("platform/")
                ? objectKey.substring("platform/".length())
                : objectKey;
    }
}
