package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.server.config.TenantOnboardingService;
import com.crdp.server.platform.TenantDirectoryService;
import com.crdp.server.platform.TenantLifecycleService;
import com.crdp.server.platform.TenantSuspensionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
 * 平台端租户管理 API。开通向导与进度是 E02-T07/T08；列表检索与信息维护（PLT-TEN-001、009）是 E05-T01；
 * 状态流转、到期设置与续期（PLT-TEN-010..013）是 E05-T02；模块开通（E05-T05）接入后向导再传对应数据。
 */
@Tag(name = "平台端租户管理")
@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {

    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final TenantOnboardingService onboardingService;
    private final TenantDirectoryService directoryService;
    private final TenantLifecycleService lifecycleService;
    private final TenantSuspensionService suspensionService;
    private final SecureRandom random = new SecureRandom();

    public PlatformTenantController(
            TenantOnboardingService onboardingService,
            TenantDirectoryService directoryService,
            TenantLifecycleService lifecycleService,
            TenantSuspensionService suspensionService) {
        this.onboardingService = onboardingService;
        this.directoryService = directoryService;
        this.lifecycleService = lifecycleService;
        this.suspensionService = suspensionService;
    }

    /** 新建租户请求：到期设置五项可整体省略（默认长期有效）；有到期日时到期日必填且须晚于当天， 提醒节点 / 宽限期缺省取默认值（30、7、1 天与 15 天）。 */
    public record CreateTenantRequest(
            String name,
            String code,
            String adminUsername,
            String adminDisplayName,
            String adminInitialPassword,
            String expireType,
            String expireAt,
            List<Integer> remindDays,
            Integer graceDays,
            Boolean graceReadOnly) {}

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

    @Operation(
            summary =
                    "状态变更影响面（PLT-TEN-010）：在线用户数与进行中任务数，供停用 / 启用 / 续期对话框二次确认时展示；"
                            + "只读信息，租户可见者即可查（与列表的用户数同敏级）")
    @GetMapping("/{tenantId}/status/impact")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<TenantLifecycleService.ImpactView> impact(@PathVariable long tenantId) {
        return ApiResponse.ok(lifecycleService.impact(tenantId));
    }

    @Operation(
            summary =
                    "状态变更（PLT-TEN-010）：action=ENABLE 转正（试用→启用）或恢复（停用→启用）、"
                            + "DISABLE 手动停用（启用→停用）；流转按规格状态图，非法流转 40901；需填原因并写 TENANT_STATUS 审计")
    @PostMapping("/{tenantId}/status")
    @RequiresPermission("platform:tenant:status")
    public ApiResponse<TenantLifecycleService.StatusResult> changeStatus(
            @PathVariable long tenantId,
            @RequestBody TenantLifecycleService.StatusChangeRequest request) {
        return ApiResponse.ok(lifecycleService.changeStatus(tenantId, request));
    }

    @Operation(
            summary =
                    "到期设置与续期（PLT-TEN-011、013）：改到期日或转长期有效；宽限期或停用中的租户续期后立即恢复启用；"
                            + "省略的提醒节点 / 宽限期沿用当前配置，写 TENANT_EXPIRE 审计")
    @PutMapping("/{tenantId}/expire")
    @RequiresPermission("platform:tenant:renew")
    public ApiResponse<TenantLifecycleService.ExpireResult> expire(
            @PathVariable long tenantId,
            @RequestBody TenantLifecycleService.ExpireRequest request) {
        return ApiResponse.ok(lifecycleService.applyExpiry(tenantId, request));
    }

    @Operation(
            summary =
                    "停用时段（PLT-TEN-017 底座，E05-T03）：倒序列出该租户每次停用的时段（restoredAt 为空 = 仍在停用中）、"
                            + "原因类别（人工 / 宽限期满自动）与恢复途径；停用补偿（任务标记“停用期间未执行”）由业务模块按时段推导（E14-T06）")
    @GetMapping("/{tenantId}/suspensions")
    @RequiresPermission("platform:tenant:view")
    public ApiResponse<List<TenantSuspensionService.SuspensionView>> suspensions(
            @PathVariable long tenantId) {
        return ApiResponse.ok(suspensionService.suspensionsOf(tenantId));
    }

    @Operation(summary = "新建租户：立即触发开通编排（可重入，失败重试即重提交）；到期设置随本任务接入（PLT-TEN-011）")
    @PostMapping
    @RequiresPermission("platform:tenant:create")
    public ApiResponse<TenantOnboardingService.OnboardingResult> create(
            @RequestBody CreateTenantRequest request) {
        String code =
                request.code() == null || request.code().isBlank()
                        ? suggestCode()
                        : request.code().trim().toLowerCase(Locale.ROOT);
        // 到期设置整体省略 = 长期有效（向导第 2 步默认值）；填了则走同一套校验
        String expireType =
                request.expireType() == null || request.expireType().isBlank()
                        ? "LONG_TERM"
                        : request.expireType();
        TenantLifecycleService.ExpiryView expire =
                TenantLifecycleService.mergedExpiry(
                        new TenantLifecycleService.ExpiryView("LONG_TERM", null, null, null, false),
                        new TenantLifecycleService.ExpireRequest(
                                expireType,
                                request.expireAt(),
                                request.remindDays(),
                                request.graceDays(),
                                request.graceReadOnly(),
                                null),
                        LocalDate.now(ZoneId.of("Asia/Shanghai")));
        return ApiResponse.ok(
                onboardingService.onboard(
                        new TenantOnboardingService.OnboardingRequest(
                                code,
                                request.name(),
                                request.adminUsername(),
                                request.adminDisplayName(),
                                request.adminInitialPassword(),
                                expire)));
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
