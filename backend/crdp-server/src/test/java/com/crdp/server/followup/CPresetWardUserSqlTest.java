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
 * E23-T22 核对（单元）：预置脚本 V137 与医院 C 规格逐项一致（tasks.csv 验收：用户按科室病区分配）。
 *
 * <p>基准是 docs/requirements/hospital-c-spec.md §7 科室病区隔离（C-WARD-001 科室沿用平台科室树 + 病区 / 中心独立字典；
 * C-WARD-002 患者归属科室 + 病区；C-WARD-003 医生、随访员、护士可负责多个科室或病区，沿用 A-WARD-002；C-WARD-004 角色数据范围在 A-WARD-003
 * 基础上增加「本人负责科室」；§7 业务规则「负责某科室即可见归属该科室的全部患者……负责多个科室或病区时取并集」）+ §10 建议角色模板（医生 / 随访员 / 护士 /
 * 科主任的数据范围与主要权限矩阵、敏感权限列）。比对对象是随包发布的种子脚本 db/tenant/seed/V137__hospital_c_ward_user_preset.sql（local
 * / dev / IT；生产不执行种子，正式 C 租户由管理员按交接单录入或批量导入真实 用户，AGENTS §5.5 真实数据不入库）。不依赖数据库，CI 与本地必跑；落库后的同一口径由
 * {@link CPresetWardUserVerifyIT}（门控 CRDP_IT_MYSQL_PORT）以 demob 作 C 化身对真实 MySQL 再核一遍。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>角色（§10 建议角色模板）：医生 / 随访员 / 护士 / 科主任四个业务角色复制为自定义角色（CUSTOM），名称与规格一致；内置租户管理员随 V100 不重建；
 *   <li>数据范围（§10「数据范围」列 + C-WARD-003/004）：医生 / 随访员 / 护士 = 本人负责病区（WARD）叠加本人负责科室
 *       （RESPONSIBLE_DEPT，C-WARD-003「可负责多个科室或病区」的两个分支都生效），科主任 = 本人负责科室（RESPONSIBLE_DEPT）；
 *   <li>菜单授予（§10「主要权限」矩阵）：逐角色核对关键菜单；敏感项（211 明文 / 212 导出 / 217 删除患者 / 229 修改已提交 / 230 删除记录 / 232
 *       下载）不预授（229 对医生的「默认分配」须平台先放开 PLT-SCOPE-004，由租户管理员配置，见 runbook）；
 *   <li>科室与病区字典（C-WARD-001）：科室沿用平台科室树结构（sys_dept，C 自有虚构取值）、病区 / 中心独立字典（3 病区 + 2 中心，类型 / 别名 / 排序 /
 *       状态齐全）；
 *   <li>用户：27 名虚构账号（14 医生 + 6 随访员 + 4 护士 + 3 科主任）、账号与工号唯一、姓名全部带「（C 示例）」虚构标记、口令为 PENDING_DEMO_HASH
 *       占位（由 DemoPasswordInitializer 在 local / dev 启动时替换）；
 *   <li>负责范围分配（C-WARD-003 / A-WARD-002）：WARD 分支用户每人至少一个病区、至少一名医生负责两个病区（多病区并集）、科主任按科室分配
 *       且不配病区清单、至少一名科主任负责两个科室（多科室并集）、至少一名医生同时负责病区与科室（科室病区取并集）、每个病区都有负责人；
 *   <li>默认随访负责人（C-WARD-001 默认责任随访人 / A-FU-009 沿用）：每个病区 / 中心的默认负责人是本院随访员且负责本病区；
 *   <li>与 A / B 预置（V123 / V136）同库共存（OQ-93 ② 同脚本口径）：角色码 / 病区码 / 科室码 / 账号四个命名空间互不相交。
 * </ul>
 */
class CPresetWardUserSqlTest {

    private static final String PRESET = "db/tenant/seed/V137__hospital_c_ward_user_preset.sql";

    /** A / B 预置脚本（V123 / V136）：共存核对用（demoa / demob 都带三套预置，OQ-93 ② 口径）。 */
    private static final String A_PRESET = "db/tenant/seed/V123__hospital_a_ward_user_preset.sql";

    private static final String B_PRESET = "db/tenant/seed/V136__hospital_b_ward_user_preset.sql";

    private static final Set<String> ROLE_CODES =
            Set.of("C_DOCTOR", "C_FOLLOWUP_STAFF", "C_NURSE", "C_DIRECTOR");

    /**
     * 敏感权限菜单（C 规格 §10「敏感权限」列与权限点表：明文 / 导出 / 删除患者 / 修改已提交 / 删除记录 / 下载； 229/230 登记
     * plt_sensitive_perm，平台按租户放开后才可分配——PLT-SCOPE-004，见平台库 V72）。
     */
    private static final Set<Integer> SENSITIVE_MENU_IDS = Set.of(211, 212, 217, 229, 230, 232);

    private static final int WORKBENCH = 10;
    private static final int FOLLOWUP_DIR = 20;
    private static final int PATIENT_LIST = 21;
    private static final int PATIENT_CREATE = 213;
    private static final int PATIENT_EDIT = 214;
    private static final int PATIENT_DETAIL = 215;
    private static final int PATIENT_TRANSFER = 216;
    private static final int TASK_LIST = 22;
    private static final int REPORT = 23;
    private static final int SCAN_UPLOAD = 231;
    private static final int PLAN_LIST = 24;
    private static final int PLAN_PUBLISH = 220;
    private static final int PLAN_ENROLL = 221;
    private static final int RECORD_EDIT = 222;
    private static final int RECORD_EDIT_ROUTE = 223;
    private static final int TASK_ASSIGN = 224;
    private static final int IMPLANT_EDIT = 228;
    private static final int DASHBOARD_VIEW = 234;

    private static String sql;
    private static String aPreset;
    private static String bPreset;
    private static Map<String, Map<String, String>> roles;
    private static Map<String, Set<Integer>> roleMenus;
    private static Map<String, Set<String>> roleScopes;
    private static Map<String, Map<String, String>> wards;
    private static Map<String, Map<String, String>> depts;
    private static Map<String, String> defaultOwners;
    private static Map<String, Map<String, String>> users;
    private static Map<String, String> userRoles;
    private static Map<String, List<String>> userWards;
    private static Map<String, List<String>> userDepts;

    @BeforeAll
    static void parse() {
        sql = read(PRESET);
        aPreset = read(A_PRESET);
        bPreset = read(B_PRESET);
        roles = roleRows(sql);
        roleMenus = menuTuples(statement(sql, "INSERT INTO sys_role_menu"));
        roleScopes = scopeTuples(statement(sql, "INSERT INTO sys_role_scope"));
        wards = wardRows(statement(sql, "INSERT INTO ward ("));
        depts = deptRows(statement(sql, "INSERT INTO sys_dept"));
        defaultOwners = defaultOwnerTuples(sql);
        users = userTuples(statement(sql, "INSERT INTO sys_user ("));
        userRoles = userRoleTuples(statement(sql, "INSERT INTO sys_user_role"));
        userWards = userWardTuples(statement(sql, "INSERT INTO sys_user_ward"));
        userDepts = userDeptTuples(statement(sql, "INSERT INTO sys_user_dept_scope"));
    }

    /** 角色（§10 建议角色模板）：复制为自定义角色，名称与规格一致；内置角色不重建。 */
    @Test
    void rolesMatchSpecMatrix() {
        assertEquals(
                Set.of("医生", "随访员", "护士", "科主任"),
                roles.values().stream()
                        .map(r -> r.get("name"))
                        .collect(java.util.stream.Collectors.toSet()),
                "四个业务角色名称与 §10 一致");
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

    /**
     * 数据范围（§10「数据范围」列 / C-WARD-003 / C-WARD-004）：医生 / 随访员 / 护士 = 本人负责病区 + 本人负责科室
     * （用户「可负责多个科室或病区」的两类分配都生效，§7 规则「取并集」），科主任 = 本人负责科室。
     */
    @Test
    void roleScopesMatchDeptWardMatrix() {
        for (String code : List.of("C_DOCTOR", "C_FOLLOWUP_STAFF", "C_NURSE")) {
            assertEquals(
                    Set.of("WARD", "RESPONSIBLE_DEPT"),
                    roleScopes.get(code),
                    code + " = 本人负责病区 + 本人负责科室（C-WARD-003 科室或病区并集）");
        }
        assertEquals(
                Set.of("RESPONSIBLE_DEPT"),
                roleScopes.get("C_DIRECTOR"),
                "科主任 = 本人负责科室（C-WARD-004 在 A-WARD-003 基础上增加的类型）");
    }

    /** 菜单授予（§10 主要权限矩阵）：逐角色核对关键权限；敏感项不预授；每角色都有首页与随访目录。 */
    @Test
    void menuGrantsMatchPermissionsMatrix() {
        Set<Integer> doctor = roleMenus.get("C_DOCTOR");
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_CREATE,
                    PATIENT_EDIT,
                    PATIENT_DETAIL,
                    IMPLANT_EDIT,
                    TASK_LIST,
                    TASK_ASSIGN,
                    RECORD_EDIT,
                    REPORT,
                    SCAN_UPLOAD,
                    DASHBOARD_VIEW
                }) {
            assertTrue(
                    doctor.contains(menu),
                    "医生应含菜单 " + menu + "（§10 患者与植入记录 / 任务 / 录入与补录 / 纸质资料 / 首页）：" + doctor);
        }
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_DETAIL,
                    TASK_LIST,
                    RECORD_EDIT,
                    REPORT,
                    SCAN_UPLOAD,
                    DASHBOARD_VIEW
                }) {
            assertTrue(
                    roleMenus.get("C_FOLLOWUP_STAFF").contains(menu),
                    "随访员应含菜单 " + menu + "（§10 任务 / 沟通记录 / 录入与补录 / 纸质资料 / 首页）");
        }
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_CREATE,
                    PATIENT_DETAIL,
                    RECORD_EDIT,
                    REPORT,
                    SCAN_UPLOAD,
                    DASHBOARD_VIEW
                }) {
            assertTrue(
                    roleMenus.get("C_NURSE").contains(menu),
                    "护士应含菜单 " + menu + "（§10 患者登记 / 录入与补录 / 纸质资料 / 首页）");
        }
        assertFalse(
                roleMenus.get("C_NURSE").contains(TASK_LIST), "护士 §10 主要权限不含任务（认领 / 改派属医生与随访员）");
        for (int menu :
                new int[] {
                    WORKBENCH,
                    FOLLOWUP_DIR,
                    PATIENT_LIST,
                    PATIENT_DETAIL,
                    TASK_LIST,
                    REPORT,
                    DASHBOARD_VIEW,
                    PLAN_LIST,
                    PATIENT_TRANSFER,
                    PLAN_PUBLISH,
                    PLAN_ENROLL
                }) {
            assertTrue(
                    roleMenus.get("C_DIRECTOR").contains(menu),
                    "科主任应含菜单 " + menu + "（§10 全部查看 / 首页 / 转科室病区 / 发布随访计划）");
        }
        for (Map.Entry<String, Set<Integer>> grant : roleMenus.entrySet()) {
            for (int menu : grant.getValue()) {
                assertFalse(
                        SENSITIVE_MENU_IDS.contains(menu),
                        grant.getKey() + " 不得预授敏感菜单 " + menu + "（§10 敏感权限：平台放开后按需分配）");
            }
        }
    }

    /** 科室与病区字典（C-WARD-001）：科室沿用平台树结构、病区独立字典 3 病区 + 2 中心，取值为 C 自有虚构值。 */
    @Test
    void wardsAndDeptsMatchDictionarySpec() {
        assertEquals(5, wards.size(), "预置 3 个病区 + 2 个中心：" + wards.keySet());
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> ward : wards.entrySet()) {
            Map<String, String> row = ward.getValue();
            assertTrue(ward.getKey().startsWith("W-C-"), "病区编码前缀：" + ward.getKey());
            assertEquals("ENABLED", row.get("status"), ward.getKey() + " 状态");
            assertTrue(
                    row.get("name").endsWith("（C 示例）"),
                    ward.getKey() + " 名称带 C 院虚构标记（AGENTS §5.5）：" + row.get("name"));
            String alias = row.get("alias");
            assertFalse(alias == null || alias.isBlank(), ward.getKey() + " 别名（历史写法映射）必填");
            assertTrue(alias.contains(";"), ward.getKey() + " 别名多值「;」分隔：" + alias);
            types.add(row.get("ward_type"));
        }
        assertEquals(Set.of("WARD", "CENTER"), types, "病区与中心两类都要有（C-WARD-001）");
        Set<Integer> sorts = new LinkedHashSet<>();
        for (Map<String, String> row : wards.values()) {
            assertTrue(sorts.add(Integer.parseInt(row.get("sort_no"))), "病区排序号唯一");
        }

        assertEquals(2, depts.size(), "预置 2 个科室（心内科 + 心外科，C 自有虚构取值）：" + depts.keySet());
        for (Map.Entry<String, Map<String, String>> dept : depts.entrySet()) {
            assertTrue(dept.getKey().startsWith("DEPT-C-"), "科室编码前缀：" + dept.getKey());
            assertEquals("ENABLED", dept.getValue().get("status"), dept.getKey() + " 状态");
            assertTrue(
                    dept.getValue().get("name").endsWith("（C 示例）"),
                    dept.getKey() + " 名称带 C 院虚构标记：" + dept.getValue().get("name"));
        }
    }

    /** 用户：27 名（14 医生 + 6 随访员 + 4 护士 + 3 科主任）、账号 / 工号唯一、全部虚构、占位口令。 */
    @Test
    void usersAreFictionalAccounts() {
        assertEquals(27, users.size(), "预置用户数：" + users.size());
        Set<String> staffNos = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> user : users.entrySet()) {
            assertTrue(user.getKey().startsWith("c."), "账号前缀：" + user.getKey());
            assertTrue(
                    user.getValue().get("displayName").endsWith("（C 示例）"),
                    user.getKey() + " 姓名带 C 院虚构标记：" + user.getValue().get("displayName"));
            assertTrue(
                    staffNos.add(user.getValue().get("staffNo")),
                    "工号唯一：" + user.getValue().get("staffNo"));
            assertTrue(
                    user.getValue().get("staffNo").startsWith("DEMO-C-"),
                    "工号虚构前缀（§17）：" + user.getValue().get("staffNo"));
        }
        String insert = statement(sql, "INSERT INTO sys_user");
        assertTrue(
                insert.contains("'PENDING_DEMO_HASH'"),
                "口令为占位哈希（DemoPasswordInitializer 启动时替换，不落明文）");
        assertTrue(insert.contains("'ENABLED'"), "预置用户启用");
    }

    /** 用户 - 角色分配：每人恰好一个业务角色，角色都在四个预置角色内；角色矩阵 14 / 6 / 4 / 3。 */
    @Test
    void userRolesAssignOneBusinessRoleEach() {
        assertEquals(27, userRoles.size(), "每个用户一条角色分配");
        assertEquals(users.keySet(), userRoles.keySet(), "分配覆盖全部 27 名用户");
        Map<String, Long> expected =
                Map.of(
                        "C_DOCTOR", 14L,
                        "C_FOLLOWUP_STAFF", 6L,
                        "C_NURSE", 4L,
                        "C_DIRECTOR", 3L);
        for (Map.Entry<String, Long> roleCount : expected.entrySet()) {
            assertEquals(
                    roleCount.getValue(),
                    userRoles.values().stream().filter(roleCount.getKey()::equals).count(),
                    "角色 " + roleCount.getKey() + " 人数（§10 矩阵）");
        }
        for (Map.Entry<String, String> assignment : userRoles.entrySet()) {
            assertTrue(
                    ROLE_CODES.contains(assignment.getValue()),
                    assignment.getKey() + " 的角色 " + assignment.getValue() + " 不在预置角色内");
        }
    }

    /**
     * 负责范围分配（C-WARD-003「可负责多个科室或病区」/ A-WARD-002）：病区分支用户每人至少一个病区，科主任按科室分配且不配病区清单； 多病区、多科室、科室 +
     * 病区混合三类并集都要存在；每个病区都有负责人、每个科室都有科主任。
     */
    @Test
    void deptWardAssignmentCoversBothBranches() {
        for (Map.Entry<String, String> assignment : userRoles.entrySet()) {
            List<String> wardsOfUser = userWards.get(assignment.getKey());
            List<String> deptsOfUser = userDepts.get(assignment.getKey());
            if ("C_DIRECTOR".equals(assignment.getValue())) {
                assertTrue(
                        wardsOfUser == null || wardsOfUser.isEmpty(),
                        assignment.getKey() + " 科主任（本人负责科室）不配病区清单");
                assertNotNull(deptsOfUser, assignment.getKey() + "（RESPONSIBLE_DEPT）须分配负责科室");
                assertFalse(deptsOfUser.isEmpty(), assignment.getKey() + " 空范围看不到患者");
            } else {
                assertTrue(
                        (wardsOfUser != null && !wardsOfUser.isEmpty())
                                || (deptsOfUser != null && !deptsOfUser.isEmpty()),
                        assignment.getKey() + "（科室或病区）至少一项负责范围（C-WARD-003）");
            }
            for (String wardCode : userWards.getOrDefault(assignment.getKey(), List.of())) {
                assertTrue(
                        wards.containsKey(wardCode),
                        assignment.getKey() + " 引用的病区 " + wardCode + " 不在预置字典内");
            }
            for (String deptCode : userDepts.getOrDefault(assignment.getKey(), List.of())) {
                assertTrue(
                        depts.containsKey(deptCode),
                        assignment.getKey() + " 引用的科室 " + deptCode + " 不在预置字典内");
            }
        }
        assertTrue(
                userWards.values().stream().anyMatch(list -> list.size() >= 2),
                "至少一名医生负责多个病区（C-WARD-003「多个科室或病区」：多病区并集）");
        assertTrue(
                userDepts.entrySet().stream()
                        .filter(e -> "C_DIRECTOR".equals(userRoles.get(e.getKey())))
                        .anyMatch(e -> e.getValue().size() >= 2),
                "至少一名科主任负责多个科室（C-WARD-003：多科室并集）");
        assertTrue(
                userWards.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !userWards.getOrDefault(e.getKey(), List.of()).isEmpty()
                                                && !userDepts
                                                        .getOrDefault(e.getKey(), List.of())
                                                        .isEmpty()),
                "至少一名用户同时负责病区与科室（§7「负责多个科室或病区时取并集」的混合形态）");
        for (String wardCode : wards.keySet()) {
            assertTrue(
                    userWards.values().stream().flatMap(List::stream).anyMatch(wardCode::equals),
                    wardCode + " 至少有一名负责用户");
        }
        for (String deptCode : depts.keySet()) {
            assertTrue(
                    userDepts.entrySet().stream()
                            .filter(e -> "C_DIRECTOR".equals(userRoles.get(e.getKey())))
                            .flatMap(e -> e.getValue().stream())
                            .anyMatch(deptCode::equals),
                    deptCode + " 至少有一名科主任负责");
        }
        Set<String> userWardPairs = new LinkedHashSet<>();
        userWards.forEach(
                (username, wardCodes) ->
                        wardCodes.forEach(code -> userWardPairs.add(username + "@" + code)));
        assertEquals(
                userWards.values().stream().mapToInt(List::size).sum(),
                userWardPairs.size(),
                "同一用户对同一病区不重复分配");
        Set<String> userDeptPairs = new LinkedHashSet<>();
        userDepts.forEach(
                (username, deptCodes) ->
                        deptCodes.forEach(code -> userDeptPairs.add(username + "@" + code)));
        assertEquals(
                userDepts.values().stream().mapToInt(List::size).sum(),
                userDeptPairs.size(),
                "同一用户对同一科室不重复分配");
    }

    /** 默认随访负责人（C-WARD-001 默认责任随访人 / A-FU-009 沿用）：每个病区 / 中心都配，负责人是负责本病区的随访员。 */
    @Test
    void defaultOwnersAreFollowupStaffOfSameWard() {
        assertEquals(wards.keySet(), defaultOwners.keySet(), "每个病区 / 中心都配默认随访负责人");
        for (Map.Entry<String, String> owner : defaultOwners.entrySet()) {
            String username = owner.getValue();
            assertEquals(
                    "C_FOLLOWUP_STAFF",
                    userRoles.get(username),
                    owner.getKey() + " 默认负责人 " + username + " 应为随访员");
            assertTrue(
                    userWards.getOrDefault(username, List.of()).contains(owner.getKey()),
                    username + " 负责本病区 " + owner.getKey());
        }
    }

    /** 与 A / B 预置（V123 / V136）同库共存：四个命名空间互不相交，演示 / IT 库三套预置并存不串（OQ-93 ②）。 */
    @Test
    void coexistsWithAAndBPresetsInSharedSeedDatabases() {
        Set<String> aRoleCodes = roleRows(aPreset).keySet();
        Set<String> bRoleCodes = roleRows(bPreset).keySet();
        for (String code : ROLE_CODES) {
            assertFalse(aRoleCodes.contains(code), "角色码与 A 预置冲突：" + code);
            assertFalse(bRoleCodes.contains(code), "角色码与 B 预置冲突：" + code);
        }
        Set<String> aWardCodes = wardRows(statement(aPreset, "INSERT INTO ward (")).keySet();
        Set<String> bWardCodes = wardRows(statement(bPreset, "INSERT INTO ward (")).keySet();
        for (String code : wards.keySet()) {
            assertFalse(aWardCodes.contains(code), "病区码与 A 预置冲突：" + code);
            assertFalse(bWardCodes.contains(code), "病区码与 B 预置冲突：" + code);
        }
        Set<String> aUsernames = userTuples(statement(aPreset, "INSERT INTO sys_user (")).keySet();
        Set<String> bUsernames = userTuples(statement(bPreset, "INSERT INTO sys_user (")).keySet();
        for (String username : users.keySet()) {
            assertFalse(aUsernames.contains(username), "账号与 A 预置冲突：" + username);
            assertFalse(bUsernames.contains(username), "账号与 B 预置冲突：" + username);
        }
        assertFalse(aUsernames.stream().anyMatch(u -> u.startsWith("c.")), "A 预置不得占用 c. 前缀");
        assertFalse(bUsernames.stream().anyMatch(u -> u.startsWith("c.")), "B 预置不得占用 c. 前缀");
    }

    /** 随包口径：种子只进 local / dev / IT，脚本不得引用其他租户专属数据或真实身份。 */
    @Test
    void presetStaysFictionalAndSeedScoped() {
        assertFalse(sql.contains("demoa") || sql.contains("demob"), "种子脚本对每个租户库执行，不写租户分支");
        assertFalse(sql.matches("(?s).*1[3-9]\\d{9}.*"), "不得出现 11 位手机号样值（OQ-92 敏感口径）");
        assertTrue(sql.contains("-- E23-T22"), "脚本头注名任务编号（AGENTS §8 提交与审计口径）");
    }

    // ---------------------------------------------------------------- 解析

    /** 取包含 marker 的整条语句。按「行尾分号」切分：病区别名值里含「;」（历史写法分隔符），不能按裸分号切。 */
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

    /** C 预置一个角色可带多个范围类型（WARD + RESPONSIBLE_DEPT），与 A / B 预置单值不同。 */
    private static Map<String, Set<String>> scopeTuples(String block) {
        Pattern pattern =
                Pattern.compile("SELECT '([A-Z_]+)'(?: AS code)?, '([A-Z_]+)'(?: AS scope_type)?");
        Map<String, Set<String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(matcher.group(2));
        }
        return rows;
    }

    private static Map<String, Map<String, String>> wardRows(String block) {
        // ('W-C-CARD1', '心内一病区（C 示例）', 'ENABLED', 'WARD', '心内1;内一病区', 10)
        Pattern pattern =
                Pattern.compile(
                        "\\('(W-C-[A-Z0-9]+)', '([^']+)', '([A-Z]+)', '([A-Z]+)', '([^']+)', (\\d+)\\)");
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

    private static Map<String, Map<String, String>> deptRows(String block) {
        // SELECT 'DEPT-C-CARDIO' AS code, '心内科（C 示例）' AS name / UNION ALL SELECT ...
        Pattern pattern =
                Pattern.compile("SELECT '(DEPT-C-[A-Z0-9]+)'(?: AS code)?, '([^']+)'(?: AS name)?");
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            Map<String, String> row = new HashMap<>();
            row.put("name", matcher.group(2));
            row.put("status", "ENABLED");
            rows.put(matcher.group(1), row);
        }
        return rows;
    }

    private static Map<String, String> defaultOwnerTuples(String whole) {
        Pattern pattern =
                Pattern.compile(
                        "WHERE u\\.username = '([a-z0-9.]+)'\\) WHERE code = '(W-C-[A-Z0-9]+)'");
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(whole);
        while (matcher.find()) {
            rows.put(matcher.group(2), matcher.group(1));
        }
        return rows;
    }

    private static Map<String, Map<String, String>> userTuples(String block) {
        // SELECT 'c.card1.doc01' AS username, '贝医生（C 示例）' AS display_name, 'DEMO-C-U01' AS
        // staff_no
        Pattern pattern =
                Pattern.compile(
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '([^']+)'(?: AS display_name)?,"
                                + " '(DEMO-C-U\\d+)'(?: AS staff_no)?");
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
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '(C_[A-Z_]+)'(?: AS role_code)?");
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
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '(W-C-[A-Z0-9]+)'(?: AS ward_code)?");
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(matcher.group(2));
        }
        return rows;
    }

    private static Map<String, List<String>> userDeptTuples(String block) {
        Pattern pattern =
                Pattern.compile(
                        "SELECT '([a-z0-9.]+)'(?: AS username)?, '(DEPT-C-[A-Z0-9]+)'(?: AS"
                                + " dept_code)?");
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(block);
        while (matcher.find()) {
            rows.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(matcher.group(2));
        }
        return rows;
    }

    private static String read(String path) {
        try (InputStream in =
                CPresetWardUserSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "找不到脚本 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读脚本失败 " + path, e);
        }
    }
}
