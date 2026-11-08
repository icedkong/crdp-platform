package com.crdp.server.platform;

import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import org.springframework.stereotype.Service;

/**
 * {@link TenantViewFieldGuard} 的视角规则驱动实现（E20-T03 / PLT-VIEW-010、011）：响应装配点（患者列表与 概览的姓名 /
 * 病案号、报告单原件附件、通话录音等）经它把视角效果落到具体值——前端不接收原值。
 *
 * <p>与效果预览共用 {@link ViewRuleResolver}（E20-T02 / PLT-VIEW-009「预览与实际执行一致」）： MASKED / PLAIN
 * 保留调用方传入的（已脱敏的）值——列表只输出脱敏值是平台铁律（AGENTS.md §5.3），视角规则的 PLAIN 不放宽明文； BLINDED 替换为「已设盲」占位； HIDDEN 给
 * null（字符串）/ true（结构字段置空判定）。非视角会话一律原样（医院端行为不变）。
 */
@Service
public class ViewRuleFieldGuard implements TenantViewFieldGuard {

    private final TenantViewRuleExecutor executor;

    public ViewRuleFieldGuard(TenantViewRuleExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String apply(String resource, String field, String value) {
        Effect effect = executor.fieldEffect(resource, field);
        if (effect == null) {
            return value;
        }
        return switch (effect.visibility()) {
            case ViewRuleCatalog.VISIBILITY_HIDDEN -> null;
            case ViewRuleCatalog.VISIBILITY_BLINDED -> BLINDED_TEXT;
            default -> value;
        };
    }

    @Override
    public boolean hidden(String resource, String field) {
        Effect effect = executor.fieldEffect(resource, field);
        return effect != null
                && (ViewRuleCatalog.VISIBILITY_HIDDEN.equals(effect.visibility())
                        || ViewRuleCatalog.VISIBILITY_BLINDED.equals(effect.visibility()));
    }
}
