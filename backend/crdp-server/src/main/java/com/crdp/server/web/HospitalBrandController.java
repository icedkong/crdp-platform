package com.crdp.server.web;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.brand.BrandAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06-T11 / PLT-CFG-008：医院端「机构设置 · 个性化」维护接口（路径与 docs/api/system.md §4 同步）。 查看与维护都用
 * tenant:brand:edit（规格权限点表只有维护一个码，菜单按它下发）。 参数 / 字典 / 通知模板 / Webhook / 安全设置页签随 E06-T10 等任务在本菜单下扩展。
 */
@Tag(name = "医院端机构设置")
@RestController
@Validated
public class HospitalBrandController {

    private final BrandAdminService service;

    public HospitalBrandController(BrandAdminService service) {
        this.service = service;
    }

    public record BrandRequest(
            @Size(max = 128) String displayName,
            @Size(max = 512) String logoUrl,
            @Size(max = 512) String loginBgUrl,
            @Size(max = 32) String themeColor,
            @Size(max = 255) String copyright) {}

    @Operation(summary = "本院个性化（PLT-CFG-008）：显示名称、Logo、登录页背景、预设主题色、版权信息")
    @GetMapping("/api/hospital/system/brand")
    @RequiresPermission("tenant:brand:edit")
    public ApiResponse<BrandAdminService.BrandView> view() {
        return ApiResponse.ok(service.view(CurrentSession.requireHospital().tenantName()));
    }

    @Operation(
            summary =
                    "保存本院个性化（即时生效并写审计）。显示名称必填；主题色只能取预设色板"
                            + "（themeColors 接口下发，OQ-23 主色固定）；素材地址为 http(s) 或站内路径")
    @PutMapping("/api/hospital/system/brand")
    @RequiresPermission("tenant:brand:edit")
    public ApiResponse<BrandAdminService.BrandView> save(@RequestBody BrandRequest request) {
        return ApiResponse.ok(
                service.save(
                        new BrandAdminService.BrandSave(
                                request.displayName(),
                                request.logoUrl(),
                                request.loginBgUrl(),
                                request.themeColor(),
                                request.copyright()),
                        CurrentSession.requireHospital().tenantName()));
    }

    @Operation(summary = "可选预设色板（含色值与文案），供机构设置页渲染色卡")
    @GetMapping("/api/hospital/system/brand/theme-colors")
    @RequiresPermission("tenant:brand:edit")
    public ApiResponse<List<BrandAdminService.ThemeColorOption>> themeColors() {
        return ApiResponse.ok(service.themeColors());
    }
}
