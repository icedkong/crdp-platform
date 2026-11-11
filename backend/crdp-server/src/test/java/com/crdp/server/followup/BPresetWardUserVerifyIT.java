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
 * E23-T18 核对（端到端 / 真实 MySQL）：种子 V136 落库后的医院 B 病区、用户、角色与数据范围 「B 用户按病区分配」（tasks.csv 验收原文）。
 *
 * <p>基准同 {@link BPresetWardUserSqlTest}（第 14 章「用户、角色取值」+ 沿用的 §6 / §10），这里是库侧行为核对； demob 作医院 B
 * 化身（{@link BPresetPlanVerifyIT} 同口径；种子对每个租户库执行同一脚本，OQ-93 ②），demoa 同样落位：
 *
 * <ol>
 *   <li>病区字典（A-WARD-001 经第 14 章沿用）：3 病区 + 2 中心落库，类型 / 别名 / 排序 / 默认随访负责人（本院随访员）齐全，取值为 B 自有；
 *   <li>用户按病区分配（第 14 章「约 30 名用户按病区分配」/ A-WARD-002）：30 名用户全部启用、口令占位已由启动初始化器替换、 角色矩阵 16 病区医生 / 8 随访员
 *       / 4 科主任 / 2 数据录入员，WARD 范围用户逐病区落位、 科主任（ALL）不配病区清单、跨病区医生负责两个病区；
 *   <li>数据范围强制过滤（A-WARD-004）：B 心外一病区医生只见本病区患者、直接访问他病区患者 40400、 跨病区医生见两病区并集（§6 注「负责多个病区时可见这些病区的并集」）；
 *   <li>权限预览（A-WARD-009）：预置病区医生显示 WARD 范围与可见病区及来源角色，科主任 / 项目负责人全院可见不列病区（A-WARD-007 同口径）；
 *   <li>双租户（AGENTS §6）：种子对每个租户库独立执行，demoa 同样 30 用户 / 5 病区（B 命名空间），两侧互不串库。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（患者病案号 / legacy_no 前缀
 * IT-E23T18-）；预置行本身只读不动（B 病区默认负责人被其他 IT 清空时按 V136 随包状态恢复，与 {@link APresetWardUserVerifyIT} 恢复 A
 * 预置同口径）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BPresetWardUserVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E23T18";

    /** 预置用户的账号前缀（V136，与 A 预置 a. / 演示账号 nurse.demo / doctor.demo 区分）。 */
    private static final String PRESET_PREFIX = "b.";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    private long cvs1WardId;

    private long cvs2WardId;

    @BeforeAll
    void prepare() {
        cleanState();
        // 其他 IT（NotifyRulesIT / NotifyAcceptanceIT 的 cleanState）会把全部病区的默认随访负责人清空，
        // 这里按 V136 随包状态恢复后再核对（与 APresetWardUserVerifyIT 恢复 A 预置同口径）。
        runInTenant(
                "demob",
                () -> {
                    for (String[] pair :
                            new String[][] {
                                {"W-B-CVS1", "b.cvs1.nur01"},
                                {"W-B-CVS2", "b.cvs2.nur01"},
                                {"W-B-CVS3", "b.cvs3.nur01"},
                                {"W-B-ICU", "b.icu.nur01"},
                                {"W-B-FU", "b.fu.nur01"}
                            }) {
                        jdbc.update(
                                "UPDATE ward SET default_owner_id ="
                                        + " (SELECT u.id FROM sys_user u WHERE u.username = ?)"
                                        + " WHERE code = ?",
                                pair[1],
                                pair[0]);
                    }
                });
        cvs1WardId = wardId("W-B-CVS1");
        cvs2WardId = wardId("W-B-CVS2");
        assertNotNull(cvs1WardId);
        assertNotNull(cvs2WardId);
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    /** 病区字典（A-WARD-001 经第 14 章沿用）：字段全集落库（取值为 B 自有），默认随访负责人 = 负责本病区的随访员。 */
    @Test
    void wardDictionaryRowsMatchSpecInDatabase() {
        runInTenant(
                "demob",
                () -> {
                    Map<String, Object> cvs1 =
                            jdbc.queryForMap(
                                    "SELECT code, name, status, ward_type, alias, sort_no,"
                                            + " default_owner_id FROM ward WHERE code = 'W-B-CVS1'");
                    assertEquals("心外一病区（B 示例）", cvs1.get("name"));
                    assertEquals("ENABLED", cvs1.get("status"));
                    assertEquals("WARD", cvs1.get("ward_type"), "类型：病区");
                    assertEquals("外一病区;心外1", cvs1.get("alias"), "别名（历史写法）");
                    assertEquals(10, ((Number) cvs1.get("sort_no")).intValue(), "排序");

                    Map<String, Object> fuCenter =
                            jdbc.queryForMap(
                                    "SELECT name, ward_type, default_owner_id FROM ward"
                                            + " WHERE code = 'W-B-FU'");
                    assertEquals("心脏术后随访中心（B 示例）", fuCenter.get("name"));
                    assertEquals("CENTER", fuCenter.get("ward_type"), "类型：中心");

                    // 默认随访负责人（A-WARD-001 / A-FU-009）：本院随访员且负责本病区
                    Map<String, Object> owner =
                            jdbc.queryForMap(
                                    "SELECT u.username, r.code AS role_code FROM sys_user u"
                                            + " JOIN sys_role r ON r.id IN"
                                            + " (SELECT ur.role_id FROM sys_user_role ur"
                                            + " WHERE ur.user_id = u.id)"
                                            + " WHERE u.id = ?",
                                    cvs1.get("default_owner_id"));
                    assertEquals("b.cvs1.nur01", owner.get("username"));
                    assertEquals("B_FOLLOWUP_NURSE", owner.get("role_code"), "默认负责人是随访员");
                    Integer owns =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN ward w ON w.id = uw.ward_id"
                                            + " WHERE u.username = 'b.cvs1.nur01' AND w.code = 'W-B-CVS1'",
                                    Integer.class);
                    assertEquals(1, owns, "默认负责人负责本病区");

                    Integer wardTypes =
                            jdbc.queryForObject(
                                    "SELECT COUNT(DISTINCT ward_type) FROM ward WHERE code LIKE 'W-B-%'",
                                    Integer.class);
                    assertEquals(2, wardTypes, "病区与中心两类都有");
                });
    }

    /** B 用户按病区分配（第 14 章 / A-WARD-002 / §10 角色矩阵）。 */
    @Test
    void thirtyUsersAssignedByWardWithRoleMatrix() {
        runInTenant(
                "demob",
                () -> {
                    assertEquals(
                            30,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'b.%'",
                                    Integer.class),
                            "预置用户数（第 14 章「约 30 名」）");
                    assertEquals(
                            30,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur"
                                            + " ON ur.user_id = u.id JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username LIKE 'b.%' AND r.code LIKE 'B_%'",
                                    Integer.class),
                            "每个预置用户都有业务角色");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'b.%'"
                                            + " AND (password_hash = 'PENDING_DEMO_HASH' OR status <> 'ENABLED')",
                                    Integer.class),
                            "占位口令已替换且全部启用");

                    // 角色矩阵（§10，与 A 预置同构）：16 / 8 / 4 / 2
                    Map<String, Integer> expected =
                            Map.of(
                                    "B_WARD_DOCTOR", 16,
                                    "B_FOLLOWUP_NURSE", 8,
                                    "B_DIRECTOR", 4,
                                    "B_DATA_CLERK", 2);
                    for (Map.Entry<String, Integer> role : expected.entrySet()) {
                        assertEquals(
                                role.getValue(),
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user u"
                                                + " JOIN sys_user_role ur ON ur.user_id = u.id"
                                                + " JOIN sys_role r ON r.id = ur.role_id"
                                                + " WHERE u.username LIKE 'b.%' AND r.code = ?",
                                        Integer.class, role.getKey()),
                                "角色 " + role.getKey() + " 人数");
                    }

                    // WARD 范围用户逐病区落位（§6「本人负责病区」清单）；ALL 不配清单
                    Map<String, Integer> wardUsers =
                            new HashMap<>(
                                    Map.of(
                                            "W-B-CVS1", 7,
                                            "W-B-CVS2", 7,
                                            "W-B-CVS3", 5,
                                            "W-B-ICU", 4,
                                            "W-B-FU", 4));
                    for (Map.Entry<String, Integer> ward : wardUsers.entrySet()) {
                        assertEquals(
                                ward.getValue(),
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user_ward uw"
                                                + " JOIN ward w ON w.id = uw.ward_id"
                                                + " JOIN sys_user u ON u.id = uw.user_id"
                                                + " WHERE w.code = ? AND u.username LIKE 'b.%'",
                                        Integer.class, ward.getKey()),
                                "病区 " + ward.getKey() + " 负责人数");
                    }
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN sys_user_role ur ON ur.user_id = u.id"
                                            + " JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username LIKE 'b.%' AND r.code = 'B_DIRECTOR'",
                                    Integer.class),
                            "科主任全部病区，不配病区清单");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " WHERE u.username = 'b.multi.doc01'",
                                    Integer.class),
                            "跨病区医生负责心外一 + 心外二（A-WARD-002 多病区）");

                    // 数据范围（A-WARD-003）：WARD ×3 + ALL ×1
                    assertEquals(
                            3,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_scope rs"
                                            + " JOIN sys_role r ON r.id = rs.role_id"
                                            + " WHERE r.code LIKE 'B_%' AND rs.scope_type = 'WARD'",
                                    Integer.class));
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_scope rs"
                                            + " JOIN sys_role r ON r.id = rs.role_id"
                                            + " WHERE r.code = 'B_DIRECTOR' AND rs.scope_type = 'ALL'",
                                    Integer.class));
                });
    }

    /** 数据范围强制过滤（A-WARD-004）：本病区可见、他病区 40400、跨病区并集。 */
    @Test
    void wardScopeFiltersPatientsByPresetAssignment() {
        String admin = adminToken();
        register(admin, "MRN-01", "金东林（B 示例）", cvs1WardId);
        register(admin, "MRN-02", "魏海涛（B 示例）", cvs2WardId);
        long patientInCvs1 = patientIdByMrn("MRN-01");
        long patientInCvs2 = patientIdByMrn("MRN-02");

        // B 心外一病区医生（b.cvs1.doc01）：只见本病区患者（A-WARD-004 后端追加条件）
        String cvs1Doctor = hospitalToken("demob", "b.cvs1.doc01", PASSWORD);
        List<String> seenByCvs1Doctor = search(cvs1Doctor, List.of(cvs1WardId, cvs2WardId));
        assertTrue(seenByCvs1Doctor.contains(LEGACY + "-MRN-01"), "本病区患者可见");
        assertFalse(seenByCvs1Doctor.contains(LEGACY + "-MRN-02"), "他病区患者不可见");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientInCvs2 + "/summary",
                                cvs1Doctor)),
                "越范围访问单个患者按不存在处理（40400）");

        // 跨病区医生（b.multi.doc01 负责 B 心外一 + 心外二）：并集可见（§6 注）
        String multiDoctor = hospitalToken("demob", "b.multi.doc01", PASSWORD);
        List<String> seenByMulti = search(multiDoctor, List.of(cvs1WardId, cvs2WardId));
        assertTrue(
                seenByMulti.containsAll(List.of(LEGACY + "-MRN-01", LEGACY + "-MRN-02")),
                "跨病区医生见两病区并集：" + seenByMulti);

        // 科主任（B_DIRECTOR 全部病区）：两病区都可见
        String director = hospitalToken("demob", "b.proj.dir", PASSWORD);
        List<String> seenByDirector = search(director, List.of(cvs1WardId, cvs2WardId));
        assertTrue(
                seenByDirector.containsAll(List.of(LEGACY + "-MRN-01", LEGACY + "-MRN-02")),
                "科主任全部病区可见（A-WARD-003 / §10）");
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientInCvs2 + "/summary",
                                director)),
                "科主任访问他病区患者详情不拦截");
    }

    /** 权限预览（A-WARD-009）：病区范围 + 可见病区及来源角色；全院范围不列病区。 */
    @Test
    void permissionPreviewShowsPresetWardScope() {
        String admin = adminToken();
        long doctorId = userIdOf("b.cvs1.doc01");
        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                admin));

        assertFalse(Boolean.TRUE.equals(view.get("scopeAll")), "病区医生不是全院范围");
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("病区医生"), scopeSources.get("WARD"), "WARD 范围来源角色：" + scopeSources);

        List<?> wards = (List<?>) view.get("wards");
        assertEquals(1, wards.size(), "本人负责病区：" + wards);
        Map<?, ?> ward = (Map<?, ?>) wards.get(0);
        assertEquals("W-B-CVS1", ward.get("code"));
        assertEquals("心外一病区（B 示例）", ward.get("name"));
        assertEquals(List.of("病区医生"), ward.get("sourceRoles"), "可见病区来源角色（A-WARD-009）");

        List<?> codes = (List<?>) view.get("permissionCodes");
        assertTrue(codes.contains("followup:patient:view"), "病区医生可看患者");
        assertTrue(codes.contains("followup:record:edit"), "病区医生可录入随访记录");
        assertFalse(codes.contains("followup:patient:plain"), "敏感权限未预授（§10）");
        assertFalse(codes.contains("followup:patient:export"), "导出未预授（§10 敏感权限）");

        long directorId = userIdOf("b.proj.dir");
        Map<?, ?> directorView =
                data(
                        get(
                                "/api/hospital/system/users/"
                                        + directorId
                                        + "/effective-permissions",
                                admin));
        assertTrue(Boolean.TRUE.equals(directorView.get("scopeAll")), "项目负责人 / 科主任全院可见");
        assertEquals(0, ((List<?>) directorView.get("wards")).size(), "全院范围不列病区");
    }

    /** 双租户（AGENTS §6）：种子对每个租户库独立执行，demoa 侧同样落位且互不串库。 */
    @Test
    void demoaPresetIsIndependentPerTenant() {
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            30,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username LIKE 'b.%'",
                                    Integer.class),
                            "demoa 预置用户数（B 命名空间）");
                    assertEquals(
                            5,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ward WHERE code LIKE 'W-B-%'",
                                    Integer.class),
                            "demoa 预置病区数（B 命名空间）");
                    // demoa 侧账号的角色与负责病区在本库解析（行落在各自租户库；跨库无关联，
                    // 不比对自增 id——CI 全新库两侧插入序列一致、id 巧合相等，664f14c 同口径）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_role ur"
                                            + " JOIN sys_user u ON u.id = ur.user_id"
                                            + " JOIN sys_role r ON r.id = ur.role_id"
                                            + " WHERE u.username = 'b.cvs1.doc01' AND r.code = 'B_WARD_DOCTOR'",
                                    Integer.class),
                            "demoa 侧角色分配落在 demoa 库");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_ward uw"
                                            + " JOIN sys_user u ON u.id = uw.user_id"
                                            + " JOIN ward w ON w.id = uw.ward_id"
                                            + " WHERE u.username = 'b.cvs1.doc01' AND w.code = 'W-B-CVS1'",
                                    Integer.class),
                            "demoa 侧负责病区落在 demoa 库");
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
                                        + " WHERE legacy_no LIKE 'IT-E23T18-%')");
                        jdbc.update(
                                "DELETE o FROM sys_outbox o JOIN pt_patient p"
                                        + " ON JSON_EXTRACT(o.payload, '$.patientId') = p.id"
                                        + " WHERE p.legacy_no LIKE 'IT-E23T18-%'");
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E23T18-%'");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE 'IT-E23T18-%'");
                        jdbc.update(
                                "DELETE h FROM pt_status_history h JOIN pt_patient p"
                                        + " ON p.id = h.patient_id WHERE p.legacy_no LIKE 'IT-E23T18-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E23T18-%'");
                    });
        }
    }

    private long wardId(String code) {
        return runInTenant(
                "demob",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
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

    private void register(String token, String mrn, String name, long wardId) {
        Map<String, Object> body =
                new HashMap<>(
                        Map.of(
                                "medicalRecordNo",
                                mrn,
                                "name",
                                name,
                                "gender",
                                "M",
                                "birthDate",
                                "1957-02-11",
                                "wardId",
                                wardId,
                                "surgeryDate",
                                "2026-10-05",
                                "admissionDate",
                                "2026-09-28",
                                "dischargeDate",
                                "2026-10-12",
                                "dischargeStatus",
                                1,
                                "legacyNo",
                                LEGACY + "-" + mrn));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败：" + created);
    }

    /** 检索限定 wardIds 后只留本类前缀的患者（其余患者的存在与否不影响断言）。 */
    private List<String> search(String token, List<Long> wardIds) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("wardIds", wardIds);
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
