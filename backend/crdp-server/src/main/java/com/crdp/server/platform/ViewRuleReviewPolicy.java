package com.crdp.server.platform;

import java.util.Objects;
import java.util.Set;

/**
 * 高敏规则变更判定（E20-T05 / PLT-VIEW-014，口径 OQ-99 ③）：将字段改为明文、取消设盲、取消只读或允许导出的
 * 变更须由发起人以外的另一名平台管理员复核通过后生效（platform-spec §8，已确认）。
 *
 * <p>判定以「行的生效内容」为单位按严格序比较（与 {@link ViewRuleResolver} 同层合并的严格序一致）： 可见性 隐藏 &gt; 设盲 &gt; 脱敏 &gt; 明文、只读
 * &gt; 可编辑、禁止导出 &gt; 允许导出。 生效内容 = 规则行 ACTIVE 时为行内容，否则（DISABLED / PENDING_REVIEW /
 * 不存在）为系统初始默认（可见、只读、禁止导出）—— 因此停用一条脱敏 / 设盲 / 隐藏规则、 重新启用一条可编辑或允许导出的规则同样属高敏（效果上等价于放宽）。
 *
 * <p>另把「生效中限制性规则的作用范围收窄」一并视为高敏：把可见性非明文的规则改选层级 / 租户 / 模块 / 资源 / 字段，或收窄适用角色（原覆盖目标不再被该限制覆盖，效果等同取消限制）。
 * 纯加宽（角色只增不减、限制只严不松） 不触发复核。
 */
public final class ViewRuleReviewPolicy {

    /** 可见性严格序（与解析器同层合并一致）：值越大越严。 */
    static int rank(String visibility) {
        return switch (visibility == null ? "" : visibility) {
            case "HIDDEN" -> 4;
            case "BLINDED" -> 3;
            case "MASKED" -> 2;
            case "PLAIN" -> 1;
            default -> 1;
        };
    }

    /** 生效内容（null = 系统初始默认：可见、只读、禁止导出，无选择器）。 */
    public record Content(
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            Set<Long> roleIds,
            String visibility,
            String operation,
            boolean allowExport) {

        /** 系统初始默认（无规则 / 规则未生效时的解析回落值，可见、只读、禁止导出）。 */
        public static Content initialDefault() {
            return new Content(null, null, null, null, null, Set.of(), "PLAIN", "READONLY", false);
        }
    }

    private ViewRuleReviewPolicy() {}

    /** 是否高敏变更（放宽）：变更前 / 后的生效内容比较。 before / after 传 null 表示该侧不生效 （系统初始默认口径）。纯函数，供单测与前端同口径文案。 */
    public static boolean sensitive(Content before, Content after) {
        Content from = before == null ? Content.initialDefault() : before;
        Content to = after == null ? Content.initialDefault() : after;
        // 可见性下调（改明文 / 取消设盲 / 取消隐藏）
        if (rank(to.visibility()) < rank(from.visibility())) {
            return true;
        }
        // 取消只读
        if ("READONLY".equals(from.operation()) && "EDITABLE".equals(to.operation())) {
            return true;
        }
        // 允许导出
        if (!from.allowExport() && to.allowExport()) {
            return true;
        }
        // 生效中的限制性规则（可见性非明文）改选择器或收窄适用角色 = 原覆盖目标失去限制
        if (before != null && rank(before.visibility()) > rank("PLAIN")) {
            return selectorsChanged(before, to) || rolesNarrowed(before.roleIds(), to.roleIds());
        }
        return false;
    }

    /** 层级 / 租户 / 模块 / 资源 / 字段任一变化（限制性规则的覆盖目标变化）。 */
    private static boolean selectorsChanged(Content before, Content after) {
        return !Objects.equals(before.layer(), after.layer())
                || !Objects.equals(before.tenantId(), after.tenantId())
                || !Objects.equals(before.moduleCode(), after.moduleCode())
                || !Objects.equals(before.resource(), after.resource())
                || !Objects.equals(before.field(), after.field());
    }

    /** 适用角色收窄：清单空 = 全部角色。前值空（全部）→ 后值非空即收窄；后值空（全部）→ 加宽不触发； 两侧均非空时后值不再覆盖前值任一角色即收窄。 */
    static boolean rolesNarrowed(Set<Long> beforeRoles, Set<Long> afterRoles) {
        if (beforeRoles == null || beforeRoles.isEmpty()) {
            return afterRoles != null && !afterRoles.isEmpty();
        }
        if (afterRoles == null || afterRoles.isEmpty()) {
            return false;
        }
        return !afterRoles.containsAll(beforeRoles);
    }
}
