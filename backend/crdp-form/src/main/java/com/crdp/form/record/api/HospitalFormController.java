package com.crdp.form.record.api;

import com.crdp.form.record.app.FormService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E11-T02 / A-NFR-002、C-NFR-002：医院端表单模板 API（docs/api/form.md §1——FormSchema 按版本读取 与
 * publish；components.md §10.1 契约）。模板读取登录即可（渲染与录入都用）；发布同字段字典权限 followup:field:edit，写 FORM_PUBLISH
 * 审计。记录读写（fe_record / FormService）是引擎服务，供业务接口 （基线 E13、随访记录 E15、报告单映射 E16、草稿 E25）复用，本控制器不暴露记录 HTTP
 * 端点。
 */
@Tag(name = "医院端表单模板")
@RestController
@RequestMapping("/api/hospital/form")
@Validated
public class HospitalFormController {

    private final FormService service;

    public HospitalFormController(FormService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "表单模板 FormSchema（components.md §10.1）：不传 version 取当前发布版；已有记录按其"
                            + "保存时的版本取（旧版本快照永久可读）；版本不存在 40400；rules 随 E11-T04")
    @PublicApi
    @GetMapping("/forms/{code}")
    @RequireModule("followup")
    public ApiResponse<FormService.SchemaView> schema(
            @PathVariable("code") @Size(max = 64) String code,
            @RequestParam(value = "version", required = false) @Min(1) @Max(1_000_000)
                    Integer version) {
        return ApiResponse.ok(service.schema(code, version));
    }

    @Operation(
            summary =
                    "发布新模板版本（form.md §3）：把当前分类与启用字段固化成快照并前移 current_version；"
                            + "停用字段不进新版本、旧版本快照保留；写 FORM_PUBLISH 审计")
    @PostMapping("/forms/{code}/publish")
    @RequireModule("followup")
    @RequiresPermission("followup:field:edit")
    public ApiResponse<FormService.PublishView> publish(
            @PathVariable("code") @Size(max = 64) String code) {
        return ApiResponse.ok(service.publish(code));
    }
}
