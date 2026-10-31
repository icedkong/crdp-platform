package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.config.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端新建租户向导的提交入口（E02-T07，验收：向导走完即触发开通）。租户列表、维护等完整租户 API 是
 * E05-T01；模块开通（E05-T05）与到期设置（E05-T02）接入后向导再传对应数据。
 */
@Tag(name = "平台端租户管理")
@RestController
public class PlatformTenantController {

    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final TenantOnboardingService onboardingService;
    private final SecureRandom random = new SecureRandom();

    public PlatformTenantController(TenantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    public record CreateTenantRequest(
            String name,
            String code,
            String adminUsername,
            String adminDisplayName,
            String adminInitialPassword) {}

    @Operation(summary = "新建租户：立即触发开通编排（可重入，失败重试即重提交）")
    @PostMapping("/api/platform/tenants")
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<TenantOnboardingService.OnboardingResult> create(
            @RequestBody CreateTenantRequest request) {
        String code =
                request.code() == null || request.code().isBlank()
                        ? suggestCode()
                        : request.code().trim().toLowerCase(Locale.ROOT);
        return ApiResponse.ok(
                onboardingService.onboard(
                        new TenantOnboardingService.OnboardingRequest(
                                code,
                                request.name(),
                                request.adminUsername(),
                                request.adminDisplayName(),
                                request.adminInitialPassword())));
    }

    @Operation(summary = "开通进度（PLT-TEN-008）：租户状态与逐步结果")
    @GetMapping("/api/platform/tenants/{tenantId}/provision")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantOnboardingService.ProvisionView> provision(
            @PathVariable long tenantId) {
        return ApiResponse.ok(onboardingService.provisionOf(tenantId));
    }

    @Operation(summary = "从失败步骤重试开通（可重入）")
    @PostMapping("/api/platform/tenants/{tenantId}/retry")
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<TenantOnboardingService.OnboardingResult> retry(
            @PathVariable long tenantId) {
        return ApiResponse.ok(onboardingService.retry(tenantId));
    }

    @Operation(summary = "放弃回收：仅开通未完成的租户（正式注销是 PLT-TEN-020 / E05-T08）")
    @DeleteMapping("/api/platform/tenants/{tenantId}")
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<Void> recycle(@PathVariable long tenantId) {
        onboardingService.recycle(tenantId);
        return ApiResponse.ok(null);
    }

    /** 租户编码建议值：页面可改（PLT-TEN-002）。 */
    private String suggestCode() {
        StringBuilder builder = new StringBuilder("t");
        for (int i = 0; i < 7; i++) {
            builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }
}
