package com.crdp.framework.fieldpolicy;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;

/**
 * 平台租户视角的字段执行点（E20-T03 / PLT-VIEW-010、011）：视角会话（{@code TENANT_VIEW}）的响应装配处按 视角规则输出脱敏 / 设盲 /
 * 隐藏值，前端不接收原值；医院端会话一律原样返回（行为不变）。
 *
 * <p>效果口径与效果预览共用同一解析器（E20-T02 / PLT-VIEW-009「预览与实际执行一致」）：可见性四档——
 *
 * <ul>
 *   <li>{@code HIDDEN} 隐藏：字段不返回（字符串字段给 null，DTO 配 {@code @JsonInclude(NON_NULL)} 即整字段不出现；
 *       结构字段（附件清单等）由调用方给空集合）；
 *   <li>{@code BLINDED} 设盲：值替换为 {@link #BLINDED_TEXT} 占位，前端显示「已设盲」（PLT-VIEW-011）；
 *       结构字段的设盲等同隐藏（附件无占位形态）；
 *   <li>{@code MASKED} / {@code PLAIN}：保留调用方传入值——敏感字段列表只输出脱敏值是平台铁律（AGENTS.md §5.3，
 *       明文仅经专用接口且视角令牌禁用），故 PLAIN 不会放宽列表明文，执行不宽于预览。
 * </ul>
 *
 * <p>接口落在 framework 供 crdp-followup 的装配点注入；实现（视角规则驱动）在 crdp-server （{@code
 * ViewRuleFieldGuard}），纯单测用 {@link #passthrough()}。
 */
public interface TenantViewFieldGuard {

    /** 设盲占位文案（PLT-VIEW-011「页面显示已设盲」，design-system §15 简体中文口径）。 */
    String BLINDED_TEXT = "已设盲";

    /**
     * 字符串字段按视角规则输出：非视角会话原样返回；视角会话 HIDDEN → null、BLINDED → 占位、其余 → 原值。
     *
     * @param resource 资源对象（如 patient；目录见平台侧 ViewRuleCatalog）
     * @param field 字段码（资源内；与视角字段目录一致）
     * @param value 调用方产出的（通常已脱敏的）值
     */
    String apply(String resource, String field, String value);

    /** 字段或资源整体（{@code field} 为空）对视角会话是否隐藏：非视角会话一律 false。 附件 / 原件类结构字段（录音清单、报告单首附件）由调用方据此置空。 */
    boolean hidden(String resource, String field);

    /** 直通实现（纯单测 / 无视角规则的兜底）：非视角原样、视角隐藏不生效——真实效果由 crdp-server 实现提供。 */
    static TenantViewFieldGuard passthrough() {
        return new TenantViewFieldGuard() {
            @Override
            public String apply(String resource, String field, String value) {
                return value;
            }

            @Override
            public boolean hidden(String resource, String field) {
                return false;
            }
        };
    }

    /** 当前会话是否租户视角（装配点快速短路用）。 */
    static boolean viewActive() {
        return CurrentSession.current().map(SessionPrincipal::tenantView).orElse(false);
    }
}
