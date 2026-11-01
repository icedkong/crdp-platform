package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.PlatformRoleService.RoleSaveRequest;
import com.crdp.server.platform.PlatformRoleService.ViewFields;
import java.util.List;
import org.junit.jupiter.api.Test;

/** E06-T04（PLT-ROLE-002、003）单元测试：角色保存校验（名称 / 状态、租户视角六字段枚举、 「指定租户」暂行拒绝、平台端菜单边界）。 */
class PlatformRoleServiceTest {

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
        return new RoleSaveRequest(
                name,
                null,
                status,
                menuIds,
                canEnter,
                visibleScope,
                tenantScope,
                configure,
                reset,
                edit);
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
    void specifiedTenantScopeIsRejectedUntilE20() {
        // OQ-48 暂行口径：「指定租户」清单由 E20-T01（PLT-VIEW-005）落地，本任务提交即 40001
        ApiException specified =
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
                                                false,
                                                false,
                                                false)));
        assertEquals(40001, specified.code());
        assertTrue(specified.getMessage().contains("E20-T01"), specified.getMessage());
    }
}
