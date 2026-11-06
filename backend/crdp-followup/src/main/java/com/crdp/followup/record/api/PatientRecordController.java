package com.crdp.followup.record.api;

import com.crdp.followup.record.app.RecordBackfillService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者随访记录接口（E15-T07 / A-FU-014、C-FU-013；E15-T06 起 UNPLANNED 计划外随访 /
 * A-FU-013、C-FU-022；docs/api/followup.md §3 {@code POST
 * /api/hospital/followup/patients/{id}/records}，患者详情「随访记录」页签的补录 / 计划外入口，pages.md §4.5）。
 *
 * <p>历史补录：不需要任务、不受窗口限制，填写实际随访日期新建过去日期的记录（source = BACKFILL）。 计划外随访（source =
 * UNPLANNED，E15-T06）：症状就诊、程控调整等直接新建记录（允许当天，须患者在随访中）， 不影响后续节点；远程监测（C-FU-025）随 E21
 * 在同一入口扩展；修改已提交与删除（C-FU-026）随 C 医院接入。
 */
@Tag(name = "医院端患者随访记录")
@RestController
@RequestMapping("/api/hospital/followup/patients/{id}/records")
public class PatientRecordController {

    private final RecordBackfillService service;

    public PatientRecordController(RecordBackfillService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "新建患者随访记录（历史补录 BACKFILL：过去日期；计划外随访 UNPLANNED：当天可录、须患者在随访中）："
                            + "创建 DRAFT 草稿并返回 recordId（统一用 recordId 进入随访表单）；"
                            + "越范围患者 40400；远程监测随 E21 仍 40001")
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

    /** 创建请求体（followup.md §3）。 */
    public record CreateBody(String source, LocalDate visitDate, String formCode) {}
}
