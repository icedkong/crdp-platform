package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.configtemplate.ConfigPackage;
import com.crdp.server.configtemplate.ConfigTemplateExportService;
import com.crdp.server.configtemplate.ConfigTemplateExportService.ExportRequest;
import com.crdp.server.configtemplate.ConfigTemplateExportService.ExportedView;
import com.crdp.server.configtemplate.ConfigTemplateExportService.PageView;
import com.crdp.server.configtemplate.ConfigTemplateExportService.TemplateDetail;
import com.crdp.server.configtemplate.ConfigTemplateImportService;
import com.crdp.server.configtemplate.ConfigTemplateImportService.AppliedView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端配置模板 API（E12-T01 导出 / E12-T02 应用，A-FLD-014，docs/api/platform.md §7.1）。
 * 导出与应用都只碰配置表注册表标注的表（不含患者数据）；管理页随 E12-T04。
 */
@Tag(name = "平台端配置模板")
@RestController
@RequestMapping("/api/platform/config-templates")
public class PlatformConfigTemplateController {

    private final ConfigTemplateExportService service;
    private final ConfigTemplateImportService importService;

    public PlatformConfigTemplateController(
            ConfigTemplateExportService service, ConfigTemplateImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    public record ExportBody(Long tenantId, String name) {}

    public record ApplyBody(Long tenantId) {}

    @Operation(
            summary =
                    "从源租户导出配置模板（A-FLD-014）：只导出注册表标注的配置表，不含患者数据；"
                            + "STRUCTURE_ONLY 表（术者、病区 / 中心）只带结构不带取值")
    @PostMapping
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<ExportedView> export(@RequestBody ExportBody body) {
        if (body == null || body.tenantId() == null) {
            throw new ApiException(400, 40001, "tenantId 必填");
        }
        return ApiResponse.ok(service.export(new ExportRequest(body.tenantId(), body.name())));
    }

    @Operation(summary = "配置模板分页（新→旧）")
    @GetMapping
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<PageView> list(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.list(pageNo, pageSize));
    }

    @Operation(summary = "配置模板详情：头信息与每张配置表的行数元信息（不带行数据）")
    @GetMapping("/{id}")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TemplateDetail> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @Operation(summary = "读取配置模板完整包（gzip 解压后的 JSON 结构；下载写审计）")
    @GetMapping("/{id}/package")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<ConfigPackage> download(@PathVariable long id) {
        return ApiResponse.ok(service.download(id));
    }

    @Operation(summary = "配置表注册表：导出会包含哪些表、按什么方式复制（预览用）")
    @GetMapping("/registry")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<List<com.crdp.platform.configtemplate.ConfigTableEntry>> registry() {
        return ApiResponse.ok(service.registry());
    }

    @Operation(
            summary =
                    "将配置模板应用到目标租户（A-FLD-014）：导入配置表并重映射 ID / 改写引用；用户引用列置空；"
                            + "角色菜单与新租户可分配范围取交集；只复制结构（术者、病区 / 中心）不落行")
    @PostMapping("/{id}/apply")
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<AppliedView> apply(@PathVariable long id, @RequestBody ApplyBody body) {
        if (body == null || body.tenantId() == null) {
            throw new ApiException(400, 40001, "tenantId 必填");
        }
        return ApiResponse.ok(
                importService.apply(
                        id, new ConfigTemplateImportService.ApplyRequest(body.tenantId())));
    }
}
