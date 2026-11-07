package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.crdp.server.platform.ViewRuleResolver.Rule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E20-T02 / PLT-VIEW-009 单元测试（纯函数，验收：预览与实际执行一致——本解析器是 预览与 E20-T03 执行点共用的唯一引擎）：层级优先（字段 &gt; 模块 &gt;
 * 租户 &gt; 全局默认）、同层更严合并（隐藏 &gt; 设盲 &gt; 脱敏 &gt; 明文、只读 &gt; 可编辑、禁止导出 &gt; 允许导出）、角色与租户 / 模块 / 字段
 * 匹配、无命中回落系统初始默认。
 */
class ViewRuleResolverTest {

    private static Rule rule(
            long id,
            String code,
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            Set<String> roleCodes,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport) {
        return new Rule(
                id,
                code,
                code,
                layer,
                tenantId,
                moduleCode,
                resource,
                field,
                roleCodes,
                visibility,
                maskRule,
                operation,
                allowExport,
                "ACTIVE");
    }

    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    private static final Set<String> ALL = Set.of();

    // ---------------------------------------------------------------- 层级优先（PLT-VIEW-009）

    @Test
    void fieldBeatsModuleBeatsTenantBeatsGlobal() {
        List<Rule> rules =
                List.of(
                        rule(
                                1,
                                "GLOBAL",
                                "GLOBAL",
                                null,
                                null,
                                null,
                                null,
                                ALL,
                                "PLAIN",
                                null,
                                "EDITABLE",
                                true),
                        rule(
                                2,
                                "TENANT",
                                "TENANT",
                                10L,
                                null,
                                null,
                                null,
                                ALL,
                                "MASKED",
                                "phone",
                                "READONLY",
                                false),
                        rule(
                                3,
                                "MODULE",
                                "MODULE",
                                null,
                                "followup",
                                null,
                                null,
                                ALL,
                                "BLINDED",
                                null,
                                "READONLY",
                                false),
                        rule(
                                4,
                                "FIELD",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "phone",
                                ALL,
                                "HIDDEN",
                                null,
                                "READONLY",
                                false));
        Effect tenant = ViewRuleResolver.resolve(rules, ADMIN, 10L, null, null, null);
        assertEquals("MASKED", tenant.visibility(), "只有租户层命中（模块 / 字段为空目标）");
        Effect module = ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", null, null);
        assertEquals("BLINDED", module.visibility(), "模块层比租户层具体");
        assertEquals(2, module.overridden().size(), "全局与租户层被覆盖展示");
        assertEquals("GLOBAL", module.overridden().get(0).layer());
        assertEquals("TENANT", module.overridden().get(1).layer());
        Effect field = ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", "patient", "phone");
        assertEquals("HIDDEN", field.visibility(), "字段层最优先");
        assertEquals(3, field.overridden().size(), "全局 / 租户 / 模块层都记录为被覆盖");
    }

    @Test
    void globalOnlyFallsBackToInitialDefaultWhenNoRuleMatches() {
        Effect none = ViewRuleResolver.resolve(List.of(), ADMIN, 10L, "followup", null, null);
        assertEquals("PLAIN", none.visibility());
        assertEquals("READONLY", none.operation());
        assertFalse(none.allowExport(), "系统初始默认：可见、只读、禁止导出（PLT-VIEW-008）");
        assertNull(none.maskRule());
        assertTrue(none.matched().isEmpty());

        // 停用规则等同不存在：全部停用 → 回落初始默认
        Rule disabled =
                new Rule(
                        1,
                        "G",
                        "G",
                        "GLOBAL",
                        null,
                        null,
                        null,
                        null,
                        Set.of(),
                        "HIDDEN",
                        null,
                        "EDITABLE",
                        true,
                        "DISABLED");
        Effect fallback = ViewRuleResolver.resolve(List.of(disabled), ADMIN, 10L, null, null, null);
        assertEquals("PLAIN", fallback.visibility(), "停用规则不参与匹配");
    }

    // ---------------------------------------------------------------- 同层更严合并（多角色）

    @Test
    void sameTierMergesStricterPerDimension() {
        List<Rule> rules =
                List.of(
                        // 全部角色：可编辑、允许导出；实施角色：只读、禁止导出（同层取更严）
                        rule(
                                1,
                                "RW",
                                "MODULE",
                                null,
                                "followup",
                                null,
                                null,
                                ALL,
                                "PLAIN",
                                null,
                                "EDITABLE",
                                true),
                        rule(
                                2,
                                "RO",
                                "MODULE",
                                null,
                                "followup",
                                null,
                                null,
                                Set.of("IMPLEMENTATION"),
                                "PLAIN",
                                null,
                                "READONLY",
                                false));
        Effect admin = ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", null, null);
        assertEquals("EDITABLE", admin.operation(), "平台管理员只命中全部角色规则");
        assertTrue(admin.allowExport(), "单条命中即取该条值");
        Effect impl =
                ViewRuleResolver.resolve(
                        rules,
                        Set.of("PLATFORM_ADMIN", "IMPLEMENTATION"),
                        10L,
                        "followup",
                        null,
                        null);
        assertEquals("READONLY", impl.operation(), "多角色同层命中：只读 > 可编辑（更严，已确认）");
        assertFalse(impl.allowExport(), "禁止导出 > 允许导出");
        assertEquals(2, impl.matched().size());
    }

    @Test
    void visibilityStrictnessOrderIsHiddenBlindedMaskedPlain() {
        List<Rule> rules =
                List.of(
                        rule(
                                1,
                                "M",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "name",
                                Set.of("A"),
                                "MASKED",
                                "name",
                                "READONLY",
                                false),
                        rule(
                                2,
                                "B",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "name",
                                Set.of("B"),
                                "BLINDED",
                                null,
                                "READONLY",
                                false),
                        rule(
                                3,
                                "H",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "name",
                                Set.of("C"),
                                "HIDDEN",
                                null,
                                "READONLY",
                                false));
        assertEquals(
                "MASKED",
                ViewRuleResolver.resolve(rules, Set.of("A"), 10L, null, "patient", "name")
                        .visibility());
        assertEquals(
                "BLINDED",
                ViewRuleResolver.resolve(rules, Set.of("A", "B"), 10L, null, "patient", "name")
                        .visibility(),
                "设盲 > 脱敏");
        assertEquals(
                "HIDDEN",
                ViewRuleResolver.resolve(rules, Set.of("A", "B", "C"), 10L, null, "patient", "name")
                        .visibility(),
                "隐藏 > 设盲");
        // 角色不在任何规则上：回落初始默认（明文、只读、禁止导出）
        assertEquals(
                "PLAIN",
                ViewRuleResolver.resolve(rules, Set.of("X"), 10L, null, "patient", "name")
                        .visibility());
    }

    // ---------------------------------------------------------------- 匹配轴（角色 / 租户 / 模块 / 字段）

    @Test
    void tenantAndModuleSelectorsNarrowMatching() {
        List<Rule> rules =
                List.of(
                        rule(
                                1,
                                "T10",
                                "TENANT",
                                10L,
                                null,
                                null,
                                null,
                                ALL,
                                "MASKED",
                                "phone",
                                "READONLY",
                                false),
                        rule(
                                2,
                                "M",
                                "MODULE",
                                10L,
                                "followup",
                                null,
                                null,
                                ALL,
                                "HIDDEN",
                                null,
                                "READONLY",
                                false));
        // 租户不匹配：TENANT 规则不命中；MODULE 规则租户也不匹配
        Effect other = ViewRuleResolver.resolve(rules, ADMIN, 20L, null, null, null);
        assertEquals("PLAIN", other.visibility(), "租户选择器不匹配即整条不命中");
        // 模块不匹配：MODULE 规则不命中，回落 TENANT 层
        Effect wrongModule = ViewRuleResolver.resolve(rules, ADMIN, 10L, "base", null, null);
        assertEquals("MASKED", wrongModule.visibility());
        // 模块为空目标：带模块选择器的 MODULE 规则不命中
        Effect noModule = ViewRuleResolver.resolve(rules, ADMIN, 10L, null, null, null);
        assertEquals("MASKED", noModule.visibility(), "MODULE 规则要求目标模块一致");
    }

    @Test
    void fieldRulesOnlyMatchTheirFieldAndResourceWideRules() {
        List<Rule> rules =
                List.of(
                        rule(
                                1,
                                "NAME",
                                "FIELD",
                                null,
                                null,
                                "patient",
                                "name",
                                ALL,
                                "MASKED",
                                "name",
                                "READONLY",
                                false),
                        rule(
                                2,
                                "REPORT",
                                "FIELD",
                                null,
                                "followup",
                                "report",
                                null,
                                ALL,
                                "HIDDEN",
                                null,
                                "READONLY",
                                false));
        // 字段不匹配：patient.phone 不命中 NAME 规则（回落初始默认）
        assertEquals(
                "PLAIN",
                ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", "patient", "phone")
                        .visibility());
        // 资源整体规则命中该资源任意字段；模块选择器须与目标模块一致
        assertEquals(
                "HIDDEN",
                ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", "report", "echocardiogram")
                        .visibility(),
                "资源整体规则（field 空）覆盖资源内任意字段");
        assertEquals(
                "PLAIN",
                ViewRuleResolver.resolve(rules, ADMIN, 10L, "base", "report", "echocardiogram")
                        .visibility(),
                "模块选择器与目标模块不一致不命中");
        // 模块级目标（field 空）不吃字段层规则
        assertEquals(
                "PLAIN",
                ViewRuleResolver.resolve(rules, ADMIN, 10L, "followup", null, null).visibility(),
                "模块级效果由模块及以上层决定，字段层规则不参与");
    }

    // ---------------------------------------------------------------- 脱敏规则与确定性

    @Test
    void maskedEffectCarriesDeterministicMaskRule() {
        List<Rule> rules =
                List.of(
                        rule(
                                2,
                                "PHONE",
                                "FIELD",
                                null,
                                null,
                                "user",
                                "phone",
                                ALL,
                                "MASKED",
                                "phone",
                                "READONLY",
                                false),
                        rule(
                                1,
                                "GENERIC",
                                "FIELD",
                                null,
                                null,
                                "user",
                                "phone",
                                Set.of("A"),
                                "MASKED",
                                "generic",
                                "READONLY",
                                false));
        // 同层同为 MASKED：取 id 最小规则携带的脱敏码（确定性，预览与执行同值）
        Effect both =
                ViewRuleResolver.resolve(rules, Set.of("A", "B"), 10L, "base", "user", "phone");
        assertEquals("MASKED", both.visibility());
        assertEquals("generic", both.maskRule());
        Effect only = ViewRuleResolver.resolve(rules, Set.of("B"), 10L, "base", "user", "phone");
        assertEquals("phone", only.maskRule());
        // 非脱敏效果不带脱敏码
        assertNull(
                ViewRuleResolver.resolve(List.of(), ADMIN, 10L, "base", "user", "phone")
                        .maskRule());
    }
}
