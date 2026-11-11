package com.crdp.server.web;

import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.TenantCancelService;
import com.crdp.server.platform.TenantDestroyService;
import com.crdp.server.platform.TenantExportService;
import com.crdp.server.platform.TenantRetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 平台端「注销与数据处理」API（E05-T08 / PLT-TEN-018..021，验收：注销需审批；销毁出证明）。
 *
 * <p>流程：发起（上传书面确认 + 法定保留清单）→ 审批（另一名平台管理员；通过 → 注销中 + 冷静期 30 天）→ 冷静期内可撤销 → 冷静期满生成迁出包（7 天 / 5
 * 次限时下载，密码经短信另行发送）→ 医院签收 → 封存保留数据 → 双人复核执行销毁 → 出具销毁证明 PDF。
 * 下载迁出包与销毁证明为敏感操作，须二次认证（X-Reauth-Token，PLT-AUTH-012）。
 */
@Tag(name = "平台端租户注销与数据处理")
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/cancel")
public class PlatformTenantCancelController {

    private final TenantCancelService cancelService;
    private final TenantExportService exportService;
    private final TenantRetentionService retentionService;
    private final TenantDestroyService destroyService;

    public PlatformTenantCancelController(
            TenantCancelService cancelService,
            TenantExportService exportService,
            TenantRetentionService retentionService,
            TenantDestroyService destroyService) {
        this.cancelService = cancelService;
        this.exportService = exportService;
        this.retentionService = retentionService;
        this.destroyService = destroyService;
    }

    /** 注销与数据处理页总览（流程 + 保留清单 + 迁出包 + 证明）。 */
    public record CancelOverview(
            TenantCancelService.CancelFlowView flow,
            List<TenantRetentionService.RetentionView> retentionItems,
            List<TenantExportService.ExportView> exports,
            List<TenantDestroyService.CertificateView> certificates) {}

    @Operation(summary = "注销流程总览（PLT-TEN-018..021）：阶段、冷静期、保留清单、迁出包与证明")
    @GetMapping
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<CancelOverview> overview(@PathVariable long tenantId) {
        return ApiResponse.ok(
                new CancelOverview(
                        cancelService.flowOf(tenantId),
                        retentionService.list(tenantId),
                        exportService.exportsOf(tenantId),
                        destroyService.certificatesOf(tenantId)));
    }

    @Operation(
            summary =
                    "发起注销申请（PLT-TEN-018）：仅停用状态租户；原因必填，须先上传医院书面确认；"
                            + "可同时登记需依法或按合同保留的数据；被驳回 / 撤销后可重新发起")
    @PostMapping
    @RequiresPermission("platform:tenant:cancel")
    public ApiResponse<TenantCancelService.ApplyResult> apply(
            @PathVariable long tenantId, @RequestBody TenantCancelService.ApplyRequest request) {
        return ApiResponse.ok(cancelService.apply(tenantId, request));
    }

    @Operation(summary = "上传医院书面确认（PLT-TEN-018）：PDF / JPG / PNG，最大 20MB；返回文件 id 供发起时引用")
    @PostMapping(value = "/confirmation-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("platform:tenant:cancel")
    public ApiResponse<TenantCancelService.FileView> uploadConfirmation(
            @PathVariable long tenantId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(cancelService.upload(tenantId, "CONFIRMATION", file));
    }

    @Operation(
            summary =
                    "审批注销（PLT-TEN-018）：decision=APPROVE 通过（→ 注销中、不可登录、冷静期 30 天）/ REJECT 驳回；"
                            + "审批人必须不是发起人（双人复核）")
    @PostMapping("/approve")
    @RequiresPermission("platform:tenant:cancel:approve")
    public ApiResponse<TenantCancelService.StageResult> approve(
            @PathVariable long tenantId, @RequestBody TenantCancelService.ApproveRequest request) {
        return ApiResponse.ok(cancelService.approve(tenantId, request));
    }

    @Operation(summary = "冷静期内撤销注销（PLT-TEN-018）：租户回到停用；冷静期已满则 40901")
    @PostMapping("/revoke")
    @RequiresPermission("platform:tenant:cancel")
    public ApiResponse<TenantCancelService.StageResult> revoke(
            @PathVariable long tenantId, @RequestBody TenantCancelService.RevokeRequest request) {
        return ApiResponse.ok(cancelService.revoke(tenantId, request));
    }

    @Operation(
            summary =
                    "生成迁出包（PLT-TEN-019）：冷静期满后按标准迁出包导出（每表 CSV+JSON、数据字典、原始文件、"
                            + "manifest SHA-256），AES-256 加密；链接 7 天 / 5 次，可重新生成；解压密码经短信另行发送")
    @PostMapping("/exports")
    @RequiresPermission("platform:tenant:export")
    public ApiResponse<TenantExportService.GenerateResult> generateExport(
            @PathVariable long tenantId) {
        return ApiResponse.ok(exportService.generate(tenantId));
    }

    @Operation(summary = "下载迁出包（PLT-TEN-019）：限时 7 天 / 5 次，过期或用完 40901；敏感操作需二次认证")
    @GetMapping("/exports/{exportId}/download")
    @RequireReauth("下载数据迁出包")
    @RequiresPermission("platform:tenant:export")
    public ResponseEntity<byte[]> downloadExport(
            @PathVariable long tenantId, @PathVariable long exportId) {
        TenantExportService.DownloadFile file = exportService.download(tenantId, exportId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.body());
    }

    @Operation(summary = "医院签收迁出包（PLT-TEN-019）：校验无误后在线确认（平台账号代录，说明必填）并上传签收单；" + "签收后才能进入销毁")
    @PostMapping("/exports/{exportId}/ack")
    @RequiresPermission("platform:tenant:export")
    public ApiResponse<TenantExportService.AckResult> ackExport(
            @PathVariable long tenantId,
            @PathVariable long exportId,
            @RequestBody TenantExportService.AckRequest request) {
        return ApiResponse.ok(exportService.ack(tenantId, exportId, request));
    }

    @Operation(summary = "上传迁出包签收单（PLT-TEN-019）：PDF / JPG / PNG，最大 20MB")
    @PostMapping(value = "/ack-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("platform:tenant:export")
    public ApiResponse<TenantCancelService.FileView> uploadAck(
            @PathVariable long tenantId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(cancelService.upload(tenantId, "ACK", file));
    }

    @Operation(summary = "登记法定保留数据（PLT-TEN-021）：整体替换未封存项；范围、依据、保留期满日必填，" + "可指定封存时导出的租户库表")
    @PutMapping("/retention")
    @RequiresPermission("platform:tenant:retain")
    public ApiResponse<List<TenantRetentionService.RetentionView>> saveRetention(
            @PathVariable long tenantId,
            @RequestBody TenantRetentionService.RetentionSaveRequest request) {
        retentionService.save(tenantId, request);
        return ApiResponse.ok(retentionService.list(tenantId));
    }

    @Operation(summary = "封存保留数据（PLT-TEN-021）：迁出包生成后、销毁前；按登记表导出并用独立密码加密封存")
    @PostMapping("/retention/seal")
    @RequiresPermission("platform:tenant:retain")
    public ApiResponse<TenantRetentionService.SealResult> sealRetention(
            @PathVariable long tenantId, @RequestBody TenantRetentionService.SealRequest request) {
        return ApiResponse.ok(retentionService.seal(tenantId, request));
    }

    @Operation(summary = "访问封存包（PLT-TEN-021）：须两名平台管理员到场（操作人 + reviewerId 复核人）；" + "敏感操作需二次认证")
    @GetMapping("/retention/{itemId}/download")
    @RequireReauth("访问封存的保留数据")
    @RequiresPermission("platform:tenant:retain")
    public ResponseEntity<byte[]> downloadRetention(
            @PathVariable long tenantId, @PathVariable long itemId, @RequestParam long reviewerId) {
        TenantRetentionService.SealedDownload file =
                retentionService.downloadSealed(tenantId, itemId, reviewerId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.body());
    }

    @Operation(
            summary = "保留期满销毁（PLT-TEN-021）：保留期满日之后按销毁流程处理并补充出具销毁证明；" + "双人复核（reviewerId 为另一名平台管理员）")
    @PostMapping("/retention/{itemId}/destroy")
    @RequiresPermission("platform:tenant:retain")
    public ApiResponse<TenantRetentionService.DestroyDueResult> destroyRetention(
            @PathVariable long tenantId,
            @PathVariable long itemId,
            @RequestBody TenantRetentionService.DestroyDueRequest request) {
        return ApiResponse.ok(retentionService.destroyDue(tenantId, itemId, request));
    }

    @Operation(
            summary =
                    "执行数据销毁（PLT-TEN-020）：须已签收且保留项全部封存；删除库 / 账号 / 对象 / 会话并注销连接池，"
                            + "状态 → 已注销，出具销毁证明；双人复核 + 二次认证")
    @PostMapping("/destroy")
    @RequireReauth("执行租户数据销毁")
    @RequiresPermission("platform:tenant:destroy")
    public ApiResponse<TenantDestroyService.DestroyResult> destroy(
            @PathVariable long tenantId, @RequestBody TenantDestroyService.DestroyRequest request) {
        return ApiResponse.ok(destroyService.destroy(tenantId, request));
    }

    @Operation(summary = "销毁证明列表（PLT-TEN-020）：租户整体销毁与保留数据期满销毁")
    @GetMapping("/certificates")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<List<TenantDestroyService.CertificateView>> certificates(
            @PathVariable long tenantId) {
        return ApiResponse.ok(destroyService.certificatesOf(tenantId));
    }

    @Operation(summary = "下载销毁证明 PDF（PLT-TEN-020）：平台存档并交付医院；敏感操作需二次认证")
    @GetMapping("/certificates/{certificateId}/download")
    @RequireReauth("下载数据销毁证明")
    @RequiresPermission("platform:tenant:destroy")
    public ResponseEntity<byte[]> downloadCertificate(
            @PathVariable long tenantId, @PathVariable long certificateId) {
        TenantDestroyService.CertificateFile file =
                destroyService.downloadCertificate(tenantId, certificateId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.body());
    }
}
