package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E04-T05 / PLT-AUTH-006：密码策略（平台下限）。 长度至少 8 位、大写 / 小写 / 数字 / 特殊字符至少 3 类、不得包含账号；
 * 租户可在平台下限之上加长最小长度（调用方取 max(平台下限, 租户配置)）。
 */
class PasswordPolicyTest {

    private static final int MIN_LENGTH = 8;
    private static final int MIN_CATEGORIES = 3;

    private static List<String> violations(String password, String username) {
        return PasswordPolicy.violations(password, username, MIN_LENGTH, MIN_CATEGORIES);
    }

    @Test
    void compliantPasswordHasNoViolations() {
        assertTrue(violations("Change@2026", "doctor.demo").isEmpty());
        assertTrue(violations("Abc12345", "doctor.demo").isEmpty());
    }

    @Test
    void tooShortPasswordIsRejected() {
        assertEquals(List.of("密码长度至少 8 位"), violations("Ab@12", "doctor.demo"));
    }

    @Test
    void tenantCanTightenTheMinimumLength() {
        // 租户把最小长度加严到 12：11 位且原本合规的密码被拒
        List<String> found =
                PasswordPolicy.violations("Ab@1234567z", "doctor.demo", 12, MIN_CATEGORIES);
        assertEquals(List.of("密码长度至少 12 位"), found);
    }

    @Test
    void fewerCategoriesThanRequiredIsRejected() {
        // 只有小写 + 数字两类
        assertEquals(
                List.of("密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类"), violations("abc12345", "doctor.demo"));
        // 只有数字一类
        assertEquals(
                List.of("密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类"), violations("12345678", "doctor.demo"));
    }

    @Test
    void passwordContainingTheUsernameIsRejected() {
        // 「不得包含账号」按忽略大小写判断子串
        List<String> found = violations("XyDoctor.Demo@26", "doctor.demo");
        assertTrue(found.contains("密码不能包含账号"), () -> "实际：" + found);
        // 账号的局部段不算（doctor.demo 的 doctor 出现在密码里不视为包含账号）
        assertTrue(violations("Xydoctor@99", "doctor.demo").isEmpty());
    }

    @Test
    void allViolationsAreReportedTogether() {
        List<String> found = violations("doc", "doctor.demo");
        assertEquals(List.of("密码长度至少 8 位", "密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类"), found);
    }

    @Test
    void blankOrNullPasswordOnlyReportsLengthAndCategories() {
        assertEquals(2, violations("", "doctor.demo").size());
        assertEquals(2, violations(null, "doctor.demo").size());
    }

    @Test
    void specialCharactersAreRecognizedAsAFourthCategory() {
        // 小写 + 数字 + 特殊字符
        assertTrue(violations("abc123!@#", "doctor.demo").isEmpty());
    }
}
