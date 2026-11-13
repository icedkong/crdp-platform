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
 * 患者转病区 / 转科室（E13-T08 / A-WARD-005；E21-T21 / C-WARD-006 科室 + 病区双维度；docs/api/patient.md §4，入口 =
 * 患者详情与列表行操作，pages.md §3 / §6）。权限 {@code followup:patient:transfer}（按钮 plt_menu 216，平台库 V40）。
 */
@Tag(name = "医院端患者转病区 / 转科室")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientTransferController {

    private final PatientTransferService service;

    public PatientTransferController(PatientTransferService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "转病区 / 转科室（A-WARD-005、C-WARD-006）：wardId / deptId 之一或同时携带（至少一项），"
                            + "目标须存在且启用、与当前归属相同 40001；只改 pt_patient 归属列并追加"
                            + " pt_ward_history / pt_dept_history——原病区 / 原科室用户立即失去访问权"
                            + "（数据范围按患者行改写，科室与病区分支 OR），任务与扫描件经 patient_id"
                            + " 随患者迁移；转科室为医院 C 口径（implant 开关关闭 40001）；写"
                            + " PATIENT_TRANSFER 审计（越范围 40400 不暴露存在并写 DENIED）")
    @PostMapping("/{id}/transfer")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:transfer")
    public ApiResponse<PatientTransferService.TransferredView> transfer(
            @PathVariable long id, @RequestBody TransferRequest request) {
        return ApiResponse.ok(
                service.transfer(
                        id,
                        new PatientTransferService.TransferRequest(
                                request.wardId(), request.deptId(), request.reason())));
    }

    @Operation(
            summary = "转病区历史（A-WARD-005「保留转病区历史」）：新在前；越范围 / 不存在的患者 40400。" + "权限与患者查看同口径（历史属患者数据）")
    @GetMapping("/{id}/ward-history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientTransferService.WardHistoryView> history(@PathVariable long id) {
        return ApiResponse.ok(service.historyOf(id));
    }

    @Operation(
            summary =
                    "转科室历史（E21-T21 / C-WARD-006「保留转科历史」）：新在前；越范围 / 不存在的患者"
                            + " 40400。权限与患者查看同口径（历史属患者数据）")
    @GetMapping("/{id}/dept-history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientTransferService.DeptHistoryView> deptHistory(@PathVariable long id) {
        return ApiResponse.ok(service.deptHistoryOf(id));
    }

    /** 转病区 / 转科室请求体：目标病区 / 科室（至少一项）+ 原因（必填）。 */
    public record TransferRequest(Long wardId, Long deptId, @Size(max = 255) String reason) {}
}
