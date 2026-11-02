package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
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
 * E11-T01 端到端 / A-FLD-001、A-FLD-002、C-FLD-001（完整应用 + 真实 MySQL，HTTP 直连）。验收原文： 显示名与编码分离，字段可启停。
 *
 * <p>覆盖：字段清单按表单 / 分类过滤，视图同时带稳定键、字段编码与显示名，选项「编码 + 显示名」分离（吸烟史 0=否 / 1=是）；维护显示名 / 帮助 / 软范围 / 必勤与选项（新增
 * / 改显示名 / 停用，编码不可改不删除）； 字段停用 / 启用（状态 + 审计）；键、编码、类型、硬范围、敏感标记不可改（请求不携带）； 无权限 40300、 模块关闭 40301、校验失败
 * 40001、字段 / 表单不存在 40400、双租户字典隔离。E11-T08 / A-FLD-009、C-FLD-009：每次维护写 fe_field_dict_log
 * 变更记录（前后值、操作人、时间），GET /fields/{key}/logs 可查。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldDictIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String FIELDS = "/api/hospital/form/fields";
    private static final String FORMS = "/api/hospital/form/forms";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 恢复被用例动过的字段与选项（种子形态，V28）
                        jdbc.update(
                                "UPDATE fe_field SET label = '吸烟史', help_text ="
                                        + " '示例（A-FLD-002）：原表列名「吸烟史(否=0，是=1)」——显示名与编码分离，"
                                        + "页面显示「吸烟史」与选项「否 / 是」，数据库存 0 / 1',"
                                        + " required = 1, status = 'ENABLED', updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f010'");
                        jdbc.update(
                                "UPDATE fe_field SET soft_range = NULL, updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f011'");
                        jdbc.update(
                                "DELETE FROM fe_option WHERE set_code = 'yesno' AND option_code > 1");
                        jdbc.update(
                                "UPDATE fe_option SET label = CASE option_code WHEN 0 THEN '否'"
                                        + " WHEN 1 THEN '是' END, sort_no = option_code * 10 + 10,"
                                        + " status = 'ENABLED', updated_by = NULL"
                                        + " WHERE set_code = 'yesno'");
                        jdbc.update("DELETE FROM fe_field_dict_log");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action = 'FIELD_DICT_UPDATE'");
                    });
        }
    }

    // ---------------------------------------------------------------- 验收：显示名与编码分离

    /** 清单带 key / code / label 三套标识，选项是「编码 + 显示名」；分类过滤生效。 */
    @Test
    void listsFieldsWithKeyCodeAndLabelSeparated() {
        String token = hospitalToken("demoa");

        // 表单清单（登录即可）：示例基线就位
        List<Map<?, ?>> forms = itemsOf(get(FORMS, token));
        assertEquals(0, code(get(FORMS, token)));
        assertTrue(
                forms.stream().anyMatch(form -> "A_BASELINE".equals(form.get("code"))),
                "表单清单含示例基线：" + forms);

        Map<?, ?> list = get(FIELDS + "?formCode=A_BASELINE", token);
        assertEquals(0, code(list));
        List<Map<?, ?>> fields = itemsOf(list);
        assertTrue(fields.size() >= 7, "示例基线字段齐全：" + fields.size());

        Map<?, ?> smoking =
                fields.stream()
                        .filter(field -> "f010".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("smoking_history", smoking.get("code"), "字段编码（导入导出名）");
        assertEquals("吸烟史", smoking.get("label"), "显示名（不带“(否=0，是=1)”编码说明）");
        assertEquals("basic_info", smoking.get("categoryKey"));
        assertEquals("病例基本信息", smoking.get("categoryLabel"));
        List<Map<?, ?>> options = (List<Map<?, ?>>) smoking.get("options");
        assertEquals(2, options.size());
        assertEquals(0, ((Number) options.get(0).get("code")).intValue(), "数据库存编码 0");
        assertEquals("否", options.get(0).get("label"), "页面显示显示名");

        // 分类过滤：手术信息只剩手术日期 + 派生字段
        List<Map<?, ?>> surgery =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE&categoryKey=surgery", token));
        assertTrue(surgery.size() >= 2);
        assertTrue(surgery.stream().allMatch(field -> "surgery".equals(field.get("categoryKey"))));

        // 表单不存在 40400
        assertEquals(40400, code(get(FIELDS + "?formCode=NOPE", token)));
    }

    // ---------------------------------------------------------------- 维护与启停

    /** 维护显示名 / 帮助 / 软范围 / 必填：返回视图与库内一致，键与编码原样。 */
    @Test
    void updatesEditableAttributesAndWritesAudit() {
        String token = hospitalToken("demoa");

        Map<?, ?> updated =
                put(
                        FIELDS + "/f010?formCode=A_BASELINE",
                        token,
                        Map.of(
                                "label", "吸烟史（既往）",
                                "helpText", "帮助说明（示例）",
                                "required", false,
                                "status", "ENABLED"));
        assertEquals(0, code(updated));
        assertEquals("吸烟史（既往）", data(updated).get("label"));
        assertEquals("smoking_history", data(updated).get("code"), "编码不可改也不被请求携带");
        assertEquals("f010", data(updated).get("key"));

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "吸烟史（既往）",
                            jdbc.queryForObject(
                                    "SELECT label FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f010'",
                                    String.class));
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'FIELD_DICT_UPDATE' AND object_id ="
                                                    + " 'A_BASELINE.f010'",
                                            Integer.class)
                                    >= 1,
                            "字段维护写审计");
                });
    }

    /** 验收「字段可启停」：停用后清单状态 DISABLED、审计点明停用；启用恢复。 */
    @Test
    void disablesAndReenablesField() {
        String token = hospitalToken("demoa");

        Map<?, ?> disabled =
                put(
                        FIELDS + "/f010?formCode=A_BASELINE",
                        token,
                        Map.of("label", "吸烟史", "status", "DISABLED"));
        assertEquals(0, code(disabled));
        assertEquals("DISABLED", data(disabled).get("status"));

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                "DISABLED",
                                jdbc.queryForObject(
                                        "SELECT status FROM fe_field WHERE form_code ="
                                                + " 'A_BASELINE' AND field_key = 'f010'",
                                        String.class)));

        Map<?, ?> enabled =
                put(
                        FIELDS + "/f010?formCode=A_BASELINE",
                        token,
                        Map.of("label", "吸烟史", "status", "ENABLED"));
        assertEquals("ENABLED", data(enabled).get("status"));
    }

    /** 选项维护：新增「已戒烟」、改「是」显示名、停用「否」；重复编码 40001；非选项字段拒绝。 */
    @Test
    void maintainsOptionsWithoutChangingCodes() {
        String token = hospitalToken("demoa");

        Map<?, ?> updated =
                put(
                        FIELDS + "/f010?formCode=A_BASELINE",
                        token,
                        Map.of(
                                "label", "吸烟史",
                                "status", "ENABLED",
                                "options",
                                        List.of(
                                                Map.of("code", 1, "label", "是（目前吸烟）", "sortNo", 30),
                                                Map.of("code", 2, "label", "已戒烟（示例）", "sortNo", 40),
                                                Map.of(
                                                        "code",
                                                        0,
                                                        "label",
                                                        "否",
                                                        "status",
                                                        "DISABLED"))));
        assertEquals(0, code(updated));
        List<Map<?, ?>> options = (List<Map<?, ?>>) data(updated).get("options");
        assertEquals(3, options.size(), "新增的选项出现");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "是（目前吸烟）",
                            jdbc.queryForObject(
                                    "SELECT label FROM fe_option WHERE set_code = 'yesno'"
                                            + " AND option_code = 1",
                                    String.class));
                    assertEquals(
                            "DISABLED",
                            jdbc.queryForObject(
                                    "SELECT status FROM fe_option WHERE set_code = 'yesno'"
                                            + " AND option_code = 0",
                                    String.class));
                });

        // 重复编码 40001；非选项型字段（身高 f011）拒绝维护选项
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "吸烟史",
                                        "status", "ENABLED",
                                        "options",
                                                List.of(
                                                        Map.of("code", 1, "label", "是"),
                                                        Map.of("code", 1, "label", "否"))))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f011?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "身高",
                                        "status", "ENABLED",
                                        "options", List.of(Map.of("code", 1, "label", "是"))))));
    }

    // ---------------------------------------------------------------- E11-T08：变更记录

    /** 维护（含停用）写变更记录：倒序、前后值快照、操作人（sys_user 联查）与时间（A-FLD-009）。 */
    @Test
    void updateWritesChangeRecordLogs() {
        String token = hospitalToken("demoa");

        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史（修订）", "status", "ENABLED"))));
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史（修订）", "status", "DISABLED"))));

        Map<?, ?> logsResponse = get(FIELDS + "/f010/logs?formCode=A_BASELINE", token);
        assertEquals(0, code(logsResponse));
        List<Map<?, ?>> logs = itemsOf(logsResponse);
        assertEquals(2, logs.size(), "两次维护各留一条：" + logs.size());

        Map<?, ?> latest = logs.get(0);
        assertEquals("FIELD_UPDATE", latest.get("action"));
        assertNotNull(latest.get("beforeJson"), "调整前快照");
        assertNotNull(latest.get("afterJson"), "调整后快照");
        assertTrue(((String) latest.get("afterJson")).contains("DISABLED"), "停用进快照");
        assertEquals("演示护士", latest.get("operatorName"), "操作人姓名（联查 sys_user）");
        assertNotNull(latest.get("createdAt"), "操作时间");

        Map<?, ?> first = logs.get(1);
        assertTrue(((String) first.get("afterJson")).contains("吸烟史（修订）"));

        // 未知字段 40400；无权限 40300（前端隐藏不能代替后端校验）
        assertEquals(40400, code(get(FIELDS + "/f999/logs?formCode=A_BASELINE", token)));
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get(FIELDS + "/f010/logs?formCode=A_BASELINE", doctor)));
    }

    // ---------------------------------------------------------------- 校验、权限、模块开关、隔离

    /** 校验失败 40001（显示名空 / 软范围倒置 / 状态非法）；字段不存在 40400。 */
    @Test
    void rejectsInvalidUpdatesAndUnknownField() {
        String token = hospitalToken("demoa");

        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                token,
                                Map.of("label", " ", "status", "ENABLED"))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f011?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label", "身高",
                                        "status", "ENABLED",
                                        "softRange", Map.of("min", 210, "max", 130)))));
        assertEquals(
                40001,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史", "status", "PAUSED"))));
        assertEquals(
                40400,
                code(
                        put(
                                FIELDS + "/f999?formCode=A_BASELINE",
                                token,
                                Map.of("label", "不存在（示例）", "status", "ENABLED"))));
    }

    /** 无 followup:field:edit 的医生 40300（前端隐藏不能代替后端校验）。 */
    @Test
    void rejectsUserWithoutPermission() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get(FIELDS + "?formCode=A_BASELINE", doctor)));
        assertEquals(
                40300,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                doctor,
                                Map.of("label", "吸烟史", "status", "ENABLED"))));
    }

    /** 模块关闭（40301）：followup 关闭后接口 40301，恢复即用（PLT-MOD-006）。 */
    @Test
    void closedFollowupModuleReturns40301() {
        String token = hospitalToken("demoa");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(get(FIELDS + "?formCode=A_BASELINE", token)));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(FIELDS + "?formCode=A_BASELINE", token)));
    }

    /** 双租户隔离：demoa 改显示名，demob 的同键字段不受影响（每院一个库）。 */
    @Test
    void tenantChangesStayIsolated() {
        String tenantA = hospitalToken("demoa");
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f010?formCode=A_BASELINE",
                                tenantA,
                                Map.of("label", "吸烟史（A 院）", "status", "ENABLED"))));

        String tenantB = hospitalToken("demob");
        Map<?, ?> tenantBField =
                itemsOf(get(FIELDS + "?formCode=A_BASELINE", tenantB)).stream()
                        .filter(field -> "f010".equals(field.get("key")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("吸烟史", tenantBField.get("label"), "B 院字典不受 A 院修改影响");
        assertNotNull(tenantBField.get("options"));
    }

    // ---------------------------------------------------------------- 辅助

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> response) {
        return (List<Map<?, ?>>) response.get("data");
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
