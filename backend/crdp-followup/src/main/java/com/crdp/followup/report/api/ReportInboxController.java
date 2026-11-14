package com.crdp.followup.report.api;

import com.crdp.followup.report.app.ReportInboxService;
import com.crdp.followup.report.app.ReportService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E21-T16 / C-SCAN-009：待归档区（先扫描后标注，批量扫描件分拣；docs/api/report.md §4）。
 *
 * <p>权限（OQ-12 report 族）：放入与归档 {@code followup:scan:upload}（按钮 231，有上传权限的角色即归档 角色）；检索 {@code
 * followup:inbox:view}（菜单 354，plt_menu.perm_code 唯一故新设码，平台库 V74）。开关 {@code report_inbox} 关闭（A / B
 * 默认）三个口一律 40301（AGENTS §5.7）；待归档区按上传人所在科室 / 病区隔离 （C-WARD-005，服务层承担）。
 */
@Tag(name = "医院端待归档区（批量扫描件分拣）")
@RestController
@RequestMapping("/api/hospital/followup")
public class ReportInboxController {

    private final ReportInboxService service;

    public ReportInboxController(ReportInboxService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "放入待归档区（C-SCAN-009 先扫描后标注）：{ fileIds[1–50] }——文件须已上传且未挂任何记录"
                            + "（PENDING）；已在待归档区 / 已挂他处 40001；开关 report_inbox 关闭 40301；"
                            + "写 RPT_INBOX_CREATE 审计")
    @PostMapping("/report-inbox/files")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportInboxService.IntakeView> intake(
            @RequestBody(required = false) IntakeBody body) {
        return ApiResponse.ok(
                service.intake(
                        body == null
                                ? null
                                : new ReportInboxService.IntakeRequest(body.fileIds())));
    }

    @Operation(
            summary =
                    "检索待归档区（C-SCAN-009 待分拣文件集中分拣）：只出待分拣行，按上传日期 / 上传人过滤；"
                            + "同样按科室病区隔离（按上传人所在科室 / 病区，无患者锚点时的暂行口径）；"
                            + "批量查询写 BATCH_QUERY 审计；开关关闭 40301")
    @PostMapping("/report-inbox/search")
    @RequireModule("followup")
    @RequiresPermission("followup:inbox:view")
    public ApiResponse<ReportInboxService.PageView> search(
            @RequestBody(required = false) SearchBody body) {
        return ApiResponse.ok(
                service.search(
                        body == null
                                ? null
                                : new ReportInboxService.SearchRequest(
                                        body.pageNo(),
                                        body.pageSize(),
                                        body.uploadedFrom(),
                                        body.uploadedTo(),
                                        body.creatorId())));
    }

    @Operation(
            summary =
                    "归档（C-SCAN-009 后标注）：{ patientId, typeCode, nameId?, reportDate?, locations[≥1] }"
                            + "——逐个指定患者、类型与位置（至少一个）后生成报告单（复用创建链路：患者越范围 40400、"
                            + "位置为空 40001、锚点按当前模板版本校验，写 RPT_CREATE）；报告时间缺省当天；"
                            + "成功后待归档行置 ARCHIVED（保留溯源）并写 RPT_INBOX_ARCHIVE 审计；"
                            + "不可见行 40400（写 DENIED 审计）、并发归档 40901、开关关闭 40301")
    @PostMapping("/report-inbox/{id}/archive")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<ReportService.CreatedView> archive(
            @PathVariable("id") long id, @RequestBody(required = false) ArchiveBody body) {
        if (body == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        return ApiResponse.ok(
                service.archive(
                        id,
                        new ReportInboxService.ArchiveRequest(
                                body.patientId(),
                                body.typeCode(),
                                body.nameId(),
                                body.reportDate(),
                                body.locations() == null
                                        ? List.of()
                                        : body.locations().stream()
                                                .map(ReportInboxController::toLocation)
                                                .toList())));
    }

    /** 进区请求体（report.md §4）。 */
    record IntakeBody(List<String> fileIds) {}

    /** 检索请求体（report.md §4；字段全部可空）。 */
    record SearchBody(
            Integer pageNo,
            Integer pageSize,
            java.time.LocalDate uploadedFrom,
            java.time.LocalDate uploadedTo,
            Long creatorId) {}

    /** 归档请求体（report.md §4）。 */
    record ArchiveBody(
            Long patientId,
            String typeCode,
            String nameId,
            java.time.LocalDate reportDate,
            List<LocationBody> locations) {}

    record LocationBody(
            String linkType,
            String linkRecordId,
            String anchorType,
            String anchorKey,
            Integer anchorTemplateVersion) {}

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
}
