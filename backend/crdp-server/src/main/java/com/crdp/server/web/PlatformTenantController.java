package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.config.TenantOnboardingService;
import com.crdp.server.platform.TenantDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.SecureRandom;
import java.util.Locale;
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
 * 平台端租户管理 API。开通向导与进度是 E02-T07/T08；列表检索与信息维护（PLT-TEN-001、009）是 E05-T01； 状态与到期（PLT-TEN-010..013）是
 * E05-T02；模块开通（E05-T05）与到期设置接入后向导再传对应数据。
 */
@Tag(name = "平台端租户管理")
@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final TenantOnboardingService onboardingService;
    private final TenantDirectoryService directoryService;
    private final SecureRandom random = new SecureRandom();

    public PlatformTenantController(
            TenantOnboardingService onboardingService, TenantDirectoryService directoryService) {
        this.onboardingService = onboardingService;
        this.directoryService = directoryService;
    }

    public record CreateTenantRequest(
            String name,
            String code,
            String adminUsername,
            String adminDisplayName,
            String adminInitialPassword) {}

    @Operation(summary = "租户列表（PLT-TEN-001）：按名称/编码关键词、状态、到期日、模块检索")
    @GetMapping
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantDirectoryService.PageView<TenantDirectoryService.TenantListItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String expireBefore,
            @RequestParam(required = false) String moduleCode,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(
                directoryService.list(
                        new TenantDirectoryService.ListQuery(
                                keyword,
                                status,
                                expireBefore,
                                moduleCode,
                                pageNo,
                                pageSize,
                                sort)));
    }

    @Operation(summary = "租户详情（PLT-TEN-009）：基本信息、联系人与备注；电话仅详情可见")
    @GetMapping("/{tenantId}")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantDirectoryService.TenantDetail> detail(@PathVariable long tenantId) {
        return ApiResponse.ok(directoryService.detail(tenantId));
    }

    @Operation(summary = "维护租户信息（PLT-TEN-009）：修改写 TENANT_UPDATE 审计记录")
    @PutMapping("/{tenantId}")
    @RequiresPermission("platform:tenant:edit")
    public ApiResponse<TenantDirectoryService.TenantDetail> update(
            @PathVariable long tenantId,
            @RequestBody TenantDirectoryService.UpdateRequest request) {
        return ApiResponse.ok(directoryService.update(tenantId, request));
    }

    @Operation(summary = "新建租户：立即触发开通编排（可重入，失败重试即重提交）")
    @PostMapping
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
    @GetMapping("/{tenantId}/provision")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantOnboardingService.ProvisionView> provision(
            @PathVariable long tenantId) {
        return ApiResponse.ok(onboardingService.provisionOf(tenantId));
    }

    @Operation(summary = "从失败步骤重试开通（可重入）")
    @PostMapping("/{tenantId}/retry")
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<TenantOnboardingService.OnboardingResult> retry(
            @PathVariable long tenantId) {
        return ApiResponse.ok(onboardingService.retry(tenantId));
    }

    @Operation(summary = "放弃回收：仅开通未完成的租户（正式注销是 PLT-TEN-020 / E05-T08）")
    @DeleteMapping("/{tenantId}")
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
