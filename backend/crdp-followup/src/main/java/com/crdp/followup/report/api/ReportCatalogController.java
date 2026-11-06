package com.crdp.followup.report.api;

import com.crdp.followup.report.app.ReportConfigService;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012；C-SCAN-002、003、004：添加报告单对话框的运行时读取 API （docs/api/report.md
 * §2）：生效类型与字段集、某类型下生效名称（含指标模板项目）。
 *
 * <p>权限 followup:patient:view（report.md §2，登录的随访用户即可读字典——添加入口的显隐由 followup:scan:upload
 * 按钮控制）。创建报告单（POST /reports）随 E16-T02。
 */
@Tag(name = "医院端报告单类型与名称字典")
@RestController
@RequestMapping("/api/hospital/followup")
public class ReportCatalogController {

    private final ReportConfigService service;

    public ReportCatalogController(ReportConfigService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "生效类型与字段集（A-ATT-003 / C-SCAN-002）：只取启用类型，附该类型字段集"
                            + "（fe_form REPORT 表单的启用字段，紧凑视图）与名称必填、默认电子化状态（C）")
    @GetMapping("/report-types")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<ReportConfigService.CatalogTypeView>> reportTypes() {
        return ApiResponse.ok(service.catalogTypes());
    }

    @Operation(
            summary =
                    "某类型下生效名称与指标模板（A-ATT-011 / C-SCAN-004）：选名称自动带出项目行"
                            + "（项目名称、代码、参考范围、单位、顺序），模板停用不再带出；类型不存在 40400")
    @GetMapping("/report-names")
    @RequireModule("followup")
    @RequiresPermission("followup:patient:view")
    public ApiResponse<List<ReportConfigService.CatalogNameView>> reportNames(
            @RequestParam("typeCode") @NotBlank @Size(max = 32) String typeCode) {
        return ApiResponse.ok(service.catalogNames(typeCode));
    }
}
