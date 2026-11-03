package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientDeleteService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequireReauth;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 删除患者（软删除）与恢复（E13-T09 / A-PAT-012、A-NFR-008、C-PAT-015、C-NFR-008；接口 docs/api/patient.md §2）。权限
 * {@code followup:patient:delete}（C 规格权限表；A 按 OQ-17 同名新增，按钮 plt_menu 217，平台库 V41）；
 * 敏感操作需二次认证（@RequireReauth，E04-T08 / PLT-AUTH-012）。
 */
@Tag(name = "医院端患者删除（软删除）")
@RestController
@RequestMapping("/api/hospital/followup/patients")
public class PatientDeletionController {

    private final PatientDeleteService service;

    public PatientDeletionController(PatientDeleteService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "软删除患者（A-PAT-012）：{ reason } 必填；只置删除标记并释放病案号，数据长期保留"
                            + "（A-NFR-008 只软删），子表经 patient_id 随患者不可见；越范围 / 不存在 40400"
                            + "（写 DENIED 审计）；敏感操作需二次认证")
    @DeleteMapping("/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:delete")
    @RequireReauth("删除患者")
    public ApiResponse<PatientDeleteService.DeletedView> delete(
            @PathVariable("id") long id, @RequestBody(required = false) DeleteRequest request) {
        return ApiResponse.ok(
                service.delete(
                        id,
                        new PatientDeleteService.DeleteRequest(
                                request == null ? null : request.reason())));
    }

    @Operation(
            summary =
                    "恢复已软删患者（A-PAT-012「可由租户管理员恢复」）：病案号已被新患者占用 40902；"
                            + "没有可恢复的已删除患者 40400；敏感操作需二次认证")
    @PostMapping("/{id}/restore")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:delete")
    @RequireReauth("恢复已删除患者")
    public ApiResponse<PatientDeleteService.RestoredView> restore(@PathVariable("id") long id) {
        return ApiResponse.ok(service.restore(id));
    }

    /** 删除请求体：原因必填（≤ 255）。 */
    public record DeleteRequest(@Size(max = 255) String reason) {}
}
