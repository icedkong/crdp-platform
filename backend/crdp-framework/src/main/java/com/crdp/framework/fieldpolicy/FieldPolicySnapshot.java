package com.crdp.framework.fieldpolicy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 某账号的生效字段策略快照（E07-T04 / PLT-PERM-009，tasks.csv 验收「隐藏、脱敏、明文按角色合并」）。
 *
 * <p>platform-spec §4 业务规则（已确认）：医院端多角色叠加授权，同一字段的字段策略<b>取最宽松</b>（可编辑 &gt; 可见（明文）&gt; 脱敏 &gt;
 * 隐藏）。当前策略集为 VISIBLE / MASKED / HIDDEN（docs/api/system.md §3）；「可编辑」与设盲（rct 项目角色，不参与本合并）
 * 随后续需求在目录上扩展。
 *
 * <p>关键口径：<b>只有角色的显式覆盖参与合并</b>——未配置的角色不把平台默认注入合并，否则叠加授权语义下任何角色的 收紧覆盖都会被其他角色的「未配置 =
 * 默认」冲掉；某字段没有任何角色覆盖时整体回落平台默认（{@link SensitiveFieldCatalog}，当前一律 MASKED， AGENTS.md §5.3 默认脱敏）。
 * 平台租户视角的同字段取更严（platform-spec §8）是另一套合并规则，随 E20 视角落地，不在本快照。
 *
 * <p>装配进登录会话（{@code HospitalAccount.fieldPolicies} → {@code SessionPrincipal.fieldPolicies}），
 * 角色字段策略保存已递增成员权限版本（PLT-PERM-010），旧会话下一次请求重算即得新策略；脱敏序列化与明文查看接口（E07-T05）直接消费 {@link
 * #decide}。平台上限（不能宽于平台默认，PLT-SCOPE-007）为 P2 后续规划，本快照不设上限。
 */
public record FieldPolicySnapshot(Map<String, String> explicit) {

    /** 平台默认快照：不含任何显式覆盖，所有字段经 {@link #decide} 回落目录默认。 */
    public static FieldPolicySnapshot defaults() {
        return new FieldPolicySnapshot(Map.of());
    }

    /**
     * 多角色显式覆盖「取最宽松」合并（纯函数，登录装配与权限预览共用）。
     *
     * <p>防御脏数据：目录外字段与非法策略行直接忽略（配置入口 {@code RoleAdminService.updateFieldPolicies} 已校验， 这里兜底）。
     *
     * @param roleOverrides 用户全部<b>启用</b>角色的显式覆盖行（SQL 侧已按角色并集）；null 视为无覆盖
     */
    public static FieldPolicySnapshot merge(Collection<FieldPolicyOverride> roleOverrides) {
        if (roleOverrides == null || roleOverrides.isEmpty()) {
            return defaults();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (FieldPolicyOverride override : roleOverrides) {
            if (override == null
                    || SensitiveFieldCatalog.find(override.resource(), override.field()).isEmpty()
                    || !SensitiveFieldCatalog.POLICIES.contains(override.policy())) {
                continue;
            }
            merged.merge(override.key(), override.policy(), FieldPolicySnapshot::mostPermissive);
        }
        return new FieldPolicySnapshot(Map.copyOf(merged));
    }

    /** 两个策略取较宽松者（排序 VISIBLE &gt; MASKED &gt; HIDDEN）。 */
    public static String mostPermissive(String left, String right) {
        return leniency(left) >= leniency(right) ? left : right;
    }

    /** 当前用户对某字段的生效策略：显式合并值优先，无覆盖回落目录默认；<b>目录外（非敏感）字段一律 VISIBLE</b>（字段级权限只控制敏感字段目录）。 */
    public String decide(String resource, String field) {
        return SensitiveFieldCatalog.find(resource, field)
                .map(
                        entry ->
                                explicit == null
                                        ? entry.defaultPolicy()
                                        : explicit.getOrDefault(entry.key(), entry.defaultPolicy()))
                .orElse(SensitiveFieldCatalog.POLICY_VISIBLE);
    }

    /** 策略宽松度：可见（明文）2 &gt; 脱敏 1 &gt; 隐藏 0；未知策略 -1（不参与合并）。 */
    static int leniency(String policy) {
        return switch (policy == null ? "" : policy) {
            case SensitiveFieldCatalog.POLICY_VISIBLE -> 2;
            case SensitiveFieldCatalog.POLICY_MASKED -> 1;
            case SensitiveFieldCatalog.POLICY_HIDDEN -> 0;
            default -> -1;
        };
    }
}
