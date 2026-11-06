package com.crdp.followup.report.api;

import com.crdp.followup.report.app.ReportConfigService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004：医院端「报告单设置」API
 * （docs/api/report.md §6，页面 /system/report-config，pages.md §10）。
 *
 * <p>查看与维护同码 followup:scan:config（菜单 plt_menu 39，平台迁移 V53，OQ-12 定论的 scan 族）。
 * 配置是平台通用能力、按院生效（A-ATT-018），同一套接口服务 A / B / C，差异全部在配置行。 字段映射（.../mappings）随
 * E16-T07、格式与大小（.../file-policy）随 E10-T01 平台参数，不在本控制器。
 */
@Tag(name = "医院端报告单设置")
@RestController
@RequestMapping("/api/hospital/followup/report-config")
public class ReportConfigController {

    private final ReportConfigService service;

    public ReportConfigController(ReportConfigService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 资料类型（A-ATT-003 / C-SCAN-002）

    @Operation(
            summary =
                    "资料类型清单（含停用，按排序号）：编码、名称、字段集（fe_form REPORT 表单）、名称是否必填"
                            + "（C-SCAN-003）、默认电子化状态（C-SCAN-011，A 为 null）、排序、启停、类型下名称数")
    @GetMapping("/types")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<List<ReportConfigService.TypeView>> types() {
        return ApiResponse.ok(service.types());
    }

    @Operation(
            summary =
                    "新建资料类型（A-ATT-003「租户管理员可增减」）：编码本院唯一（40902）且创建后不可改、"
                            + "字段集必须是 REPORT 表单（40001）；写 RPT_TYPE_CREATE 审计")
    @PostMapping("/types")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.TypeView> createType(
            @RequestBody ReportConfigService.TypeCreateRequest request) {
        return ApiResponse.ok(service.createType(request));
    }

    @Operation(
            summary =
                    "编辑资料类型：名称、字段集、名称必填、默认电子化状态、排序、启停、说明"
                            + "（编码不可改）；写 RPT_TYPE_UPDATE 审计（前后值，启停在描述点明）")
    @PutMapping("/types/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.TypeView> updateType(
            @PathVariable("id") long id,
            @RequestBody ReportConfigService.TypeUpdateRequest request) {
        return ApiResponse.ok(service.updateType(id, request));
    }

    @Operation(
            summary =
                    "删除资料类型（A-ATT-003「可增减」）：类型下仍有名称时 40901（先删名称）；"
                            + "写 RPT_TYPE_DELETE 审计（含被阻止 DENIED）")
    @DeleteMapping("/types/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<Void> deleteType(@PathVariable("id") long id) {
        service.deleteType(id);
        return ApiResponse.ok(null);
    }

    // ---------------------------------------------------------------- 名称字典（A-ATT-011 / C-SCAN-003）

    @Operation(summary = "名称字典清单（typeCode 可空 = 全部，含停用）：所属类型、名称、绑定的指标模板、" + "电子化状态覆盖（C）、排序、启停")
    @GetMapping("/names")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<List<ReportConfigService.NameView>> names(
            @RequestParam(value = "typeCode", required = false) @Size(max = 32) String typeCode) {
        return ApiResponse.ok(service.names(typeCode));
    }

    @Operation(
            summary =
                    "新建名称：同类型下唯一（40902）；同名报告单可多次添加、按报告时间区分（A-ATT-011，"
                            + "字典只管取值）；绑定指标模板须存在；写 RPT_NAME_CREATE 审计")
    @PostMapping("/names")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.NameView> createName(
            @RequestBody ReportConfigService.NameCreateRequest request) {
        return ApiResponse.ok(service.createName(request));
    }

    @Operation(summary = "编辑名称：名称、绑定模板、电子化状态覆盖、排序、启停（所属类型不可改）；" + "写 RPT_NAME_UPDATE 审计（前后值）")
    @PutMapping("/names/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.NameView> updateName(
            @PathVariable("id") long id,
            @RequestBody ReportConfigService.NameUpdateRequest request) {
        return ApiResponse.ok(service.updateName(id, request));
    }

    @Operation(summary = "删除名称；写 RPT_NAME_DELETE 审计（报告单引用守卫随 E16-T02 落地）")
    @DeleteMapping("/names/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<Void> deleteName(@PathVariable("id") long id) {
        service.deleteName(id);
        return ApiResponse.ok(null);
    }

    // ---------------------------------------------------------------- 指标模板（A-ATT-012 / C-SCAN-004）

    @Operation(
            summary = "指标模板清单（含停用，模板带全部项目：项目名称、代码、参考范围（可按性别区分）、" + "单位、顺序；boundNameCount = 绑定名称数）")
    @GetMapping("/indicator-templates")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<List<ReportConfigService.IndicatorTplView>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @Operation(
            summary =
                    "新建指标模板（A-ATT-012「模板由租户管理员维护」）：名称本院唯一（40902）、"
                            + "项目全量给出（代码模板内唯一、至少一行）；写 RPT_INDICATOR_TPL_CREATE 审计")
    @PostMapping("/indicator-templates")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.IndicatorTplView> createTemplate(
            @RequestBody ReportConfigService.TplCreateRequest request) {
        return ApiResponse.ok(service.createTemplate(request));
    }

    @Operation(
            summary =
                    "编辑指标模板：名称、说明、启停与项目全量替换（先清后插）；修改只影响之后新建的"
                            + "报告单（已保存指标行不变，C 规格）；写 RPT_INDICATOR_TPL_UPDATE 审计（前后值）")
    @PutMapping("/indicator-templates/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<ReportConfigService.IndicatorTplView> updateTemplate(
            @PathVariable("id") long id,
            @RequestBody ReportConfigService.TplUpdateRequest request) {
        return ApiResponse.ok(service.updateTemplate(id, request));
    }

    @Operation(
            summary = "删除指标模板：仍被名称绑定时 40901（先解绑）；写 RPT_INDICATOR_TPL_DELETE 审计" + "（含被阻止 DENIED）")
    @DeleteMapping("/indicator-templates/{id}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:config")
    public ApiResponse<Void> deleteTemplate(@PathVariable("id") long id) {
        service.deleteTemplate(id);
        return ApiResponse.ok(null);
    }
}
