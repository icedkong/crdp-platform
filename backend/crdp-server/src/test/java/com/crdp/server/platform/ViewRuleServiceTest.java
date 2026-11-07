package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.ViewRuleService.SaveCommand;
import com.crdp.server.platform.ViewRuleService.SaveRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E20-T02 / PLT-VIEW-007 单元测试（纯函数）：保存校验——名称、层级形状（全局不带选择器 / 租户必带租户 / 模块必带模块 /
 * 字段必带目录内资源与字段）、可见性与脱敏码一致性、枚举与状态默认值。 查库类引用校验 （租户 / 模块 / 角色存在性）与 CRUD、预览一致性在
 * IT（PlatformViewRuleMySqlIT）覆盖。
 */
class ViewRuleServiceTest {

    private static ApiException invalid(SaveRequest request) {
        return assertThrows(ApiException.class, () -> ViewRuleService.validate(request));
    }

    private static SaveRequest request(
            String layer, Long tenantId, String moduleCode, String resource, String field) {
        return new SaveRequest(
                "测试规则（示例）",
                layer,
                tenantId,
                moduleCode,
                resource,
                field,
                List.of(),
                "PLAIN",
                null,
                "READONLY",
                false,
                null);
    }

    @Test
    void normalizesAndDefaults() {
        SaveCommand command =
                ViewRuleService.validate(
                        new SaveRequest(
                                "  排障放宽（示例）  ",
                                " GLOBAL ",
                                null,
                                null,
                                null,
                                null,
                                List.of(3L, 1L),
                                "MASKED ",
                                " phone ",
                                "READONLY",
                                false,
                                " "));
        assertEquals("排障放宽（示例）", command.name(), "名称去首尾空白");
        assertEquals("GLOBAL", command.layer());
        assertEquals("MASKED", command.visibility());
        assertEquals("phone", command.maskRule(), "脱敏码去空白");
        assertEquals("ACTIVE", command.status(), "状态默认生效");
        assertEquals(Set.of(1L, 3L), command.roleIds(), "角色清单去重");
    }

    @Test
    void layerShapeConstraints() {
        // 名称必填、长度上限
        ApiException noName =
                assertThrows(
                        ApiException.class,
                        () ->
                                ViewRuleService.validate(
                                        new SaveRequest(
                                                "  ",
                                                "GLOBAL",
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of(),
                                                "PLAIN",
                                                null,
                                                "READONLY",
                                                false,
                                                null)));
        assertEquals(40001, noName.code());
        ApiException longName =
                assertThrows(
                        ApiException.class,
                        () ->
                                ViewRuleService.validate(
                                        new SaveRequest(
                                                "长".repeat(129),
                                                "GLOBAL",
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of(),
                                                "PLAIN",
                                                null,
                                                "READONLY",
                                                false,
                                                null)));
        assertEquals(40001, longName.code());

        // 层级 / 可见性 / 操作 / 状态枚举
        assertEquals(40001, invalid(request("TIER", null, null, null, null)).code());
        assertEquals(
                40001,
                invalid(
                                new SaveRequest(
                                        "枚举（示例）",
                                        "GLOBAL",
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "PLAINTEXT",
                                        null,
                                        "READONLY",
                                        false,
                                        null))
                        .code());
        assertEquals(
                40001,
                invalid(
                                new SaveRequest(
                                        "枚举（示例）", "GLOBAL", null, null, null, null, List.of(),
                                        "PLAIN", null, "WRITE", false, null))
                        .code());
        assertEquals(
                40001,
                invalid(
                                new SaveRequest(
                                        "枚举（示例）",
                                        "GLOBAL",
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "PLAIN",
                                        null,
                                        "READONLY",
                                        false,
                                        "PENDING"))
                        .code(),
                "状态 PENDING_REVIEW 随 E20-T05，本任务只允许 ACTIVE / DISABLED");

        // GLOBAL 不带任何选择器
        assertEquals(40001, invalid(request("GLOBAL", 1L, null, null, null)).code(), "全局默认层级不能带租户");
        assertEquals(
                40001,
                invalid(request("GLOBAL", null, "followup", null, null)).code(),
                "全局默认层级不能带模块");
        // TENANT 必带租户、不带模块 / 字段
        assertEquals(40001, invalid(request("TENANT", null, null, null, null)).code());
        assertEquals(40001, invalid(request("TENANT", 1L, "followup", null, null)).code());
        // MODULE 必带模块、不带字段；租户可选
        assertEquals(40001, invalid(request("MODULE", null, null, null, null)).code());
        assertEquals(40001, invalid(request("MODULE", 1L, "followup", "patient", "name")).code());
        // FIELD 必带目录内资源；字段在资源目录内
        assertEquals(40001, invalid(request("FIELD", null, null, "unknown", null)).code());
        assertEquals(40001, invalid(request("FIELD", null, null, null, "name")).code());
        assertEquals(40001, invalid(request("FIELD", null, null, "patient", "height")).code());
        SaveCommand wholeResource =
                ViewRuleService.validate(request("FIELD", null, null, "report", null));
        assertEquals("report", wholeResource.resource(), "资源整体规则（field 空）允许");
        assertNull(wholeResource.field());
    }

    @Test
    void maskRuleConsistency() {
        // MASKED 必带脱敏码
        assertEquals(
                40001,
                invalid(
                                new SaveRequest(
                                        "脱敏（示例）",
                                        "FIELD",
                                        null,
                                        null,
                                        "patient",
                                        "phone",
                                        List.of(),
                                        "MASKED",
                                        null,
                                        "READONLY",
                                        false,
                                        null))
                        .code());
        assertEquals(
                40001,
                invalid(
                                new SaveRequest(
                                        "脱敏（示例）",
                                        "FIELD",
                                        null,
                                        null,
                                        "patient",
                                        "phone",
                                        List.of(),
                                        "MASKED",
                                        "sha256",
                                        "READONLY",
                                        false,
                                        null))
                        .code());
        // 非 MASKED 不允许带脱敏码
        ApiException plainWithMask =
                assertThrows(
                        ApiException.class,
                        () ->
                                ViewRuleService.validate(
                                        new SaveRequest(
                                                "明文（示例）",
                                                "FIELD",
                                                null,
                                                null,
                                                "patient",
                                                "phone",
                                                List.of(),
                                                "PLAIN",
                                                "phone",
                                                "READONLY",
                                                false,
                                                null)));
        assertEquals(40001, plainWithMask.code());
        // MASKED 带目录码通过
        SaveCommand masked =
                ViewRuleService.validate(
                        new SaveRequest(
                                "脱敏（示例）",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "phone",
                                List.of(),
                                "MASKED",
                                "phone",
                                "READONLY",
                                false,
                                null));
        assertEquals("phone", masked.maskRule());
    }

    @Test
    void catalogExposesFieldsAndMaskCodes() {
        // catalog 是纯静态清单的形状检查（无库依赖部分由 IT 全量覆盖）
        assertTrue(
                ViewRuleCatalog.all().stream()
                        .anyMatch(
                                entry ->
                                        "patient".equals(entry.resource())
                                                && "name".equals(entry.field())),
                "患者姓名在目录内");
        assertTrue(
                ViewRuleCatalog.MASK_RULES.containsAll(
                        List.of(
                                "name",
                                "phone",
                                "idCard",
                                "medicalRecordNo",
                                "address",
                                "generic")));
        assertTrue(ViewRuleCatalog.resourceKnown("report"), "报告单原件资源在目录内");
    }
}
