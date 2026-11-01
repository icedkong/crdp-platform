package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.system.role.RoleAdminService;
import com.crdp.server.system.role.RoleAdminService.CopyRequest;
import com.crdp.server.system.role.RoleAdminService.FieldPolicyItem;
import com.crdp.server.system.role.RoleAdminService.RoleFieldPoliciesView;
import com.crdp.server.system.role.RoleAdminService.RoleMembersView;
import com.crdp.server.system.role.RoleAdminService.RoleMenusView;
import com.crdp.server.system.role.RoleAdminService.RoleSave;
import com.crdp.server.system.role.RoleAdminService.RoleScopeView;
import com.crdp.server.system.role.RoleAdminService.RoleView;
import com.crdp.server.system.role.RoleAdminService.ScopeSave;
import com.crdp.server.system.role.RoleAdminService.StatusChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T05 / PLT-ORG-004、005、PLT-ROLE-004、005：医院端角色管理接口（docs/api/system.md §3）。 查看
 * tenant:role:view，维护（含复制、模板应用、菜单 / 范围 / 成员配置）tenant:role:edit。
 */
@Tag(name = "医院端角色管理")
@RestController
@Validated
public class HospitalRoleController {

    private final RoleAdminService service;

    public HospitalRoleController(RoleAdminService service) {
        this.service = service;
    }

    public record RoleMenusSaveRequest(List<Long> menuIds) {}

    public record RoleMembersSaveRequest(List<Long> userIds) {}

    /** 模板应用请求（PLT-ORG-005）：要复制的模板角色 + 目标编码 / 名称（说明可覆盖模板说明）。 */
    public record FromTemplateRequest(
            Long templateId, String code, String name, String description) {

        CopyRequest toCopyRequest() {
            return new CopyRequest(code, name, description);
        }
    }

    @Operation(summary = "角色列表（PLT-ORG-004）：内置 / 模板 / 自定义，附成员数与菜单数；内置角色不可删、不可改权限")
    @GetMapping("/api/hospital/system/roles")
    @RequiresPermission("tenant:role:view")
    public ApiResponse<List<RoleView>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(summary = "新建自定义角色（PLT-ORG-004）：编码租户内唯一、创建后不可改，写 ROLE_CREATE 审计")
    @PostMapping("/api/hospital/system/roles")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> create(@RequestBody RoleSave request) {
        return ApiResponse.ok(service.create(request));
    }

    @Operation(summary = "编辑角色（PLT-ORG-004）：名称与说明可改、编码不可改；仅自定义角色，写 ROLE_UPDATE 审计")
    @PutMapping("/api/hospital/system/roles/{roleId}")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> update(@PathVariable long roleId, @RequestBody RoleSave request) {
        return ApiResponse.ok(service.update(roleId, request));
    }

    @Operation(summary = "删除角色（PLT-ORG-004）：仅无成员的自定义角色；内置与模板不可删除（40901），写 ROLE_DELETE 审计")
    @DeleteMapping("/api/hospital/system/roles/{roleId}")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<Void> delete(@PathVariable long roleId) {
        service.delete(roleId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "复制自定义角色（PLT-ORG-004「复制」）：菜单、数据范围与自定义科室一并复制，写 ROLE_COPY 审计")
    @PostMapping("/api/hospital/system/roles/{roleId}/copy")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> copy(@PathVariable long roleId, @RequestBody CopyRequest request) {
        return ApiResponse.ok(service.copyRole(roleId, request));
    }

    @Operation(summary = "应用业务角色模板（PLT-ORG-005 / PLT-ROLE-005）：把模块下发的模板复制成自定义角色，之后在副本上调整")
    @PostMapping("/api/hospital/system/roles/from-template")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> fromTemplate(@RequestBody FromTemplateRequest request) {
        if (request == null || request.templateId() == null || request.templateId() <= 0) {
            throw new ApiException(400, 40001, "templateId 必填（要复制的模板角色）");
        }
        return ApiResponse.ok(
                service.copyFromTemplate(request.templateId(), request.toCopyRequest()));
    }

    @Operation(
            summary =
                    "角色停用 / 启用（PLT-ORG-004）：action=ENABLE|DISABLE；内置与模板角色 40901；"
                            + "停用后成员权限立即失效（权限版本递增），写 ROLE_STATUS 审计")
    @PostMapping("/api/hospital/system/roles/{roleId}/status")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> changeStatus(
            @PathVariable long roleId, @RequestBody StatusChange request) {
        return ApiResponse.ok(service.changeStatus(roleId, request));
    }

    @Operation(summary = "菜单按钮页签（PLT-ORG-004）：可分配范围树（assignable 标记）+ 当前授权")
    @GetMapping("/api/hospital/system/roles/{roleId}/menus")
    @RequiresPermission("tenant:role:view")
    public ApiResponse<RoleMenusView> menus(@PathVariable long roleId) {
        return ApiResponse.ok(service.menusView(roleId));
    }

    @Operation(
            summary =
                    "保存菜单与按钮（PLT-ORG-004）：全量覆盖；只能在平台可分配范围内（超出 40001），" + "写 ROLE_MENUS 审计并递增成员权限版本")
    @PutMapping("/api/hospital/system/roles/{roleId}/menus")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> updateMenus(
            @PathVariable long roleId, @RequestBody RoleMenusSaveRequest request) {
        return ApiResponse.ok(
                service.updateMenus(roleId, request == null ? null : request.menuIds()));
    }

    @Operation(
            summary =
                    "数据范围页签（PLT-ORG-004）：范围类型与自定义科室清单；生效口径 E07-T02 已落地（见 docs/api/README.md §2.5）")
    @GetMapping("/api/hospital/system/roles/{roleId}/scope")
    @RequiresPermission("tenant:role:view")
    public ApiResponse<RoleScopeView> scope(@PathVariable long roleId) {
        return ApiResponse.ok(service.scopeView(roleId));
    }

    @Operation(
            summary =
                    "保存数据范围（PLT-ORG-004）：类型 ∈ 七种平台口径；CUSTOM_DEPT 必须勾选科室；"
                            + "写 ROLE_SCOPE 审计并递增成员权限版本")
    @PutMapping("/api/hospital/system/roles/{roleId}/scope")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> updateScope(
            @PathVariable long roleId, @RequestBody ScopeSave request) {
        return ApiResponse.ok(service.updateScope(roleId, request));
    }

    @Operation(summary = "成员页签（PLT-ORG-004 编辑页）：当前成员清单（id、账号、姓名、状态）")
    @GetMapping("/api/hospital/system/roles/{roleId}/members")
    @RequiresPermission("tenant:role:view")
    public ApiResponse<RoleMembersView> members(@PathVariable long roleId) {
        return ApiResponse.ok(service.membersView(roleId));
    }

    @Operation(
            summary =
                    "字段策略页签（E07-T06 / PLT-PERM-009）：敏感字段目录 + 本角色生效策略"
                            + "（未覆盖项回落平台默认 MASKED；多角色合并随 E07-T04）")
    @GetMapping("/api/hospital/system/roles/{roleId}/field-policies")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleFieldPoliciesView> fieldPolicies(@PathVariable long roleId) {
        return ApiResponse.ok(service.fieldPoliciesView(roleId));
    }

    @Operation(
            summary =
                    "保存字段策略（E07-T06 / PLT-PERM-009）：全量覆盖显式覆盖项（空数组即全部回落平台默认）；"
                            + "仅自定义角色（40901），字段须在敏感字段目录内、策略 ∈ VISIBLE / MASKED / HIDDEN（40001）；"
                            + "写 ROLE_FIELD_POLICY 审计并递增成员权限版本")
    @PutMapping("/api/hospital/system/roles/{roleId}/field-policies")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleView> updateFieldPolicies(
            @PathVariable long roleId, @RequestBody FieldPoliciesSaveRequest request) {
        return ApiResponse.ok(
                service.updateFieldPolicies(roleId, request == null ? null : request.policies()));
    }

    /** 字段策略保存请求：显式覆盖项全量覆盖（不在清单内的字段回落平台默认）。 */
    public record FieldPoliciesSaveRequest(List<FieldPolicyItem> policies) {}

    @Operation(
            summary =
                    "保存成员（PLT-ORG-004）：全量覆盖；模板角色不可直接分配（40901，先复制）；"
                            + "不能移除最后一名启用的租户管理员（40901）；写 ROLE_MEMBERS 审计")
    @PutMapping("/api/hospital/system/roles/{roleId}/members")
    @RequiresPermission("tenant:role:edit")
    public ApiResponse<RoleMembersView> updateMembers(
            @PathVariable long roleId, @RequestBody RoleMembersSaveRequest request) {
        return ApiResponse.ok(
                service.updateMembers(roleId, request == null ? null : request.userIds()));
    }
}
