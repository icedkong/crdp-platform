package com.crdp.followup.patient.api;

import com.crdp.followup.patient.app.PatientCorrectionService;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** OQR-04：登记信息与敏感身份分流，无明文读取旁路。 */
@Tag(name = "医院端登记纠错")
@RestController
@RequestMapping("/api/hospital/followup/patients/{id}")
public class PatientCorrectionController {
    private final PatientCorrectionService service;

    public PatientCorrectionController(PatientCorrectionService service) {
        this.service = service;
    }

    @Operation(summary = "读取登记元数据、实体信息及脱敏身份，不能获取旧明文")
    @GetMapping("/registration")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<PatientCorrectionService.View> view(@PathVariable long id) {
        return ApiResponse.ok(service.view(id));
    }

    @Operation(summary = "登记实体就地纠错：版本校验、原因、字段历史与日期重算")
    @PutMapping
    @RequireModule("followup")
    @RequiresPermission("followup:patient:edit")
    public ApiResponse<PatientCorrectionService.View> save(
            @PathVariable long id, @RequestBody PatientCorrectionService.Command command) {
        return ApiResponse.ok(service.save(id, command));
    }

    @Operation(summary = "敏感身份就地更正：独立权限、二次确认与密文摘要历史")
    @PutMapping("/identity")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:correct")
    public ApiResponse<PatientCorrectionService.View> identity(
            @PathVariable long id, @RequestBody PatientCorrectionService.Command command) {
        return ApiResponse.ok(service.correctIdentity(id, command));
    }

    @Operation(summary = "读取登记字段历史，敏感身份仅返回密文摘要")
    @GetMapping("/registration/history")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<FieldHistoryService.HistoryView>> history(@PathVariable long id) {
        return ApiResponse.ok(service.history(id));
    }
}
