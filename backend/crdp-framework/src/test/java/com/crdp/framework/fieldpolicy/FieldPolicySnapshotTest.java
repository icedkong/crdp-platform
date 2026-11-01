package com.crdp.framework.fieldpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E07-T04 / PLT-PERM-009（tasks.csv 验收：隐藏、脱敏、明文按角色合并）： 字段策略多角色「取最宽松」合并的纯函数。
 *
 * <p>platform-spec §4 业务规则（已确认）：同一字段的字段策略取最宽松（可编辑 &gt; 可见 &gt; 脱敏 &gt; 隐藏）， 因为角色是叠加授权。当前策略集为
 * VISIBLE / MASKED / HIDDEN（docs/api/system.md §3），排序 VISIBLE &gt; MASKED &gt; HIDDEN；
 * 「可编辑」随设盲（rct）等后续需求扩展。
 *
 * <p>关键口径：<b>只有显式覆盖参与合并</b>——未配置的角色不把平台默认注入合并（否则叠加授权语义下， 任何角色的收紧覆盖都会被其他角色的「未配置 = 默认
 * MASKED」冲掉）；某字段没有任何角色覆盖时整体回落平台默认（SensitiveFieldCatalog，当前一律 MASKED）。
 */
class FieldPolicySnapshotTest {

    private static FieldPolicyOverride override(String field, String policy) {
        return new FieldPolicyOverride("patient", field, policy);
    }

    /** 无任何覆盖：患者身份五位字段全部回落平台默认（MASKED，AGENTS.md §5.3 默认脱敏）。 */
    @Test
    void defaultsFallBackToCatalogMasked() {
        FieldPolicySnapshot snapshot = FieldPolicySnapshot.defaults();
        for (SensitiveFieldCatalog.Entry entry : SensitiveFieldCatalog.all()) {
            assertEquals(
                    SensitiveFieldCatalog.POLICY_MASKED,
                    snapshot.decide(entry.resource(), entry.field()),
                    entry.field() + " 默认应为脱敏");
        }
        assertTrue(snapshot.explicit().isEmpty(), "默认快照不含任何显式覆盖");
    }

    /** 单角色单字段覆盖：生效策略即覆盖值（其余字段继续默认）。 */
    @Test
    void singleOverrideTakesEffect() {
        FieldPolicySnapshot snapshot =
                FieldPolicySnapshot.merge(List.of(override("name", "VISIBLE")));
        assertEquals(SensitiveFieldCatalog.POLICY_VISIBLE, snapshot.decide("patient", "name"));
        assertEquals(SensitiveFieldCatalog.POLICY_MASKED, snapshot.decide("patient", "phone"));
        assertEquals(1, snapshot.explicit().size());
    }

    /** E07-T04 验收：隐藏、脱敏、明文按角色合并——同字段多角色取最宽松。 */
    @Test
    void mergeTakesMostPermissiveAcrossRoles() {
        assertEquals(
                SensitiveFieldCatalog.POLICY_MASKED,
                FieldPolicySnapshot.merge(
                                List.of(override("name", "HIDDEN"), override("name", "MASKED")))
                        .decide("patient", "name"),
                "隐藏 + 脱敏 → 脱敏");
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                FieldPolicySnapshot.merge(
                                List.of(override("name", "MASKED"), override("name", "VISIBLE")))
                        .decide("patient", "name"),
                "脱敏 + 明文 → 明文");
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                FieldPolicySnapshot.merge(
                                List.of(override("name", "HIDDEN"), override("name", "VISIBLE")))
                        .decide("patient", "name"),
                "隐藏 + 明文 → 明文（叠加授权，任一角色放宽即生效）");
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                FieldPolicySnapshot.merge(
                                List.of(
                                        override("name", "VISIBLE"),
                                        override("name", "VISIBLE"),
                                        override("name", "HIDDEN")))
                        .decide("patient", "name"),
                "三角色混合仍取最宽松");
    }

    /**
     * 叠加授权语义：未配置的角色不注入默认值参与合并——某角色把住址收紧为 HIDDEN 时， 即使用户还有别的未覆盖角色，住址仍是 HIDDEN（而不是被「默认 MASKED」顶回脱敏）。
     */
    @Test
    void absentOverrideDoesNotWeakenExplicitOne() {
        FieldPolicySnapshot snapshot =
                FieldPolicySnapshot.merge(List.of(override("address", "HIDDEN")));
        assertEquals(SensitiveFieldCatalog.POLICY_HIDDEN, snapshot.decide("patient", "address"));
    }

    /** 不同字段互不影响：每个目录字段独立合并。 */
    @Test
    void fieldsMergeIndependently() {
        FieldPolicySnapshot snapshot =
                FieldPolicySnapshot.merge(
                        List.of(
                                override("name", "VISIBLE"),
                                override("phone", "HIDDEN"),
                                override("address", "MASKED")));
        assertEquals(SensitiveFieldCatalog.POLICY_VISIBLE, snapshot.decide("patient", "name"));
        assertEquals(SensitiveFieldCatalog.POLICY_HIDDEN, snapshot.decide("patient", "phone"));
        assertEquals(SensitiveFieldCatalog.POLICY_MASKED, snapshot.decide("patient", "address"));
        assertEquals(SensitiveFieldCatalog.POLICY_MASKED, snapshot.decide("patient", "idCard"));
    }

    /** 防御脏数据：目录外字段与非法策略值不参与合并（保存入口已校验，这里兜底）。 */
    @Test
    void ignoresUnknownFieldsAndPolicies() {
        FieldPolicySnapshot snapshot =
                FieldPolicySnapshot.merge(
                        List.of(
                                new FieldPolicyOverride("patient", "nickname", "VISIBLE"),
                                override("name", "PLAIN"),
                                override("phone", "VISIBLE")));
        assertEquals(
                SensitiveFieldCatalog.POLICY_MASKED,
                snapshot.decide("patient", "name"),
                "非法策略行忽略，姓名回落默认脱敏");
        assertEquals(SensitiveFieldCatalog.POLICY_VISIBLE, snapshot.decide("patient", "phone"));
        assertTrue(
                !snapshot.explicit().containsKey("patient.nickname"), "目录外字段不进快照（非敏感字段不受字段级权限控制）");
    }

    /** 目录外（非敏感）字段不控制：decide 一律 VISIBLE。 */
    @Test
    void nonCatalogFieldsAreAlwaysVisible() {
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                FieldPolicySnapshot.defaults().decide("patient", "diagnosis"));
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                FieldPolicySnapshot.defaults().decide("followup", "content"));
    }

    /** 空清单与 null：等价于默认快照（防御装配路径传 null）。 */
    @Test
    void emptyOrNullOverridesYieldDefaults() {
        assertEquals(
                SensitiveFieldCatalog.POLICY_MASKED,
                FieldPolicySnapshot.merge(List.of()).decide("patient", "name"));
        assertEquals(
                SensitiveFieldCatalog.POLICY_MASKED,
                FieldPolicySnapshot.merge(null).decide("patient", "name"));
    }
}
