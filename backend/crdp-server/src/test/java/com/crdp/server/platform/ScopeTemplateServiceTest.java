package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T07（PLT-SCOPE-003、004）单元测试：模板保存校验（base 不进模板、只能勾本模块医院端菜单、敏感项不能预置） 与敏感权限清单校验（权限码格式、唯一、说明必填）。
 */
class ScopeTemplateServiceTest {

    private final PlatformMapper platformMapper = mock(PlatformMapper.class);
    private final JdbcTemplate platform = mock(JdbcTemplate.class);

    @BeforeEach
    void setUp() {
        TenantContext.usePlatform();
        when(platformMapper.listMenus("HOSPITAL"))
                .thenReturn(
                        List.of(
                                menu(21L, "followup"),
                                menu(22L, "followup"),
                                menu(211L, "followup", "followup:patient:plain"),
                                menu(31L, "base")));
        lenient()
                .when(
                        platform.queryForList(
                                "SELECT perm_code FROM plt_sensitive_perm", String.class))
                .thenReturn(List.of("followup:patient:plain"));
        lenient()
                .when(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_module WHERE code = ?",
                                Integer.class,
                                "followup"))
                .thenReturn(1);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    private static MenuRow menu(long id, String module) {
        return menu(id, module, null);
    }

    private static MenuRow menu(long id, String module, String permCode) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(0L);
        row.setSide("HOSPITAL");
        row.setType(permCode == null ? "MENU" : "BUTTON");
        row.setModuleCode(module);
        row.setTitle("菜单" + id);
        row.setPermCode(permCode);
        return row;
    }

    // ---------------------------------------------------------------- 模板（PLT-SCOPE-003）

    @Test
    void templateRejectsNullMenuIdsAndBaseModule() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        ScopeTemplateService.normalizeTemplate(
                                                new ScopeTemplateService.TemplateUpdateRequest(
                                                        null),
                                                "followup",
                                                platformMapper,
                                                platform))
                        .code());
        ApiException base =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeTemplate(
                                        new ScopeTemplateService.TemplateUpdateRequest(List.of()),
                                        "base",
                                        platformMapper,
                                        platform));
        assertEquals(40001, base.code());
        assertTrue(base.getMessage().contains("固定能力"), base.getMessage());
    }

    @Test
    void templateRejectsMenusOfOtherModules() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeTemplate(
                                        new ScopeTemplateService.TemplateUpdateRequest(
                                                List.of(31L)),
                                        "followup",
                                        platformMapper,
                                        platform));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("不属于模块"), ex.getMessage());
    }

    @Test
    void templateRejectsSensitiveItems() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeTemplate(
                                        new ScopeTemplateService.TemplateUpdateRequest(
                                                List.of(21L, 211L)),
                                        "followup",
                                        platformMapper,
                                        platform));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("不能预置进模板"), ex.getMessage());
    }

    @Test
    void templateAcceptsNonSensitiveModuleMenus() {
        assertEquals(
                List.of(21L, 22L),
                ScopeTemplateService.normalizeTemplate(
                        new ScopeTemplateService.TemplateUpdateRequest(List.of(22L, 21L, 21L)),
                        "followup",
                        platformMapper,
                        platform));
    }

    // ---------------------------------------------------------------- 敏感清单（PLT-SCOPE-004）

    @Test
    void sensitivePermsValidateFormatNameAndUniqueness() {
        ApiException format =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeSensitivePerms(
                                        new ScopeTemplateService.SensitivePermUpdateRequest(
                                                List.of(
                                                        new ScopeTemplateService.SensitivePermItem(
                                                                "patient-plain", "格式不对")))));
        assertEquals(40001, format.code());
        ApiException blankName =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeSensitivePerms(
                                        new ScopeTemplateService.SensitivePermUpdateRequest(
                                                List.of(
                                                        new ScopeTemplateService.SensitivePermItem(
                                                                "followup:patient:plain", " ")))));
        assertEquals(40001, blankName.code());
        ApiException dup =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeSensitivePerms(
                                        new ScopeTemplateService.SensitivePermUpdateRequest(
                                                List.of(
                                                        new ScopeTemplateService.SensitivePermItem(
                                                                "followup:patient:plain", "明文查看"),
                                                        new ScopeTemplateService.SensitivePermItem(
                                                                "followup:patient:plain",
                                                                "再次登记")))));
        assertEquals(40001, dup.code());
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());
        ApiException longName =
                assertThrows(
                        ApiException.class,
                        () ->
                                ScopeTemplateService.normalizeSensitivePerms(
                                        new ScopeTemplateService.SensitivePermUpdateRequest(
                                                List.of(
                                                        new ScopeTemplateService.SensitivePermItem(
                                                                "followup:patient:plain",
                                                                "长".repeat(129))))));
        assertEquals(40001, longName.code());
    }

    @Test
    void sensitivePermsAcceptValidList() {
        List<ScopeTemplateService.SensitivePermItem> items =
                ScopeTemplateService.normalizeSensitivePerms(
                        new ScopeTemplateService.SensitivePermUpdateRequest(
                                List.of(
                                        new ScopeTemplateService.SensitivePermItem(
                                                "followup:patient:plain", "敏感字段明文查看"),
                                        // 未绑定菜单的码先登记后生效（如 rct 模块后续码）
                                        new ScopeTemplateService.SensitivePermItem(
                                                "rct:blind:unblind", "RCT 揭盲"))));
        assertEquals(2, items.size());
        assertEquals("rct:blind:unblind", items.get(1).permCode());
    }
}
