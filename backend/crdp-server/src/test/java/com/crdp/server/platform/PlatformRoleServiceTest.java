package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.PlatformRoleService.RoleSaveRequest;
import com.crdp.server.platform.PlatformRoleService.ViewFields;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E06-T04 / E20-T01（PLT-ROLE-002、003、PLT-VIEW-005）单元测试：角色保存校验（名称 / 状态、
 * 租户视角六字段枚举、「指定租户」须带非空清单（E20-T01 起放开，OQ-48 收敛）、平台端菜单边界）。
 */
class PlatformRoleServiceTest {

    private static RoleSaveRequest request(
            String name,
            String status,
            List<Long> menuIds,
            Boolean canEnter,
            String visibleScope,
            String tenantScope,
            List<Long> specifiedTenantIds,
            Boolean configure,
            Boolean reset,
            Boolean edit) {
        return new RoleSaveRequest(
                name,
                null,
                status,
                menuIds,
                canEnter,
                visibleScope,
                tenantScope,
                specifiedTenantIds,
                configure,
                null,
                reset,
                edit);
    }

    /** 旧签名（无指定租户清单）快捷构造：清单置 null。 */
    private static RoleSaveRequest request(
            String name,
            String status,
            List<Long> menuIds,
            Boolean canEnter,
            String visibleScope,
            String tenantScope,
            Boolean configure,
            Boolean reset,
            Boolean edit) {
        return request(
                name,
                status,
                menuIds,
                canEnter,
                visibleScope,
                tenantScope,
                null,
                configure,
                reset,
                edit);
    }

    // ---------------------------------------------------------------- 代为配置资源范围（E20-T06 /
    // PLT-VIEW-013）

    @Test
    void configResourcesEmptyMeansAll() {
        ViewFields view =
                PlatformRoleService.validateViewFields(
                        request(
                                "巡检角色（示例）",
                                null,
                                null,
                                true,
                                "CONFIG_AND_BUSINESS",
                                "ALL",
                                null,
                                true,
                                false,
                                false));
        assertTrue(view.canConfigure());
        assertNull(view.configResources(), "未配置范围 = 全部配置类资源（已确认）");
        assertNull(PlatformRoleService.configResourcesCsv(view), "空清单落 NULL");
    }

    @Test
    void configResourcesValidatedAgainstCatalog() {
        ViewFields view =
                PlatformRoleService.validateViewFields(
                        new RoleSaveRequest(
                                "巡检角色（示例）",
                                null,
                                null,
                                null,
                                true,
                                "CONFIG_AND_BUSINESS",
                                "ALL",
                                null,
                                true,
                                List.of(" user ", "dict", "user"),
                                false,
                                false));
        assertEquals(List.of("user", "dict"), view.configResources(), "去重保序 + 去空白");
        assertEquals("user,dict", PlatformRoleService.configResourcesCsv(view));
        assertEquals(
                List.of("user", "dict"), PlatformRoleService.parseConfigResources("user,dict"));
        assertNull(PlatformRoleService.parseConfigResources(null));
        assertNull(PlatformRoleService.parseConfigResources(" "));
    }

    @Test
    void configResourcesUnknownCodeRejected() {
        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateViewFields(
                                        new RoleSaveRequest(
                                                "巡检角色（示例）",
                                                null,
                                                null,
                                                null,
                                                true,
                                                "CONFIG_AND_BUSINESS",
                                                "ALL",
                                                null,
                                                true,
                                                List.of("patient"),
                                                false,
                                                false)));
        assertEquals(40001, unknown.code());
        assertTrue(unknown.getMessage().contains("不在可选目录内"), "业务资源不能进配置范围：" + unknown.getMessage());
    }

    @Test
    void nameIsRequiredAndBounded() {
        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateCommon(
                                        request(
                                                "  ", null, null, null, null, null, null, null,
                                                null)));
        assertEquals(40001, blank.code());
        assertTrue(blank.getMessage().contains("名称必填"));

        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateCommon(
                                        request(
                                                null, null, null, null, null, null, null, null,
                                                null)));
        assertEquals(40001, missing.code());

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateCommon(
                                        request(
                                                "角".repeat(65),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)));
        assertEquals(40001, tooLong.code());

        // 合法名称与状态边界
        PlatformRoleService.validateCommon(
                request("一线支持", "DISABLED", null, null, null, null, null, null, null));
        ApiException badStatus =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateCommon(
                                        request(
                                                "一线支持", "PAUSED", null, null, null, null, null,
                                                null, null)));
        assertEquals(40001, badStatus.code());
    }

    @Test
    void viewFieldsDefaultsToConfigOnlyAndResponsible() {
        ViewFields fields =
                PlatformRoleService.validateViewFields(
                        request("客服", null, null, null, null, null, null, null, null));
        assertFalse(fields.canEnter());
        assertEquals(PlatformRoleService.VISIBLE_CONFIG_ONLY, fields.visibleScope());
        assertEquals(PlatformRoleService.TENANT_SCOPE_RESPONSIBLE, fields.tenantScope());
        assertFalse(fields.canConfigure());
        assertFalse(fields.canResetPassword());
        assertFalse(fields.canEditBusiness());
    }

    @Test
    void viewFieldsAcceptsPresetShapes() {
        // 平台管理员默认形态（种子 V104）：全部放开
        ViewFields admin =
                PlatformRoleService.validateViewFields(
                        request(
                                "管理员",
                                null,
                                null,
                                true,
                                "CONFIG_AND_BUSINESS",
                                "ALL",
                                true,
                                true,
                                true));
        assertTrue(admin.canEnter());
        assertTrue(admin.canConfigure());
        assertTrue(admin.canResetPassword());
        assertTrue(admin.canEditBusiness());

        // 实施人员：可代为配置，不可编辑业务数据、不可代重置密码
        ViewFields impl =
                PlatformRoleService.validateViewFields(
                        request(
                                "实施",
                                null,
                                null,
                                true,
                                "CONFIG_AND_BUSINESS",
                                "ALL",
                                true,
                                false,
                                false));
        assertTrue(impl.canConfigure());
        assertFalse(impl.canResetPassword());
        assertFalse(impl.canEditBusiness());

        // 运维人员：仅配置类
        ViewFields ops =
                PlatformRoleService.validateViewFields(
                        request("运维", null, null, true, "CONFIG_ONLY", "ALL", false, false, false));
        assertEquals(PlatformRoleService.VISIBLE_CONFIG_ONLY, ops.visibleScope());
    }

    @Test
    void viewFieldsRejectUnknownEnums() {
        ApiException badVisible =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateViewFields(
                                        request(
                                                "x",
                                                null,
                                                null,
                                                true,
                                                "EVERYTHING",
                                                "ALL",
                                                false,
                                                false,
                                                false)));
        assertEquals(40001, badVisible.code());

        ApiException badTenantScope =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateViewFields(
                                        request(
                                                "x",
                                                null,
                                                null,
                                                true,
                                                "CONFIG_ONLY",
                                                "MINE",
                                                false,
                                                false,
                                                false)));
        assertEquals(40001, badTenantScope.code());
    }

    @Test
    void specifiedTenantScopeRequiresNonEmptyList() {
        // E20-T01（PLT-VIEW-005）起「指定租户」可选：缺清单 / 空清单 → 40001
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateViewFields(
                                        request(
                                                "x",
                                                null,
                                                null,
                                                true,
                                                "CONFIG_ONLY",
                                                "SPECIFIED",
                                                (List<Long>) null,
                                                false,
                                                false,
                                                false)));
        assertEquals(40001, missing.code());
        assertTrue(missing.getMessage().contains("至少一个租户"), missing.getMessage());

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                PlatformRoleService.validateViewFields(
                                        request(
                                                "x",
                                                null,
                                                null,
                                                true,
                                                "CONFIG_ONLY",
                                                "SPECIFIED",
                                                List.of(),
                                                false,
                                                false,
                                                false)));
        assertEquals(40001, empty.code());

        // 带非空清单即通过（清单成员存在性由 validateSpecifiedTenants 查库，IT 覆盖）
        ViewFields fields =
                PlatformRoleService.validateViewFields(
                        request(
                                "x",
                                null,
                                null,
                                true,
                                "CONFIG_ONLY",
                                "SPECIFIED",
                                List.of(1L, 2L),
                                false,
                                false,
                                false));
        assertEquals(PlatformRoleService.TENANT_SCOPE_SPECIFIED, fields.tenantScope());

        // 非 SPECIFIED 不要求清单
        ViewFields all =
                PlatformRoleService.validateViewFields(
                        request(
                                "y",
                                null,
                                null,
                                true,
                                "CONFIG_AND_BUSINESS",
                                "ALL",
                                (List<Long>) null,
                                false,
                                false,
                                false));
        assertEquals(PlatformRoleService.TENANT_SCOPE_ALL, all.tenantScope());
    }
}
