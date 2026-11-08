package com.crdp.followup.dashboard.api;

import com.crdp.followup.dashboard.app.DashboardLayoutService;
import com.crdp.followup.dashboard.app.DashboardLayoutService.DashboardLayoutView;
import com.crdp.followup.dashboard.app.DashboardLayoutService.RoleCardSave;
import com.crdp.followup.dashboard.app.DashboardLayoutService.RoleLayoutsView;
import com.crdp.followup.dashboard.app.DashboardLayoutService.UserPrefSave;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页卡片目录、按角色布局与用户隐藏 / 显示的接口（E19-T06 / A-DASH-013、A-DASH-014；docs/api/stats.md §2、 system.md §5）。
 *
 * <p>权限：{@code GET /dashboard/layout} 与 {@code PUT /dashboard/user-pref}
 * 登录即可（卡片范围已由解析裁剪， @PublicApi）；角色配置三接口 {@code followup:dashboard:config}（平台库 V60 建码、种子 V130 授
 * TENANT_ADMIN）。 配置只决定显示与顺序、不放宽数据权限：每张卡片的数据接口照常受卡片权限与 {@code @DataScope} 出行（A 规格 §9）。
 *
 * <p>即时生效（tasks.csv E19-T06 验收「按角色配置后即生效」）：读取路径每次实时查库，无缓存；租户视角会话对写 方法按 E20-T03 口径 40303（读 layout
 * 只会得到视角规则允许的卡片范围）。
 */
@Tag(name = "医院端首页卡片配置")
@RestController
public class DashboardLayoutController {

    private final DashboardLayoutService service;

    public DashboardLayoutController(DashboardLayoutService service) {
        this.service = service;
    }

    @Operation(
            summary =
                    "当前用户合并后的卡片（stats.md §2）：目录 ∩ 功能开关 ∩ 权限 × 角色布局 ∩ 用户隐藏；"
                            + "source=DEFAULT 目录默认 / ROLE_CONFIG 角色配置；visible=false 仅来自用户隐藏"
                            + "（角色隐藏与开关关闭、无权限的卡片不在响应）；cards 按展示顺序，含 width（FULL 整行 /"
                            + " HALF 半行并排）、allowUserHide、userHidden（卡片设置抽屉用）")
    @PublicApi
    @GetMapping("/api/hospital/followup/dashboard/layout")
    @RequireModule("followup")
    public ApiResponse<DashboardLayoutView> layout() {
        return ApiResponse.ok(service.current(CurrentSession.requireHospital()));
    }

    @Operation(
            summary =
                    "用户隐藏 / 显示卡片（A-DASH-014，P1）：hidden = 要隐藏的卡片编码全量清单（空 = 恢复角色"
                            + " 默认）；只能在角色允许且 allowUserHide 的卡片内隐藏（否则 40001）；用户不能加入角色"
                            + " 未允许的卡片、不能调整顺序；写 DASHBOARD_USER_PREF 审计")
    @PublicApi
    @PutMapping("/api/hospital/followup/dashboard/user-pref")
    @RequireModule("followup")
    public ApiResponse<DashboardLayoutView> saveUserPref(@RequestBody UserPrefSave request) {
        return ApiResponse.ok(service.saveUserPref(CurrentSession.requireHospital(), request));
    }

    @Operation(
            summary =
                    "角色卡片布局（A-DASH-013，配置页）：角色候选 roles（配置页下拉，不依赖 tenant:role:view）"
                            + " + 当前角色视图（roleId 缺省取第一个角色）；configured=false 时 visible / order /"
                            + " allowUserHide 为目录默认值；卡片全目录列出（featureOn=false 表示开关关闭、配置了"
                            + " 也不显示）")
    @GetMapping("/api/hospital/followup/dashboard/role-layouts")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:config")
    public ApiResponse<RoleLayoutsView> roleLayouts(
            @RequestParam(value = "roleId", required = false) Long roleId) {
        return ApiResponse.ok(service.roleView(roleId));
    }

    @Operation(
            summary =
                    "保存角色布局（全量覆盖）：{ cards: [{ code, visible, order, allowUserHide }] }——code 须在"
                            + " 卡片目录内、order 从 1 起且不重复（40001）；allowUserHide 缺省 = 目录默认；角色不存在"
                            + " 40400；下一次 GET /dashboard/layout 即生效（无缓存）；写 DASHBOARD_ROLE_LAYOUT 审计")
    @PutMapping("/api/hospital/followup/dashboard/role-layouts/{roleId}")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:config")
    public ApiResponse<RoleLayoutsView> saveRoleLayout(
            @PathVariable("roleId") long roleId, @RequestBody RoleLayoutSaveRequest request) {
        return ApiResponse.ok(
                service.saveRoleLayout(
                        CurrentSession.requireHospital(),
                        roleId,
                        request == null ? null : request.cards()));
    }

    /** 保存请求体（system.md §5）：卡片清单全量覆盖。 */
    public record RoleLayoutSaveRequest(List<RoleCardSave> cards) {}

    @Operation(
            summary =
                    "恢复角色默认布局（DELETE）：清空该角色配置行，未配置的角色使用目录默认布局（A-DASH-013）；"
                            + "写 DASHBOARD_ROLE_LAYOUT 审计")
    @DeleteMapping("/api/hospital/followup/dashboard/role-layouts/{roleId}")
    @RequireModule("followup")
    @RequiresPermission("followup:dashboard:config")
    public ApiResponse<RoleLayoutsView> resetRoleLayout(@PathVariable("roleId") long roleId) {
        return ApiResponse.ok(service.resetRoleLayout(CurrentSession.requireHospital(), roleId));
    }
}
