package com.crdp.framework.security;

import java.util.ArrayList;
import java.util.List;

/**
 * E04-T05 / PLT-AUTH-006：密码策略（平台下限，租户可在最小长度上加严）。
 *
 * <p>规格（platform-spec 第 7 章安全参数表）：长度至少 8 位（租户可加长）； 大写、小写、数字、特殊字符至少 3 类；不得包含账号。 历史密码（不得与最近 5
 * 次相同）需要比对哈希，由调用方（改密用例）负责，不在本类。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    /**
     * 返回不满足的策略条目（简体中文文案，供接口 message 与前端提示共用口径）。 全部满足时返回空列表。
     *
     * @param password 候选密码明文（只在这里出现，不落日志与审计）
     * @param username 当前账号（「不得包含账号」按忽略大小写的子串判断）
     * @param minLength 最小长度（平台下限与租户加严取大者）
     * @param minCategories 至少包含的字符类别数（1–4）
     */
    public static List<String> violations(
            String password, String username, int minLength, int minCategories) {
        List<String> found = new ArrayList<>();
        if (password == null || password.length() < minLength) {
            found.add("密码长度至少 " + minLength + " 位");
        }
        if (categories(password) < minCategories) {
            found.add("密码需包含大写字母、小写字母、数字、特殊字符中的至少 " + minCategories + " 类");
        }
        if (username != null
                && !username.isBlank()
                && password != null
                && password.toLowerCase(java.util.Locale.ROOT)
                        .contains(username.toLowerCase(java.util.Locale.ROOT))) {
            found.add("密码不能包含账号");
        }
        return found;
    }

    /** 四类字符的命中数：大写 / 小写 / 数字 / 特殊字符（非字母数字视为特殊字符）。 */
    public static int categories(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (Character.isUpperCase(ch)) {
                upper = true;
            } else if (Character.isLowerCase(ch)) {
                lower = true;
            } else if (Character.isDigit(ch)) {
                digit = true;
            } else {
                special = true;
            }
        }
        int count = 0;
        count += upper ? 1 : 0;
        count += lower ? 1 : 0;
        count += digit ? 1 : 0;
        count += special ? 1 : 0;
        return count;
    }
}
