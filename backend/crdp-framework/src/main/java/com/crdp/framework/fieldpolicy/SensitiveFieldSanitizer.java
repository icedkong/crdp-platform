package com.crdp.framework.fieldpolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏序列化（E07-T05 / A-PAT-010、C-PAT-012；tech-plan §5「实现」：返回前按字段策略脱敏或隐藏， 列表、详情、导出、站内信共用同一套）。
 *
 * <p>对动态结构（Map 行、导出列、站内信参数）应用生效策略：{@code HIDDEN} → 键不出现、 {@code MASKED} → {@link
 * SensitiveValueMasker} 掩码值、{@code VISIBLE} → 原值；目录外字段不归字段级权限管（{@link SensitiveFieldCatalog}）。
 * DTO（Jackson）路径走 {@link MaskedByPolicy} + {@link PolicyMaskedSerializer}，两条路径同一套算法与策略口径。 快照为
 * null（平台端会话兜底值已注入；此处为防御）时 fail closed 全部脱敏。
 *
 * <p>不修改源 Map（调用方可能是缓存的共享行），返回保持键序的副本。
 */
public final class SensitiveFieldSanitizer {

    private SensitiveFieldSanitizer() {}

    /**
     * 按当前账号生效策略产出可返回 / 可导出的字段集。
     *
     * @param policy 生效字段策略快照；null 视为「无覆盖」的平台默认（全部脱敏，fail closed）
     * @param resource 资源对象（如 patient）；目录外资源的字段原样返回
     * @param values 明文字段集；敏感字段键须与目录字段码一致
     */
    public static Map<String, Object> apply(
            FieldPolicySnapshot policy, String resource, Map<String, Object> values) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String decided =
                    (policy == null ? FieldPolicySnapshot.defaults() : policy)
                            .decide(resource, entry.getKey());
            switch (decided) {
                case SensitiveFieldCatalog.POLICY_HIDDEN -> {
                    // 隐藏：键整体不出现（不是空串，避免前端误渲染空占位）
                }
                case SensitiveFieldCatalog.POLICY_MASKED ->
                        sanitized.put(
                                entry.getKey(),
                                entry.getValue() == null
                                        ? null
                                        : SensitiveValueMasker.mask(
                                                entry.getKey(), String.valueOf(entry.getValue())));
                default -> sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }
}
