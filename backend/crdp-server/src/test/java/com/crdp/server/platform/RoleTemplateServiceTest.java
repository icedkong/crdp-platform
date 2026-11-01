package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.RoleTemplateService.TemplateRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E06-T05 / PLT-ROLE-005 单元测试：模块开通时把平台库角色模板下发到租户库（sys_role 的 TEMPLATE 行 + 模板菜单授权）。 真实 MySQL
 * 的端到端（开通后 模板可复制、幂等重开）由 {@code RoleManagementMySqlIT} 覆盖。
 */
class RoleTemplateServiceTest {

    private static final long TENANT_ID = 1L;

    private final JdbcTemplate platform = mockJdbc();

    private static JdbcTemplate mockJdbc() {
        return org.mockito.Mockito.mock(JdbcTemplate.class);
    }

    private RoleTemplateService service;

    @BeforeEach
    void setUp() {
        service = new RoleTemplateService(platform);
        lenient()
                .when(
                        platform.query(
                                contains("FROM plt_role_template"),
                                any(org.springframework.jdbc.core.RowMapper.class),
                                anyString()))
                .thenReturn(List.of());
        TenantContext.usePlatform();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void seedSkipsWhenModuleHasNoTemplates() {
        when(platform.query(
                        contains("FROM plt_role_template"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq("base")))
                .thenReturn(List.of());

        int ensured = service.seedToTenant(TENANT_ID, "demoa", "base");

        assertEquals(0, ensured);
        verify(platform, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void seedUpsertsTemplateRolesAndGrantsMenus() {
        TemplateRow nurse =
                new TemplateRow(11L, "followup", "FOLLOWUP_NURSE", "随访员", "随访执行（模板）", 10);
        TemplateRow clerk =
                new TemplateRow(12L, "followup", "FOLLOWUP_DATA_CLERK", "资料录入员", "补录（模板）", 50);
        when(platform.query(
                        contains("FROM plt_role_template"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq("followup")))
                .thenReturn(List.of(nurse, clerk));
        when(platform.queryForList(
                        contains("FROM plt_role_template_menu"), eq(Long.class), eq(11L)))
                .thenReturn(List.of(20L, 21L, 22L));
        when(platform.queryForList(
                        contains("FROM plt_role_template_menu"), eq(Long.class), eq(12L)))
                .thenReturn(List.of(20L, 21L, 23L));
        when(platform.queryForObject(
                        eq("SELECT id FROM sys_role WHERE code = ?"),
                        eq(Long.class),
                        eq("FOLLOWUP_NURSE")))
                .thenReturn(101L);
        when(platform.queryForObject(
                        eq("SELECT id FROM sys_role WHERE code = ?"),
                        eq(Long.class),
                        eq("FOLLOWUP_DATA_CLERK")))
                .thenReturn(102L);

        int ensured = service.seedToTenant(TENANT_ID, "demoa", "followup");

        assertEquals(2, ensured);
        // sys_role upsert 成 TEMPLATE 行（幂等：重复开通只补不重）
        verify(platform)
                .update(
                        contains("INSERT INTO sys_role"),
                        eq("FOLLOWUP_NURSE"),
                        eq("随访员"),
                        eq("followup"),
                        eq("随访执行（模板）"));
        verify(platform)
                .update(
                        contains("INSERT INTO sys_role"),
                        eq("FOLLOWUP_DATA_CLERK"),
                        eq("资料录入员"),
                        eq("followup"),
                        eq("补录（模板）"));
        // 模板菜单授权（INSERT IGNORE 幂等；只授医院端启用菜单，见 menusOfTemplates 的 JOIN）
        verify(platform).update(contains("INSERT IGNORE INTO sys_role_menu"), eq(101L), eq(20L));
        verify(platform).update(contains("INSERT IGNORE INTO sys_role_menu"), eq(101L), eq(22L));
        verify(platform).update(contains("INSERT IGNORE INTO sys_role_menu"), eq(102L), eq(23L));
        verify(platform, never())
                .update(contains("INSERT IGNORE INTO sys_role_menu"), eq(101L), eq(23L));
    }
}
