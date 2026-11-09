package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.ViewRuleReviewPolicy.Content;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E20-T05 / PLT-VIEW-014 高敏判定矩阵（口径 OQ-99 ③）：规格四类放宽（改明文、取消设盲、取消只读、允许导出）
 * 加等效放宽（停用限制性规则、重新启用宽松规则、收窄限制性规则的作用范围）都须第二人复核； 收严、改文案、加宽角色即时生效。
 */
class ViewRuleReviewPolicyTest {

    private static Content content(String visibility, String operation, boolean allowExport) {
        return new Content(
                "FIELD",
                null,
                "followup",
                "patient",
                "name",
                Set.of(),
                visibility,
                operation,
                allowExport);
    }

    @Test
    void specNamedRelaxationsAreSensitive() {
        // 字段改为明文（脱敏 → 明文）
        assertTrue(
                ViewRuleReviewPolicy.sensitive(
                        content("MASKED", "READONLY", false), content("PLAIN", "READONLY", false)));
        // 取消设盲（设盲 → 脱敏，未到明文也是放宽）
        assertTrue(
                ViewRuleReviewPolicy.sensitive(
                        content("BLINDED", "READONLY", false),
                        content("MASKED", "READONLY", false)));
        assertTrue(
                ViewRuleReviewPolicy.sensitive(
                        content("HIDDEN", "READONLY", false),
                        content("BLINDED", "READONLY", false)));
        // 取消只读（只读 → 可编辑）
        assertTrue(
                ViewRuleReviewPolicy.sensitive(
                        content("PLAIN", "READONLY", false), content("PLAIN", "EDITABLE", false)));
        // 允许导出
        assertTrue(
                ViewRuleReviewPolicy.sensitive(
                        content("PLAIN", "READONLY", false), content("PLAIN", "READONLY", true)));
    }

    @Test
    void equivalentRelaxationsAreSensitive() {
        // 停用一条脱敏 / 设盲 / 隐藏规则（后值 = 不生效 = 系统初始默认明文）
        assertTrue(ViewRuleReviewPolicy.sensitive(content("MASKED", "READONLY", false), null));
        assertTrue(ViewRuleReviewPolicy.sensitive(content("BLINDED", "READONLY", false), null));
        assertTrue(ViewRuleReviewPolicy.sensitive(content("HIDDEN", "READONLY", false), null));
        // 重新启用一条可编辑规则（前值不生效 = 默认只读）
        assertTrue(ViewRuleReviewPolicy.sensitive(null, content("PLAIN", "EDITABLE", false)));
        // 新建即允许导出（前值 = 初始默认禁止导出）
        assertTrue(ViewRuleReviewPolicy.sensitive(null, content("PLAIN", "READONLY", true)));
        // 限制性规则改选择器（脱敏规则从患者姓名改到用户手机号 = 患者姓名失去限制）
        Content before =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(),
                        "MASKED",
                        "READONLY",
                        false);
        Content redirected =
                new Content(
                        "FIELD",
                        null,
                        "base",
                        "user",
                        "phone",
                        Set.of(),
                        "MASKED",
                        "READONLY",
                        false);
        assertTrue(ViewRuleReviewPolicy.sensitive(before, redirected));
        // 限制性规则收窄适用角色（全部角色 → 指定角色）
        Content allRoles =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(),
                        "MASKED",
                        "READONLY",
                        false);
        Content subset =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(2L),
                        "MASKED",
                        "READONLY",
                        false);
        assertTrue(ViewRuleReviewPolicy.sensitive(allRoles, subset));
        Content narrower =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(2L),
                        "MASKED",
                        "READONLY",
                        false);
        Content dropped =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(3L),
                        "MASKED",
                        "READONLY",
                        false);
        assertTrue(ViewRuleReviewPolicy.sensitive(narrower, dropped));
    }

    @Test
    void tighteningsAndNeutralChangesAreNotSensitive() {
        // 收严（明文 → 脱敏 / 脱敏 → 设盲）
        assertFalse(
                ViewRuleReviewPolicy.sensitive(
                        content("PLAIN", "READONLY", false), content("MASKED", "READONLY", false)));
        assertFalse(
                ViewRuleReviewPolicy.sensitive(
                        content("MASKED", "READONLY", false),
                        content("HIDDEN", "READONLY", false)));
        // 可编辑 → 只读、允许导出 → 禁止导出
        assertFalse(
                ViewRuleReviewPolicy.sensitive(
                        content("PLAIN", "EDITABLE", true), content("PLAIN", "READONLY", false)));
        // 同效果停用（明文只读禁导出规则的生效内容与初始默认相同）
        assertFalse(ViewRuleReviewPolicy.sensitive(content("PLAIN", "READONLY", false), null));
        // 新建脱敏 / 设盲规则（前值默认明文，收严）
        assertFalse(ViewRuleReviewPolicy.sensitive(null, content("MASKED", "READONLY", false)));
        // 仅改名称 / 收紧（脱敏码换更严口径同 rank）不变选择器与角色 → 即时生效
        Content same =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(2L),
                        "MASKED",
                        "READONLY",
                        false);
        assertFalse(ViewRuleReviewPolicy.sensitive(same, same));
        // 限制性规则加宽角色（指定角色 → 全部角色）不触发
        Content subset =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(2L),
                        "MASKED",
                        "READONLY",
                        false);
        Content widened =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(),
                        "MASKED",
                        "READONLY",
                        false);
        assertFalse(ViewRuleReviewPolicy.sensitive(subset, widened));
        // 非限制性规则（明文）改选择器不触发（无限制可被取消）
        Content plainA =
                new Content(
                        "FIELD",
                        null,
                        "followup",
                        "patient",
                        "name",
                        Set.of(),
                        "PLAIN",
                        "READONLY",
                        false);
        Content plainB =
                new Content(
                        "FIELD",
                        null,
                        "base",
                        "user",
                        "phone",
                        Set.of(),
                        "PLAIN",
                        "READONLY",
                        false);
        assertFalse(ViewRuleReviewPolicy.sensitive(plainA, plainB));
    }

    @Test
    void visibilityRankMatchesResolverMergeOrder() {
        // 与同层合并的严格序一致：隐藏 > 设盲 > 脱敏 > 明文
        assertTrue(ViewRuleReviewPolicy.rank("HIDDEN") > ViewRuleReviewPolicy.rank("BLINDED"));
        assertTrue(ViewRuleReviewPolicy.rank("BLINDED") > ViewRuleReviewPolicy.rank("MASKED"));
        assertTrue(ViewRuleReviewPolicy.rank("MASKED") > ViewRuleReviewPolicy.rank("PLAIN"));
        // 未知值按最宽处理（不误判收严）
        assertTrue(ViewRuleReviewPolicy.rank("PLAIN") == ViewRuleReviewPolicy.rank("unknown"));
    }
}
