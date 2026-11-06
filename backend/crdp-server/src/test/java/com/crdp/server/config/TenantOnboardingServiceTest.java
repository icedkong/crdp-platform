package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantModuleService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** E02-T04：开通编排按序执行、逐步幂等、失败即停并记录（可重跑续跑）。 */
class TenantOnboardingServiceTest {

    private static final long TENANT_ID = 77L;

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final TenantDataSourceRegistry registry = mock(TenantDataSourceRegistry.class);
    private final ProvisioningDatabase provisioning = mock(ProvisioningDatabase.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ProvisioningProperties properties = new ProvisioningProperties();
    private final TenantModuleService tenantModules = mock(TenantModuleService.class);
    // E12-T05：配置模板初始化（模板头校验 + 应用到新租户）
    private final com.crdp.server.configtemplate.ConfigTemplateExportService templateExport =
            mock(com.crdp.server.configtemplate.ConfigTemplateExportService.class);
    private final com.crdp.server.configtemplate.ConfigTemplateImportService templateImport =
            mock(com.crdp.server.configtemplate.ConfigTemplateImportService.class);

    private TenantOnboardingService service;

    @BeforeEach
    void setUp() {
        properties.setTenantJdbcUrlTemplate("jdbc:mysql://127.0.0.1:13306/{db}");
        service =
                new TenantOnboardingService(
                        platform,
                        registry,
                        provisioning,
                        properties,
                        encoder,
                        tenantModules,
                        // E06-T12：local/dev 演示占位替换器，单测无容器传空提供者（不开通真实租户库）
                        EmptyObjectProvider.get(),
                        EmptyObjectProvider.get(),
                        templateExport,
                        templateImport);
    }

    /** 调用链：第 1 次 queryForList（查租户行）为空 → 走插入；其后（角色/用户/绑定查询）都命中。 */
    private void stubTenantId() {
        when(platform.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of(TENANT_ID))
                .thenReturn(List.of(TENANT_ID))
                .thenReturn(List.of(TENANT_ID))
                .thenReturn(List.of(TENANT_ID));
        when(platform.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(
                        invocation -> {
                            KeyHolder holder = invocation.getArgument(1);
                            holder.getKeyList().add(Map.of("GENERATED_KEY", TENANT_ID));
                            return 1;
                        });
        // 无资源行 → queryForList 返回空列表（Mockito 默认），走生成路径
        when(encoder.encode(anyString())).thenReturn("argon2-hash");
    }

    private TenantOnboardingService.OnboardingRequest request() {
        return new TenantOnboardingService.OnboardingRequest(
                "democ", "虚构示例医院丙", "admin", "丙院管理员", "Init@2026", null, null, null);
    }

    @Test
    void provisionOfReturnsTenantStatusAndSteps() {
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenReturn(Map.of("code", "democ", "name", "虚构示例医院丙", "status", "PROVISIONING"));
        when(platform.query(
                        anyString(),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq(TENANT_ID)))
                .thenReturn(
                        List.of(
                                new TenantOnboardingService.StepResult(
                                        "tenant-row", "SUCCESS", null),
                                new TenantOnboardingService.StepResult(
                                        "database", "FAILED", "DDL 冲突")));

        TenantOnboardingService.ProvisionView view = service.provisionOf(TENANT_ID);

        assertEquals("democ", view.code());
        assertEquals("PROVISIONING", view.status());
        assertEquals(2, view.steps().size());
        assertEquals("FAILED", view.steps().get(1).status());
    }

    @Test
    void retryRerunsOnboardingWithStoredNameAndCode() {
        stubTenantId();
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenReturn(Map.of("code", "democ", "name", "虚构示例医院丙"));

        TenantOnboardingService.OnboardingResult result = service.retry(TENANT_ID);

        assertEquals("democ", result.code());
        assertTrue(
                result.steps().stream()
                        .anyMatch(
                                step ->
                                        "SKIPPED".equals(step.status())
                                                && "admin".equals(step.step())),
                "重试用库内信息，不再携带管理员参数");
    }

    /** E12-T05：重试未携带 templateId 时，沿用登记在 plt_tenant.init_template_id 的选择。 */
    @Test
    void retryReappliesTheStoredInitTemplate() {
        stubTenantId();
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenReturn(Map.of("code", "democ", "name", "虚构示例医院丙"));
        when(platform.queryForObject(contains("init_template_id"), eq(Long.class), eq(TENANT_ID)))
                .thenReturn(9L);

        service.retry(TENANT_ID);

        ArgumentCaptor<com.crdp.server.configtemplate.ConfigTemplateImportService.ApplyRequest>
                captured =
                        ArgumentCaptor.forClass(
                                com.crdp.server.configtemplate.ConfigTemplateImportService
                                        .ApplyRequest.class);
        verify(templateImport).apply(eq(9L), captured.capture());
        assertEquals(TENANT_ID, captured.getValue().tenantId());
    }

    @Test
    void recycleOnlyAcceptsProvisioningTenants() {
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenReturn(Map.of("code", "democ", "status", "TRIAL"));

        assertThrows(ApiException.class, () -> service.recycle(TENANT_ID));
        verify(provisioning, never()).execute(anyString());
        verify(registry, never()).unregister(anyLong());
    }

    @Test
    void recycleDropsEverythingOfAProvisioningTenant() {
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenReturn(Map.of("code", "democ", "status", "PROVISIONING"));

        service.recycle(TENANT_ID);

        verify(registry).unregister(TENANT_ID);
        verify(provisioning).execute(contains("DROP DATABASE IF EXISTS `t_democ`"));
        verify(provisioning).execute(contains("DROP USER IF EXISTS `t_democ`"));
        verify(platform).update(contains("DELETE FROM plt_tenant WHERE id = ?"), eq(TENANT_ID));
    }

    @Test
    void adminStepIsSkippable() {
        stubTenantId();

        TenantOnboardingService.OnboardingResult result = service.onboard(requestWithoutAdmin());

        assertTrue(
                result.steps().stream()
                        .anyMatch(
                                step ->
                                        "SKIPPED".equals(step.status())
                                                && "admin".equals(step.step())),
                "未提供管理员时 admin 步骤应记为 SKIPPED：" + result.steps());
        verify(platform, never()).update(contains("INSERT INTO sys_user"), any(Object[].class));
    }

    private TenantOnboardingService.OnboardingRequest requestWithoutAdmin() {
        return new TenantOnboardingService.OnboardingRequest(
                "democ", "虚构示例医院丙", null, null, null, null, null, null);
    }

    @Test
    void rejectsInvalidRequestsBeforeTouchingAnything() {
        TenantOnboardingService.OnboardingRequest badCode =
                new TenantOnboardingService.OnboardingRequest(
                        "DemoC", "名", "admin", null, "Init@2026", null, null, null);
        TenantOnboardingService.OnboardingRequest noPassword =
                new TenantOnboardingService.OnboardingRequest(
                        "democ", "名", "admin", null, " ", null, null, null);

        assertThrows(ApiException.class, () -> service.onboard(badCode));
        assertThrows(ApiException.class, () -> service.onboard(noPassword));
        Mockito.verifyNoInteractions(platform, registry, provisioning);
    }

    /** E12-T05：未知模板在开工前拒绝（40400），不建库、不登记租户。 */
    @Test
    void unknownTemplateIsRejectedBeforeAnyProvisioning() {
        when(templateExport.headerOf(999L)).thenThrow(new ApiException(404, 40400, "配置模板不存在: 999"));

        TenantOnboardingService.OnboardingRequest unknownTemplate =
                new TenantOnboardingService.OnboardingRequest(
                        "democ", "虚构示例医院丙", "admin", null, "Init@2026", null, null, 999L);

        ApiException ex = assertThrows(ApiException.class, () -> service.onboard(unknownTemplate));
        assertEquals(40400, ex.code());
        Mockito.verifyNoInteractions(platform, registry, provisioning);
        verify(templateImport, never()).apply(anyLong(), any());
    }

    @Test
    void runsAllStepsInOrderAndMarksTrial() {
        stubTenantId();

        TenantOnboardingService.OnboardingResult result = service.onboard(request());

        assertEquals(TENANT_ID, result.tenantId());
        assertEquals(
                List.of(
                        "tenant-row",
                        "resource-row",
                        "database",
                        "migrate-register",
                        "admin",
                        "config-template"),
                result.steps().stream().map(TenantOnboardingService.StepResult::step).toList());
        assertTrue(
                result.steps().stream()
                        .allMatch(
                                s ->
                                        "SUCCESS".equals(s.status())
                                                || ("config-template".equals(s.step())
                                                        && "SKIPPED".equals(s.status()))),
                "未选模板时 config-template 记 SKIPPED，其余全 SUCCESS：" + result.steps());
        verify(provisioning, times(4)).execute(contains("t_democ"));
        verify(registry).register(any());
        verify(platform).update(contains("UPDATE plt_tenant"), eq(TENANT_ID));
        verify(platform, times(6)).update(contains("plt_provision_step"), any(Object[].class));
    }

    /** E12-T05（A-FLD-014「新建租户时选择模板初始化」）：选择模板时， 模板在模块开通之后应用（可分配范围就绪、授权取交集才有意义）， 应用目标即新租户。 */
    @Test
    void selectedTemplateIsAppliedAfterModuleOpening() {
        stubTenantId();
        when(templateExport.headerOf(9L))
                .thenReturn(
                        new com.crdp.server.configtemplate.ConfigTemplateExportService.ExportedView(
                                9L,
                                1L,
                                "demoa",
                                "虚构示例医院甲",
                                "演练模板（示例）",
                                1,
                                1,
                                21,
                                100,
                                "hash",
                                "2026-11-07T00:00:00+08:00"));

        TenantOnboardingService.OnboardingResult result =
                service.onboard(
                        new TenantOnboardingService.OnboardingRequest(
                                "democ",
                                "虚构示例医院丙",
                                "admin",
                                "丙院管理员",
                                "Init@2026",
                                null,
                                List.of("followup"),
                                9L));

        assertTrue(
                result.steps().stream()
                        .anyMatch(
                                s ->
                                        "config-template".equals(s.step())
                                                && "SUCCESS".equals(s.status())),
                "选择模板时 config-template 步骤执行成功：" + result.steps());
        ArgumentCaptor<com.crdp.server.configtemplate.ConfigTemplateImportService.ApplyRequest>
                captured =
                        ArgumentCaptor.forClass(
                                com.crdp.server.configtemplate.ConfigTemplateImportService
                                        .ApplyRequest.class);
        verify(templateImport).apply(eq(9L), captured.capture());
        assertEquals(TENANT_ID, captured.getValue().tenantId());
        org.mockito.InOrder order = Mockito.inOrder(tenantModules, templateImport);
        order.verify(tenantModules).openForOnboarding(eq(TENANT_ID), any());
        order.verify(templateImport).apply(eq(9L), any());
        verify(platform, times(6)).update(contains("plt_provision_step"), any(Object[].class));
    }

    /** E12-T05：模板应用失败 → 步骤记 FAILED、租户停留 PROVISIONING（可从失败步骤重试）。 */
    @Test
    void templateApplyFailureMarksStepFailedAndStaysProvisioning() {
        stubTenantId();
        when(templateExport.headerOf(9L))
                .thenReturn(
                        new com.crdp.server.configtemplate.ConfigTemplateExportService.ExportedView(
                                9L,
                                1L,
                                "demoa",
                                "虚构示例医院甲",
                                "演练模板（示例）",
                                1,
                                1,
                                21,
                                100,
                                "hash",
                                "2026-11-07T00:00:00+08:00"));
        when(templateImport.apply(eq(9L), any())).thenThrow(new RuntimeException("配置包解析失败"));

        TenantOnboardingService.OnboardingRequest request =
                new TenantOnboardingService.OnboardingRequest(
                        "democ",
                        "虚构示例医院丙",
                        "admin",
                        "丙院管理员",
                        "Init@2026",
                        null,
                        List.of("followup"),
                        9L);

        assertThrows(RuntimeException.class, () -> service.onboard(request));
        verify(platform)
                .update(
                        contains("plt_provision_step"),
                        eq(TENANT_ID),
                        eq("config-template"),
                        eq("FAILED"),
                        contains("配置包解析失败"));
        verify(platform, never()).update(contains("status = 'TRIAL'"), eq(TENANT_ID));
    }

    @Test
    void databaseFailureStopsTheRunAndLaterStepsDoNotExecute() {
        stubTenantId();
        doThrow(new RuntimeException("特权账号被拒绝")).when(provisioning).execute(anyString());

        assertThrows(RuntimeException.class, () -> service.onboard(request()));

        verify(registry, never()).register(any());
        verify(platform, never()).update(contains("UPDATE plt_tenant"), eq(TENANT_ID));
        verify(platform)
                .update(
                        contains("plt_provision_step"),
                        eq(TENANT_ID),
                        eq("database"),
                        eq("FAILED"),
                        contains("特权账号被拒绝"));
    }

    @Test
    void rerunReusesTheStoredResourcePasswordInsteadOfGeneratingANewOne() {
        stubTenantId();
        when(platform.queryForList(
                        contains("SELECT password_cipher"), eq(String.class), eq(TENANT_ID)))
                .thenReturn(List.of("local:FirstPassword1"));

        service.onboard(request());

        verify(platform, never())
                .update(contains("INSERT INTO plt_tenant_resource"), any(Object[].class));
        verify(provisioning, times(2)).execute(contains("FirstPassword1"));
    }

    /** E05-T01 起医院名称全平台唯一（V7 唯一键）：撞名（而非撞编码）时给 40901，不落 NPE。 */
    @Test
    void duplicateHospitalNameIsRejectedInsteadOfNpe() {
        when(platform.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(platform.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenThrow(new DuplicateKeyException("uk_plt_tenant_name"));

        ApiException ex = assertThrows(ApiException.class, () -> service.onboard(request()));
        assertEquals(40901, ex.code(), () -> ex.getMessage());
        verify(registry, never()).register(any());
    }
}
