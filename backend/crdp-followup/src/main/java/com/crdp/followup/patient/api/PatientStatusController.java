package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientStatusHistoryService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者随访状态（E13-T03；docs/api/patient.md §3）。状态流转接口 {@code POST /{id}/status}（暂停 / 移除 / 恢复 / 停止 / 失访 /
 * 死亡，A-PAT-011）随 E14-T06 落地——它要联动关闭未完成任务（A-FU-008，任务表随 E15）。 本任务先交付时间线查询。
 */
@Tag(name = "医院端患者随访状态")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientStatusController {

    private final PatientStatusHistoryService service;

    public PatientStatusController(PatientStatusHistoryService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "状态变更历史（E13-T03，pt_status_history 只追加）：生效日期新在前；登记初始行"
                            + " ACTIVE（source=REGISTRATION）；流转写入随 E14-T06")
    @GetMapping("/{id}/status-history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<PatientStatusHistoryService.StatusItemView>> statusHistory(
            @PathVariable("id") long id) {
        return ApiResponse.ok(
                service.historyOf(id).stream()
                        .map(
                                row ->
                                        new PatientStatusHistoryService.StatusItemView(
                                                String.valueOf(row.id()),
                                                row.status(),
                                                row.effectiveDate(),
                                                row.reason(),
                                                row.source(),
                                                row.createdBy() == null
                                                        ? null
                                                        : String.valueOf(row.createdBy()),
                                                row.createdAt()))
                        .toList());
    }
}
