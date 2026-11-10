package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T09 UAT 自查缺陷 UAT-SELF-04 核对（单元）：「通知设置」菜单补授脚本 V134 与根因口径一致。
 *
 * <p>背景：UAT 脚本自查（`docs/testing/uat-remediation-hospital-a-fullstack1.md` §4）发现全新租户库**没有任何角色**
 * 持有「通知设置」菜单（plt_menu 36，`followup:notice:config`）——followup V22 把它授予 TENANT_ADMIN，但全新库 的种子 V100（创建
 * TENANT_ADMIN 角色）按版本号排序在 V22 之后执行，V22 的 INSERT…SELECT 命中 0 行（与 base V7 / V10 的授予在全新库落空、由种子 V102 /
 * V105 兜底是同一类问题）。后果：G-3（P1，A-NTF-001）任何角色（含租户 管理员）打开通知设置 40300，逐事件规则配置页完全不可用。
 *
 * <p>本测试锁定的口径：
 *
 * <ol>
 *   <li>V134 只做补授（INSERT ... SELECT ... WHERE NOT EXISTS 幂等，不 DELETE / UPDATE 既有授予）；
 *   <li>补授面恰为「TENANT_ADMIN ← 36」一对（A 规格 §10 租户管理员「主要权限」列明确含通知设置）；
 *   <li>补授条件限定内置角色（built_in = 1），不误授同名自定义角色；
 *   <li>菜单 36 是非敏感菜单（不在 §10 敏感权限面：明文 / 导出 / 删除 / 下载）。
 * </ol>
 *
 * <p>生产不执行种子（同 V102 / V105 口径）：真实租户的权限由开通向导 + 配置模板（sys_role_menu 随模板导入）落地； 脚本对每个租户库独立执行，幂等可重放。
 */
class NoticeMenuGrantSqlTest {

    private static final String GRANT = "db/tenant/seed/V134__grant_notice_menu.sql";

    /** 通知设置菜单（plt_menu 36，平台库 V32，followup:notice:config）。 */
    private static final int NOTICE_MENU = 36;

    /** 敏感权限菜单（A 规格 §10「敏感权限」列：明文 211 / 导出 212 / 删除 217 / 下载 232——按需分配，不预授）。 */
    private static final int[] SENSITIVE_MENU_IDS = {211, 212, 217, 232};

    private static String grantSql;

    private static String statement;

    @BeforeAll
    static void parse() {
        grantSql = read(GRANT);
        int start = grantSql.indexOf("INSERT INTO sys_role_menu");
        assertTrue(start >= 0, "必须是 sys_role_menu 的 INSERT");
        int end = grantSql.indexOf(';', start);
        assertTrue(end > start, "语句未以分号收尾");
        statement = grantSql.substring(start, end);
    }

    /** 脚本在位且只做幂等补授：INSERT ... SELECT + NOT EXISTS 守卫，不改不删既有行（AGENTS §5.6）。 */
    @Test
    void grantScriptIsIdempotentAppendOnly() {
        assertTrue(grantSql.contains("INSERT INTO sys_role_menu"), "必须是 sys_role_menu 的 INSERT");
        assertTrue(grantSql.contains("NOT EXISTS"), "幂等守卫（NOT EXISTS）：老库已由 V22 命中，重复执行零新增");
        assertEquals(0, countOccurrences("DELETE FROM"), "种子只新增（AGENTS §5.6）");
        assertEquals(0, countOccurrences("UPDATE "), "种子只新增（AGENTS §5.6）");
    }

    /** 补授面（UAT-SELF-04）：恰为「TENANT_ADMIN ← 36」一对，成对书写防指向错误菜单。 */
    @Test
    void grantsNoticeMenuToTenantAdminExactly() {
        assertTrue(
                statement.contains("'TENANT_ADMIN' AS code, " + NOTICE_MENU + " AS menu_id"),
                "补授对必须成对书写：'TENANT_ADMIN' AS code, 36 AS menu_id");
        assertEquals(
                1, countOccurrences(String.valueOf(NOTICE_MENU)), "语句体中菜单号 36 只出现一次（不夹带其他菜单授予）");
        for (int sensitive : SENSITIVE_MENU_IDS) {
            assertTrue(
                    !statement.contains(String.valueOf(sensitive)),
                    "不得借机预授敏感菜单 " + sensitive + "（§10 敏感权限按需分配）");
        }
    }

    /** 补授限定内置角色（built_in = 1）：租户自建的同名自定义角色不因本脚本获得权限。 */
    @Test
    void grantTargetsBuiltInRoleOnly() {
        assertTrue(statement.contains("built_in = 1"), "限定 built_in = 1（与 V105 机构设置菜单兜底同口径）");
    }

    /** 脚本注明根因与依据（全新库 V22 先于 V100 建角色落空；A 规格 §10 租户管理员含通知设置）。 */
    @Test
    void grantDocumentsRootCauseAndSpecBasis() {
        assertTrue(grantSql.contains("§10"), "注明 A 规格 §10 依据（租户管理员主要权限含通知设置）");
        assertTrue(
                grantSql.contains("V22") && grantSql.contains("V100"),
                "注明根因：followup V22 的授予在全新库先于种子 V100 建角色执行而落空");
    }

    // ---------------------------------------------------------------- 解析工具（APresetUatGrantSqlTest
    // 同款最小实现）

    private static String read(String path) {
        try (InputStream in =
                NoticeMenuGrantSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少种子脚本资源：" + path + "（E23-T09 补授脚本未落地？）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取种子脚本失败：" + path, e);
        }
    }

    private int countOccurrences(String needle) {
        int count = 0;
        int index = 0;
        while ((index = statement.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
