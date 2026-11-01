package com.crdp.server.web;

import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.auth.UserAdminService;
import com.crdp.server.system.user.UserAccountAdminService;
import com.crdp.server.system.user.UserAccountAdminService.ResetPasswordResult;
import com.crdp.server.system.user.UserAccountAdminService.StatusChange;
import com.crdp.server.system.user.UserAccountAdminService.UserCreateRequest;
import com.crdp.server.system.user.UserAccountAdminService.UserDetail;
import com.crdp.server.system.user.UserAccountAdminService.UserOptionView;
import com.crdp.server.system.user.UserAccountAdminService.UserPage;
import com.crdp.server.system.user.UserAccountAdminService.UserUpdateRequest;
import com.crdp.server.system.user.UserAccountAdminService.WardOption;
import com.crdp.server.system.user.UserPermissionPreviewService;
import com.crdp.server.system.user.UserPermissionPreviewService.UserEffectivePermissionsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T07 / PLT-ORG-006、008、A-WARD-002、C-WARD-003：医院端用户管理接口（docs/api/system.md §2）。 查看
 * tenant:user:view，新建 / 编辑 / 停用 / 启用 / 离职 tenant:user:edit，重置密码与解锁 tenant:user:reset。解锁接口先由 E04-T05
 * 落地（auth.UserAdminService），此处保持不动。
 */
@Tag(name = "医院端用户管理")
@RestController
public class HospitalUserAdminController {

    private final UserAdminService userAdminService;
    private final UserAccountAdminService service;
    private final UserPermissionPreviewService previewService;

    public HospitalUserAdminController(
            UserAdminService userAdminService,
            UserAccountAdminService service,
            UserPermissionPreviewService previewService) {
        this.userAdminService = userAdminService;
        this.service = service;
        this.previewService = previewService;
    }

    @Operation(summary = "用户分页列表（PLT-ORG-006）：筛选 keyword / deptId / wardId / roleId / status；手机号脱敏")
    @GetMapping("/api/hospital/system/users")
    @RequiresPermission("tenant:user:view")
    public ApiResponse<UserPage> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "deptId", required = false) Long deptId,
            @RequestParam(value = "wardId", required = false) Long wardId,
            @RequestParam(value = "roleId", required = false) Long roleId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(
                service.list(keyword, deptId, wardId, roleId, status, pageNo, pageSize));
    }

    @Operation(summary = "用户详情（编辑回显）：含 roleIds、wardIds（负责病区）、deptScopeIds（负责科室）")
    @GetMapping("/api/hospital/system/users/{id}")
    @RequiresPermission("tenant:user:view")
    public ApiResponse<UserDetail> detail(@PathVariable("id") long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @Operation(
            summary =
                    "新建用户（PLT-ORG-006）：账号与手机号本院唯一（40902）；初始密码由系统生成、"
                            + "只随本次响应下发一次，首次登录强制改密；写 USER_CREATE 审计")
    @PostMapping("/api/hospital/system/users")
    @RequiresPermission("tenant:user:edit")
    public ApiResponse<UserAccountAdminService.CreatedUser> create(
            @RequestBody UserCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @Operation(
            summary =
                    "编辑用户（PLT-ORG-006、A-WARD-002、C-WARD-003）：主科室、角色、负责病区 / "
                            + "负责科室多选全量覆盖，变更即递增权限版本号（立即生效）；写 USER_UPDATE 审计")
    @PutMapping("/api/hospital/system/users/{id}")
    @RequiresPermission("tenant:user:edit")
    public ApiResponse<UserDetail> update(
            @PathVariable("id") long id, @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @Operation(
            summary =
                    "用户停用 / 启用 / 离职（PLT-ORG-006）：action=ENABLE|DISABLE|RESIGN（离职为终态）；"
                            + "停用与离职立即下线全部会话；最后一名启用的租户管理员不可停用 / 离职（40901）；"
                            + "写 USER_STATUS 审计")
    @PostMapping("/api/hospital/system/users/{id}/status")
    @RequiresPermission("tenant:user:edit")
    public ApiResponse<UserDetail> changeStatus(
            @PathVariable("id") long id, @RequestBody StatusChange request) {
        return ApiResponse.ok(service.changeStatus(id, request));
    }

    @Operation(
            summary =
                    "重置密码（PLT-ORG-008）：生成一次性临时密码（响应只返回一次），置「首次登录强制改密」，"
                            + "旧哈希入历史表，该账号全部会话失效并解除登录锁定；写 USER_RESET_PASSWORD 审计")
    @PostMapping("/api/hospital/system/users/{id}/reset-password")
    @RequiresPermission("tenant:user:reset")
    public ApiResponse<ResetPasswordResult> resetPassword(@PathVariable("id") long id) {
        return ApiResponse.ok(service.resetPassword(id));
    }

    @Operation(summary = "解锁登录锁定（连续失败 5 次锁 30 分钟，管理员可提前解锁）。写审计。")
    @RequiresPermission("tenant:user:reset")
    @PostMapping("/api/hospital/system/users/{id}/unlock")
    public ApiResponse<Map<String, Object>> unlock(@PathVariable("id") long id) {
        // 解锁在当前租户上下文内进行（TokenAuthenticationFilter 已按会话设置）
        String username = userAdminService.unlockHospitalUser(id);
        return ApiResponse.ok(Map.of("userId", id, "username", username, "unlocked", true));
    }

    @Operation(summary = "病区候选（用户编辑页「负责病区」多选；只读启用项，病区字典维护属病区管理任务）")
    @GetMapping("/api/hospital/system/wards/options")
    @RequiresPermission("tenant:user:view")
    public ApiResponse<List<WardOption>> wardOptions() {
        return ApiResponse.ok(service.wardOptions());
    }

    @Operation(
            summary =
                    "用户权限预览（PLT-ORG-009、A-WARD-009、C-WARD-008）：最终菜单树（含按钮）与来源角色、"
                            + "权限码、数据范围（范围类型 / 可见病区 / 负责科室，逐项标注来源角色）；"
                            + "与登录会话同一计算入口，实时反映当前授权")
    @GetMapping("/api/hospital/system/users/{id}/effective-permissions")
    @RequiresPermission("tenant:user:view")
    public ApiResponse<UserEffectivePermissionsView> effectivePermissions(
            @PathVariable("id") long id) {
        return ApiResponse.ok(previewService.preview(id));
    }

    @Operation(
            summary =
                    "用户选择器（PLT-ORG-012）：本院启用用户，只返回 id、账号、显示名与主科室名；"
                            + "keyword 命中账号 / 姓名 / 工号，deptId 命中主科室或负责科室；登录即可调用")
    @PublicApi
    @GetMapping("/api/hospital/system/users/options")
    public ApiResponse<List<UserOptionView>> userOptions(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "deptId", required = false) Long deptId) {
        return ApiResponse.ok(service.userOptions(keyword, deptId));
    }
}
