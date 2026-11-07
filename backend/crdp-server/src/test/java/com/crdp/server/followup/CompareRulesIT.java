package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E17-T01 端到端 / A-FU-027（Q41 默认规则的前两条随迁移 V67 种子；C-FU-011 随 E21）。验收原文：规则开关与阈值由租户管理员配置。
 *
 * <p>链路：租户管理员（TENANT_ADMIN，菜单 plt_menu 40 / 权限码 followup:abnormal:config，平台库 V57 + followup V67 /
 * 种子 V127 授予）→ GET /compare-rules 清单（含字段显示名）→ PUT 开关 + 调阈值 → 落库 fu_compare_rule.params → 写
 * COMPARE_RULE_UPDATE 审计。
 *
 * <p>覆盖：清单与显示名、调阈值与停用生效并审计、无权限 40300（FOLLOWUP_DOCTOR）、未登录 40100、功能开关 compare_highlight 关闭
 * 40301（恢复后可用）、校验失败 40001、规则不存在 40400、双租户隔离（甲院改动不影响乙院）。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompareRulesIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String TENANT_B = "demob";
    private static final long TENANT_B_ID = 2L;
    private static final String PASSWORD = "Demo@2026";

    private static final String ADMIN = "nurse.demo";
    private static final String DOCTOR = "doctor.demo";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenantA(this::cleanTenant);
        TenantContext.runAsTenant(TENANT_B_ID, TENANT_B, () -> restoreDemoRules(jdbc));
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(this::cleanTenant);
        TenantContext.runAsTenant(TENANT_B_ID, TENANT_B, () -> restoreDemoRules(jdbc));
    }

    private void cleanTenant() {
        restoreDemoRules(jdbc);
        jdbc.update("UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'compare_highlight'");
    }

    /**
     * 恢复被用例改过的种子规则（ef_drop 阈值 10 启用、lvdd_rise 阈值 5 启用，迁移 V67 同值）。 审计行不清理——aud_log 只读（PLT-AUD-011
     * 有删除拦截触发器），断言用「存在即可」口径。
     */
    private static void restoreDemoRules(JdbcTemplate jdbc) {
        jdbc.update(
                "UPDATE fu_compare_rule SET params ="
                        + " JSON_OBJECT('direction', 'DROP', 'threshold', 10), status = 'ENABLED',"
                        + " updated_by = NULL WHERE form_code = 'A_ANNUAL_FU' AND rule_code = 'ef_drop'");
        jdbc.update(
                "UPDATE fu_compare_rule SET params ="
                        + " JSON_OBJECT('direction', 'RISE', 'threshold', 5), status = 'ENABLED',"
                        + " updated_by = NULL WHERE form_code = 'A_ANNUAL_FU' AND rule_code = 'lvdd_rise'");
    }

    // ---------------------------------------------------------------- 用例

    /** 验收主链路：租户管理员看到种子规则（字段显示名、基线回落），调整阈值并停用后落库且写审计。 */
    @Test
    void adminAdjustsThresholdAndSwitch() {
        String adminToken = login(TENANT_A, ADMIN);

        Map<?, ?> list = get("/api/hospital/followup/compare-rules", adminToken).getBody();
        assertEquals(0, code(list));
        List<?> items = (List<?>) list.get("data");
        assertEquals(2, items.size(), "迁移 V67 种子的两条 Q41 默认规则");
        Map<?, ?> ef =
                items.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> "ef_drop".equals(item.get("ruleCode")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("射血分数下降", ef.get("name"));
        assertEquals("EF 射血分数", ef.get("fieldLabel"), "关联字段字典显示名");
        assertEquals("出院前最后一次EF", ef.get("baselineFieldLabel"), "基线回落字段显示名");
        assertEquals(Boolean.TRUE, ef.get("enabled"));
        assertEquals(10, ((Number) paramsOf(ef).get("threshold")).intValue());

        long id = ((Number) ef.get("id")).longValue();
        Map<?, ?> updated =
                put(
                                "/api/hospital/followup/compare-rules/" + id,
                                adminToken,
                                "{\"enabled\":false,\"params\":{\"direction\":\"DROP\",\"threshold\":15}}")
                        .getBody();
        assertEquals(0, code(updated));
        assertEquals(Boolean.FALSE, data(updated).get("enabled"));
        assertEquals(15, ((Number) paramsOf(data(updated)).get("threshold")).intValue());

        runInTenantA(
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT params, status FROM fu_compare_rule WHERE rule_code ="
                                            + " 'ef_drop'");
                    assertEquals(
                            15,
                            ((Number)
                                            jdbc.queryForObject(
                                                    "SELECT CAST(JSON_EXTRACT(params, '$.threshold') AS DOUBLE) FROM"
                                                            + " fu_compare_rule WHERE rule_code ="
                                                            + " 'ef_drop'",
                                                    Object.class))
                                    .intValue(),
                            "阈值落库");
                    assertEquals("DISABLED", row.get("status"), "开关落库");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'COMPARE_RULE_UPDATE'"
                                                    + " AND target = 'FU_COMPARE_RULE:ef_drop'"
                                                    + " AND detail LIKE '%射血分数下降%'",
                                            Integer.class)
                                    >= 1,
                            "配置变更写审计");
                });
    }

    /** formCode 过滤与 GET 一致（E17-T03 求值同入口）：按表单只取该表单规则。 */
    @Test
    void listFiltersByFormCode() {
        String adminToken = login(TENANT_A, ADMIN);
        Map<?, ?> list =
                get("/api/hospital/followup/compare-rules?formCode=A_ANNUAL_FU", adminToken)
                        .getBody();
        assertEquals(0, code(list));
        assertEquals(2, ((List<?>) list.get("data")).size());
        Map<?, ?> other =
                get("/api/hospital/followup/compare-rules?formCode=NOT_EXIST", adminToken)
                        .getBody();
        assertEquals(0, code(other));
        assertEquals(0, ((List<?>) other.get("data")).size());
    }

    /** 权限与登录口径：FOLLOWUP_DOCTOR 无配置码 40300；未登录 40100。 */
    @Test
    void doctorWithoutPermissionAndAnonymousRejected() {
        String doctorToken = login(TENANT_A, DOCTOR);
        assertEquals(
                40300, code(get("/api/hospital/followup/compare-rules", doctorToken).getBody()));
        ResponseEntity<Map> anonymous =
                rest.getForEntity("/api/hospital/followup/compare-rules", Map.class);
        assertEquals(40100, ((Number) anonymous.getBody().get("code")).intValue());
    }

    /** 功能开关 compare_highlight 关闭 → 40301（AGENTS.md 规则 7：后端拒绝、前端隐藏入口）。 */
    @Test
    void featureOffBlocksEndpoints() {
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                        + " 'compare_highlight'"));
        String adminToken = login(TENANT_A, ADMIN);
        Map<?, ?> listBlocked = get("/api/hospital/followup/compare-rules", adminToken).getBody();
        assertEquals(40301, code(listBlocked));
        Map<?, ?> putBlocked =
                put(
                                "/api/hospital/followup/compare-rules/1",
                                adminToken,
                                "{\"enabled\":true,\"params\":{\"direction\":\"DROP\",\"threshold\":10}}")
                        .getBody();
        assertEquals(40301, code(putBlocked));

        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                        + " 'compare_highlight'"));
        assertEquals(0, code(get("/api/hospital/followup/compare-rules", adminToken).getBody()));
    }

    /** 校验失败 40001（缺必填阈值键）不落库；规则不存在 40400。 */
    @Test
    void invalidParamsAndUnknownRuleRejected() {
        String adminToken = login(TENANT_A, ADMIN);
        long id = ruleIdByCode("ef_drop");
        Map<?, ?> missing =
                put(
                                "/api/hospital/followup/compare-rules/" + id,
                                adminToken,
                                "{\"enabled\":true,\"params\":{\"direction\":\"DROP\"}}")
                        .getBody();
        assertEquals(40001, code(missing));
        Map<?, ?> unknown =
                put(
                                "/api/hospital/followup/compare-rules/999999",
                                adminToken,
                                "{\"enabled\":true,\"params\":{}}")
                        .getBody();
        assertEquals(40400, code(unknown));
        runInTenantA(
                () -> {
                    assertEquals(
                            10,
                            ((Number)
                                            jdbc.queryForObject(
                                                    "SELECT CAST(JSON_EXTRACT(params, '$.threshold') AS DOUBLE) FROM"
                                                            + " fu_compare_rule WHERE rule_code ="
                                                            + " 'ef_drop'",
                                                    Object.class))
                                    .intValue(),
                            "40001 不落库");
                    assertFalse(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_compare_rule WHERE status ="
                                                    + " 'DISABLED'",
                                            Integer.class)
                                    > 0,
                            "未停用任何规则");
                });
    }

    /** 双租户隔离：甲院调阈值不影响乙院同名规则（各租户独立库）。 */
    @Test
    void tenantAChangeDoesNotAffectTenantB() {
        long id = ruleIdByCode("ef_drop");
        String adminToken = login(TENANT_A, ADMIN);
        assertEquals(
                0,
                code(
                        put(
                                        "/api/hospital/followup/compare-rules/" + id,
                                        adminToken,
                                        "{\"enabled\":true,\"params\":{\"direction\":\"DROP\",\"threshold\":20}}")
                                .getBody()));
        Integer tenantBThreshold =
                TenantContext.callAsTenant(
                        TENANT_B_ID,
                        TENANT_B,
                        () ->
                                ((Number)
                                                jdbc.queryForObject(
                                                        "SELECT CAST(JSON_EXTRACT(params,"
                                                                + " '$.threshold') AS DOUBLE)"
                                                                + " FROM fu_compare_rule WHERE"
                                                                + " rule_code = 'ef_drop'",
                                                        Object.class))
                                        .intValue());
        assertEquals(10, tenantBThreshold, "乙院阈值保持种子值");
    }

    // ---------------------------------------------------------------- 工具

    private long ruleIdByCode(String ruleCode) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_compare_rule WHERE rule_code = '"
                                        + ruleCode
                                        + "'",
                                Long.class));
    }

    private static Map<?, ?> paramsOf(Map<?, ?> view) {
        return (Map<?, ?>) view.get("params");
    }

    private String login(String tenant, String username) {
        Map<?, ?> body = Map.of("tenantCode", tenant, "username", username, "password", PASSWORD);
        Map<?, ?> response =
                rest.postForEntity("/api/hospital/auth/login", body, Map.class).getBody();
        assertEquals(0, ((Number) response.get("code")).intValue(), "登录成功：" + username);
        return (String) ((Map<?, ?>) response.get("data")).get("token");
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> put(String path, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(jsonBody, headers), Map.class);
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private <T> T runInTenantA(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_A_ID, TENANT_A, action);
    }

    private void runInTenantA(Runnable action) {
        TenantContext.callAsTenant(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    action.run();
                    return null;
                });
    }
}
