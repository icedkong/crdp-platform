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
 * 平台端视角规则 API（E20-T02 / PLT-VIEW-007、008、009；E20-T05 / PLT-VIEW-014、015，docs/api/platform.md §6）。
 *
 * <p>平台管理员按层级（全局默认 / 租户 / 模块 / 字段）配置脱敏、设盲、只读、导出规则（无需医院参与），
 * 系统初始默认以种子规则交付（V109）；效果预览与执行共用同一解析器（ViewRuleResolver），预览值即执行值。 查看 {@code
 * platform:view:rule:view}，新建 / 修改 / 停用 / 回滚 {@code platform:view:rule:edit}；
 * 高敏变更（改明文、取消设盲、取消只读、允许导出及等效放宽）进入待复核，由发起人以外的另一名平台管理员 复核通过后生效（PLT-VIEW-014，权限 {@code
 * platform:view:rule:review}）； 规则每次变更生成版本并记录变更人、前后值与复核人，可回滚（PLT-VIEW-015）。
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
                            + "每行带 version 与 pendingChange（存在待复核变更）")
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

    @Operation(
            summary = "新建规则（PLT-VIEW-007、014）：普通变更即时生效；新建即放宽（可编辑 / 允许导出）为高敏，" + "规则行待复核、第二人复核通过后生效")
    @PostMapping
    @RequiresPermission("platform:view:rule:edit")
    public ApiResponse<ViewRuleService.SaveOutcome> create(
            @RequestBody ViewRuleService.SaveRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @Operation(
            summary =
                    "维护规则（含停用 / 启用，PLT-VIEW-007、014）：普通变更即时生效（版本 +1）；"
                            + "高敏变更（放宽 / 停用限制性规则 / 收窄范围）规则保持原状，进入待复核；"
                            + "同一规则同时只允许一条待复核变更（40901）")
    @PutMapping("/{id}")
    @RequiresPermission("platform:view:rule:edit")
    public ApiResponse<ViewRuleService.SaveOutcome> update(
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

    // ---------------------------------------------------------------- 复核与版本链（E20-T05 /
    // PLT-VIEW-014、015）

    @Operation(
            summary =
                    "待复核清单（PLT-VIEW-014）：全部 PENDING_REVIEW 版本行（含规则编号与名称），"
                            + "复核操作见 /reviews/{versionId}/approve|reject")
    @GetMapping("/reviews/pending")
    @RequiresPermission("platform:view:rule:view")
    public ApiResponse<List<ViewRuleService.VersionView>> pendingReviews() {
        return ApiResponse.ok(ruleService.pendingReviews());
    }

    @Operation(
            summary =
                    "复核高敏变更（PLT-VIEW-014，验收「高敏变更需第二人批准」）：发起人复核自己的变更 40901；"
                            + "同意按版本快照写入规则行并生效，驳回则规则保持原状（新建即待复核的规则行置停用）；"
                            + "写平台访问审计 VIEW_RULE_APPROVE / VIEW_RULE_REJECT")
    @PostMapping("/reviews/{versionId}/approve")
    @RequiresPermission("platform:view:rule:review")
    public ApiResponse<ViewRuleService.RuleView> approve(
            @PathVariable long versionId, @RequestBody ViewRuleService.ReviewAction action) {
        return ApiResponse.ok(
                ruleService.review(
                        versionId, new ViewRuleService.ReviewAction(true, commentOf(action))));
    }

    /** 驳回高敏变更（同上，意见必填可读性更好但不强制）。 */
    @PostMapping("/reviews/{versionId}/reject")
    @RequiresPermission("platform:view:rule:review")
    public ApiResponse<ViewRuleService.RuleView> reject(
            @PathVariable long versionId, @RequestBody ViewRuleService.ReviewAction action) {
        return ApiResponse.ok(
                ruleService.review(
                        versionId, new ViewRuleService.ReviewAction(false, commentOf(action))));
    }

    @Operation(summary = "规则版本链（PLT-VIEW-015）：该规则全部版本行（含被驳回），记录变更人、前后值与复核人；" + "版本号倒序")
    @GetMapping("/{id}/versions")
    @RequiresPermission("platform:view:rule:view")
    public ApiResponse<List<ViewRuleService.VersionView>> versions(@PathVariable long id) {
        return ApiResponse.ok(ruleService.versions(id));
    }

    @Operation(
            summary =
                    "回滚（PLT-VIEW-015「可回滚」）：以已生效过的历史版本内容发起一次新变更；"
                            + "回滚本身若属放宽（如脱敏回滚到明文）同样进入待复核（PLT-VIEW-014）")
    @PostMapping("/{id}/rollback")
    @RequiresPermission("platform:view:rule:edit")
    public ApiResponse<ViewRuleService.SaveOutcome> rollback(
            @PathVariable long id, @RequestBody ViewRuleService.RollbackRequest request) {
        return ApiResponse.ok(ruleService.rollback(id, request));
    }

    private static String commentOf(ViewRuleService.ReviewAction action) {
        return action == null ? null : action.comment();
    }
}
