package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E13-T03 端到端（验收原文：联系方式改动保留历史）。场景：
 *
 * <ol>
 *   <li>联系方式版本（tech-plan §7 pt_contact「每个号码一个版本」）：新增只 INSERT——换号后版本 2 为当前、 版本 1 旧号保留；查询输出脱敏、明文只走
 *       /plaintext（当前电话1并入明文源）；与当前版本相同 40001；
 *   <li>状态历史：登记即写初始行 ACTIVE（OQ-71②），GET status-history 可查；
 *   <li>当前状态摘要：登记建行（pt_patient_summary，派生列随 E14 / E15 写入）；
 *   <li>权限与隔离：无 followup:patient:edit 40300（view 不受影响）；越范围患者 40400；模块关闭 40301； 同一号码在 demoa / demob
 *       两院独立落库（每院一库）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T03-、病区 W-IT-C、 角色
 * IT-R-NOEDIT / ITR-PLAIN3、用户 IT-U-NOE / itr.plain3.*）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientContactHistoryIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T03";
    private static final String PHONE_V1 = "13800001234";
    private static final String PHONE_V2 = "13900008888";

    private JdbcTemplate platform;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TenantModuleService moduleService;

    @BeforeAll
    void connectPlatform() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
    }

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        deleteChildren(jdbc);
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE access_method ="
                                        + " 'VIEW_PLAIN' AND object_id IN (SELECT CAST(p.id AS CHAR)"
                                        + " FROM pt_patient p WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%')");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('PATIENT_CREATE', 'PATIENT_CONTACT_ADD')"
                                        + " AND (target IN (SELECT CONCAT('PATIENT:', id) FROM"
                                        + " pt_patient WHERE legacy_no LIKE '"
                                        + LEGACY
                                        + "-%')"
                                        + " OR detail LIKE '%出院登记被阻止：%')");
                        jdbc.update(
                                "DELETE FROM pt_patient WHERE legacy_no LIKE '" + LEGACY + "-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-NOE' OR u.username LIKE"
                                        + " 'itr.plain3.%'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code IN ('IT-R-NOEDIT', 'ITR-PLAIN3')");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code IN ('IT-R-NOEDIT', 'ITR-PLAIN3')");
                        jdbc.update(
                                "DELETE FROM sys_role WHERE code IN ('IT-R-NOEDIT', 'ITR-PLAIN3')");
                        jdbc.update(
                                "DELETE FROM sys_user WHERE username LIKE 'IT-U-NOE'"
                                        + " OR username LIKE 'itr.plain3.%'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-C'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        restorePlainMenu();
        Long rateUserId = runInTenant("demoa", () -> userId(jdbc, "itr.plain3.rate"));
        if (rateUserId != null) {
            redis.delete("crdp:plain:reveal:1:" + rateUserId);
        }
        platform.update("DELETE FROM plt_security_alert WHERE user_name LIKE 'itr.plain3.%'");
    }

    /** 子表先于患者清理（按本套件 legacy 前缀圈定，全部自建数据）。 */
    private static void deleteChildren(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE c FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'");
        jdbc.update(
                "DELETE h FROM pt_status_history h JOIN pt_patient p ON h.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'");
        jdbc.update(
                "DELETE s FROM pt_patient_summary s JOIN pt_patient p ON s.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'");
    }

    // ---------------------------------------------------------------- 联系方式版本（验收：改动保留历史）

    @Test
    void contactChangeKeepsHistoryAndMasksOutput() {
        String token = doctorToken();
        long patientId = registerPatient(token, "IT-MRN-3101", "张小明（示例）");

        // 版本 1：登记后补录电话1
        Map<?, ?> v1 = post(contactPath(patientId), token, contactBody("PHONE_1", PHONE_V1, false));
        assertEquals(0, codeOf(v1), "新增版本 1 失败：" + v1);
        assertEquals(1, ((Number) data(v1).get("version")).intValue());
        assertEquals("138****1234", data(v1).get("phoneMasked"), "输出一律脱敏");
        assertTrue((Boolean) data(v1).get("current"));

        // 换号：新增版本 2（不覆盖旧值，验收「联系方式改动保留历史」）
        Map<?, ?> v2 = post(contactPath(patientId), token, contactBody("PHONE_2", PHONE_V2, true));
        assertEquals(0, codeOf(v2));
        assertEquals(1, ((Number) data(v2).get("version")).intValue(), "电话2 首个版本为 1");

        Map<?, ?> v3 = post(contactPath(patientId), token, contactBody("PHONE_1", PHONE_V2, true));
        assertEquals(0, codeOf(v3));
        assertEquals(2, ((Number) data(v3).get("version")).intValue(), "电话1 换号 = 版本 2");

        Map<?, ?> info = get(contactPath(patientId), token);
        assertEquals(0, codeOf(info));
        List<?> items = (List<?>) data(info).get("items");
        assertEquals(3, items.size(), "电话1 两版 + 电话2 一版全部返回（含历史）");

        Map<?, ?> phone1V2 = (Map<?, ?>) items.get(0);
        Map<?, ?> phone1V1 = (Map<?, ?>) items.get(1);
        Map<?, ?> phone2V1 = (Map<?, ?>) items.get(2);
        assertEquals("PHONE_1", phone1V2.get("slot"));
        assertEquals(2, ((Number) phone1V2.get("version")).intValue());
        assertEquals("139****8888", phone1V2.get("phoneMasked"));
        assertTrue((Boolean) phone1V2.get("current"), "电话1 当前版本 = 版本 2");
        assertEquals(1, ((Number) phone1V1.get("version")).intValue());
        assertEquals("138****1234", phone1V1.get("phoneMasked"));
        assertFalse((Boolean) phone1V1.get("current"), "旧版本保留但不再当前");
        assertEquals("PHONE_2", phone2V1.get("slot"));
        assertTrue((Boolean) phone2V1.get("current"));

        runInTenant(
                "demoa",
                () -> {
                    List<Map<String, Object>> rows =
                            jdbc.queryForList(
                                    "SELECT slot, phone_cipher, phone_blind, version FROM pt_contact"
                                            + " WHERE patient_id = ? ORDER BY slot, version",
                                    patientId);
                    assertEquals(3, rows.size(), "旧版本行物理保留（换号不覆盖）");
                    assertFalse(
                            String.valueOf(rows.get(0).get("phone_cipher")).contains(PHONE_V1),
                            "电话明文不落库");
                    assertFalse(
                            String.valueOf(rows.get(0).get("phone_cipher")).contains(PHONE_V2),
                            "电话明文不落库");
                    assertTrue(
                            !String.valueOf(rows.get(0).get("phone_blind"))
                                    .equals(String.valueOf(rows.get(1).get("phone_blind"))),
                            "不同号码盲索引不同");

                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'PATIENT_CONTACT_ADD' AND target = ? AND result ="
                                            + " 'SUCCESS'",
                                    Integer.class,
                                    "PATIENT:" + patientId);
                    assertEquals(3, audits, "每次新增版本写一条审计");
                    String detail =
                            jdbc.queryForObject(
                                    "SELECT detail FROM aud_log WHERE event_type ="
                                            + " 'PATIENT_CONTACT_ADD' AND target = ? LIMIT 1",
                                    String.class,
                                    "PATIENT:" + patientId);
                    assertFalse(detail.contains(PHONE_V1), "审计不得出现号码明文");
                    assertFalse(detail.contains(PHONE_V2), "审计不得出现号码明文");
                });

        // 与当前版本完全相同（号码 + 微信标志）→ 40001，不制造无变化版本
        assertEquals(
                40001,
                codeOf(post(contactPath(patientId), token, contactBody("PHONE_1", PHONE_V2, true))),
                "无变化的新增被拒绝");
    }

    @Test
    void plaintextPhoneComesFromCurrentPhone1Version() {
        long patientId = registerPatient(doctorToken(), "IT-MRN-3202", "李大中（示例）");
        assertEquals(
                0,
                codeOf(
                        post(
                                contactPath(patientId),
                                doctorToken(),
                                contactBody("PHONE_1", PHONE_V1, false))));
        assertEquals(
                0,
                codeOf(
                        post(
                                contactPath(patientId),
                                doctorToken(),
                                contactBody("PHONE_1", PHONE_V2, false))));

        // E13-T03 起电话并入明文源：phone = 当前电话1（版本 2），不是旧号
        String token = userWithPlainRole("itr.plain3.main");
        Map<?, ?> revealed =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/plaintext",
                        token,
                        Map.of("fields", List.of("phone"), "scene", "detail"));
        assertEquals(0, codeOf(revealed));
        Map<?, ?> values = (Map<?, ?>) data(revealed).get("values");
        assertEquals(PHONE_V2, values.get("phone"), "明文取当前电话1版本");
    }

    // ---------------------------------------------------------------- 状态历史与当前状态摘要

    @Test
    void registrationWritesInitialStatusRowAndSummaryRow() {
        long patientId = registerPatient(doctorToken(), "IT-MRN-3303", "王五六（示例）");

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> status =
                            jdbc.queryForMap(
                                    "SELECT status, effective_date, source FROM pt_status_history"
                                            + " WHERE patient_id = ?",
                                    patientId);
                    assertEquals("ACTIVE", status.get("status"), "登记即随访中（OQ-71②）");
                    assertEquals("REGISTRATION", status.get("source"));

                    Map<String, Object> summary =
                            jdbc.queryForMap(
                                    "SELECT latest_followup_date, next_plan_date,"
                                            + " baseline_completeness FROM pt_patient_summary"
                                            + " WHERE patient_id = ?",
                                    patientId);
                    assertNull(summary.get("latest_followup_date"), "随访派生列随 E15 写入");
                    assertNull(summary.get("next_plan_date"), "下次窗口随 E14 写入");
                    assertNull(summary.get("baseline_completeness"), "完整度随 E13-T02 写入");
                });

        Map<?, ?> history = get(statusPath(patientId), doctorToken());
        assertEquals(0, codeOf(history));
        List<?> items = (List<?>) history.get("data");
        assertEquals(1, items.size(), "登记初始行可经接口查询");
        assertEquals("ACTIVE", ((Map<?, ?>) items.get(0)).get("status"));
        assertEquals("REGISTRATION", ((Map<?, ?>) items.get(0)).get("source"));
    }

    // ---------------------------------------------------------------- 权限、范围、模块与租户隔离

    @Test
    void editRequiresPermissionWhileViewSucceeds() {
        long patientId = registerPatient(doctorToken(), "IT-MRN-3404", "冯陈楚（示例）");
        long roleId = noEditRoleId();
        String admin = adminToken();
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username", "IT-U-NOE",
                                "displayName", "IT 无编辑权限用户（示例）",
                                "phone", "13900003101",
                                "mainDeptId", deptIdByCode("DEPT-CARDIO"),
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-NOE", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");

        assertEquals(0, codeOf(get(contactPath(patientId), token)), "followup:patient:view 可查询");
        assertEquals(0, codeOf(get(statusPath(patientId), token)), "状态历史同样只要 view");
        assertEquals(
                40300,
                codeOf(post(contactPath(patientId), token, contactBody("PHONE_1", PHONE_V1, null))),
                "无 followup:patient:edit 40300");
    }

    @Test
    void outOfScopePatientGets40400ForContactAndStatusHistory() {
        long wardA = wardIdByCode("W-DEMO-1");
        long wardB = createWard("W-IT-C", "IT 越范围病区（示例）");
        String token = doctorToken(); // doctor.demo 只负责 W-DEMO-1
        long inScope = registerPatient(token, "IT-MRN-3505", "蒋沈韩（示例）");
        long outOfScope = registerPatientInWard(token, "IT-MRN-3606", "杨朱秦（示例）", wardB);

        assertEquals(0, codeOf(get(contactPath(inScope), token)), "范围内患者可见");
        assertEquals(40400, codeOf(get(contactPath(outOfScope), token)), "越范围患者 40400（不暴露存在）");
        assertEquals(
                40400,
                codeOf(
                        post(
                                contactPath(outOfScope),
                                token,
                                contactBody("PHONE_1", PHONE_V1, null))));
        assertEquals(40400, codeOf(get(statusPath(outOfScope), token)));
        assertEquals(
                0, codeOf(get(contactPath(outOfScope), adminToken())), "全院范围（TENANT_ADMIN）可见同一患者");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        long patientId = registerPatient(doctorToken(), "IT-MRN-3707", "何吕施（示例）");
        String token = doctorToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, codeOf(get(contactPath(patientId), token)), "模块关闭 40301");
            assertEquals(
                    40301,
                    codeOf(
                            post(
                                    contactPath(patientId),
                                    token,
                                    contactBody("PHONE_1", PHONE_V1, null))));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    @Test
    void samePhoneIsIndependentAcrossTenants() {
        // 每院独立租户库：同一号码在 demoa / demob 各自落版本，互不可见
        long demoaId = registerPatient(doctorToken(), "IT-MRN-3808", "高林钟（示例）");
        assertEquals(
                0,
                codeOf(
                        post(
                                contactPath(demoaId),
                                doctorToken(),
                                contactBody("PHONE_1", PHONE_V1, null))));

        long demobWard =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        Map<String, Object> body = registrationBody("IT-MRN-3808", "徐邱洛（示例）", demobWard);
        Map<?, ?> created = post("/api/hospital/followup/patients", demobAdmin, body);
        assertEquals(0, codeOf(created), "demob 登记失败：" + created);
        long demobId = Long.parseLong(String.valueOf(data(created).get("id")));
        assertEquals(
                0,
                codeOf(
                        post(
                                contactPath(demobId),
                                demobAdmin,
                                contactBody("PHONE_1", PHONE_V1, null))),
                "同一号码在另一院独立登记版本（租户隔离）");
        assertEquals(
                40001,
                codeOf(
                        post(
                                contactPath(demobId),
                                demobAdmin,
                                contactBody("PHONE_1", PHONE_V1, null))),
                "demob 同样按自己的当前版本判重（同一套规则、库各自独立）");
    }

    // ---------------------------------------------------------------- 基础设施（与 PatientPlaintextIT /
    // PatientRegistrationIT 同口径）

    /** 建持有按钮 211（followup:patient:plain）的角色并建用户登录；敏感权限需平台先放开可分配范围。 */
    private String userWithPlainRole(String username) {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                    + " VALUES ('ITR-PLAIN3', 'IT 明文查看角色三（示例）', 0, 'ENABLED',"
                                    + " 'CUSTOM')");
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'ITR-PLAIN3'",
                                    Long.class);
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 211)", roleId);
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) SELECT ?, 'IT 明文用户三（示例）',"
                                    + " password_hash, 'ENABLED', 0 FROM sys_user WHERE username ="
                                    + " 'doctor.demo'",
                            username);
                    long userId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    Long.class,
                                    username);
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                            userId,
                            roleId);
                });
        releasePlainMenu();
        return login("demoa", username);
    }

    private void releasePlainMenu() {
        platform.update("DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 211");
        platform.update(
                "INSERT INTO plt_tenant_scope (tenant_id, menu_id, assignable, reason)"
                        + " VALUES (1, 211, 1, 'IT 联系方式明文用例放开（示例）')");
    }

    private void restorePlainMenu() {
        platform.update("DELETE FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 211");
        platform.update("DELETE FROM plt_scope_log WHERE reason LIKE 'IT 联系方式明文用例%'");
    }

    private long noEditRoleId() {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES ('IT-R-NOEDIT', 'IT 无编辑权限（示例）', 0, 'ENABLED')");
                    long id =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'IT-R-NOEDIT'",
                                    Long.class);
                    // 只授患者档案（21 = followup:patient:view），不授编辑按钮（214）
                    jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)", id);
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            id);
                    return id;
                });
    }

    private long registerPatient(String token, String mrn, String name) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        registrationBody(mrn, name, wardIdByCode("W-DEMO-1")));
        assertEquals(0, codeOf(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    private long registerPatientInWard(String token, String mrn, String name, long wardId) {
        Map<?, ?> created =
                post("/api/hospital/followup/patients", token, registrationBody(mrn, name, wardId));
        assertEquals(0, codeOf(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    private static Map<String, Object> registrationBody(String mrn, String name, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("surgeryDate", "2026-03-01");
        body.put("admissionDate", "2026-02-25");
        body.put("dischargeDate", "2026-03-08");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        return body;
    }

    private static Map<String, Object> contactBody(String slot, String phone, Boolean wechatAdded) {
        Map<String, Object> body = new HashMap<>();
        body.put("slot", slot);
        body.put("phone", phone);
        if (wechatAdded != null) {
            body.put("wechatAdded", wechatAdded);
        }
        return body;
    }

    private static String contactPath(long patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/contact-info";
    }

    private static String statusPath(long patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/status-history";
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
    }

    private String login(String tenant, String username) {
        return hospitalToken(tenant, username, PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
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
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private static Long userId(JdbcTemplate jdbc, String username) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
