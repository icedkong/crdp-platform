package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T10 UAT 自查缺陷 UAT-SELF-01 核对（单元）：补授脚本 V133 与医院 A 规格 §10 权限矩阵一致。
 *
 * <p>背景：UAT 脚本自查（`docs/testing/uat-remediation-hospital-a.md` §4）发现 V123 预置角色权限面缺口—— 随访员 /
 * 病区医生缺「上传文件」（`followup:scan:upload`，H-1 / H-2 / H-3 直接 40300）、数据录入员缺
 * 「导入向导」（`followup:patient:import`，J-1 40300）、科主任缺「批量指定责任医生」（`followup:task:assign`， E-5）。A 规格 §10
 * 角色模板「主要权限」列明确包含：病区医生「报告单添加与查看」、随访员「报告单添加与查看」、 数据录入员「导入」。
 *
 * <p>本测试锁定的口径：
 *
 * <ol>
 *   <li>V133 只做补授（INSERT ... SELECT ... WHERE NOT EXISTS 幂等，不 DELETE / UPDATE 既有授予）；
 *   <li>补授后四个预置角色的菜单并集（V123 ∪ V133）满足 §10 权限矩阵的上述缺口项；
 *   <li>敏感权限（明文 211 / 导出 212 / 删除 217，A 规格 §10「敏感权限」列）仍不预授；
 *   <li>补授行按「角色编码 + 菜单号」成对书写并指向既注册的平台菜单（231 / 353 / 224）。
 * </ol>
 *
 * <p>生产不执行种子（同 V123 口径）；脚本对每个租户库独立执行，无 A_* 角色的库自动空过。
 */
class APresetUatGrantSqlTest {

    private static final String PRESET = "db/tenant/seed/V123__hospital_a_ward_user_preset.sql";

    private static final String GRANT = "db/tenant/seed/V133__grant_a_report_import_assign.sql";

    /** 平台菜单号（plt_menu）：231 上传文件（V34）/ 353 导入向导（V74）/ 224 批量指定责任医生（V44）。 */
    private static final int SCAN_UPLOAD = 231;

    private static final int IMPORT_WIZARD = 353;

    private static final int TASK_ASSIGN = 224;

    /** 敏感权限菜单（A 规格 §10「敏感权限」列：明文 / 导出 / 删除不预授，V123 同口径）。 */
    private static final Set<Integer> SENSITIVE_MENU_IDS = Set.of(211, 212, 217);

    private static String grantSql;

    private static Map<String, Set<Integer>> grantTuples;

    /** V123 ∪ V133 的角色菜单并集（补授后口径）。 */
    private static Map<String, Set<Integer>> combinedMenus;

    @BeforeAll
    static void parse() {
        grantSql = read(GRANT);
        grantTuples = menuTuples(statement(grantSql, "INSERT INTO sys_role_menu"));
        combinedMenus = menuTuples(statement(read(PRESET), "INSERT INTO sys_role_menu"));
        for (Map.Entry<String, Set<Integer>> entry : grantTuples.entrySet()) {
            combinedMenus
                    .computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                    .addAll(entry.getValue());
        }
    }

    /** 脚本在位且只做幂等补授：INSERT ... SELECT + WHERE NOT EXISTS，不改不删既有行。 */
    @Test
    void grantScriptIsIdempotentAppendOnly() {
        assertTrue(grantSql.contains("INSERT INTO sys_role_menu"), "必须是 sys_role_menu 的 INSERT");
        assertTrue(grantSql.contains("WHERE NOT EXISTS"), "幂等守卫（NOT EXISTS）：重复执行零新增（与 V123 ② 同口径）");
        assertEquals(0, countOccurrences(grantSql, "DELETE FROM"), "种子只新增（AGENTS §5.6）");
        assertEquals(0, countOccurrences(grantSql, "UPDATE "), "种子只新增（AGENTS §5.6）");
    }

    /** 补授面（UAT-SELF-01）：四个缺口逐角色落位，不夹带其他授予。 */
    @Test
    void grantsCoverUatSelf01GapsExactly() {
        assertEquals(Set.of(231), grantTuples.get("A_WARD_DOCTOR"), "病区医生补授「上传文件」（§10 报告单添加与查看）");
        assertEquals(Set.of(231), grantTuples.get("A_FOLLOWUP_NURSE"), "随访员补授「上传文件」（§10 报告单添加与查看）");
        assertEquals(Set.of(353), grantTuples.get("A_DATA_CLERK"), "数据录入员补授「导入向导」（§10 导入）");
        assertEquals(
                Set.of(224),
                grantTuples.get("A_DIRECTOR"),
                "科主任补授「批量指定责任医生」（E-5，A-FU-009 / A-PAT-017）");
        assertEquals(
                Set.of("A_WARD_DOCTOR", "A_FOLLOWUP_NURSE", "A_DATA_CLERK", "A_DIRECTOR"),
                grantTuples.keySet(),
                "只补授四个 A 预置角色，不夹带其他角色");
    }

    /** 补授后（V123 ∪ V133）：§10 矩阵缺口项齐备，且敏感权限仍不预授。 */
    @Test
    void combinedGrantsSatisfySpecMatrixWithoutSensitive() {
        assertTrue(
                combinedMenus.get("A_WARD_DOCTOR").contains(SCAN_UPLOAD),
                "病区医生 §10「报告单添加与查看」→ followup:scan:upload");
        assertTrue(
                combinedMenus.get("A_FOLLOWUP_NURSE").contains(SCAN_UPLOAD),
                "随访员 §10「报告单添加与查看」→ followup:scan:upload");
        assertTrue(
                combinedMenus.get("A_DATA_CLERK").contains(IMPORT_WIZARD),
                "数据录入员 §10「导入」→ followup:patient:import");
        assertTrue(
                combinedMenus.get("A_DIRECTOR").contains(TASK_ASSIGN),
                "科主任批量指定 / 改派（E-5）→ followup:task:assign");
        for (Map.Entry<String, Set<Integer>> grant : combinedMenus.entrySet()) {
            for (int menu : grant.getValue()) {
                assertTrue(
                        !SENSITIVE_MENU_IDS.contains(menu),
                        grant.getKey() + " 不得预授敏感菜单 " + menu + "（§10 敏感权限按需分配）");
            }
        }
    }

    /** 补授行按「角色编码 + 菜单号」成对书写（与 V123 同形态，防指向不存在的菜单号）。 */
    @Test
    void grantedRowsAreCodeMenuPairs() {
        for (String pair :
                new String[] {
                    "'A_WARD_DOCTOR' AS code, " + SCAN_UPLOAD + " AS menu_id",
                    "'A_FOLLOWUP_NURSE', " + SCAN_UPLOAD,
                    "'A_DATA_CLERK', " + IMPORT_WIZARD,
                    "'A_DIRECTOR', " + TASK_ASSIGN
                }) {
            assertTrue(grantSql.contains(pair), "脚本缺少补授对：" + pair);
        }
    }

    // ---------------------------------------------------------------- 解析工具（与
    // APresetWardUserSqlTest 同款的最小实现，独立复制避免测试间耦合）

    private static String read(String path) {
        try (InputStream in =
                APresetUatGrantSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少种子脚本资源：" + path + "（E23-T10 补授脚本未落地？）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取种子脚本失败：" + path, e);
        }
    }

    private static String statement(String sql, String marker) {
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "脚本缺少语句：" + marker);
        int end = sql.indexOf(';', start);
        assertTrue(end > start, "语句未以分号收尾：" + marker);
        return sql.substring(start, end);
    }

    /** 解析「'CODE', menuId」元组（V123 / V133 的 UNION ALL 形态，含 AS 别名列）。 */
    private static Map<String, Set<Integer>> menuTuples(String insert) {
        Map<String, Set<Integer>> result = new HashMap<>();
        Pattern tuple =
                Pattern.compile("'([A-Z_]+)'(?:\\s+AS\\s+code)?,\\s*(\\d+)(?:\\s+AS\\s+menu_id)?");
        Matcher matcher = tuple.matcher(insert);
        while (matcher.find()) {
            result.computeIfAbsent(matcher.group(1), k -> new HashSet<>())
                    .add(Integer.parseInt(matcher.group(2)));
        }
        return result;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
