package com.crdp.followup.record.api;

import com.crdp.followup.record.app.RecordBackfillService;
import com.crdp.followup.record.app.RecordEntryAssistService;
import com.crdp.followup.record.app.TimepointSuggestionService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者随访记录接口（E15-T07 / A-FU-014、C-FU-013；E15-T06 起 UNPLANNED 计划外随访 /
 * A-FU-013、C-FU-022；docs/api/followup.md §3 {@code POST
 * /api/hospital/followup/patients/{id}/records}，患者详情「随访记录」页签的补录 / 计划外入口，pages.md §4.5）。
 *
 * <p>历史补录：不需要任务、不受窗口限制，填写实际随访日期新建过去日期的记录（source = BACKFILL）。 计划外随访（source =
 * UNPLANNED，E15-T06）：症状就诊、程控调整等直接新建记录（允许当天，须患者在随访中）， 不影响后续节点；远程监测（C-FU-025）随 E21-T12
 * 在同一入口扩展。E21-T11 起附重复记录提示（C-FU-024）：同一患者同一随访日期已有记录时提示，确认后可保存。
 */
@Tag(name = "医院端患者随访记录")
@RestController
@RequestMapping("/api/hospital/followup/patients/{id}/records")
public class PatientRecordController {

    private final RecordBackfillService service;
    private final RecordEntryAssistService assistService;
    private final TimepointSuggestionService timepoints;

    public PatientRecordController(
            RecordBackfillService service,
            RecordEntryAssistService assistService,
            TimepointSuggestionService timepoints) {
        this.service = service;
        this.assistService = assistService;
        this.timepoints = timepoints;
    }

    @Operation(
            summary =
                    "新建患者随访记录（历史补录 BACKFILL：过去日期；计划外随访 UNPLANNED：当天可录、须患者在随访中）："
                            + "创建 DRAFT 草稿并返回 recordId（统一用 recordId 进入随访表单）；"
                            + "越范围患者 40400；远程监测随 E21-T12 仍 40001")
    @PostMapping
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<RecordBackfillService.BackfillView> create(
            @PathVariable("id") long id, @RequestBody(required = false) CreateBody body) {
        return ApiResponse.ok(
                service.create(
                        id,
                        body == null
                                ? null
                                : new RecordBackfillService.BackfillCommand(
                                        body.source(), body.visitDate(), body.formCode())));
    }

    @Operation(
            summary =
                    "重复记录提示（C-FU-024）：同一患者同一随访日期（有效口径 visit_date ?? 表单随访日期 ?? "
                            + "任务计划日期）已有在册记录（含草稿，软删不算）时 duplicate = true 并返回冲突行，"
                            + "前端提示「该患者在 {日期} 已有一条随访记录，确定继续？」确认后照常保存；"
                            + "excludeRecordId 排除自身（修改 / 重新提交场景）；越范围患者 40400")
    @GetMapping("/check-duplicate")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<RecordEntryAssistService.DuplicateCheckView> checkDuplicate(
            @PathVariable("id") long id,
            @RequestParam("visitDate") LocalDate visitDate,
            @RequestParam(value = "excludeRecordId", required = false) Long excludeRecordId) {
        if (visitDate == null) {
            throw new ApiException(400, 40001, "visitDate 必填（C-FU-024 同一随访日期判定）");
        }
        return ApiResponse.ok(assistService.checkDuplicate(id, visitDate, excludeRecordId));
    }

    /** 创建请求体（followup.md §3）。 */
    public record CreateBody(String source, LocalDate visitDate, String formCode) {}

    /**
     * 补录时建议随访时间点（E21-T14 /
     * C-FU-014）：按随访日期距植入日期的天数建议选项（0–7「术后72小时/出院前」…330–400「术后1年」、其后「每6-12个月」；空档区间无建议）、
     * 自动关联当时器械（C-PAT-007 区间匹配）、器械接近 ERI 时优先建议「ERI期随访」（在组 mode=ERI 且随访日期 ≥ eri_since）； 附
     * earliestImplantDate（补录日期下界「不早于植入日期」）。建议值可改选；区间经租户参数 followup.timepoint.ranges 可配。 越范围患者
     * 40400；visitDate 必填 40001。
     */
    @Operation(
            summary =
                    "补录时建议随访时间点（C-FU-014）：距植入天数 → 选项编码与显示名（空档区间 / 无器械 / "
                            + "A-B 表单无字段时无建议），附当时器械与最早植入日期；越范围患者 40400")
    @GetMapping("/suggest-timepoint")
    @RequireModule("followup")
    @RequiresPermission("followup:record:edit")
    public ApiResponse<TimepointSuggestionService.SuggestionView> suggestTimepoint(
            @PathVariable("id") long id,
            @RequestParam("visitDate") LocalDate visitDate,
            @RequestParam(value = "formCode", required = false) String formCode) {
        return ApiResponse.ok(timepoints.suggest(id, visitDate, formCode));
    }
}
