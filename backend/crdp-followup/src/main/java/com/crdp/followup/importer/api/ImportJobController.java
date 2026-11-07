package com.crdp.followup.importer.api;

import com.crdp.followup.importer.app.ImportCommitService.BatchView;
import com.crdp.followup.importer.app.ImportCommitService.ConfirmOptions;
import com.crdp.followup.importer.app.ImportCommitService.RollbackView;
import com.crdp.followup.importer.app.ImportJobService;
import com.crdp.followup.importer.app.ImportJobService.FileDownload;
import com.crdp.followup.importer.app.ImportJobService.IssuePage;
import com.crdp.followup.importer.app.ImportJobService.JobView;
import com.crdp.followup.importer.app.ImportJobService.UploadedJob;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * E18-T06 / A-PAT-008、A-PAT-018：患者历史数据导入向导接口（docs/api/import-export.md §1，端点定稿随本任务）。 权限 {@code
 * followup:patient:import}（隐藏路由 plt_menu 353，平台库 V58）；功能开关 {@code wide_import} 关闭 → 40301（服务内判定，
 * AGENTS.md 规则 7）。作业属租户级资源：同租户同时只允许一个作业（40901），状态与问题对持导入权限者可见。
 */
@Tag(name = "医院端患者历史数据导入")
@RestController
public class ImportJobController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportJobService imports;

    public ImportJobController(ImportJobService imports) {
        this.imports = imports;
    }

    @Operation(
            summary =
                    "下载导入模板（A-PAT-008）：type=STANDARD（长表「基线」+「随访记录」，默认）| WIDE（宽表，A-PAT-018）；"
                            + "表头来自字段字典、示例行全部虚构数据")
    @GetMapping("/api/hospital/followup/imports/template")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ResponseEntity<byte[]> template(
            @RequestParam(value = "type", required = false) String type) {
        FileDownload file = imports.template(type);
        return attachment(file, XLSX_MIME);
    }

    @Operation(
            summary =
                    "上传导入文件（multipart 字段 file）→ { jobId }，预检异步开始；同一租户已有运行中作业 → 40901；"
                            + "预检进度看 GET /imports/{jobId}（status=PRECHECKING、stagedRows 递增）")
    @PostMapping("/api/hospital/followup/imports")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<UploadedJob> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ApiResponse.ok(imports.upload(file.getOriginalFilename(), file.getBytes()));
    }

    @Operation(
            summary =
                    "作业状态与进度（向导轮询）：状态机 UPLOADED → PRECHECKING → PRECHECKED | PRECHECK_FAILED →"
                            + " IMPORTING → DONE | PARTIAL_FAILED（CANCELLED / ROLLED_BACK 终态）；"
                            + "stagedRows 预检进度、errorCount / warnCount 校验计数、batches 提交批次进度与统计")
    @GetMapping("/api/hospital/followup/imports/{jobId}")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<JobView> view(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(imports.view(jobId));
    }

    @Operation(
            summary = "预检问题分页（level=ERROR|WARN 可空）：行号、列字母、表头、原因、级别；" + "不含单元格原值（敏感列不回显），明细看批注版报告下载")
    @GetMapping("/api/hospital/followup/imports/{jobId}/issues")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<IssuePage> issues(
            @PathVariable("jobId") long jobId,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(imports.issues(jobId, level, pageNo, pageSize));
    }

    @Operation(
            summary =
                    "确认提交（A-PAT-018）：{ duplicateStrategy: UPDATE|SKIP, defaultWardId? }；"
                            + "有 ERROR 问题 → 40001；prepare 冻结选项与操作人范围后异步执行，进度看批次视图")
    @PostMapping("/api/hospital/followup/imports/{jobId}/confirm")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<UploadedJob> confirm(
            @PathVariable("jobId") long jobId, @RequestBody ConfirmOptions options) {
        return ApiResponse.ok(imports.confirm(jobId, options));
    }

    @Operation(summary = "取消作业（只允许确认前：UPLOADED / PRECHECK_FAILED / PRECHECKED → CANCELLED）")
    @PostMapping("/api/hospital/followup/imports/{jobId}/cancel")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<JobView> cancel(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(imports.cancel(jobId));
    }

    @Operation(summary = "重试失败批次（PARTIAL_FAILED → IMPORTING，只重跑 FAILED 批，tech-plan §12⑥）")
    @PostMapping("/api/hospital/followup/imports/{jobId}/retry")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<UploadedJob> retry(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(imports.retry(jobId));
    }

    @Operation(summary = "回滚整个作业（全部已完成批次逐批回滚；E18-T05 按写入逆序删，写 IMPORT_ROLLBACK 审计）")
    @PostMapping("/api/hospital/followup/imports/{jobId}/rollback")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<List<RollbackView>> rollback(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(imports.rollback(jobId));
    }

    @Operation(summary = "回滚一批（只允许已完成批次；失败批次无写数，用重试）")
    @PostMapping("/api/hospital/followup/imports/{jobId}/batches/{batchNo}/rollback")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<RollbackView> rollbackBatch(
            @PathVariable("jobId") long jobId, @PathVariable("batchNo") int batchNo) {
        return ApiResponse.ok(imports.rollbackBatch(jobId, batchNo));
    }

    @Operation(
            summary =
                    "下载批注版校验报告（E18-T03：错误 / 警告单元格标色加批注 + 首张「校验报告」问题清单；"
                            + "预检完成后可用）；每次下载写 DATA_ACCESS 审计")
    @GetMapping("/api/hospital/followup/imports/{jobId}/result")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ResponseEntity<byte[]> result(@PathVariable("jobId") long jobId) {
        FileDownload file = imports.report(jobId);
        return attachment(file, XLSX_MIME);
    }

    /** 批次进度视图也可单独取（向导「按批展示与回滚」）。 */
    @Operation(summary = "批次列表（按批进度：PENDING / RUNNING / DONE / FAILED / ROLLED_BACK 与四类计数）")
    @GetMapping("/api/hospital/followup/imports/{jobId}/batches")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:import")
    public ApiResponse<List<BatchView>> batches(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(imports.view(jobId).batches());
    }

    private static ResponseEntity<byte[]> attachment(FileDownload file, String mime) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(mime))
                .body(file.body());
    }
}
