package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E23-T22 核对（端到端 / 真实 MySQL）：种子 V137 落库后的医院 C 科室、病区、用户与角色 「用户按科室病区分配」（tasks.csv 验收原文）。
 *
 * <p>基准同 {@link CPresetWardUserSqlTest}（C 规格 §7 科室病区隔离 + §10 建议角色模板），这里是库侧行为核对； demob 作医院 C
 * 化身（{@link BPresetWardUserVerifyIT} 同口径；种子对每个租户库执行同一脚本，OQ-93 ②），demoa 同样落位：
 *
 * <ol>
 *   <li>科室与病区字典（C-WARD-001）：2 科室（平台科室树结构）+ 3 病区 + 2 中心落库，类型 / 别名 / 排序 / 默认随访负责人（本院随访员） 齐全，取值为 C 自有；
 *   <li>用户按科室病区分配（C-WARD-003 / §10 角色矩阵）：27 名用户全部启用、口令占位已由启动初始化器替换、角色矩阵 14 医生 / 6 随访员 / 4 护士 / 3
 *       科主任；临床用户按病区落位，科主任按科室落位（不配病区清单），跨病区 / 跨科室 / 病区 + 科室混合三类并集都有载体；
 *   <li>数据范围强制过滤（C-WARD-005 沿用 A-WARD-004）：心内一病区医生只见本病区患者、直接访问他病区患者 40400；负责心内科的科主任可见
 *       归属心内科的全部患者、不论这些患者在哪个病区（§7 验收原文），他科室患者 40400；跨科室主任见并集；病区 + 科室混合医生见两分支并集；
 *   <li>权限预览（C-WARD-008 沿用 A-WARD-009）：医生显示 WARD + RESPONSIBLE_DEPT 范围与可见病区 / 科室及来源角色，敏感权限未预授；
 *   <li>双租户（AGENTS §6）：种子对每个租户库独立执行，demoa 同样 27 用户 / 5 病区 / 2 科室（C 命名空间），两侧互不串库。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（患者病案号 / legacy_no 前缀 IT-E23T22-）；
 * 预置行本身只读不动（C 病区默认负责人被其他 IT 清空时按 V137 随包状态恢复，与 {@link BPresetWardUserVerifyIT} 恢复 B 预置同口径）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CPresetWardUserVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E23T22";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    private long card1WardId;

    private long card2WardId;

    private long card3WardId;

    private long cardioDeptId;

    private long cvsDeptId;

    @BeforeAll
    void prepare() {
        cleanState();
        // 其他 IT（NotifyRulesIT / NotifyAcceptanceIT 的 cleanState）会把全部病区的默认随访负责人清空，
        // 这里按 V137 随包状态恢复后再核对（与 APresetWardUserVerifyIT / BPresetWardUserVerifyIT 同口径）。
        runInTenant(
                "demob",
                () -> {
                    for (String[] pair :
                            new String[][] {
                                {"W-C-CARD1", "c.card1.fu01"},
                                {"W-C-CARD2", "c.card2.fu01"},
                                {"W-C-CARD3", "c.card3.fu01"},
                                {"W-C-CCU", "c.ccu.fu01"},
                                {"W-C-PMFU", "c.pmfu.fu01"}
                            }) {
                        jdbc.update(
                                "UPDATE ward SET default_owner_id ="
                                        + " (SELECT u.id FROM sys_user u WHERE u.username = ?)"
                                        + " WHERE code = ?",
                                pair[1],
                                pair[0]);
                    }
                });
        card1WardId = wardId("W-C-CARD1");
        card2WardId = wardId("W-C-CARD2");
        card3WardId = wardId("W-C-CARD3");
        cardioDeptId = deptId("DEPT-C-CARDIO");
        cvsDeptId = deptId("DEPT-C-CVS");
        assertNotNull(card1WardId);
        assertNotNull(card2WardId);
        assertNotNull(card3WardId);
        assertNotNull(cardioDeptId);
        assertNotNull(cvsDeptId);
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    /** 科室与病区字典（C-WARD-001）：字段全集落库（取值为 C 自有），默认随访负责人 = 负责本病区的随访员。 */
    @Test
    void wardAndDeptDictionaryRowsMatchSpecInDatabase() {
        runInTenant(
                "demob",
                () -> {
                    Map<String, Object> card1 =
                            jdbc.queryForMap(
                                    "SELECT code, name, status, ward_type, alias, sort_no,"
                                            + " default_owner_id FROM ward WHERE code = 'W-C-CARD1'");
                    assertEquals("心内一病区（C 示例）", card1.get("name"));
                    assertEquals("ENABLED", card1.get("status"));
                    assertEquals("WARD", card1.get("ward_type"), "类型：病区");
                    assertEquals("心内1;内一病区;起搏器一区", card1.get("alias"), "别名（历史写法）");
                    assertEquals(10, ((Number) card1.get("sort_no")).intValue(), "排序");

                    Map<String, Object> pmfu =
                            jdbc.queryForMap(
                                    "SELECT name, ward_type, default_owner_id FROM ward"
                                            + " WHERE code = 'W-C-PMFU'");
                    assertEquals("起搏器程控随访中心（C 示例）", pmfu.get("name"));
                    assertEquals("CENTER", pmfu.get("ward_type"), "类型：中心");

                    // 默认随访负责人（C-WARD-001 默认责任随访人 / A-FU-009）：本院随访员且负责本病区
                    Map<String, Object> owner =
                            jdbc.queryForMap(
                                    "SELECT u.username, r.code AS role_code FROM sys_user u"
                                            + " JOIN sys_role r ON r.id IN"
                                            + " (SELECT ur.role_id FROM sys_user_role ur"
                                            + " WHERE ur.user_id = u.id)"
                                            + " WHERE u.id = ?",
                                    card1.get("default_owner_id"));
                    assertEquals("c.card1.fu01", owner.get("username"));
                    assertEquals("C_FOLLOWUP_STAFF", owner.get("role_code"), "默认负责人是随访员");
                    Integer owns =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN ward w ON w.id = uw.ward_id"
                                            + " WHERE u.username = 'c.card1.fu01' AND w.code = 'W-C-CARD1'",
                                    Integer.class);
                    assertEquals(1, owns, "默认负责人负责本病区");

                    Integer wardTypes =
                            jdbc.queryForObject(
                                    "SELECT COUNT(DISTINCT ward_type) FROM ward WHERE code LIKE 'W-C-%'",
                                    Integer.class);
                    assertEquals(2, wardTypes, "病区与中心两类都有");

                    // 科室沿用平台科室树（C-WARD-001）：C 自有虚构取值落库
                    Map<String, Object> cardio =
                            jdbc.queryForMap(
                                    "SELECT d.name, d.status, o.code AS org_code FROM sys_dept d"
                                            + " JOIN sys_org o ON o.id = d.org_id"
                                            + " WHERE d.code = 'DEPT-C-CARDIO'");
                    assertEquals("心内科（C 示例）", cardio.get("name"));
                    assertEquals("ENABLED", cardio.get("status"));
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sys_dept WHERE code LIKE 'DEPT-C-%'",
                                            Integer.class)
                                    .intValue(),
                            "预置心内 + 心外两个科室");
                });
    }

    /** C 用户按科室病区分配（C-WARD-003 / §10 角色矩阵：14 医生 + 6 随访员 + 4 护士 + 3 科主任）。 */
    @Test
    void usersAssignedByDeptAndWardWithRoleMatrix() {
        runInTenant(
                "demob",
                () -> {
                    assertEquals(
                            27,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'c.%'",
                                    Integer.class),
                            "预置用户数");
                    assertEquals(
                            27,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur"
                                            + " ON ur.user_id = u.id JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username LIKE 'c.%' AND r.code LIKE 'C_%'",
                                    Integer.class),
                            "每个预置用户都有业务角色");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'c.%'"
                                            + " AND (password_hash = 'PENDING_DEMO_HASH' OR status <> 'ENABLED')",
                                    Integer.class),
                            "占位口令已替换且全部启用");

                    // 角色矩阵（§10）：14 / 6 / 4 / 3
                    Map<String, Integer> expected =
                            Map.of(
                                    "C_DOCTOR", 14,
                                    "C_FOLLOWUP_STAFF", 6,
                                    "C_NURSE", 4,
                                    "C_DIRECTOR", 3);
                    for (Map.Entry<String, Integer> role : expected.entrySet()) {
                        assertEquals(
                                role.getValue(),
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user u"
                                                + " JOIN sys_user_role ur ON ur.user_id = u.id"
                                                + " JOIN sys_role r ON r.id = ur.role_id"
                                                + " WHERE u.username LIKE 'c.%' AND r.code = ?",
                                        Integer.class, role.getKey()),
                                "角色 " + role.getKey() + " 人数");
                    }

                    // 负责病区落位（C-WARD-003 病区分支）
                    Map<String, Integer> wardUsers =
                            new HashMap<>(
                                    Map.of(
                                            "W-C-CARD1", 7,
                                            "W-C-CARD2", 7,
                                            "W-C-CARD3", 5,
                                            "W-C-CCU", 4,
                                            "W-C-PMFU", 2));
                    for (Map.Entry<String, Integer> ward : wardUsers.entrySet()) {
                        assertEquals(
                                ward.getValue(),
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user_ward uw"
                                                + " JOIN ward w ON w.id = uw.ward_id"
                                                + " JOIN sys_user u ON u.id = uw.user_id"
                                                + " WHERE w.code = ? AND u.username LIKE 'c.%'",
                                        Integer.class, ward.getKey()),
                                "病区 " + ward.getKey() + " 负责人数");
                    }
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " WHERE u.username = 'c.multi.doc01'",
                                    Integer.class),
                            "跨病区医生负责心内一 + 心内二（C-WARD-003 多病区并集）");

                    // 负责科室落位（C-WARD-004：科主任本人负责科室，不配病区清单）
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN sys_user_role ur ON ur.user_id = u.id"
                                            + " JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username LIKE 'c.%' AND r.code = 'C_DIRECTOR'",
                                    Integer.class),
                            "科主任按科室负责，不配病区清单");
                    Map<String, Integer> deptCounts =
                            Map.of(
                                    "c.cardio.dir", 1,
                                    "c.cvs.dir", 1,
                                    "c.both.dir", 2,
                                    "c.mix.doc01", 1);
                    for (Map.Entry<String, Integer> user : deptCounts.entrySet()) {
                        assertEquals(
                                user.getValue(),
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user_dept_scope ds"
                                                + " JOIN sys_user u ON u.id = ds.user_id"
                                                + " WHERE u.username = ?",
                                        Integer.class,
                                        user.getKey()),
                                user.getKey() + " 负责科室数");
                    }
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_dept_scope ds"
                                            + " JOIN sys_user u ON u.id = ds.user_id"
                                            + " WHERE u.username = 'c.both.dir'",
                                    Integer.class),
                            "跨科室主任负责心内 + 心外（C-WARD-003 多科室并集）");

                    // 数据范围（§10「数据范围」列）：WARD ×3（临床角色叠加）、RESPONSIBLE_DEPT ×4、C 角色不配 ALL
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_scope rs"
                                            + " JOIN sys_role r ON r.id = rs.role_id"
                                            + " WHERE r.code LIKE 'C_%' AND rs.scope_type = 'WARD'",
                                    Integer.class));
                    assertEquals(
                            4,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_scope rs"
                                            + " JOIN sys_role r ON r.id = rs.role_id"
                                            + " WHERE r.code LIKE 'C_%'"
                                            + " AND rs.scope_type = 'RESPONSIBLE_DEPT'",
                                    Integer.class),
                            "四个 C 角色都带本人负责科室（临床三角色叠加 + 科主任）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_scope rs"
                                            + " JOIN sys_role r ON r.id = rs.role_id"
                                            + " WHERE r.code LIKE 'C_%' AND rs.scope_type = 'ALL'",
                                    Integer.class),
                            "C 预置角色不配全院（全院留给内置租户管理员，C-WARD-007）");
                });
    }

    /**
     * 数据范围强制过滤（C-WARD-005 沿用 A-WARD-004 + §7 验收）：本病区可见、他病区 40400、 科主任跨病区见本科室患者、跨科室并集、 病区 + 科室混合并集。
     */
    @Test
    void deptWardScopeFiltersPatientsByPresetAssignment() {
        String admin = adminToken();
        // 归属矩阵（C-WARD-002：患者归属一个科室和一个病区）：
        //   MRN-01 心内科 + 心内一 / MRN-02 心内科 + 心内二 / MRN-03 心外科 + 心内二 / MRN-04 心内科 + 心内三
        register(admin, "MRN-01", "贝一心（C 示例）", cardioDeptId, card1WardId);
        register(admin, "MRN-02", "贝二心（C 示例）", cardioDeptId, card2WardId);
        register(admin, "MRN-03", "齐三安（C 示例）", cvsDeptId, card2WardId);
        register(admin, "MRN-04", "贝四律（C 示例）", cardioDeptId, card3WardId);
        long patientInCvs = patientIdByMrn("MRN-03");
        List<Long> cWards = List.of(card1WardId, card2WardId, card3WardId);
        List<Long> cDepts = List.of(cardioDeptId, cvsDeptId);

        // 心内一病区医生（c.card1.doc01，WARD 分支）：只见本病区患者（§7 验收「护士只负责心内一病区」同构）
        String card1Doctor = hospitalToken("demob", "c.card1.doc01", PASSWORD);
        List<String> seenByCard1Doctor = search(card1Doctor, cWards, cDepts);
        assertTrue(seenByCard1Doctor.contains(LEGACY + "-MRN-01"), "本病区患者可见");
        assertFalse(seenByCard1Doctor.contains(LEGACY + "-MRN-02"), "他病区患者不可见（科室相同也不可见，分支独立）");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientInCvs + "/summary",
                                card1Doctor)),
                "越范围访问单个患者按不存在处理（40400）");

        // 负责心内科的科主任（c.cardio.dir，RESPONSIBLE_DEPT 分支）：可见归属心内科的全部患者，
        // 不论这些患者在哪个病区（§7 验收原文）；心外科患者不可见
        String cardioDirector = hospitalToken("demob", "c.cardio.dir", PASSWORD);
        List<String> seenByCardioDirector = search(cardioDirector, cWards, cDepts);
        assertTrue(
                seenByCardioDirector.containsAll(
                        List.of(LEGACY + "-MRN-01", LEGACY + "-MRN-02", LEGACY + "-MRN-04")),
                "心内科科主任跨三个病区见本科室全部患者：" + seenByCardioDirector);
        assertFalse(seenByCardioDirector.contains(LEGACY + "-MRN-03"), "他科室患者不可见");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientInCvs + "/summary",
                                cardioDirector)),
                "科主任越科室访问按不存在处理（40400）");

        // 负责心外科的科主任（c.cvs.dir）：只见心外科患者
        String cvsDirector = hospitalToken("demob", "c.cvs.dir", PASSWORD);
        List<String> seenByCvsDirector = search(cvsDirector, cWards, cDepts);
        assertEquals(List.of(LEGACY + "-MRN-03"), seenByCvsDirector, "心外科科主任只见本科室患者");

        // 跨科室主任（c.both.dir 负责心内 + 心外）：两科室并集（§7「负责多个科室时取并集」）
        String bothDirector = hospitalToken("demob", "c.both.dir", PASSWORD);
        List<String> seenByBoth = search(bothDirector, cWards, cDepts);
        assertTrue(
                seenByBoth.containsAll(
                        List.of(
                                LEGACY + "-MRN-01",
                                LEGACY + "-MRN-02",
                                LEGACY + "-MRN-03",
                                LEGACY + "-MRN-04")),
                "跨科室主任见两科室并集：" + seenByBoth);

        // 病区 + 科室混合医生（c.mix.doc01 负责心内三病区 + 心外科）：病区分支（MRN-04）∪ 科室分支（MRN-03）
        String mixDoctor = hospitalToken("demob", "c.mix.doc01", PASSWORD);
        List<String> seenByMix = search(mixDoctor, cWards, cDepts);
        assertTrue(
                seenByMix.containsAll(List.of(LEGACY + "-MRN-03", LEGACY + "-MRN-04")),
                "混合医生见病区 + 科室并集：" + seenByMix);
        assertFalse(seenByMix.contains(LEGACY + "-MRN-01"), "未负责的病区且非负责科室的患者不可见");
        assertFalse(seenByMix.contains(LEGACY + "-MRN-02"), "未负责的病区且非负责科室的患者不可见");
    }

    /** 权限预览（C-WARD-008 沿用 A-WARD-009）：病区 + 科室范围与来源角色；敏感权限未预授。 */
    @Test
    void permissionPreviewShowsPresetDeptWardScope() {
        String admin = adminToken();
        long doctorId = userIdOf("c.card1.doc01");
        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                admin));

        assertFalse(Boolean.TRUE.equals(view.get("scopeAll")), "病区医生不是全院范围");
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("医生"), scopeSources.get("WARD"), "WARD 范围来源角色：" + scopeSources);
        assertEquals(
                List.of("医生"),
                scopeSources.get("RESPONSIBLE_DEPT"),
                "RESPONSIBLE_DEPT 范围来源角色（C-WARD-003 科室或病区）：" + scopeSources);

        List<?> wards = (List<?>) view.get("wards");
        assertEquals(1, wards.size(), "本人负责病区：" + wards);
        Map<?, ?> ward = (Map<?, ?>) wards.get(0);
        assertEquals("W-C-CARD1", ward.get("code"));
        assertEquals("心内一病区（C 示例）", ward.get("name"));
        assertEquals(List.of("医生"), ward.get("sourceRoles"), "可见病区来源角色（A-WARD-009 沿用）");
        assertEquals(0, ((List<?>) view.get("depts")).size(), "未分配负责科室时不列科室");

        List<?> codes = (List<?>) view.get("permissionCodes");
        assertTrue(codes.contains("followup:patient:view"), "医生可看患者");
        assertTrue(codes.contains("followup:implant:edit"), "医生可编辑植入记录（§10 患者与植入记录）");
        assertTrue(codes.contains("followup:record:edit"), "医生可录入随访记录");
        assertFalse(codes.contains("followup:patient:plain"), "敏感权限未预授（§10）");
        assertFalse(codes.contains("followup:patient:export"), "导出未预授（§10 敏感权限）");

        // 混合医生（c.mix.doc01）：病区 + 科室两个清单并列（C-WARD-003 科室病区并集的预览形态）
        long mixId = userIdOf("c.mix.doc01");
        Map<?, ?> mixView =
                data(get("/api/hospital/system/users/" + mixId + "/effective-permissions", admin));
        List<?> mixWards = (List<?>) mixView.get("wards");
        assertEquals(1, mixWards.size(), "混合医生负责一个病区");
        assertEquals("W-C-CARD3", ((Map<?, ?>) mixWards.get(0)).get("code"));
        List<?> mixDepts = (List<?>) mixView.get("depts");
        assertEquals(1, mixDepts.size(), "混合医生负责一个科室");
        assertEquals("DEPT-C-CVS", ((Map<?, ?>) mixDepts.get(0)).get("code"));
        assertEquals("心外科（C 示例）", ((Map<?, ?>) mixDepts.get(0)).get("name"));

        // 跨科室主任（c.both.dir）：本人负责科室清单，不列病区（无 WARD 分支）
        long bothId = userIdOf("c.both.dir");
        Map<?, ?> bothView =
                data(get("/api/hospital/system/users/" + bothId + "/effective-permissions", admin));
        assertFalse(Boolean.TRUE.equals(bothView.get("scopeAll")), "科主任不是全院范围（本人负责科室）");
        assertEquals(0, ((List<?>) bothView.get("wards")).size(), "无病区分支不列病区");
        List<?> bothDepts = (List<?>) bothView.get("depts");
        assertEquals(2, bothDepts.size(), "负责两个科室：" + bothDepts);
    }

    /** 双租户（AGENTS §6）：种子对每个租户库独立执行，demoa 侧同样落位且互不串库。 */
    @Test
    void demoaPresetIsIndependentPerTenant() {
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            27,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'c.%'",
                                    Integer.class),
                            "demoa 预置用户数（C 命名空间）");
                    assertEquals(
                            5,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ward WHERE code LIKE 'W-C-%'",
                                    Integer.class),
                            "demoa 预置病区数（C 命名空间）");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_dept WHERE code LIKE 'DEPT-C-%'",
                                    Integer.class),
                            "demoa 预置科室数（C 命名空间）");
                    // demoa 侧账号的角色 / 负责病区 / 负责科室在本库解析（行落在各自租户库；跨库无关联，
                    // 不比对自增 id——CI 全新库两侧插入序列一致、id 巧合相等，9f15419 同口径）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_role ur"
                                            + " JOIN sys_user u ON u.id = ur.user_id"
                                            + " JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username = 'c.card1.doc01' AND r.code = 'C_DOCTOR'",
                                    Integer.class),
                            "demoa 侧角色分配落在 demoa 库");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN ward w ON w.id = uw.ward_id"
                                            + " WHERE u.username = 'c.card1.doc01' AND w.code = 'W-C-CARD1'",
                                    Integer.class),
                            "demoa 侧负责病区落在 demoa 库");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_dept_scope ds"
                                            + " JOIN sys_user u ON u.id = ds.user_id"
                                            + " JOIN sys_dept d ON d.id = ds.dept_id"
                                            + " WHERE u.username = 'c.both.dir'"
                                            + " AND d.code IN ('DEPT-C-CARDIO', 'DEPT-C-CVS')",
                                    Integer.class),
                            "demoa 侧负责科室落在 demoa 库");
                });
    }

    // ---------------------------------------------------------------- 辅助

    private void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE 'IT-E23T22-%')");
                        jdbc.update(
                                "DELETE o FROM sys_outbox o JOIN pt_patient p"
                                        + " ON JSON_EXTRACT(o.payload, '$.patientId') = p.id"
                                        + " WHERE p.legacy_no LIKE 'IT-E23T22-%'");
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E23T22-%'");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE 'IT-E23T22-%'");
                        jdbc.update(
                                "DELETE h FROM pt_status_history h JOIN pt_patient p"
                                        + " ON p.id = h.patient_id WHERE p.legacy_no LIKE 'IT-E23T22-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E23T22-%'");
                    });
        }
    }

    private long wardId(String code) {
        return runInTenant(
                "demob",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId(String code) {
        return runInTenant(
                "demob",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long userIdOf(String username) {
        return runInTenant(
                "demob",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private long patientIdByMrn(String mrn) {
        return runInTenant(
                "demob",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_patient WHERE legacy_no = ?",
                                Long.class,
                                LEGACY + "-" + mrn));
    }

    private void register(String token, String mrn, String name, long deptId, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1958-03-14");
        body.put("deptId", deptId);
        body.put("wardId", wardId);
        body.put("surgeryDate", "2026-10-08");
        body.put("admissionDate", "2026-10-01");
        body.put("dischargeDate", "2026-10-15");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败：" + created);
    }

    /**
     * 检索限定 wardIds + deptIds（AND）后只留本类前缀的患者：C 预置科室病区内的患者全部命中， 其余用例的患者（无科室归属）被 deptIds 条件排除，互不干扰断言。
     */
    private List<String> search(String token, List<Long> wardIds, List<Long> deptIds) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("wardIds", wardIds);
        filters.put("deptIds", deptIds);
        Map<String, Object> body =
                new HashMap<>(Map.of("pageNo", 1, "pageSize", 50, "filters", filters));
        Map<?, ?> page = data(post("/api/hospital/followup/patients/search", token, body));
        return ((List<?>) page.get("items"))
                .stream()
                        .map(item -> String.valueOf(((Map<?, ?>) item).get("legacyNo")))
                        .filter(legacy -> legacy.startsWith(LEGACY + "-"))
                        .toList();
    }

    private static Map<String, List<String>> scopeTypeSources(Map<?, ?> view) {
        Map<String, List<String>> sources = new HashMap<>();
        for (Object item : (List<?>) view.get("scopeTypes")) {
            Map<?, ?> type = (Map<?, ?>) item;
            sources.put(
                    String.valueOf(type.get("type")),
                    ((List<?>) type.get("sourceRoles")).stream().map(String::valueOf).toList());
        }
        return sources;
    }

    private String adminToken() {
        return hospitalToken("demob", "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败（" + username + "）：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
