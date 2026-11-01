package com.crdp.server.web;

import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.system.audit.AuditTables;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.AuditDetailView;
import com.crdp.server.system.audit.AuditViews.AuditPage;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportRequest;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
import com.crdp.server.system.audit.HospitalAuditExportService;
import com.crdp.server.system.audit.HospitalAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E08-T04 / PLT-AUD-005、006、007：医院端审计中心接口（docs/api/audit.md §2）。 查看与患者页签 tenant:audit:view；申请导出与下载
 * tenant:audit:export（敏感）； 审批 tenant:audit:export:approve（租户管理员）。 租户侧不含平台访问记录（PLT-AUD-004 仅存平台侧）。
 */
@Tag(name = "医院端审计中心")
@RestController
public class HospitalAuditController {

    private final HospitalAuditService query;
    private final HospitalAuditExportService exports;

    public HospitalAuditController(HospitalAuditService query, HospitalAuditExportService exports) {
        this.query = query;
        this.exports = exports;
    }

    @Operation(
            summary =
                    "日志查询（PLT-AUD-005）：type = operation|login|data-access；时间、用户、模块、对象、结果、IP、traceId 组合，跨度 ≤ 3 个月")
    @PostMapping("/api/hospital/system/audit/{type}/search")
    @RequiresPermission("tenant:audit:view")
    public ApiResponse<AuditPage> search(
            @PathVariable("type") String type, @RequestBody SearchRequest request) {
        return ApiResponse.ok(query.search(AuditTables.tenantKind(type), request));
    }

    @Operation(summary = "日志详情（PLT-AUD-006）：前后值对比（敏感值为摘要）+ 同 traceId 关联链路")
    @GetMapping("/api/hospital/system/audit/{type}/{id}")
    @RequiresPermission("tenant:audit:view")
    public ApiResponse<AuditDetailView> detail(
            @PathVariable("type") String type, @PathVariable("id") long id) {
        return ApiResponse.ok(query.detail(AuditTables.tenantKind(type), id));
    }

    @Operation(summary = "患者「操作日志」页签：该患者相关的操作与数据访问日志（只读，audit.md §2）")
    @GetMapping("/api/hospital/followup/patients/{id}/logs")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<AuditPage> patientLogs(
            @PathVariable("id") long id,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(query.patientLogs(id, pageNo, pageSize));
    }

    @Operation(
            summary =
                    "提交审计导出（PLT-AUD-007）：租户管理员免审批直接生成；普通用户待租户管理员审批；导出本身记审计。"
                            + "敏感操作：需二次认证（X-Reauth-Token，E04-T08 / PLT-AUTH-012）")
    @PostMapping("/api/hospital/system/audit/exports")
    @RequiresPermission("tenant:audit:export")
    @RequireReauth("提交审计导出")
    public ApiResponse<ExportSubmitted> submit(@RequestBody ExportRequest request) {
        return ApiResponse.ok(exports.submit(request));
    }

    @Operation(summary = "我的审计导出（含状态：待审批 / 生成中 / 可下载 / 已驳回 / 失败）")
    @GetMapping("/api/hospital/system/audit/exports")
    @RequiresPermission("tenant:audit:export")
    public ApiResponse<ExportJobPage> mine(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.mine(pageNo, pageSize));
    }

    @Operation(summary = "待审批导出列表（import-export.md §3：租户管理员，tenant:audit:export:approve）")
    @GetMapping("/api/hospital/system/export-approvals")
    @RequiresPermission("tenant:audit:export:approve")
    public ApiResponse<ExportJobPage> pending(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.pending(pageNo, pageSize));
    }

    @Operation(summary = "审批通过：状态迁移 PENDING_APPROVAL → GENERATING，异步生成文件（写 OPERATION 审计）")
    @PostMapping("/api/hospital/system/export-approvals/{id}/approve")
    @RequiresPermission("tenant:audit:export:approve")
    public ApiResponse<?> approve(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.approve(id, action));
    }

    @Operation(summary = "审批驳回（终态，写 OPERATION 审计）")
    @PostMapping("/api/hospital/system/export-approvals/{id}/reject")
    @RequiresPermission("tenant:audit:export:approve")
    public ApiResponse<?> reject(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.reject(id, action));
    }

    @Operation(
            summary =
                    "下载导出文件：仅申请人，READY 且 24 小时内有效；每次下载写 DATA_ACCESS 审计。"
                            + "敏感操作：需二次认证（X-Reauth-Token，E04-T08 / PLT-AUTH-012）")
    @GetMapping("/api/hospital/system/audit/exports/{id}/download")
    @RequiresPermission("tenant:audit:export")
    @RequireReauth("下载审计导出文件")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        HospitalAuditExportService.DownloadFile file = exports.download(id);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(file.body());
    }
}
