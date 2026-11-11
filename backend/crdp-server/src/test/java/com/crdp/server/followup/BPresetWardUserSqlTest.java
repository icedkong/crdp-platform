package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T18 核对（单元）：预置脚本 V136 与医院 B 规格逐项一致（tasks.csv 验收：B 用户按病区分配）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md——第 14 章「医院 B 配置差异」（用户、角色取值：用户账号不复制，B 约 30
 * 名用户按病区分配；术者、病区 / 中心只复制结构、不复制取值，由 B 租户管理员自行维护）+ B 沿用的第 1–13 章：§6 病区与数据隔离（A-WARD-001
 * 病区字典关键字段、A-WARD-002 一个医生可负责多个病区、A-WARD-003 角色病区范围三选项、A-WARD-007 租户管理员全院）、§10 建议角色模板（病区医生 / 随访员 /
 * 科主任 / 数据录入员的病区范围与主要权限矩阵）。比对对象是随包发布的种子脚本
 * db/tenant/seed/V136__hospital_b_ward_user_preset.sql（local / dev / IT；生产不执行种子，正式 B
 * 租户由管理员按交接单录入或批量导入真实用户，AGENTS §5.5 真实数据不入库）。不依赖数据库，CI 与本地必跑；落库后的同一口径由 {@link
 * BPresetWardUserVerifyIT}（门控 CRDP_IT_MYSQL_PORT）以 demob 作 B 化身对真实 MySQL 再核一遍。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>角色（§10 经第 14 章沿用）：四个业务角色复制为自定义角色（CUSTOM），名称与规格一致；内置租户管理员随 V100 不重建；
 *   <li>数据范围（A-WARD-003）：病区医生 / 随访员 / 数据录入员 = 本人负责病区（WARD），科主任 = 全部病区（ALL）；
 *   <li>菜单授予（§10 主要权限矩阵）：逐角色核对关键菜单；敏感项（211 明文 / 212 导出 / 217 删除）不预授；
 *   <li>病区字典（A-WARD-001 经第 14 章沿用）：编码 / 名称 / 类型（病区与中心两类都有）/ 别名 / 排序 / 状态齐全，取值为 B 自有（第 14 章「不复制」行）；
 *   <li>用户（第 14 章「约 30 名用户按病区分配」）：恰好 30 名、账号与工号唯一、姓名全部带「（B 示例）」虚构标记、口令为 PENDING_DEMO_HASH 占位（由
 *       DemoPasswordInitializer 在 local / dev 启动时替换）；
 *   <li>负责病区分配（A-WARD-002）：WARD 范围用户每人至少一个病区、科主任（ALL）不配病区清单、至少一名医生负责两个病区（多病区并集）、每个病区都有负责人；
 *   <li>默认随访负责人（A-WARD-001 / A-FU-009）：每个病区 / 中心的默认负责人是本院随访员且负责本病区；
 *   <li>与 A 预置（V123）同库共存（OQ-93 ② 同脚本口径）：角色码 / 病区码 / 账号三个命名空间互不相交，演示库内两套预置并存不串。
 * </ul>
 */
class BPresetWardUserSqlTest {

    private static final String PRESET = "db/tenant/seed/V136__hospital_b_ward_user_preset.sql";

    /** A 预置脚本（V123）：共存核对用（demoa / demob 都带两套预置，OQ-93 ② / OQ-128 ③ 口径）。 */
    private static final String A_PRESET = "db/tenant/seed/V123__hospital_a_ward_user_preset.sql";

    private static final Set<String> ROLE_CODES =
            Set.of("B_WARD_DOCTOR", "B_FOLLOWUP_NURSE", "B_DIRECTOR", "B_DATA_CLERK");

    /** 敏感权限菜单（A 规格 §10「敏感权限」列：明文 / 导出 / 删除不预授，租户管理员按需分配）。 */
    private static final Set<Integer> SENSITIVE_MENU_IDS = Set.of(211, 212, 217);

    private static final int WORKBENCH = 10;
    private static final int FOLLOWUP_DIR = 20;
    private static final int PATIENT_LIST = 21;
    private static final int PATIENT_CREATE = 213;
    private static final int PATIENT_EDIT = 214;
    private static final int PATIENT_DETAIL = 215;
    private static final int PATIENT_TRANSFER = 216;
    private static final int TASK_LIST = 22;
    private static final int REPORT = 23;
    private static final int PLAN_LIST = 24;
    private static final int PLAN_PUBLISH = 220;
    private static final int PLAN_ENROLL = 221;
    private static final int RECORD_EDIT = 222;
    private static final int BATCH_ASSIGN = 224;
    private static final int BATCH_ENROLL_PAGE = 225;

    private static String sql;
    private static String aPreset;
    private static Map<String, Map<String, String>> roles;
    private static Map<String, Set<Integer>> roleMenus;
    private static Map<String, String> roleScopes;
    private static Map<String, Map<String, String>> wards;
    private static Map<String, String> defaultOwners;
    private static Map<String, Map<String, String>> users;
    private static Map<String, String> userRoles;
    private static Map<String, List<String>> userWards;

    @BeforeAll
    static void parse() {
        sql = read(PRESET);
        aPreset = read(A_PRESET);
        roles = roleRows(sql);
        roleMenus = menuTuples(statement(sql, "INSERT INTO sys_role_menu"));
        roleScopes = scopeTuples(statement(sql, "INSERT INTO sys_role_scope"));
        wards = wardRows(statement(sql, "INSERT INTO ward ("));
        defaultOwners = defaultOwnerTuples(sql);
        users = userTuples(statement(sql, "INSERT INTO sys_user ("));
        userRoles = userRoleTuples(statement(sql, "INSERT INTO sys_user_role"));
        userWards = userWardTuples(statement(sql, "INSERT INTO sys_user_ward"));
    }

    /** 角色（§10 建议角色模板经第 14 章沿用）：复制为自定义角色，名称与规格一致；内置角色不重建。 */
    @Test
    void rolesMatchSpecMatrix() {
        assertEquals(
                Set.of("病区医生", "随访员", "科主任", "数据录入员"),
                roles.values().stream()
                        .map(r -> r.get("name"))
                        .collect(java.util.stream.Collectors.toSet()),
                "四个业务角色名称与 §10 一致（B 沿用第 1–13 章）");
        for (String code : ROLE_CODES) {
            Map<String, String> role = roles.get(code);
            assertNotNull(role, "缺少角色 " + code);
            assertEquals("0", role.get("built_in"), code + " 不是内置角色");
            assertEquals("ENABLED", role.get("status"), code + " 启用");
            assertEquals("CUSTOM", role.get("role_type"), code + " 复制为自定义角色（PLT-ORG-005）");
            assertEquals("followup", role.get("module_code"), code + " 归随访模块");
        }
        assertFalse(roles.containsKey("TENANT_ADMIN"), "租户管理员随 V100 内置，不重建");
    }

    /** 数据范围（A-WARD-003 / §10「病区范围」列）：三角色本人负责病区，科主任全部病区。 */
    @Test
    void roleScopesMatchWardMatrix() {
        assertEquals("WARD", roleScopes.get("B_WARD_DOCTOR"), "病区医生 = 本人负责病区");
        assertEquals("WARD", roleScopes.get("B_FOLLOWUP_NURSE"), "随访员 = 本人负责病区");
        assertEquals("WARD", roleScopes.get("B_DATA_CLERK"), "数据录入员 = 本人负责病区");
        assertEquals("ALL", roleScopes.get("B_DIRECTOR"), "科主任 = 全部病区");
    }

    /** 菜单授予（§10 主要权限矩阵）：逐角色核对关键权限；敏感项不预授；每角色都有首页与随访目录。 */
    @Test
    void menuGrantsMatchPermissionsMatrix() {
        Set<Integer> doctor = roleMenus.get("B_WARD_DOCTOR");
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_CREATE,
                    PATIENT_EDIT,
                    PATIENT_DETAIL,
                    TASK_LIST,
                    RECORD_EDIT,
                    REPORT,
                    PLAN_ENROLL,
                    BATCH_ASSIGN,
                    BATCH_ENROLL_PAGE
                }) {
            assertTrue(doctor.contains(menu), "病区医生应含菜单 " + menu + "：" + doctor);
        }
        for (int menu : new int[] {WORKBENCH, FOLLOWUP_DIR, TASK_LIST, RECORD_EDIT, REPORT}) {
            assertTrue(roleMenus.get("B_FOLLOWUP_NURSE").contains(menu), "随访员应含菜单 " + menu);
        }
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_DETAIL,
                    TASK_LIST,
                    REPORT,
                    PLAN_LIST,
                    PATIENT_TRANSFER,
                    PLAN_PUBLISH,
                    PLAN_ENROLL
                }) {
            assertTrue(roleMenus.get("B_DIRECTOR").contains(menu), "科主任应含菜单 " + menu);
        }
        for (int menu :
                new int[] {WORKBENCH, FOLLOWUP_DIR, PATIENT_LIST, PATIENT_CREATE, PATIENT_EDIT}) {
            assertTrue(roleMenus.get("B_DATA_CLERK").contains(menu), "数据录入员应含菜单 " + menu);
        }
        for (Map.Entry<String, Set<Integer>> grant : roleMenus.entrySet()) {
            for (int menu : grant.getValue()) {
                assertFalse(
                        SENSITIVE_MENU_IDS.contains(menu),
                        grant.getKey() + " 不得预授敏感菜单 " + menu + "（§10 敏感权限按需分配）");
            }
        }
    }

    /** 病区字典（A-WARD-001 关键字段全集）：类型两类、别名、排序、状态；取值为 B 自有虚构值（第 14 章「不复制」）。 */
    @Test
    void wardsMatchDictionarySpec() {
        assertEquals(5, wards.size(), "预置 3 个病区 + 2 个中心：" + wards.keySet());
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> ward : wards.entrySet()) {
            Map<String, String> row = ward.getValue();
            assertTrue(ward.getKey().startsWith("W-B-"), "病区编码前缀：" + ward.getKey());
            assertEquals("ENABLED", row.get("status"), ward.getKey() + " 状态");
            assertTrue(
                    row.get("name").endsWith("（B 示例）"),
                    ward.getKey() + " 名称带 B 院虚构标记（AGENTS §5.5）：" + row.get("name"));
            String alias = row.get("alias");
            assertFalse(alias == null || alias.isBlank(), ward.getKey() + " 别名（历史写法映射）必填");
            assertTrue(alias.contains(";"), ward.getKey() + " 别名多值「;」分隔：" + alias);
            types.add(row.get("ward_type"));
        }
        assertEquals(Set.of("WARD", "CENTER"), types, "病区与中心两类都要有（A-WARD-001）");
        Set<Integer> sorts = new LinkedHashSet<>();
        for (Map<String, String> row : wards.values()) {
            assertTrue(sorts.add(Integer.parseInt(row.get("sort_no"))), "病区排序号唯一");
        }
    }

    /** 用户（第 14 章「B 约 30 名用户按病区分配」）：恰好 30 名、账号 / 工号唯一、全部虚构、占位口令。 */
    @Test
    void usersAreThirtyFictionalAccounts() {
        assertEquals(30, users.size(), "预置用户数（第 14 章「约 30 名」）：" + users.size());
        Set<String> staffNos = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> user : users.entrySet()) {
            assertTrue(user.getKey().startsWith("b."), "账号前缀：" + user.getKey());
            assertTrue(
                    user.getValue().get("displayName").endsWith("（B 示例）"),
                    user.getKey() + " 姓名带 B 院虚构标记：" + user.getValue().get("displayName"));
            assertTrue(
                    staffNos.add(user.getValue().get("staffNo")),
                    "工号唯一：" + user.getValue().get("staffNo"));
            assertTrue(
                    user.getValue().get("staffNo").startsWith("DEMO-B-"),
                    "工号虚构前缀（§17）：" + user.getValue().get("staffNo"));
        }
        String insert = statement(sql, "INSERT INTO sys_user");
        assertTrue(
                insert.contains("'PENDING_DEMO_HASH'"),
                "口令为占位哈希（DemoPasswordInitializer 启动时替换，不落明文）");
        assertTrue(insert.contains("'ENABLED'"), "预置用户启用");
    }

    /** 用户 - 角色分配：每人恰好一个业务角色，角色都在四个预置角色内；角色矩阵 16 / 8 / 4 / 2。 */
    @Test
    void userRolesAssignOneBusinessRoleEach() {
        assertEquals(30, userRoles.size(), "每个用户一条角色分配");
        assertEquals(users.keySet(), userRoles.keySet(), "分配覆盖全部 30 名用户");
        Map<String, Long> expected =
                Map.of(
                        "B_WARD_DOCTOR", 16L,
                        "B_FOLLOWUP_NURSE", 8L,
                        "B_DIRECTOR", 4L,
                        "B_DATA_CLERK", 2L);
        for (Map.Entry<String, Long> roleCount : expected.entrySet()) {
            assertEquals(
                    roleCount.getValue(),
                    userRoles.values().stream().filter(roleCount.getKey()::equals).count(),
                    "角色 " + roleCount.getKey() + " 人数（§10 矩阵，与 A 预置同构）");
        }
        for (Map.Entry<String, String> assignment : userRoles.entrySet()) {
            assertTrue(
                    ROLE_CODES.contains(assignment.getValue()),
                    assignment.getKey() + " 的角色 " + assignment.getValue() + " 不在预置角色内");
        }
    }

    /** 负责病区分配（A-WARD-002）：WARD 范围用户每人至少一个病区，科主任不配清单，多病区负责存在。 */
    @Test
    void wardAssignmentCoversWardScopedUsers() {
        for (Map.Entry<String, String> assignment : userRoles.entrySet()) {
            List<String> wardsOfUser = userWards.get(assignment.getKey());
            if ("B_DIRECTOR".equals(assignment.getValue())) {
                assertTrue(
                        wardsOfUser == null || wardsOfUser.isEmpty(),
                        assignment.getKey() + " 全部病区（ALL）不配病区清单");
            } else {
                assertNotNull(wardsOfUser, assignment.getKey() + "（WARD 范围）须分配负责病区");
                assertFalse(wardsOfUser.isEmpty(), assignment.getKey() + " 空范围看不到患者（§6 注）");
                for (String wardCode : wardsOfUser) {
                    assertTrue(
                            wards.containsKey(wardCode),
                            assignment.getKey() + " 引用的病区 " + wardCode + " 不在预置字典内");
                }
            }
        }
        assertTrue(
                userWards.values().stream().anyMatch(list -> list.size() >= 2),
                "至少一名医生负责多个病区（A-WARD-002「一个医生可负责多个病区」）");
        for (String wardCode : wards.keySet()) {
            assertTrue(
                    userWards.values().stream().flatMap(List::stream).anyMatch(wardCode::equals),
                    wardCode + " 至少有一名负责用户");
        }
        Set<String> userWardPairs = new LinkedHashSet<>();
        userWards.forEach(
                (username, wardCodes) ->
                        wardCodes.forEach(code -> userWardPairs.add(username + "@" + code)));
        assertEquals(
                userWards.values().stream().mapToInt(List::size).sum(),
                userWardPairs.size(),
                "同一用户对同一病区不重复分配");
    }

    /** 默认随访负责人（A-WARD-001）：每个病区 / 中心都配，负责人是负责本病区的随访员。 */
    @Test
    void defaultOwnersAreNurseLeadsOfSameWard() {
        assertEquals(wards.keySet(), defaultOwners.keySet(), "每个病区 / 中心都配默认随访负责人");
        for (Map.Entry<String, String> owner : defaultOwners.entrySet()) {
            String username = owner.getValue();
            assertEquals(
                    "B_FOLLOWUP_NURSE",
                    userRoles.get(username),
                    owner.getKey() + " 默认负责人 " + username + " 应为随访员");
            assertTrue(
                    userWards.getOrDefault(username, List.of()).contains(owner.getKey()),
                    username + " 负责本病区 " + owner.getKey());
        }
    }

    /** 与 A 预置（V123）同库共存：三个命名空间互不相交，演示 / IT 库两套预置并存不串（OQ-93 ②）。 */
    @Test
    void coexistsWithAPresetInSharedSeedDatabases() {
        Set<String> aRoleCodes = roleRows(aPreset).keySet();
        for (String code : ROLE_CODES) {
            assertFalse(aRoleCodes.contains(code), "角色码与 A 预置冲突：" + code);
        }
        Set<String> aWardCodes = wardRows(statement(aPreset, "INSERT INTO ward (")).keySet();
        for (String code : wards.keySet()) {
            assertFalse(aWardCodes.contains(code), "病区码与 A 预置冲突：" + code);
        }
        Set<String> aUsernames = userTuples(statement(aPreset, "INSERT INTO sys_user (")).keySet();
        for (String username : users.keySet()) {
            assertFalse(aUsernames.contains(username), "账号与 A 预置冲突：" + username);
        }
    }

    /** 随包口径：种子只进 local / dev / IT，脚本不得引用其他租户专属数据或真实身份。 */
    @Test
    void presetStaysFictionalAndSeedScoped() {
        assertFalse(sql.contains("demoa") || sql.contains("demob"), "种子脚本对每个租户库执行，不写租户分支");
        assertFalse(sql.matches("(?s).*1[3-9]\\d{9}.*"), "不得出现 11 位手机号样值（OQ-92 敏感口径）");
        assertTrue(sql.contains("-- E23-T18"), "脚本头注名任务编号（AGENTS §8 提交与审计口径）");
    }

    // ---------------------------------------------------------------- 解析

    /** 取包含 marker 的整条语句。按「行尾分号」切分：病区别名值里含「;」（A-WARD-006 历史写法分隔符）， 不能按裸分号切。 */
    private static String statement(String whole, String marker) {
        for (String part : whole.split(";\\s*\\n")) {
            if (part.contains(marker)) {
                return part;
            }
        }
        throw new IllegalStateException("脚本缺少语句：" + marker);
    }

    private static Map<String, Map<String, String>> roleRows(String whole) {
        // INSERT INTO sys_role (...) VALUES ('CODE', '名称', 0, 'ENABLED', 'CUSTOM', 'followup',
        // '说明'), ...
        Pattern pattern =
                Pattern.compile(
                        "\\('([A-Z_]+)', '([^']+)', (\\d+), '([A-Z]+)', '([A-Z_]+)', '([a-z]+)',"
                                + " '([^']*)'\\)");
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(statement(whole, "INSERT INTO sys_role"));
        while (matcher.find()) {
            Map<String, String> row = new HashMap<>();
            row.put("name", matcher.group(2));
            row.put("built_in", matcher.group(3));
            row.put("status", matcher.group(4));
            row.put("role_type", matcher.group(5));
            row.put("module_code", matcher.group(6));
            rows.put(matcher.group(1), row);
        }
        return rows;
    }

    private static Map<String, Set<Integer>> menuTuples(String block) {
        // SELECT 'ROLE' AS code, 10 AS menu_id / UNION ALL SELECT 'ROLE', 10
        return intTuples(block);
    }

    private static Map<String, String> scopeTuples(String block) {
        Pattern pattern =
                Pattern.compile("SELECT '([A-Z_]+)'(?: AS code)?, '([A-Z_]+)'(?: AS scope_type)?");
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.put(matcher.group(1), matcher.group(2));
        }
        return rows;
    }

    private static Map<String, Set<Integer>> intTuples(String block) {
        Pattern pattern =
                Pattern.compile("SELECT '([A-Z_]+)'(?: AS code)?, (\\d+)(?: AS menu_id)?");
        Map<String, Set<Integer>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(Integer.parseInt(matcher.group(2)));
        }
        return rows;
    }

    private static Map<String, Map<String, String>> wardRows(String block) {
        // ('W-B-CVS1', '心外一病区（B 示例）', 'ENABLED', 'WARD', '外一病区;心外1', 10)
        Pattern pattern =
                Pattern.compile(
                        "\\('(W-B-[A-Z0-9]+)', '([^']+)', '([A-Z]+)', '([A-Z]+)', '([^']+)', (\\d+)\\)");
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            Map<String, String> row = new HashMap<>();
            row.put("name", matcher.group(2));
            row.put("status", matcher.group(3));
            row.put("ward_type", matcher.group(4));
            row.put("alias", matcher.group(5));
            row.put("sort_no", matcher.group(6));
            rows.put(matcher.group(1), row);
        }
        return rows;
    }

    private static Map<String, String> defaultOwnerTuples(String whole) {
        Pattern pattern =
                Pattern.compile(
                        "WHERE u\\.username = '([a-z0-9.]+)'\\) WHERE code = '(W-B-[A-Z0-9]+)'");
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(whole);
        while (matcher.find()) {
            rows.put(matcher.group(2), matcher.group(1));
        }
        return rows;
    }

    private static Map<String, Map<String, String>> userTuples(String block) {
        // SELECT 'b.cvs1.dir' AS username, '华主任（B 示例）' AS display_name, 'DEMO-B-U01' AS staff_no
        Pattern pattern =
                Pattern.compile(
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '([^']+)'(?: AS display_name)?,"
                                + " '(DEMO-B-U\\d+)'(?: AS staff_no)?");
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            Map<String, String> row = new HashMap<>();
            row.put("displayName", matcher.group(2));
            row.put("staffNo", matcher.group(3));
            rows.put(matcher.group(1), row);
        }
        return rows;
    }

    private static Map<String, String> userRoleTuples(String block) {
        Pattern pattern =
                Pattern.compile(
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '(B_[A-Z_]+)'(?: AS role_code)?");
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.put(matcher.group(1), matcher.group(2));
        }
        return rows;
    }

    private static Map<String, List<String>> userWardTuples(String block) {
        Pattern pattern =
                Pattern.compile(
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '(W-B-[A-Z0-9]+)'(?: AS ward_code)?");
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(matcher.group(2));
        }
        return rows;
    }

    private static String read(String path) {
        try (InputStream in =
                BPresetWardUserSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "找不到脚本 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读脚本失败 " + path, e);
        }
    }
}
