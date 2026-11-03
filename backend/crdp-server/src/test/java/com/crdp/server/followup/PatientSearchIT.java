package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.HashMap;
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
 * E13-T04 端到端（验收原文：按病区隔离，按姓名精确检索）。场景：
 *
 * <ol>
 *   <li>列表与列（A-PAT-001）：doctor.demo（W-DEMO-1 病区范围）检索返回范围内患者，行内姓名 / 病案号只含脱敏值；批量查询写 BATCH_QUERY
 *       数据访问审计；筛选候选返回启用病区；
 *   <li>按病区隔离（A-WARD-004 / E07-T02）：登记到另一病区的患者对病区医生不可见（数据范围改写，非查询条件）；
 *   <li>按姓名精确检索（A-PAT-002）：keyword = 姓名只返回同名患者（盲索引相等，非模糊），keyword = 病案号同样精确命中；分页与排序、状态 / 病区 /
 *       出院日期筛选生效；
 *   <li>校验与权限：非法状态 40001；无 followup:patient:view 40300；followup 模块关闭 40301；demoa / demob 双租户互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T04-、病区 W-IT-T4、角色
 * IT-R-NOVIEW、用户 IT-U-NV）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientSearchIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T04";
    private static final String NAME_A = "张小明（示例）";
    private static final String NAME_OTHER = "李大中（示例）";

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
                                "DELETE FROM aud_data_access_log WHERE action = 'BATCH_QUERY'"
                                        + " AND object_type = 'PATIENT'"
                                        + " AND description = '检索患者列表'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T04-%'");
                        jdbc.update(
                                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                                        + " (SELECT id FROM pt_patient)");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-NV'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-NV'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-NV'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-NOVIEW'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-NOVIEW'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-NOVIEW'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T4'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 列表与列（A-PAT-001）

    @Test
    void searchReturnsMaskedRowsAndWritesBatchQueryAudit() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        register(token, "IT-MRN-T4-01", NAME_A, wardId, "1955-01-01");

        Map<?, ?> page = post("/api/hospital/followup/patients/search", token, body(null, null));

        assertEquals(0, codeOf(page), "检索失败：" + page);
        List<?> items = (List<?>) data(page).get("items");
        assertTrue(items.size() >= 1, "范围内至少一名患者");
        Map<?, ?> row =
                items.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(
                                item ->
                                        legacyNo(item) != null
                                                && legacyNo(item).equals(LEGACY + "-IT-MRN-T4-01"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(row, "应包含本用例登记的患者");
        assertEquals("张*）", row.get("name"), "行内姓名脱敏（列表不提供逐行明文）");
        assertEquals("IT********01", row.get("patientNo"), "本系统编号 = 病案号脱敏（前 2 后 2）");
        assertEquals("M", row.get("gender"));
        assertNotNull(row.get("age"), "手术年龄 = 手术日期 − 出生日期（周岁）");
        assertEquals("虚构病区一", row.get("wardName"));
        assertEquals("ACTIVE", row.get("status"), "登记即随访中（OQ-71②）");
        assertEquals((Number) 1, pageNoOf(page));
        assertEquals((Number) 20, data(page).get("pageSize"));

        runInTenant(
                "demoa",
                () -> {
                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_data_access_log"
                                            + " WHERE action = 'BATCH_QUERY' AND object_type ="
                                            + " 'PATIENT' AND description = '检索患者列表'"
                                            + " AND result = 'SUCCESS'",
                                    Integer.class);
                    assertTrue(audits >= 1, "批量查询写 BATCH_QUERY 审计");
                });
    }

    @Test
    void filterOptionsReturnsEnabledWards() {
        Map<?, ?> options = get("/api/hospital/followup/patients/filter-options", doctorToken());
        assertEquals(0, codeOf(options));
        List<?> wards = (List<?>) data(options).get("wards");
        assertTrue(wards.size() >= 1, "候选病区应包含启用病区");
    }

    // ---------------------------------------------------------------- 按病区隔离（验收）

    @Test
    void searchIsIsolatedByWardThroughDataScope() {
        String admin = adminToken();
        long otherWard = createWard("W-IT-T4", "示例 IT 隔离病区");
        register(admin, "IT-MRN-T4-02", NAME_OTHER, otherWard, "1948-06-01");
        register(admin, "IT-MRN-T4-03", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");

        // doctor.demo 只授权 W-DEMO-1（种子 sys_user_ward）：另一病区患者不可见
        Map<?, ?> page =
                post("/api/hospital/followup/patients/search", doctorToken(), body(null, null));
        assertEquals(0, codeOf(page));
        List<?> items = (List<?>) data(page).get("items");
        String otherWardLegacy = LEGACY + "-IT-MRN-T4-02";
        assertFalse(
                items.stream().anyMatch(item -> otherWardLegacy.equals(legacyNo(item))),
                "其他病区患者不可见（数据范围改写，而非查询条件）");
        String ownWardLegacy = LEGACY + "-IT-MRN-T4-03";
        assertTrue(
                items.stream().anyMatch(item -> ownWardLegacy.equals(legacyNo(item))), "本病区患者可见");

        // 管理员（ALL 范围）能同时看到两名
        Map<?, ?> adminPage =
                post("/api/hospital/followup/patients/search", admin, body(null, null));
        List<?> adminItems = (List<?>) data(adminPage).get("items");
        assertEquals(
                2,
                adminItems.stream()
                        .filter(
                                item ->
                                        legacyNo(item) != null
                                                && legacyNo(item).startsWith(LEGACY + "-"))
                        .count(),
                "ALL 范围可见两个病区的全部 IT 患者");
    }

    // ---------------------------------------------------------------- 按姓名精确检索（验收）

    @Test
    void searchMatchesNameExactlyViaBlindIndex() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        register(token, "IT-MRN-T4-11", NAME_A, wardId, "1955-01-01");
        register(token, "IT-MRN-T4-12", NAME_OTHER, wardId, "1948-06-01");

        // 姓名精确：同名一名命中，其余（含姓名前缀相同的「张小明（示例）」子串情形）不命中
        Map<?, ?> byName =
                post("/api/hospital/followup/patients/search", token, body(NAME_A, null));
        assertEquals(0, codeOf(byName));
        List<?> nameItems = (List<?>) data(byName).get("items");
        assertEquals(1, nameItems.size(), "按姓名精确检索只返回同名患者（盲索引相等，非模糊）");
        assertEquals("张*）", ((Map<?, ?>) nameItems.get(0)).get("name"));

        // 姓名子串 / 部分输入不命中（精确语义）
        Map<?, ?> byPartial =
                post("/api/hospital/followup/patients/search", token, body("张小明", null));
        assertEquals(0, ((Number) data(byPartial).get("total")).intValue(), "部分姓名不命中");

        // 病案号精确：同一 keyword 落在病案号盲索引上
        Map<?, ?> byMrn =
                post("/api/hospital/followup/patients/search", token, body("IT-MRN-T4-12", null));
        assertEquals(1, ((Number) data(byMrn).get("total")).intValue(), "按病案号精确命中");
        assertEquals("李*）", ((Map<?, ?>) ((List<?>) data(byMrn).get("items")).get(0)).get("name"));
    }

    @Test
    void searchAppliesFiltersPagingAndSort() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        register(token, "IT-MRN-T4-21", NAME_A, wardId, "1955-01-01");
        register(token, "IT-MRN-T4-22", NAME_OTHER, wardId, "1948-06-01");

        // 状态筛选
        Map<String, Object> statusBody = body(null, null);
        ((Map<String, Object>) statusBody.get("filters")).put("status", List.of("ACTIVE"));
        Map<?, ?> byStatus = post("/api/hospital/followup/patients/search", token, statusBody);
        assertTrue(((Number) data(byStatus).get("total")).intValue() >= 2, "随访中筛选命中");

        Map<String, Object> emptyStatusBody = body(null, null);
        ((Map<String, Object>) emptyStatusBody.get("filters")).put("status", List.of("PAUSED"));
        Map<?, ?> byPaused = post("/api/hospital/followup/patients/search", token, emptyStatusBody);
        assertEquals(0, ((Number) data(byPaused).get("total")).intValue(), "无已暂停患者");

        // 病区筛选（W-DEMO-1）
        Map<String, Object> wardBody = body(null, null);
        ((Map<String, Object>) wardBody.get("filters")).put("wardIds", List.of(wardId));
        Map<?, ?> byWard = post("/api/hospital/followup/patients/search", token, wardBody);
        assertTrue(((Number) data(byWard).get("total")).intValue() >= 2);

        // 出院日期范围（登记日期固定 2026-03-08）
        Map<String, Object> rangeBody = body(null, null);
        Map<String, Object> filters = (Map<String, Object>) rangeBody.get("filters");
        filters.put("dischargeFrom", "2026-03-01");
        filters.put("dischargeTo", "2026-03-31");
        Map<?, ?> byRange = post("/api/hospital/followup/patients/search", token, rangeBody);
        assertEquals(2, ((Number) data(byRange).get("total")).intValue(), "出院日期范围内命中");

        filters.put("dischargeFrom", "2027-01-01");
        filters.put("dischargeTo", "2027-12-31");
        Map<?, ?> byFarRange = post("/api/hospital/followup/patients/search", token, rangeBody);
        assertEquals(0, ((Number) data(byFarRange).get("total")).intValue(), "范围外不命中");

        // 分页 + 出院日期倒序（稳定校验总数与页大小）
        Map<String, Object> pagedBody = body(null, null);
        pagedBody.put("pageNo", 1);
        pagedBody.put("pageSize", 1);
        pagedBody.put("sort", List.of(Map.of("field", "dischargeDate", "order", "desc")));
        Map<?, ?> paged = post("/api/hospital/followup/patients/search", token, pagedBody);
        assertEquals(1, ((List<?>) data(paged).get("items")).size(), "pageSize=1 只返回一行");
        assertEquals((Number) 1, pageNoOf(paged));
    }

    // ---------------------------------------------------------------- 校验、权限与租户隔离

    @Test
    void rejectsInvalidFiltersWith40001() {
        String token = doctorToken();
        Map<String, Object> bad = body(null, null);
        ((Map<String, Object>) bad.get("filters")).put("status", List.of("NOT_A_STATUS"));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, bad)),
                "非法状态 40001");

        Map<String, Object> badSort = body(null, null);
        badSort.put("sort", List.of(Map.of("field", "nameCipher", "order", "asc")));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, badSort)),
                "排序字段白名单外 40001");

        Map<String, Object> badRange = body(null, null);
        Map<String, Object> filters = (Map<String, Object>) badRange.get("filters");
        filters.put("dischargeFrom", "2026-12-31");
        filters.put("dischargeTo", "2026-01-01");
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, badRange)),
                "日期区间先后 40001");

        Map<String, Object> badPage = body(null, null);
        badPage.put("pageNo", 0);
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, badPage)),
                "pageNo 从 1 开始");
    }

    @Test
    void withoutViewPermissionRejectedWith40300() {
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-NOVIEW', 'IT 无列表权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-NOVIEW'",
                                            Long.class);
                            // 只授随访任务（22），不授患者档案（21）
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 22)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId = deptIdByCode("DEPT-CARDIO");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken(),
                        Map.of(
                                "username", "IT-U-NV",
                                "displayName", "IT 无列表权限用户（示例）",
                                "phone", "13900001302",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-NV", temporary);
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
                codeOf(post("/api/hospital/followup/patients/search", token, body(null, null))),
                "无 followup:patient:view 40300");
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/patients/filter-options", token)),
                "筛选候选同样需要 view 权限");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        String token = doctorToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(post("/api/hospital/followup/patients/search", token, body(null, null))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    @Test
    void searchResultsAreIsolatedAcrossTenants() {
        register(adminToken(), "IT-MRN-T4-31", NAME_A, wardIdByCode("W-DEMO-1"), "1955-01-01");
        // demob 同名患者：独立租户库，demoa 的检索互不可见
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        long demobWard =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        registerAsTenant("demob", demobAdmin, "IT-MRN-T4-31", NAME_OTHER, demobWard, "1948-06-01");

        Map<?, ?> demoaPage =
                post("/api/hospital/followup/patients/search", adminToken(), body(NAME_A, null));
        assertEquals(1, ((Number) data(demoaPage).get("total")).intValue(), "demoa 姓名精确只命中本院患者");

        Map<?, ?> demobPage =
                post("/api/hospital/followup/patients/search", demobAdmin, body(NAME_OTHER, null));
        assertEquals(
                1, ((Number) data(demobPage).get("total")).intValue(), "demob 姓名精确只命中本院患者（双租户隔离）");
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> body(String keyword, Object ignored) {
        Map<String, Object> filters = new HashMap<>();
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 20);
        if (keyword != null) {
            body.put("keyword", keyword);
        }
        body.put("filters", filters);
        return body;
    }

    private static String legacyNo(Object item) {
        Object value = ((Map<?, ?>) item).get("legacyNo");
        return value == null ? null : String.valueOf(value);
    }

    private static Number pageNoOf(Map<?, ?> page) {
        return (Number) data(page).get("pageNo");
    }

    private void register(String token, String mrn, String name, long wardId, String birthDate) {
        registerAsTenant("demoa", token, mrn, name, wardId, birthDate);
    }

    private void registerAsTenant(
            String tenant, String token, String mrn, String name, long wardId, String birthDate) {
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
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败（" + tenant + "）：" + created);
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
