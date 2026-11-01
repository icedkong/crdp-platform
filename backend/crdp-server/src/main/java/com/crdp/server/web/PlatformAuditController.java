package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.PlatformAuditExportService;
import com.crdp.server.platform.PlatformAuditService;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.AuditDetailView;
import com.crdp.server.system.audit.AuditViews.AuditPage;
import com.crdp.server.system.audit.AuditViews.ChainCheckView;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportRequest;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E08-T04 / PLT-AUD-005、006、007、009：平台端审计中心接口（docs/api/audit.md §3）。 查看与验链结果
 * platform:audit:view；申请导出与下载 platform:audit:export；审批 platform:audit:export:approve（平台管理员，
 * 申请人不能审批自己的申请）。
 */
@Tag(name = "平台端审计中心")
@RestController
public class PlatformAuditController {

    private final PlatformAuditService query;
    private final PlatformAuditExportService exports;

    public PlatformAuditController(PlatformAuditService query, PlatformAuditExportService exports) {
        this.query = query;
        this.exports = exports;
    }

    @Operation(
            summary =
                    "日志查询（PLT-AUD-005）：type = operation|login|tenant-view|view-rule|control-plane，可按租户筛选")
    @PostMapping("/api/platform/audit/{type}/search")
    @RequiresPermission("platform:audit:view")
    public ApiResponse<AuditPage> search(
            @PathVariable("type") String type, @RequestBody SearchRequest request) {
        return ApiResponse.ok(query.search(type, request));
    }

    @Operation(summary = "日志详情（PLT-AUD-006）：前后值对比 + 同 traceId 关联")
    @GetMapping("/api/platform/audit/{type}/{id}")
    @RequiresPermission("platform:audit:view")
    public ApiResponse<AuditDetailView> detail(
            @PathVariable("type") String type, @PathVariable("id") long id) {
        return ApiResponse.ok(query.detail(type, id));
    }

    @Operation(summary = "最近一次哈希链校验结果（PLT-AUD-009）：tenantId 为空表示平台库自身")
    @GetMapping("/api/platform/audit/integrity")
    @RequiresPermission("platform:audit:view")
    public ApiResponse<List<ChainCheckView>> integrity(
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return ApiResponse.ok(query.integrity(tenantId));
    }

    @Operation(summary = "提交平台审计导出（PLT-AUD-007）：一律待平台管理员审批，申请人不能自审；导出本身记审计")
    @PostMapping("/api/platform/audit/exports")
    @RequiresPermission("platform:audit:export")
    public ApiResponse<ExportSubmitted> submit(@RequestBody ExportRequest request) {
        return ApiResponse.ok(exports.submit(request));
    }

    @Operation(summary = "我的审计导出（含状态）")
    @GetMapping("/api/platform/audit/exports")
    @RequiresPermission("platform:audit:export")
    public ApiResponse<ExportJobPage> mine(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.mine(pageNo, pageSize));
    }

    @Operation(summary = "待审批导出列表（platform:audit:export:approve，平台管理员）")
    @GetMapping("/api/platform/audit/export-approvals")
    @RequiresPermission("platform:audit:export:approve")
    public ApiResponse<ExportJobPage> pending(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.pending(pageNo, pageSize));
    }

    @Operation(summary = "审批通过：PENDING_APPROVAL → GENERATING，异步生成；申请人不能审批自己的申请（40901）")
    @PostMapping("/api/platform/audit/export-approvals/{id}/approve")
    @RequiresPermission("platform:audit:export:approve")
    public ApiResponse<?> approve(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.approve(id, action));
    }

    @Operation(summary = "审批驳回（终态）")
    @PostMapping("/api/platform/audit/export-approvals/{id}/reject")
    @RequiresPermission("platform:audit:export:approve")
    public ApiResponse<?> reject(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.reject(id, action));
    }

    @Operation(summary = "下载导出文件：仅申请人，READY 且 24 小时内有效；每次下载写 DATA_ACCESS 审计")
    @GetMapping("/api/platform/audit/exports/{id}/download")
    @RequiresPermission("platform:audit:export")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        PlatformAuditExportService.DownloadFile file = exports.download(id);
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
