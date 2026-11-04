package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientBatchAssignService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量指定责任医生（E13-T07 / A-PAT-017、C-PAT-016；docs/api/patient.md §2，入口 = 患者列表勾选与批量入组页右侧， pages.md §3 /
 * §8）。 权限 {@code followup:task:assign}（OQ-18 暂行口径：A 规格无独立「患者改派」码，与任务批量改派同码；按钮 plt_menu 224，平台库
 * V52）。
 */
@Tag(name = "医院端患者批量操作")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientBatchController {

    private final PatientBatchAssignService service;

    public PatientBatchController(PatientBatchAssignService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "批量指定责任医生（A-PAT-017）：只能选该病区的医生——医生须负责患者当前病区"
                            + "（sys_user_ward），跨病区患者逐条跳过；越范围 / 不存在患者按 NOT_FOUND"
                            + " 逐条列明（不暴露存在）；单次 ≤ 500；写 PATIENT_BATCH_ASSIGN 汇总审计")
    @PostMapping("/batch-assign")
    @RequireModule("followup")
    @RequiresPermission("followup:task:assign")
    public ApiResponse<PatientBatchAssignService.BatchAssignResult> batchAssign(
            @RequestBody BatchAssignRequest request) {
        return ApiResponse.ok(
                service.assign(
                        request == null ? null : request.patientIds(),
                        request == null ? null : request.doctorId()));
    }

    @Operation(
            summary =
                    "责任医生候选（A-PAT-017「只能选该病区的医生」的数据面）：启用病区的启用用户"
                            + "（负责病区 = sys_user_ward），可选按病区过滤；逐条强校验在服务层")
    @GetMapping("/doctor-options")
    @RequireModule("followup")
    @RequiresPermission("followup:task:assign")
    public ApiResponse<PatientBatchAssignService.DoctorOptionsView> doctorOptions(
            @RequestParam(value = "wardIds", required = false) List<Long> wardIds) {
        return ApiResponse.ok(service.doctorOptions(wardIds));
    }

    /** 批量指定请求体（单次 ≤ 500）。 */
    public record BatchAssignRequest(List<Long> patientIds, Long doctorId) {}
}
