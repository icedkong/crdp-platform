package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * E13-T01 端到端（验收原文：重复病案号提示并阻止）。场景：
 *
 * <ol>
 *   <li>出院登记（A-PAT-003）：最小必填集落库，姓名 / 病案号只存加密列 + 盲索引（明文不进库），写 PATIENT_CREATE 操作审计（摘要不含明文）；候选接口返回病区 /
 *       科室 / 性别 / 出院状态；
 *   <li>查重与阻止（A-PAT-007、C-PAT-002，tasks.csv 验收）：check-duplicate 实时提示并给出范围内已有患者 脱敏摘要（可跳转）；重复登记 40902
 *       阻止；范围外同号只报 exists 不泄露摘要；姓名 + 出生日期相同提示 可能重复（不阻止）；
 *   <li>校验（40001）：最小必填集、日期先后（入院 ≤ 手术 ≤ 出院）、病区停用；
 *   <li>权限与隔离：无 followup:patient:create 40300；followup 模块关闭 40301；同一病案号在 demoa / demob 互不冲突（每院独立库）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T01-、病区 W-IT-B、角色
 * IT-R-NOCREATE、用户 IT-U-NOC）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientRegistrationIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T01";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                        + " AND (target IN (SELECT CONCAT('PATIENT:', id) FROM"
                                        + " pt_patient WHERE legacy_no LIKE 'IT-E13T01-%')"
                                        + " OR detail LIKE '出院登记被阻止：%')");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T01-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-NOC'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-NOC'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-NOC'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-NOCREATE'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-NOCREATE'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-NOCREATE'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-B'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 登记（A-PAT-003）

    @Test
    void registrationStoresEncryptedColumnsAndWritesAudit() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");

        Map<?, ?> options = get("/api/hospital/followup/patients/registration-options", token);
        assertEquals(0, codeOf(options));
        List<?> wards = (List<?>) data(options).get("wards");
        assertTrue(wards.size() >= 1, "候选病区应包含启用病区");
        assertEquals(
                List.of("1", "2", "3"),
                ((List<Map<?, ?>>) data(options).get("dischargeStatuses"))
                        .stream().map(item -> String.valueOf(item.get("code"))).toList(),
                "出院状态 1=存活 / 2=病重自动出院 / 3=死亡");

        String mrn = "IT-MRN-1001";
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        body(mrn, "张小明（示例）", wardId, "1955-01-01"));
        assertEquals(0, codeOf(created), "登记失败：" + created);
        String patientId = String.valueOf(data(created).get("id"));
        assertTrue(Long.parseLong(patientId) > 0);
        assertEquals("张*）", data(created).get("nameMasked"), "登记结果姓名脱敏");

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT mrn_cipher, mrn_blind, name_cipher, name_blind,"
                                            + " gender, discharge_status, legacy_no, ward_id"
                                            + " FROM pt_patient WHERE id = ?",
                                    Long.parseLong(patientId));
                    assertFalse(String.valueOf(row.get("mrn_cipher")).contains(mrn), "病案号明文不落库");
                    assertFalse(String.valueOf(row.get("name_cipher")).contains("张小明"), "姓名明文不落库");
                    assertTrue(
                            String.valueOf(row.get("mrn_blind")).length() >= 32
                                    && !String.valueOf(row.get("mrn_blind")).contains(mrn),
                            "盲索引为规范化后 HMAC 摘要（Base64），不含明文");
                    assertEquals("M", row.get("gender"));
                    assertEquals(1, ((Number) row.get("discharge_status")).intValue());
                    assertEquals(LEGACY + "-" + mrn, row.get("legacy_no"));
                    assertEquals(wardId, ((Number) row.get("ward_id")).longValue());

                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                            + " AND target = ? AND result = 'SUCCESS'",
                                    Integer.class,
                                    "PATIENT:" + patientId);
                    assertEquals(1, audits, "登记写 PATIENT_CREATE 审计");
                    String detail =
                            jdbc.queryForObject(
                                    "SELECT detail FROM aud_log WHERE event_type = 'PATIENT_CREATE'"
                                            + " AND target = ?",
                                    String.class,
                                    "PATIENT:" + patientId);
                    assertFalse(detail.contains(mrn), "审计不得出现病案号明文");
                });
    }

    // ---------------------------------------------------------------- 查重与阻止（A-PAT-007、C-PAT-002）

    @Test
    void duplicateMedicalRecordNoIsPromptedAndBlocked() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-2002";
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                token,
                                body(mrn, "李大中（示例）", wardId, "1948-06-01"))),
                "首次登记应成功");

        // 实时查重（A 规格 §4 验收：立即提示并给出已有患者链接数据）
        Map<?, ?> check =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        token,
                        Map.of(
                                "medicalRecordNo",
                                mrn,
                                "name",
                                "王小红（示例）",
                                "birthDate",
                                "1960-02-02"));
        assertEquals(0, codeOf(check));
        Map<?, ?> summary = (Map<?, ?>) data(check).get("patient");
        assertNotNull(summary, "范围内重复应返回已有患者脱敏摘要");
        assertEquals("李*）", summary.get("nameMasked"));
        assertNotNull(summary.get("id"), "前端按 id 跳转已有患者");

        // 重复登记被阻止（tasks.csv 验收：重复病案号提示并阻止）
        Map<?, ?> blocked =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        body(mrn, "王小红（示例）", wardId, "1960-02-02"));
        assertEquals(40902, codeOf(blocked), "重复病案号登记必须 40902 阻止");
        assertEquals("李*）", ((Map<?, ?>) dataOf(blocked).get("patient")).get("nameMasked"));

        runInTenant(
                "demoa",
                () -> {
                    Integer denied =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'PATIENT_CREATE' AND result = 'DENIED' AND error_code"
                                            + " = 40902",
                                    Integer.class);
                    assertTrue(denied >= 1, "被阻止的登记写 DENIED 审计");
                });
    }

    @Test
    void duplicateInAnotherWardBlocksButHidesSummary() {
        String admin = adminToken();
        long wardA = wardIdByCode("W-DEMO-1");
        long wardB = createWard("W-IT-B", "示例 IT 病区乙");
        String mrn = "IT-MRN-3003";
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                admin,
                                body(mrn, "赵大同（示例）", wardB, "1950-03-03"))),
                "管理员在另一病区先登记同号患者");

        // 病区医生（W-DEMO-1 范围）登记同号：全院唯一约束仍然阻止，但不泄露范围外摘要
        Map<?, ?> blocked =
                post(
                        "/api/hospital/followup/patients",
                        doctorToken(),
                        body(mrn, "钱小小（示例）", wardA, "1961-04-04"));
        assertEquals(40902, codeOf(blocked), "范围外同号同样阻止（本院唯一）");
        assertNull(dataOf(blocked).get("patient"), "范围外只报重复，不泄露摘要");

        Map<?, ?> check =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        doctorToken(),
                        Map.of("medicalRecordNo", mrn));
        assertEquals(0, codeOf(check));
        assertTrue((Boolean) data(check).get("exists"), "查重按全院报存在");
        assertNull(data(check).get("patient"), "范围外不下发摘要");
    }

    @Test
    void sameNameAndBirthDateIsFlaggedAsPossibleDuplicate() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                token,
                                body("IT-MRN-4004", "孙长海（示例）", wardId, "1952-08-08"))));

        Map<?, ?> check =
                post(
                        "/api/hospital/followup/patients/check-duplicate",
                        token,
                        Map.of(
                                "medicalRecordNo",
                                "IT-MRN-4005",
                                "name",
                                "孙长海（示例）",
                                "birthDate",
                                "1952-08-08"));
        assertEquals(0, codeOf(check));
        assertFalse((Boolean) data(check).get("exists"), "病案号未占用");
        List<?> possible = (List<?>) data(check).get("possibleMatches");
        assertEquals(1, possible.size(), "姓名 + 出生日期相同提示可能重复（C-PAT-002）");
        assertEquals("孙*）", ((Map<?, ?>) possible.get(0)).get("nameMasked"));
    }

    // ---------------------------------------------------------------- 校验（40001）

    @Test
    void rejectsInvalidRegistration() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");

        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                token,
                                body(null, "张小明（示例）", wardId, "1955-01-01"))),
                "病案号必填");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                token,
                                body("IT-MRN-5006", "张小明（示例）", 9_999_999L, "1955-01-01"))),
                "病区不存在");
        Map<String, Object> wrongOrder = body("IT-MRN-5007", "张小明（示例）", wardId, "1955-01-01");
        wrongOrder.put("surgeryDate", "2026-03-01");
        wrongOrder.put("admissionDate", "2026-02-20");
        wrongOrder.put("dischargeDate", "2026-02-28");
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients", token, wrongOrder)),
                "出院日期不能早于手术日期");
    }

    // ---------------------------------------------------------------- 登记即死亡（E23-T09 / UAT-SELF-05）

    /**
     * A 规格 §10 状态规则表「已死亡：……或出院状态 = 死亡」与 §5「批量入组：已在该计划中、已死亡、已从该计划 移除的患者跳过并说明」：出院状态 = 3（死亡）的患者登记后
     * followup_status 必须是 DEAD（不是 ACTIVE）、 状态时间线初始行记 DEAD，且批量入组预检 / 执行按 DEAD 跳过——修复前依赖列默认
     * ACTIVE，死亡患者可被 批量入组并继续创建随访任务（UAT 脚本 D-2 预期「死亡患者被跳过并说明」失败）。
     */
    @Test
    void dischargedDeadRegistrationMarksDeadAndSkipsBatchEnrollment() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-DEAD1";
        Map<String, Object> dead = body(mrn, "殳病故（示例）", wardId, "1950-06-06");
        dead.put("dischargeStatus", 3);

        Map<?, ?> created = post("/api/hospital/followup/patients", token, dead);
        assertEquals(0, codeOf(created), "死亡出院登记应成功（可建档但不加入，A 规格 §5）");
        long patientId = Long.parseLong(String.valueOf(data(created).get("id")));

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "DEAD",
                            jdbc.queryForObject(
                                    "SELECT followup_status FROM pt_patient WHERE id = ?",
                                    String.class,
                                    patientId),
                            "出院状态=死亡 → 登记即已死亡（A 规格 §10 状态规则表，UAT-SELF-05）");
                    assertEquals(
                            "DEAD",
                            jdbc.queryForObject(
                                    "SELECT status FROM pt_status_history WHERE patient_id = ?"
                                            + " AND source = 'REGISTRATION'",
                                    String.class,
                                    patientId),
                            "状态时间线初始行记 DEAD（登记，不写 ACTIVE）");
                    Integer enrollments =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM fu_enrollment WHERE patient_id = ?",
                                    Integer.class,
                                    patientId);
                    assertEquals(0, enrollments, "死亡患者不自动入组（Q35）");
                });

        // D-2 口径：批量入组预检与执行对死亡患者按 DEAD 跳过并说明（A 规格 §5）
        List<Long> plans =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT id FROM fu_plan WHERE code = 'A_ANNUAL_FU_PLAN'"
                                                + " AND status = 'PUBLISHED'",
                                        Long.class));
        if (!plans.isEmpty()) {
            Map<?, ?> outcome =
                    post(
                            "/api/hospital/followup/plans/"
                                    + plans.get(0)
                                    + "/enrollments/batch-preview",
                            token,
                            Map.of("patientIds", List.of(String.valueOf(patientId))));
            assertEquals(0, codeOf(outcome), "预检失败：" + outcome);
            List<Map<?, ?>> items = (List<Map<?, ?>>) data(outcome).get("items");
            assertEquals(1, items.size());
            assertEquals("SKIP", items.get(0).get("outcome"), "死亡患者预检应跳过");
            assertEquals("DEAD", items.get(0).get("reason"), "跳过原因应为 DEAD（D-2 预期）");
        }
    }

    // ---------------------------------------------------------------- 权限、模块与租户隔离

    @Test
    void withoutCreatePermissionRejectedWith40300() {
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-NOCREATE', 'IT 无登记权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-NOCREATE'",
                                            Long.class);
                            // 只授患者列表（21），不授新建患者（213）
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId = deptIdByCode("DEPT-CARDIO");
        String admin = adminToken();
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username", "IT-U-NOC",
                                "displayName", "IT 无登记权限用户（示例）",
                                "phone", "13900001301",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));

        // 初始密码可用，但强制改密期间其余接口 40305（PLT-AUTH-010），先改密再测 40300
        String token = hospitalToken("demoa", "IT-U-NOC", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                token,
                                body(
                                        "IT-MRN-6008",
                                        "周吴郑（示例）",
                                        wardIdByCode("W-DEMO-1"),
                                        "1955-01-01"))),
                "无 followup:patient:create 40300");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        String token = doctorToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(
                            post(
                                    "/api/hospital/followup/patients",
                                    token,
                                    body(
                                            "IT-MRN-7009",
                                            "冯陈楚（示例）",
                                            wardIdByCode("W-DEMO-1"),
                                            "1955-01-01"))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    @Test
    void sameMedicalRecordNoAllowedAcrossTenants() {
        String mrn = "IT-MRN-8010";
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                adminToken(),
                                body(mrn, "蒋沈韩（示例）", wardIdByCode("W-DEMO-1"), "1955-01-01"))),
                "demoa 登记成功");
        // 每院独立租户库：同一病案号在 demob 不受 demoa 约束（病案号本院唯一）
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long demobWard =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients",
                                demobAdmin,
                                body(mrn, "杨朱秦（示例）", demobWard, "1955-01-01"))),
                "demob 同号登记应成功（租户隔离）");
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> body(
            String mrn, String name, long wardId, String birthDate) {
        return new java.util.HashMap<>(
                Map.of(
                        "medicalRecordNo",
                        mrn == null ? "" : mrn,
                        "name",
                        name,
                        "gender",
                        "M",
                        "birthDate",
                        birthDate,
                        "wardId",
                        wardId,
                        "surgeryDate",
                        "2026-03-01",
                        "admissionDate",
                        "2026-02-25",
                        "dischargeDate",
                        "2026-03-08",
                        "dischargeStatus",
                        1,
                        "legacyNo",
                        LEGACY + "-" + mrn));
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
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

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        Object payload = response.get("data");
        return payload instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
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

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
