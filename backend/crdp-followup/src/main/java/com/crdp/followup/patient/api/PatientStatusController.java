package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientLostService;
import com.crdp.followup.patient.app.PatientLostService.LostTransitionView;
import com.crdp.followup.patient.app.PatientStatusHistoryService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者随访状态（E13-T03；docs/api/patient.md §3）。状态时间线查询 + 改为失访写入口（S5 / A-PAT-011、A-FU-012）； 暂停 / 恢复 /
 * 移出随计划入组操作（E14-T06，PlanController）。
 */
@Tag(name = "医院端患者随访状态")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientStatusController {

    private final PatientStatusHistoryService service;
    private final PatientLostService lostService;

    public PatientStatusController(
            PatientStatusHistoryService service, PatientLostService lostService) {
        this.service = service;
        this.lostService = lostService;
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

    @Operation(
            summary =
                    "患者改为失访（S5 / A-FU-012「系统提示后由医生改为失访」、A-PAT-011）：body { action:"
                            + " \"LOST\", reason }，reason 必填；写状态历史（source=MANUAL）与 PATIENT_LOST_MARK 审计，"
                            + "失访后该患者未开始 / 未完成任务全部终止（终止原因「失访」），之后不再创建任务；"
                            + "只接受随访中 / 已暂停，已死亡 / 已终止 / 已失访 40901；越范围 40400（不暴露存在）")
    @PostMapping("/{id}/status")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:edit")
    public ApiResponse<LostTransitionView> markLost(
            @PathVariable("id") long id, @RequestBody(required = false) StatusBody body) {
        if (body == null || !"LOST".equals(body.action())) {
            throw new ApiException(400, 40001, "本接口当前只支持 action=LOST（改为失访）；暂停 / 恢复 / 移出走计划入组操作");
        }
        return ApiResponse.ok(
                lostService.markLost(id, body.reason(), CurrentSession.requireHospital().userId()));
    }

    /**
     * 状态写入口请求体（patient.md §1：action 取 PAUSE|RESUME|REMOVE|TERMINATE|LOST|DEAD，本轮只开放 LOST，其余随
     * E14-T06 计划入组操作）。
     */
    record StatusBody(String action, String reason) {}
}
