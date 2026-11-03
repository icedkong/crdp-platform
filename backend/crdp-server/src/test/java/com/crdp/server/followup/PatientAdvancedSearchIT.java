package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * E13-T06 端到端（验收原文：只能引用字典字段；无原始 SQL）。场景：
 *
 * <ol>
 *   <li>字段目录（A-PAT-014）：search-fields 由字段字典生成——含手术 / 入院 / 出院日期、性别（M/F）、
 *       病区（ward.id）、出院状态（1–3）；敏感字段（姓名 / 病案号）与 JSON 字段不出现；
 *   <li>高级检索条件树（A-PAT-014 / C-PAT-013）：等于 / 介于 / 为空等运算在真实库命中预期患者， 目录外字段与非法取值 40001；
 *   <li>无原始 SQL + 自动加病区条件（A-NFR-009）：条件值只作为预编译参数（SQL 注入样例原样绑定不生效）、
 *       病区隔离由数据范围改写（另一病区患者对病区医生不可见，即使条件放行）；
 *   <li>保存的筛选（A-PAT-015）：保存 / 应用 / 重命名 / 删除仅本人可见（第二用户不可见、不可删他人）， 检索词 keyword
 *       不被保存，每人上限与条件校验生效；权限与模块关闭 40300 / 40301。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T06-、 fu_saved_filter
 * 全清）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientAdvancedSearchIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T06";
    private static final String NAME_A = "张小明（示例）";
    private static final String NAME_OTHER = "李大中（示例）";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action LIKE 'SAVED_FILTER_%'"
                                    + " AND module = 'followup'");
                    jdbc.update("DELETE FROM fu_saved_filter");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T06-%'");
                    jdbc.update(
                            "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                                    + " (SELECT id FROM pt_patient)");
                    jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T6'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username = 'IT-U-T6'");
                    jdbc.update(
                            "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                    + " ON u.id = ph.user_id WHERE u.username = 'IT-U-T6'");
                    jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-T6'");
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'IT-R-T6'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code = 'IT-R-T6'");
                    jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-T6'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 字段目录（A-PAT-014）

    @Test
    void searchFieldsComeFromDictionaryWithBindings() {
        Map<?, ?> response = get("/api/hospital/followup/patients/search-fields", doctorToken());
        assertEquals(0, codeOf(response), "目录查询失败：" + response);
        List<?> fields = (List<?>) data(response).get("fields");
        assertTrue(fields.size() >= 5, "目录应包含字典实体列字段");

        List<String> codes =
                fields.stream().map(f -> String.valueOf(((Map<?, ?>) f).get("field"))).toList();
        assertTrue(codes.contains("surgery_date"), "手术日期（字典 + 绑定）");
        assertTrue(codes.contains("discharge_date"), "出院日期");
        assertTrue(codes.contains("ward"), "病区（病区字典选项）");
        assertTrue(codes.contains("discharge_status"), "出院状态（选项集）");
        assertFalse(codes.contains("patient_name"), "敏感加密字段不进条件树（走 keyword 盲索引）");
        assertFalse(codes.contains("mrn"), "病案号不进条件树");
        assertFalse(codes.contains("height_cm"), "JSON 存储字段（随 E13-T02）不进目录");

        Map<?, ?> gender =
                fields.stream()
                        .map(f -> (Map<?, ?>) f)
                        .filter(f -> "gender".equals(f.get("field")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of("M", "F"),
                ((List<?>) gender.get("options"))
                        .stream().map(o -> ((Map<?, ?>) o).get("value")).toList(),
                "性别按存储口径 M / F");
        assertTrue(((List<?>) gender.get("ops")).containsAll(List.of("EQ", "NE", "IS_NULL")));
    }

    // ---------------------------------------------------------------- 条件树检索（A-PAT-014、C-PAT-013）

    @Test
    void advancedConditionFiltersPatientsByDictionaryFields() {
        String token = doctorToken();
        long wardId = wardIdByCode("W-DEMO-1");
        register(token, "IT-MRN-T6-01", NAME_A, wardId, "1955-01-01", "2026-03-08", 1);
        register(token, "IT-MRN-T6-02", NAME_OTHER, wardId, "1948-06-01", "2027-01-15", 3);
        // 患者类型登记接口暂不采集（随编辑 / 导入维护），直接置业务字典编码用于条件验证
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET patient_type = 'ELECTIVE'"
                                        + " WHERE legacy_no = ?",
                                LEGACY + "-IT-MRN-T6-02"));

        // 出院日期介于 2026 年 → 只命中 01；空 patient_type → 只命中 02（并且）
        Map<String, Object> body = baseBody();
        body.put(
                "condition",
                Map.of(
                        "logic",
                        "AND",
                        "items",
                        List.of(
                                Map.of(
                                        "field",
                                        "discharge_date",
                                        "op",
                                        "BETWEEN",
                                        "value",
                                        List.of("2026-01-01", "2026-12-31")),
                                Map.of("field", "patient_type", "op", "IS_NULL"))));
        Map<?, ?> page = post("/api/hospital/followup/patients/search", token, body);
        assertEquals(0, codeOf(page));
        assertEquals(1, ((Number) data(page).get("total")).intValue(), "介于 + 为空（并且）命中一名患者");
        assertEquals(
                LEGACY + "-IT-MRN-T6-01",
                legacyOf(((Map<?, ?>) ((List<?>) data(page).get("items")).get(0))));

        // 出院状态 = 3（死亡）→ 命中 02；性别 = M（或者）
        Map<String, Object> orBody = baseBody();
        orBody.put(
                "condition",
                Map.of(
                        "logic",
                        "OR",
                        "items",
                        List.of(
                                Map.of("field", "discharge_status", "op", "EQ", "value", 3),
                                Map.of("field", "gender", "op", "EQ", "value", "F"))));
        Map<?, ?> orPage = post("/api/hospital/followup/patients/search", token, orBody);
        assertEquals(1, ((Number) data(orPage).get("total")).intValue(), "等于（或者）命中出院状态 3 的患者");
        assertEquals(
                LEGACY + "-IT-MRN-T6-02",
                legacyOf(((Map<?, ?>) ((List<?>) data(orPage).get("items")).get(0))));

        // 病区字段（字典生成 + 病区字典选项，值为 ward.id）
        Map<String, Object> wardBody = baseBody();
        wardBody.put(
                "condition",
                Map.of(
                        "items",
                        List.of(Map.of("field", "ward", "op", "EQ", "value", (int) wardId))));
        Map<?, ?> wardPage = post("/api/hospital/followup/patients/search", token, wardBody);
        assertEquals(2, ((Number) data(wardPage).get("total")).intValue(), "病区等于本病区命中两名");
    }

    @Test
    void conditionOutsideCatalogOrInvalidRejectedWith40001() {
        String token = doctorToken();

        Map<String, Object> unknownField = baseBody();
        unknownField.put(
                "condition",
                Map.of(
                        "items",
                        List.of(
                                Map.of(
                                        "field",
                                        "name_cipher",
                                        "op",
                                        "EQ",
                                        "value",
                                        "' OR '1'='1"))));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, unknownField)),
                "目录外字段（字典字段才可引用）40001");

        Map<String, Object> rawSql = baseBody();
        rawSql.put(
                "condition",
                Map.of(
                        "items",
                        List.of(
                                Map.of(
                                        "field",
                                        "discharge_date; DROP TABLE pt_patient",
                                        "op",
                                        "EQ",
                                        "value",
                                        "2026-01-01"))));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, rawSql)),
                "SQL 文本当字段名 40001（无原始 SQL）");

        Map<String, Object> badOption = baseBody();
        badOption.put(
                "condition",
                Map.of("items", List.of(Map.of("field", "gender", "op", "EQ", "value", "X"))));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/patients/search", token, badOption)),
                "选项白名单外取值 40001");

        // 注入样例作为取值：原样绑定不生效（参数化），返回空结果而不是全部命中
        Map<String, Object> injectValue = baseBody();
        injectValue.put(
                "condition",
                Map.of(
                        "items",
                        List.of(
                                Map.of(
                                        "field",
                                        "patient_type",
                                        "op",
                                        "EQ",
                                        "value",
                                        "ELECTIVE' OR '1'='1"))));
        Map<?, ?> injectPage = post("/api/hospital/followup/patients/search", token, injectValue);
        assertEquals(0, codeOf(injectPage));
        assertEquals(
                0,
                ((Number) data(injectPage).get("total")).intValue(),
                "注入样例只作为参数值比较（A-NFR-009 参数化）");
    }

    // ---------------------------------------------------------------- 自动加病区条件（A-NFR-009）

    @Test
    void advancedSearchIsWardIsolatedByDataScope() {
        String admin = adminToken();
        long otherWard = createWard("W-IT-T6", "示例 IT 隔离病区");
        // 病区医生范围内 1 名 + 范围外（另一病区）1 名，两者都满足条件
        register(
                doctorToken(),
                "IT-MRN-T6-11",
                NAME_A,
                wardIdByCode("W-DEMO-1"),
                "1955-01-01",
                "2026-03-08",
                1);
        register(admin, "IT-MRN-T6-12", NAME_OTHER, otherWard, "1948-06-01", "2026-03-08", 1);

        Map<String, Object> body = baseBody();
        body.put(
                "condition",
                Map.of(
                        "items",
                        List.of(
                                Map.of(
                                        "field",
                                        "discharge_date",
                                        "op",
                                        "EQ",
                                        "value",
                                        "2026-03-08"))));
        Map<?, ?> page = post("/api/hospital/followup/patients/search", doctorToken(), body);
        assertEquals(0, codeOf(page));
        List<?> items = (List<?>) data(page).get("items");
        assertEquals(1, items.size(), "条件放行但数据范围自动追加病区条件（越范围不可见）");
        assertEquals(LEGACY + "-IT-MRN-T6-11", legacyOf((Map<?, ?>) items.get(0)));

        // 管理员（ALL 范围）能看到两个病区各一名
        Map<?, ?> adminPage = post("/api/hospital/followup/patients/search", admin, body);
        assertEquals(2, ((Number) data(adminPage).get("total")).intValue(), "ALL 范围无病区收窄");
    }

    // ---------------------------------------------------------------- 保存的筛选（A-PAT-015）

    @Test
    void savedFilterLifecycleIsPersonalOnly() {
        String token = doctorToken();
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/saved-filters",
                        token,
                        Map.of(
                                "name",
                                "上半年出院（示例）",
                                "payload",
                                Map.of(
                                        "filters",
                                        Map.of("wardIds", List.of(wardIdByCode("W-DEMO-1"))),
                                        "condition",
                                        Map.of(
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "field",
                                                                "discharge_date",
                                                                "op",
                                                                "BETWEEN",
                                                                "value",
                                                                List.of(
                                                                        "2026-01-01",
                                                                        "2026-06-30")))))));
        assertEquals(0, codeOf(created), "保存失败：" + created);
        String id = String.valueOf(data(created).get("id"));
        assertEquals("上半年出院（示例）", data(created).get("name"));

        // 本人清单可见；检索词不进保存的筛选
        Map<?, ?> mine = get("/api/hospital/followup/saved-filters", token);
        assertEquals(1, ((List<?>) data(mine).get("items")).size());
        Map<?, ?> payload =
                (Map<?, ?>) ((Map<?, ?>) ((List<?>) data(mine).get("items")).get(0)).get("payload");
        assertFalse(payload.containsKey("keyword"), "payload 不含检索词");

        // 重命名（修改）与删除；第二用户不可见、删他人 40400
        Map<?, ?> renamed =
                put(
                        "/api/hospital/followup/saved-filters/" + id,
                        token,
                        Map.of("name", "上半年出院 v2（示例）"));
        assertEquals(0, codeOf(renamed));
        assertEquals("上半年出院 v2（示例）", data(renamed).get("name"));

        String other = hospitalToken("demoa", "nurse.demo", PASSWORD);
        Map<?, ?> otherList = get("/api/hospital/followup/saved-filters", other);
        assertEquals(0, ((List<?>) data(otherList).get("items")).size(), "个人筛选仅本人可见");
        assertEquals(
                40400,
                codeOf(delete("/api/hospital/followup/saved-filters/" + id, other)),
                "删他人筛选 40400（不暴露存在性）");

        assertEquals(0, codeOf(delete("/api/hospital/followup/saved-filters/" + id, token)));
        Map<?, ?> afterDelete = get("/api/hospital/followup/saved-filters", token);
        assertEquals(0, ((List<?>) data(afterDelete).get("items")).size());

        runInTenant(
                "demoa",
                () -> {
                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log"
                                            + " WHERE action LIKE 'SAVED_FILTER_%' AND result ="
                                            + " 'SUCCESS'",
                                    Integer.class);
                    assertTrue(audits >= 3, "保存 / 修改 / 删除写 OPERATION 审计");
                });
    }

    @Test
    void savedFilterRejectsKeywordAndInvalidCondition() {
        String token = doctorToken();
        Map<?, ?> withKeyword =
                post(
                        "/api/hospital/followup/saved-filters",
                        token,
                        Map.of("name", "示例", "payload", Map.of("keyword", NAME_A)));
        assertEquals(40001, codeOf(withKeyword), "检索词不进保存的筛选");

        Map<?, ?> badCondition =
                post(
                        "/api/hospital/followup/saved-filters",
                        token,
                        Map.of(
                                "name",
                                "示例",
                                "payload",
                                Map.of(
                                        "condition",
                                        Map.of(
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "field",
                                                                "not_a_dict_field",
                                                                "op",
                                                                "EQ",
                                                                "value",
                                                                1))))));
        assertEquals(40001, codeOf(badCondition), "保存时条件树按字段目录校验（只能引用字典字段）");
    }

    // ---------------------------------------------------------------- 权限与模块开关

    @Test
    void withoutViewPermissionRejectedWith40300AndClosedModule40301() {
        // 专用无列表权限用户（只授随访任务菜单 22，不授患者档案菜单 21；与 PatientSearchIT 同构）
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-T6', 'IT 无列表权限（示例）', 0, 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-T6'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 22)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken(),
                        Map.of(
                                "username", "IT-U-T6",
                                "displayName", "IT 无列表权限用户（示例）",
                                "phone", "13900001303",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-T6", temporary);
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
                codeOf(get("/api/hospital/followup/patients/search-fields", token)),
                "目录同样需要 view 权限");
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/saved-filters", token)),
                "个人筛选同样需要 view 权限");
        Map<String, Object> body = baseBody();
        body.put(
                "condition",
                Map.of("items", List.of(Map.of("field", "ward", "op", "EQ", "value", 1))));
        assertEquals(
                40300,
                codeOf(post("/api/hospital/followup/patients/search", token, body)),
                "高级检索同样需要 view 权限");

        String doctor = doctorToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    codeOf(get("/api/hospital/followup/patients/search-fields", doctor)),
                    "模块关闭 40301");
            assertEquals(
                    40301,
                    codeOf(get("/api/hospital/followup/saved-filters", doctor)),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 20);
        body.put("filters", new HashMap<String, Object>());
        return body;
    }

    private static String legacyOf(Map<?, ?> item) {
        Object value = item.get("legacyNo");
        return value == null ? null : String.valueOf(value);
    }

    private void register(
            String token,
            String mrn,
            String name,
            long wardId,
            String birthDate,
            String dischargeDate,
            int dischargeStatus) {
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
                                dischargeDate,
                                "dischargeStatus",
                                dischargeStatus,
                                "legacyNo",
                                LEGACY + "-" + mrn));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败：" + created);
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
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
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
