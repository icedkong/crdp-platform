package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientTransferService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者转病区（E13-T08 / A-WARD-005、C-WARD-006；docs/api/patient.md §4，入口 = 患者详情与列表行操作， pages.md §3 /
 * §6）。权限 {@code followup:patient:transfer}（按钮 plt_menu 216，平台库 V40）。
 */
@Tag(name = "医院端患者转病区")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientTransferController {

    private final PatientTransferService service;

    public PatientTransferController(PatientTransferService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "转病区（A-WARD-005）：目标病区启用 + 原因必填；只改 pt_patient.ward_id 并追加"
                            + " pt_ward_history——原病区医生立即失去访问权（数据范围按患者行改写），"
                            + "任务与附件经 patient_id 随患者迁移；写 PATIENT_TRANSFER 审计（越范围"
                            + " 40400 不暴露存在并写 DENIED）")
    @PostMapping("/{id}/transfer")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:transfer")
    public ApiResponse<PatientTransferService.TransferredView> transfer(
            @PathVariable long id, @RequestBody TransferRequest request) {
        return ApiResponse.ok(
                service.transfer(
                        id,
                        new PatientTransferService.TransferRequest(
                                request.wardId(), request.reason())));
    }

    @Operation(
            summary = "转病区历史（A-WARD-005「保留转病区历史」）：新在前；越范围 / 不存在的患者 40400。" + "权限与患者查看同口径（历史属患者数据）")
    @GetMapping("/{id}/ward-history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientTransferService.WardHistoryView> history(@PathVariable long id) {
        return ApiResponse.ok(service.historyOf(id));
    }

    /** 转病区请求体：目标病区 + 原因（必填）。 */
    public record TransferRequest(Long wardId, @Size(max = 255) String reason) {}
}
