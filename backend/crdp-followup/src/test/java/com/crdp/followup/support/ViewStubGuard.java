package com.crdp.followup.support;

import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import java.util.Set;

/**
 * E20-T07 测试夹具：按（资源, 字段）表模拟视角效果的 {@link TenantViewFieldGuard} 桩—— 视角会话（TENANT_VIEW）里设盲字段替换「已设盲」、
 * 隐藏字段返回 null 且 {@code hidden()=true}、其余保留传入值；非视角会话一律原样（与 crdp-server 的 ViewRuleFieldGuard 契约一致，
 * OQ-107④⑤）。
 *
 * <p>键格式 {@code 资源:字段}；{@code 资源:*} 表示资源整体（field 为 null 或任意字段都命中，如报告单原件）。
 */
public final class ViewStubGuard implements TenantViewFieldGuard {

    private final Set<String> blinded;

    private final Set<String> hidden;

    private ViewStubGuard(Set<String> blinded, Set<String> hidden) {
        this.blinded = blinded;
        this.hidden = hidden;
    }

    /** blinded / hidden 为（资源:字段）键集合；资源整体用 {@code 资源:*}。 */
    public static TenantViewFieldGuard of(Set<String> blinded, Set<String> hidden) {
        return new ViewStubGuard(Set.copyOf(blinded), Set.copyOf(hidden));
    }

    @Override
    public String apply(String resource, String field, String value) {
        if (!TenantViewFieldGuard.viewActive()) {
            return value;
        }
        if (hit(hidden, resource, field)) {
            return null;
        }
        if (hit(blinded, resource, field)) {
            return BLINDED_TEXT;
        }
        return value;
    }

    @Override
    public boolean hidden(String resource, String field) {
        // 结构字段（录音清单、首附件）的设盲等同隐藏（OQ-107⑤）
        return TenantViewFieldGuard.viewActive()
                && (hit(hidden, resource, field) || hit(blinded, resource, field));
    }

    private static boolean hit(Set<String> keys, String resource, String field) {
        return keys.contains(resource + ":" + field) || keys.contains(resource + ":*");
    }
}
