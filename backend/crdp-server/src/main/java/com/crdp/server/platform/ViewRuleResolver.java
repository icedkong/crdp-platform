package com.crdp.server.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 视角规则的唯一解析引擎（E20-T02 / PLT-VIEW-009；验收「预览与实际执行一致」）。
 *
 * <p>效果预览（{@code POST /api/platform/view-rules/preview}）与执行点（E20-T03 / PLT-VIEW-010..012 的
 * 脱敏、设盲、只读拦截）都必须经过本类的 {@link #resolve}——两边喂同一批 ACTIVE 规则与同一目标 （平台角色集 + 租户 + 模块 +
 * 资源字段），输出即一致；任何一侧不得自行实现匹配。
 *
 * <p>匹配口径（platform-spec §8 业务规则，已确认）：
 *
 * <ol>
 *   <li>只参与 {@code ACTIVE} 规则（停用等同不存在）；
 *   <li>角色：空清单（全部角色）或与账号平台角色有交集；
 *   <li>租户 / 模块：空选择器 = 全部；否则须与目标一致（目标模块为空时带模块选择器的规则不命中）；
 *   <li>字段层规则只在「目标资源 + 字段」非空时参与，资源整体规则（field 空）命中该资源任意字段； 模块级目标不吃字段层规则；
 *   <li>层级优先：字段 &gt; 模块 &gt; 租户 &gt; 全局默认，只取最具体层；
 *   <li>同一层命中多条（如账号有多个平台角色）按更严合并：隐藏 &gt; 设盲 &gt; 脱敏 &gt; 明文、 只读 &gt; 可编辑、禁止导出 &gt; 允许导出；同为 MASKED
 *       时脱敏码取同层命中里 id 最小的规则 携带值（确定性；目录兜底 generic）。
 * </ol>
 *
 * <p>无任何命中回落系统初始默认（可见、只读、禁止导出，PLT-VIEW-008）——与全局默认规则同值， 平台管理员停用全部规则不会放开权限。
 */
public final class ViewRuleResolver {

    /** 解析输入的规则行（{@code roleCodes} 为空 = 全部角色；来自 plt_view_rule + plt_view_rule_role）。 */
    public record Rule(
            long id,
            String code,
            String name,
            String layer,
            Long tenantId,
            String moduleCode,
            String resource,
            String field,
            Set<String> roleCodes,
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            String status) {}

    /** 命中规则的回显（预览「由哪条规则决定 / 哪些被覆盖」）。 */
    public record Trace(long id, String code, String name, String layer) {}

    /** 解析结果：三维度生效值 + 决定（最具体层）与被覆盖（更低层）的规则。 */
    public record Effect(
            String visibility,
            String maskRule,
            String operation,
            boolean allowExport,
            List<Trace> matched,
            List<Trace> overridden) {}

    /** 系统初始默认（PLT-VIEW-008；无命中时的回落值，与全局默认规则同值）。 */
    public static final Effect INITIAL_DEFAULT =
            new Effect(
                    ViewRuleCatalog.VISIBILITY_PLAIN,
                    null,
                    ViewRuleCatalog.OPERATION_READONLY,
                    false,
                    List.of(),
                    List.of());

    private ViewRuleResolver() {}

    /**
     * 解析某目标（平台角色集 + 租户 + 模块 + 可选资源字段）的最终效果。纯函数。
     *
     * @param rules 全部规则（含停用行，内部过滤）
     * @param roleCodes 账号 / 预选的平台角色码集合
     * @param tenantId 目标租户（预览必选；规则空选择器 = 全部租户）
     * @param moduleCode 目标模块（空 = 不限定，模块层规则不参与）
     * @param resource 目标资源对象（空 = 模块级效果，字段层规则不参与）
     * @param field 目标字段码（资源内）
     */
    public static Effect resolve(
            List<Rule> rules,
            Set<String> roleCodes,
            Long tenantId,
            String moduleCode,
            String resource,
            String field) {
        List<Rule> matches = new ArrayList<>();
        for (Rule rule : rules) {
            if (!ViewRuleCatalog.STATUS_ACTIVE.equals(rule.status())) {
                continue;
            }
            if (!rule.roleCodes().isEmpty()
                    && rule.roleCodes().stream().noneMatch(roleCodes::contains)) {
                continue;
            }
            if (rule.tenantId() != null && !rule.tenantId().equals(tenantId)) {
                continue;
            }
            if (rule.moduleCode() != null
                    && (moduleCode == null || !rule.moduleCode().equals(moduleCode))) {
                continue;
            }
            if (ViewRuleCatalog.LAYER_FIELD.equals(rule.layer())) {
                if (resource == null || !rule.resource().equals(resource)) {
                    continue;
                }
                if (rule.field() != null && !rule.field().equals(field)) {
                    continue;
                }
            }
            matches.add(rule);
        }
        if (matches.isEmpty()) {
            return INITIAL_DEFAULT;
        }
        int bestTier = matches.stream().mapToInt(rule -> tier(rule.layer())).max().orElse(0);
        List<Rule> winning =
                matches.stream()
                        .filter(rule -> tier(rule.layer()) == bestTier)
                        .sorted(Comparator.comparingLong(Rule::id))
                        .toList();
        List<Rule> overridden =
                matches.stream()
                        .filter(rule -> tier(rule.layer()) < bestTier)
                        .sorted(Comparator.comparingLong(Rule::id))
                        .toList();

        String visibility =
                winning.stream()
                        .map(Rule::visibility)
                        .max(Comparator.comparingInt(ViewRuleResolver::strictness))
                        .orElse(ViewRuleCatalog.VISIBILITY_PLAIN);
        String maskRule = null;
        if (ViewRuleCatalog.VISIBILITY_MASKED.equals(visibility)) {
            maskRule =
                    winning.stream()
                            .filter(
                                    rule ->
                                            ViewRuleCatalog.VISIBILITY_MASKED.equals(
                                                    rule.visibility()))
                            .filter(rule -> rule.maskRule() != null)
                            .findFirst()
                            .map(Rule::maskRule)
                            .orElse(ViewRuleCatalog.defaultMaskRule(resource, field));
        }
        String operation =
                winning.stream()
                                .anyMatch(
                                        rule ->
                                                ViewRuleCatalog.OPERATION_READONLY.equals(
                                                        rule.operation()))
                        ? ViewRuleCatalog.OPERATION_READONLY
                        : ViewRuleCatalog.OPERATION_EDITABLE;
        boolean allowExport = winning.stream().allMatch(Rule::allowExport);
        return new Effect(
                visibility,
                maskRule,
                operation,
                allowExport,
                winning.stream().map(ViewRuleResolver::traceOf).toList(),
                overridden.stream().map(ViewRuleResolver::traceOf).toList());
    }

    /** 层级具体度：字段 3 &gt; 模块 2 &gt; 租户 1 &gt; 全局默认 0。 */
    static int tier(String layer) {
        return switch (layer == null ? "" : layer) {
            case ViewRuleCatalog.LAYER_FIELD -> 3;
            case ViewRuleCatalog.LAYER_MODULE -> 2;
            case ViewRuleCatalog.LAYER_TENANT -> 1;
            default -> 0;
        };
    }

    /** 可见性严格度：隐藏 3 &gt; 设盲 2 &gt; 脱敏 1 &gt; 明文 0。 */
    static int strictness(String visibility) {
        return switch (visibility == null ? "" : visibility) {
            case ViewRuleCatalog.VISIBILITY_HIDDEN -> 3;
            case ViewRuleCatalog.VISIBILITY_BLINDED -> 2;
            case ViewRuleCatalog.VISIBILITY_MASKED -> 1;
            default -> 0;
        };
    }

    private static Trace traceOf(Rule rule) {
        return new Trace(rule.id(), rule.code(), rule.name(), rule.layer());
    }
}
