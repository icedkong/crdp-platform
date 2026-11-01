package com.crdp.system.brand;

import java.util.Arrays;
import java.util.Optional;

/**
 * E06-T11 / PLT-CFG-008：租户个性化可选的预设主题色（仅系统预设，规格「只能从系统预设色板中选择」）。
 *
 * <p>色板取自 docs/design/tokens.ts 的既有四色；按 OQ-23 定论，主色固定 #3A75FF， 租户只能换 Logo、名称与预设辅助色——因此默认项 BLUE
 * 即主色本身，其余为预设辅助色。 前端在 {@code frontend/packages/api} 维护同一份清单（THEME_COLOR_OPTIONS）。
 */
public enum BrandThemeColor {
    BLUE("BLUE", "#3A75FF", "平台蓝（默认）"),
    TEAL("TEAL", "#1DC29E", "青绿"),
    ORANGE("ORANGE", "#FA8C16", "暖橙"),
    RED("RED", "#FF4D4F", "朱红");

    private final String key;
    private final String hex;
    private final String label;

    BrandThemeColor(String key, String hex, String label) {
        this.key = key;
        this.hex = hex;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String hex() {
        return hex;
    }

    public String label() {
        return label;
    }

    /** 按 key 解析预设色；未知或空返回 empty（调用方按校验失败或未配置处理）。 */
    public static Optional<BrandThemeColor> ofKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(color -> color.key.equals(key.trim())).findFirst();
    }

    /** 未配置时的缺省色（主色）。 */
    public static BrandThemeColor defaults() {
        return BLUE;
    }
}
