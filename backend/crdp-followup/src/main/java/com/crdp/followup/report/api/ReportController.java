package com.crdp.followup.report.api;

import com.crdp.followup.report.app.ReportMappingService;
import com.crdp.followup.report.app.ReportService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：报告单创建、位置树与患者内清单 （docs/api/report.md §2）。上传
 * {@code followup:scan:upload}（OQ-12），读取 {@code followup:patient:view}（与运行时目录同口径，E16-T01）。
 */
@Tag(name = "医院端报告单（上传与患者内清单）")
@RestController
@RequestMapping("/api/hospital/followup")
public class ReportController {

    private final ReportService service;

    private final ReportMappingService mappingService;

    public ReportController(ReportService service, ReportMappingService mappingService) {
        this.service = service;
        this.mappingService = mappingService;
    }

    @Operation(
            summary =
                    "创建报告单（A-ATT-001 / C-SCAN-001）：位置至少 1 个（40001）、附件 ≤ 每份上限；" + "写 RPT_CREATE 审计")
    @PostMapping("/reports")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportService.CreatedView> create(@Valid @RequestBody CreateBody body) {
        if (body == null || body.patientId() == null || body.patientId() <= 0) {
            throw new ApiException(400, 40001, "缺少患者");
        }
        return ApiResponse.ok(service.create(body.patientId(), toRequest(body)));
    }

    @Operation(
            summary =
                    "批量上传（E16-T03 / A-ATT-014、C-SCAN-007）：先统一类型与位置（至少 1 个），"
                            + "再逐份调整名称与报告时间；mode=ONE_PER_FILE 每个文件一份、MERGE 合并为一份多页资料；"
                            + "一次 ≤ 50 个文件、单份 ≤ 每份上限（超限 40001）；整批一个事务，"
                            + "每份写 RPT_CREATE、另写一条 RPT_BATCH_CREATE 汇总审计")
    @PostMapping("/reports/batch-create")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportService.BatchCreatedView> batchCreate(
            @Valid @RequestBody BatchCreateBody body) {
        if (body == null || body.patientId() == null || body.patientId() <= 0) {
            throw new ApiException(400, 40001, "缺少患者");
        }
        return ApiResponse.ok(service.batchCreate(body.patientId(), toBatchRequest(body)));
    }

    @Operation(
            summary =
                    "患者内报告单清单（pages.md §4.6 报告单页签）：scope=all 默认 / baseline / followup"
                            + "（后两者只保留含该侧位置的报告单，左栏附件导航 E16-T14 用）")
    @GetMapping("/patients/{id}/reports")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<ReportService.ReportsView> reportsOf(
            @PathVariable("id") long id,
            @RequestParam(value = "scope", required = false) String scope) {
        return ApiResponse.ok(service.reportsOf(id, scope));
    }

    // ---------------------------------------------------------------- 删除（E16-T09 /
    // A-ATT-007、C-SCAN-016）

    @Operation(
            summary =
                    "删除整份报告单（软删除，A-ATT-007 / C-SCAN-016）：{ reason } 必填（40001）；"
                            + "status=DELETED + 原因 / 删除人 / 删除时间，附件台账一并软删（预览 / 下载 40400），"
                            + "数据长期保留；越范围 / 不存在 40400（写 DENIED 审计）、重复删除 40901；写 RPT_DELETE 审计")
    @DeleteMapping("/reports/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:delete")
    public ApiResponse<ReportService.DeletedView> delete(
            @PathVariable("id") long id, @RequestBody(required = false) DeleteBody body) {
        return ApiResponse.ok(
                service.delete(
                        id, new ReportService.DeleteRequest(body == null ? null : body.reason())));
    }

    @Operation(
            summary =
                    "删除报告单中的单个文件（软删除，A-ATT-007）：报告单保留、只软删该附件台账"
                            + "（sys_file DELETED，预览 / 下载随即 40400）；{ reason } 必填（40001）；"
                            + "文件不在该报告单 / 已删 40400；写 RPT_FILE_DELETE 审计")
    @DeleteMapping("/reports/{id}/files/{fileId}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:delete")
    public ApiResponse<ReportService.DeletedView> deleteFile(
            @PathVariable("id") long id,
            @PathVariable("fileId") long fileId,
            @RequestBody(required = false) DeleteBody body) {
        return ApiResponse.ok(
                service.deleteFile(
                        id,
                        fileId,
                        new ReportService.DeleteRequest(body == null ? null : body.reason())));
    }

    /** 删除请求体：原因必填、≤ 255 字（A-ATT-007「须填写原因」）。 */
    record DeleteBody(String reason) {}

    @Operation(
            summary =
                    "报告单位置树（components.md §8 LocationPicker options）：基线资料（当前发布版分类）"
                            + "+ 随访记录（整条 + 按该记录固化版本的分组）；植入记录锚点随 E21-T32")
    @GetMapping("/patients/{id}/report-locations")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<ReportService.LocationNodeView>> reportLocations(
            @PathVariable("id") long id) {
        return ApiResponse.ok(service.reportLocations(id));
    }

    // ---------------------------------------------------------------- 映射写入（E16-T07 /
    // A-ATT-013、C-SCAN-005）

    @Operation(
            summary =
                    "映射预览（A-ATT-013 流程第 4 步「列出将写入的字段及原值」）：适用映射、候选目标"
                            + "（映射目标须是位置之一）、字段原值 / 新值与 conflict 标记、值转换错误、时相边界 / "
                            + "基线锁定 blockedReason；不写任何数据")
    @PostMapping("/reports/{id}/mapping/preview")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportMappingService.PreviewView> mappingPreview(
            @PathVariable("id") long id) {
        return ApiResponse.ok(mappingService.preview(id));
    }

    @Operation(
            summary =
                    "映射确认写入（流程第 5 步「参数写入映射目标记录，并记入字段变更记录」）：只写 fieldKeys"
                            + " 内的键（原值不同的须逐项确认覆盖，未确认的冲突字段跳过并回告）；"
                            + "同一事务写 fe_field_history（来源 = 报告单）与 RPT_MAPPING_WRITE 审计；"
                            + "版本冲突 40900、基线锁定 40901、时相越界 40001")
    @PostMapping("/reports/{id}/mapping/confirm")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportMappingService.ConfirmedView> mappingConfirm(
            @PathVariable("id") long id, @RequestBody(required = false) MappingConfirmBody body) {
        if (body == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        ReportMappingService.ConfirmCommand command =
                new ReportMappingService.ConfirmCommand(
                        body.mappingId(),
                        body.linkRecordId(),
                        body.targetVersion(),
                        body.fieldKeys());
        return ApiResponse.ok(mappingService.confirm(id, command));
    }

    /** 映射确认请求体（report.md §3；targetVersion 未建基线分节为 0）。 */
    record MappingConfirmBody(
            String mappingId, String linkRecordId, Integer targetVersion, List<String> fieldKeys) {}

    private static ReportService.CreateRequest toRequest(CreateBody body) {
        List<ReportService.IndicatorInput> indicators =
                body.indicators() == null
                        ? List.of()
                        : body.indicators().stream()
                                .map(
                                        item ->
                                                new ReportService.IndicatorInput(
                                                        item.itemCode(),
                                                        item.result(),
                                                        item.refRange()))
                                .toList();
        List<ReportService.LocationInput> locations =
                body.locations() == null
                        ? List.of()
                        : body.locations().stream().map(ReportController::toLocation).toList();
        return new ReportService.CreateRequest(
                body.typeCode(),
                body.nameId(),
                body.reportDate(),
                body.fields(),
                indicators,
                body.findings(),
                body.conclusion(),
                locations,
                body.fileIds(),
                body.note());
    }

    private static ReportService.BatchCreateRequest toBatchRequest(BatchCreateBody body) {
        List<ReportService.BatchItemOverride> items =
                body.items() == null
                        ? List.of()
                        : body.items().stream()
                                .map(
                                        item ->
                                                new ReportService.BatchItemOverride(
                                                        item.fileId(),
                                                        item.nameId(),
                                                        item.reportDate(),
                                                        item.locations() == null
                                                                ? null
                                                                : item.locations().stream()
                                                                        .map(
                                                                                ReportController
                                                                                        ::toLocation)
                                                                        .toList()))
                                .toList();
        List<ReportService.LocationInput> locations =
                body.locations() == null
                        ? List.of()
                        : body.locations().stream().map(ReportController::toLocation).toList();
        return new ReportService.BatchCreateRequest(
                body.mode(),
                body.typeCode(),
                body.nameId(),
                body.reportDate(),
                locations,
                body.fileIds(),
                items);
    }

    private static ReportService.LocationInput toLocation(LocationBody loc) {
        Long recordId = null;
        if (loc.linkRecordId() != null && !loc.linkRecordId().isBlank()) {
            try {
                recordId = Long.parseLong(loc.linkRecordId().trim());
            } catch (NumberFormatException e) {
                throw new ApiException(404, 40400, "随访记录不存在");
            }
        }
        return new ReportService.LocationInput(
                loc.linkType(),
                recordId,
                loc.anchorType(),
                loc.anchorKey(),
                loc.anchorTemplateVersion());
    }

    /** 创建请求体（报告单，report.md §2；ID 序列化为字符串，超 2^53 安全）。 */
    record CreateBody(
            Long patientId,
            String typeCode,
            String nameId,
            java.time.LocalDate reportDate,
            java.util.Map<String, Object> fields,
            List<IndicatorBody> indicators,
            String findings,
            String conclusion,
            List<LocationBody> locations,
            List<String> fileIds,
            String note) {}

    /** 批量上传请求体（report.md §2 POST /reports/batch-create）。 */
    record BatchCreateBody(
            Long patientId,
            String mode,
            String typeCode,
            String nameId,
            java.time.LocalDate reportDate,
            List<LocationBody> locations,
            List<String> fileIds,
            List<BatchItemBody> items) {}

    /** 逐份调整（ONE_PER_FILE；空 / null 项沿用公共值）。 */
    record BatchItemBody(
            String fileId,
            String nameId,
            java.time.LocalDate reportDate,
            List<LocationBody> locations) {}

    record IndicatorBody(String itemCode, String result, String refRange) {}

    record LocationBody(
            String linkType,
            String linkRecordId,
            String anchorType,
            String anchorKey,
            Integer anchorTemplateVersion) {}
}
