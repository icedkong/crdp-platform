package com.crdp.server.web;

import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.followup.export.PatientExportService;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.crdp.server.system.audit.AuditViews.ExportJobPage;
import com.crdp.server.system.audit.AuditViews.ExportSubmitted;
import com.crdp.server.system.audit.HospitalAuditExportService.DownloadFile;
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
 * E13-T09 / A-PAT-009、C-PAT-014 患者导出接口（docs/api/import-export.md §3）。申请与下载 {@code
 * followup:patient:export}（敏感权限，按钮 plt_menu 212；二次认证 E04-T08）；审批 {@code
 * followup:patient:export:approve}（租户管理员，按钮 plt_menu 218，平台库 V41）。 复用 E08-T04 导出作业表与状态机（biz_type =
 * PATIENT）。
 */
@Tag(name = "医院端患者导出")
@RestController
public class PatientExportController {

    private final PatientExportService exports;

    public PatientExportController(PatientExportService exports) {
        this.exports = exports;
    }

    @Operation(
            summary =
                    "提交患者导出（A-PAT-009）：{ scope=PATIENT, valueMode=CODE|LABEL, fields[],"
                            + " condition（与列表相同的筛选 + 高级检索条件树，不含检索词）, reason }；"
                            + "租户管理员免审批直接生成，普通用户待租户管理员审批（验收「导出需审批」）；"
                            + "内容按导出人数据范围与字段策略（脱敏值，OQ-20）。敏感操作：需二次认证")
    @PostMapping("/api/hospital/followup/exports")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export")
    @RequireReauth("提交患者导出")
    public ApiResponse<ExportSubmitted> submit(
            @RequestBody PatientExportService.ExportRequest request) {
        return ApiResponse.ok(exports.submit(request));
    }

    @Operation(summary = "我的患者导出（含状态：待审批 / 生成中 / 可下载 / 已驳回 / 失败）")
    @GetMapping("/api/hospital/followup/exports")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export")
    public ApiResponse<ExportJobPage> mine(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.mine(pageNo, pageSize));
    }

    @Operation(summary = "待审批患者导出列表（import-export.md §3：租户管理员）")
    @GetMapping("/api/hospital/followup/export-approvals")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export:approve")
    public ApiResponse<ExportJobPage> pending(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(exports.pending(pageNo, pageSize));
    }

    @Operation(summary = "审批通过：PENDING_APPROVAL → GENERATING，按固化条件异步生成（写 OPERATION 审计）")
    @PostMapping("/api/hospital/followup/export-approvals/{id}/approve")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export:approve")
    public ApiResponse<?> approve(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.approve(id, action));
    }

    @Operation(summary = "审批驳回（终态，写 OPERATION 审计）")
    @PostMapping("/api/hospital/followup/export-approvals/{id}/reject")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export:approve")
    public ApiResponse<?> reject(
            @PathVariable("id") long id, @RequestBody(required = false) ApprovalAction action) {
        return ApiResponse.ok(exports.reject(id, action));
    }

    @Operation(summary = "下载导出文件：仅申请人，READY 且 24 小时内有效；每次下载写 DATA_ACCESS 审计。" + "敏感操作：需二次认证")
    @GetMapping("/api/hospital/followup/exports/{id}/download")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:export")
    @RequireReauth("下载患者导出文件")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        DownloadFile file = exports.download(id);
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
