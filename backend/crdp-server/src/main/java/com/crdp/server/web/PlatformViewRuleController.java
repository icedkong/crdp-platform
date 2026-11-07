package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.platform.ViewRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端视角规则 API（E20-T02 / PLT-VIEW-007、008、009，docs/api/platform.md §6）。
 *
 * <p>平台管理员按层级（全局默认 / 租户 / 模块 / 字段）配置脱敏、设盲、只读、导出规则（无需医院参与），
 * 系统初始默认以种子规则交付（V109）；效果预览与执行共用同一解析器（ViewRuleResolver），预览值即执行值。 查看 {@code
 * platform:view:rule:view}，新建 / 修改 / 停用 {@code platform:view:rule:edit} （高敏变更的第二人复核接口随 E20-T05 /
 * PLT-VIEW-014）。
 */
@Tag(name = "平台端视角规则")
@RestController
@RequestMapping("/api/platform/view-rules")
public class PlatformViewRuleController {

    private final ViewRuleService ruleService;

    public PlatformViewRuleController(ViewRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Operation(
            summary =
                    "规则列表（PLT-VIEW-007）：过滤参数均可空；roleCodes 为空 = 全部平台角色；"
                            + "每行带 version（每次变更 +1，完整版本链随 E20-T05）")
    @GetMapping
    @RequiresPermission("platform:view:rule:view")
    public ApiResponse<List<ViewRuleService.RuleView>> list(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String moduleCode,
            @RequestParam(required = false) String resource) {
        return ApiResponse.ok(ruleService.list(layer, status, tenantId, moduleCode, resource));
    }

    @Operation(summary = "视角字段目录（FIELD 层引用与预览用）与脱敏规则码清单")
    @GetMapping("/catalog")
    @RequiresPermission("platform:view:rule:view")
    public ApiResponse<ViewRuleService.Catalog> catalog() {
        return ApiResponse.ok(ruleService.catalog());
    }

    @Operation(summary = "新建规则（PLT-VIEW-007）：规则编号服务端生成（VR-00001）；普通变更即时生效" + "（高敏变更待复核随 E20-T05）")
    @PostMapping
    @RequiresPermission("platform:view:rule:edit")
    public ApiResponse<ViewRuleService.RuleView> create(
            @RequestBody ViewRuleService.SaveRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @Operation(
            summary = "维护规则（含停用 / 启用，PLT-VIEW-007）：版本 +1；未知规则 40400；" + "停用后进行中的视角会话在下一次请求按剩余规则执行")
    @PutMapping("/{id}")
    @RequiresPermission("platform:view:rule:edit")
    public ApiResponse<ViewRuleService.RuleView> update(
            @PathVariable long id, @RequestBody ViewRuleService.SaveRequest request) {
        return ApiResponse.ok(ruleService.update(id, request));
    }

    @Operation(
            summary =
                    "效果预览（PLT-VIEW-009，验收「预览与实际执行一致」）：选平台角色（多选）+ 租户，"
                            + "返回各模块（页面）与各字段目录项的最终效果（可见性 / 操作 / 允许导出与"
                            + "命中的规则）；与执行点共用同一解析器")
    @PostMapping("/preview")
    @RequiresPermission("platform:view:rule:view")
    public ApiResponse<ViewRuleService.PreviewResult> preview(
            @RequestBody ViewRuleService.PreviewRequest request) {
        return ApiResponse.ok(ruleService.preview(request));
    }
}
