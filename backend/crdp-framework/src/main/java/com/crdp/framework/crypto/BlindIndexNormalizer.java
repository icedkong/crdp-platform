package com.crdp.framework.crypto;

import java.util.Locale;

/**
 * 盲索引规范化（E03-T04，技术方案第 4 章"HMAC-SHA256 规范化后计算"）。细则规格未定，规则集中在
 * 本类，医院确认后如需调整只改这里；已入库的索引需按新规则重算（切换路径见技术方案第 4 章双列并存）。
 *
 * <p>规则：全角转半角 → 去空白与括号连字符 → 去 +86 国码前缀 → 大写化。写入侧与查询侧都经 {@link
 * EnvelopeCryptoService#blindIndex}，同一值的不同写法得到同一索引。
 */
public final class BlindIndexNormalizer {

    private BlindIndexNormalizer() {}

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = stripSeparators(toHalfWidth(value));
        if (normalized.startsWith("+86")) {
            normalized = normalized.substring(3);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    /** 全角空格转半角空格，全角 ASCII 区（！~）整体减 0xFEE0。 */
    private static String toHalfWidth(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '　') {
                builder.append(' ');
            } else if (c >= '！' && c <= '～') {
                builder.append((char) (c - 0xFEE0));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String stripSeparators(String value) {
        return value.replaceAll("[\\s()（）\\-]", "");
    }
}
