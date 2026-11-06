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
 * E16-T01 端到端 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004（完整应用 + 真实 MySQL，HTTP
 * 直连）。验收原文：A 预置 10 类资料类型可配置。
 *
 * <p>覆盖：配置接口返回 10 类预置（编码、名称、名称必填、排序、启用）且可增、改（排序 / 名称 / 启停）、删（有 名称时
 * 40901）；名称字典同类型唯一（40902）、绑定指标模板；指标模板项目全量替换、被绑定时删除 40901；字段集 必须是 REPORT
 * 表单（40001）；运行时目录（report-types / report-names，followup:patient:view）只吐启用 类型、选名称带出指标行；无权限 40300、模块关闭
 * 40301、类型不存在 40400、双租户配置隔离；菜单 39 随 V53/V62/V124 授予 TENANT_ADMIN。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportConfigIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup/report-config";
    private static final String CATALOG_TYPES = "/api/hospital/followup/report-types";
    private static final String CATALOG_NAMES = "/api/hospital/followup/report-names";

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
                        jdbc.update(
                                "DELETE n FROM rpt_name n JOIN rpt_type t ON t.code = n.type_code"
                                        + " WHERE n.name LIKE 'IT %' OR t.code = 'IT_TYPE'");
                        jdbc.update("DELETE FROM rpt_type WHERE code = 'IT_TYPE'");
                        jdbc.update("DELETE FROM rpt_indicator_tpl WHERE name = 'IT 模板（凝血四项示例）'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action LIKE 'RPT_%'");
                    });
        }
    }

    // ---------------------------------------------------------------- 验收：A 预置 10 类可配置

    /** A-ATT-003：10 类预置（编码、名称、顺序）与规格一致；名称必填、启用、未启用电子化状态。 */
    @Test
    void tenPresetTypesAreConfigurable() {
        String token = hospitalToken("demoa");

        List<Map<?, ?>> types = itemsOf(get(BASE + "/types", token));
        assertEquals(10, types.size(), "A 医院预置 10 类资料类型");
        List<String> codes = types.stream().map(type -> str(type.get("code"))).toList();
        assertEquals(
                List.of(
                        "ECHO",
                        "ANGIO",
                        "ECG",
                        "CHEST_IMAGING",
                        "LAB",
                        "DISCHARGE_SUMMARY",
                        "OPERATION_RECORD",
                        "CONSENT",
                        "CALL",
                        "OTHER"),
                codes,
                "编码与排序（A 规格预置资料类型表）");
        Map<?, ?> echo =
                types.stream().filter(t -> "ECHO".equals(t.get("code"))).findFirst().orElseThrow();
        assertEquals("心脏超声", echo.get("name"));
        assertEquals("RPT_ECHO", echo.get("formCode"), "字段集引用 REPORT 表单");
        assertEquals(true, echo.get("nameRequired"), "A 名称必填");
        assertEquals(null, echo.get("defaultDigitizeStatus"), "A 不启用电子化状态（A-ATT-018）");
        assertEquals("ENABLED", echo.get("status"));
        assertEquals(3L, ((Number) echo.get("nameCount")).longValue(), "心脏超声 3 个预置名称");
    }

    /** A-ATT-003「租户管理员可增减、停用、排序」：新建 → 编辑（停用）→ 删除，全程留审计。 */
    @Test
    void typeLifecycleCreateUpdateDeleteWithAudit() {
        String token = hospitalToken("demoa");

        Map<?, ?> created =
                post(
                        BASE + "/types",
                        token,
                        Map.of(
                                "code", "IT_TYPE",
                                "name", "IT 新类型（示例）",
                                "formCode", "RPT_OTHER",
                                "sortNo", 55));
        assertEquals(0, code(created));
        assertEquals("IT_TYPE", data(created).get("code"));
        assertEquals("其他资料", data(created).get("formName"), "字段集联查表单名");

        // 重编码 40902；非 REPORT 字段集 40001
        assertEquals(
                40902,
                code(
                        post(
                                BASE + "/types",
                                token,
                                Map.of("code", "IT_TYPE", "name", "重复", "formCode", "RPT_OTHER"))));
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/types",
                                token,
                                Map.of(
                                        "code",
                                        "IT_NEW",
                                        "name",
                                        "错字段集",
                                        "formCode",
                                        "A_BASELINE"))));

        long id = Long.parseLong(str(data(created).get("id")));
        Map<?, ?> updated =
                put(
                        BASE + "/types/" + id,
                        token,
                        Map.of(
                                "name",
                                "IT 新类型（改名）",
                                "formCode",
                                "RPT_OTHER",
                                "status",
                                "DISABLED",
                                "sortNo",
                                56));
        assertEquals(0, code(updated));
        assertEquals("DISABLED", data(updated).get("status"), "类型可停用（不删除）");

        // 空类型删除成功；LAB 有名称删除被 40901 拦截
        assertEquals(0, code(delete(BASE + "/types/" + id, token)));
        List<Map<?, ?>> labOnly =
                itemsOf(get(BASE + "/types", token)).stream()
                        .filter(t -> "LAB".equals(t.get("code")))
                        .toList();
        assertEquals(
                40901,
                code(delete(BASE + "/types/" + labOnly.get(0).get("id"), token)),
                "类型下有名称不能删");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            5L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE action LIKE 'RPT_TYPE_%'",
                                    Long.class),
                            "新建 / 重码 DENIED / 编辑 / 删除被阻止 DENIED / 删除共 5 条");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_TYPE_DELETE'"
                                                    + " AND result = 'DENIED'",
                                            Long.class)
                                    >= 1,
                            "删除被阻止写 DENIED 审计");
                });
    }

    // ---------------------------------------------------------------- 名称字典与指标模板（A-ATT-011 /
    // A-ATT-012）

    /** 名称：同类型唯一 40902；绑定模板后运行时目录带出指标行；模板被绑定不能删（40901）。 */
    @Test
    void nameDictionaryAndIndicatorTemplates() {
        String token = hospitalToken("demoa");

        // 预置绑定：检验 › 血常规 → 血常规模板（WBC / HGB / PLT）
        List<Map<?, ?>> labNames = itemsOf(get(CATALOG_NAMES + "?typeCode=LAB", token));
        Map<?, ?> cbc =
                labNames.stream()
                        .filter(n -> "血常规".equals(n.get("name")))
                        .findFirst()
                        .orElseThrow();
        List<Map<?, ?>> items = (List<Map<?, ?>>) cbc.get("items");
        assertEquals(3, items.size(), "血常规模板 3 项（WBC / HGB / PLT）");
        assertEquals("WBC", ((Map<?, ?>) items.get(0)).get("itemCode"));
        assertEquals("10^9/L", ((Map<?, ?>) items.get(0)).get("unit"));
        Map<?, ?> glucose =
                labNames.stream()
                        .filter(n -> "血糖与糖化血红蛋白".equals(n.get("name")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, ((List<?>) glucose.get("items")).size(), "未绑定模板的名称不带指标行");

        // 新名称 + 重名 40902 + 未知类型 40400
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/names",
                                token,
                                Map.of("typeCode", "ECHO", "name", "IT 床旁超声（示例）", "sortNo", 25))));
        assertEquals(
                40902,
                code(
                        post(
                                BASE + "/names",
                                token,
                                Map.of("typeCode", "ECHO", "name", "IT 床旁超声（示例）"))));
        assertEquals(
                40400,
                code(post(BASE + "/names", token, Map.of("typeCode", "NOPE", "name", "IT 未知类型"))));

        // 新模板（项目全量）→ 项目代码重复 40001 → 绑给名称 → 被绑定删除 40901 → 解绑后可删
        Map<?, ?> tpl =
                post(
                        BASE + "/indicator-templates",
                        token,
                        Map.of(
                                "name",
                                "IT 模板（凝血四项示例）",
                                "items",
                                List.of(
                                        Map.of(
                                                "itemName",
                                                "凝血酶原时间",
                                                "itemCode",
                                                "PT",
                                                "unit",
                                                "s",
                                                "sortNo",
                                                10),
                                        Map.of(
                                                "itemName",
                                                "国际标准化比值",
                                                "itemCode",
                                                "INR",
                                                "unit",
                                                "—",
                                                "sortNo",
                                                20))));
        assertEquals(0, code(tpl));
        String tplId = str(data(tpl).get("id"));
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/indicator-templates",
                                token,
                                Map.of(
                                        "name",
                                        "IT 模板（重复项）",
                                        "items",
                                        List.of(
                                                Map.of("itemName", "甲", "itemCode", "DUP"),
                                                Map.of("itemName", "乙", "itemCode", "DUP"))))));

        Map<?, ?> boundName =
                post(
                        BASE + "/names",
                        token,
                        Map.of("typeCode", "LAB", "name", "IT 凝血四项（示例）", "indicatorTplId", tplId));
        assertEquals(tplId, str(data(boundName).get("indicatorTplId")));

        assertEquals(
                40901, code(delete(BASE + "/indicator-templates/" + tplId, token)), "模板被名称绑定不能删");

        // 更新模板 = 全量替换项目；停用后运行时不再带出
        Map<?, ?> replaced =
                put(
                        BASE + "/indicator-templates/" + tplId,
                        token,
                        Map.of(
                                "name",
                                "IT 模板（凝血四项示例）",
                                "status",
                                "DISABLED",
                                "items",
                                List.of(
                                        Map.of(
                                                "itemName",
                                                "凝血酶原时间",
                                                "itemCode",
                                                "PT",
                                                "unit",
                                                "s",
                                                "sortNo",
                                                10))));
        assertEquals(0, code(replaced));
        assertEquals(1, ((List<?>) data(replaced).get("items")).size(), "项目全量替换为 1 行");

        List<Map<?, ?>> after =
                itemsOf(get(CATALOG_NAMES + "?typeCode=LAB", token)).stream()
                        .filter(n -> "IT 凝血四项（示例）".equals(n.get("name")))
                        .toList();
        assertEquals(
                0, ((List<?>) after.get(0).get("items")).size(), "模板停用后新报告单不再带出项目（C-SCAN-004）");

        // 删除名称（解绑随之消失）→ 模板可删
        assertEquals(0, code(delete(BASE + "/names/" + data(boundName).get("id"), token)));
        assertEquals(0, code(delete(BASE + "/indicator-templates/" + tplId, token)));
    }

    // ---------------------------------------------------------------- 运行时目录（report.md §2）

    /** 类型与字段集（C-SCAN-002）：只吐启用类型；检验带报告时间字段；停用类型立即消失。 */
    @Test
    void catalogServesEnabledTypesWithFieldSets() {
        String token = hospitalToken("demoa");

        List<Map<?, ?>> catalog = itemsOf(get(CATALOG_TYPES, token));
        assertEquals(10, catalog.size());
        Map<?, ?> lab =
                catalog.stream().filter(t -> "LAB".equals(t.get("code"))).findFirst().orElseThrow();
        List<Map<?, ?>> fields = (List<Map<?, ?>>) lab.get("fields");
        assertTrue(
                fields.stream().anyMatch(f -> "report_date".equals(f.get("code"))), "检验字段集含报告时间");
        assertTrue((Boolean) lab.get("nameRequired"));

        Map<?, ?> operation =
                catalog.stream()
                        .filter(t -> "OPERATION_RECORD".equals(t.get("code")))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                ((List<Map<?, ?>>) operation.get("fields"))
                        .stream().anyMatch(f -> "surgery_date".equals(f.get("code"))),
                "手术记录以手术日期归档（A 规格预置资料类型表）");

        assertEquals(40400, code(get(CATALOG_NAMES + "?typeCode=NOPE", token)));
    }

    // ---------------------------------------------------------------- 权限 / 模块开关 / 租户隔离

    /** 无权限 40300：FOLLOWUP_DOCTOR 无 followup:scan:config；运行时目录按 patient:view 可读。 */
    @Test
    void configRequiresScanConfigPermission() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get(BASE + "/types", doctor)));
        assertEquals(
                40300,
                code(
                        post(
                                BASE + "/types",
                                doctor,
                                Map.of("code", "IT_X", "name", "x", "formCode", "RPT_OTHER"))));
        assertEquals(0, code(get(CATALOG_TYPES, doctor)), "运行时目录按 followup:patient:view 可读");
    }

    /** 模块关闭（40301）：followup 关闭后配置与目录接口都 40301，恢复即用（PLT-MOD-006）。 */
    @Test
    void closedFollowupModuleReturns40301() {
        String token = hospitalToken("demoa");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(get(BASE + "/types", token)));
            assertEquals(40301, code(get(CATALOG_TYPES, token)));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(BASE + "/types", token)));
    }

    /** 双租户隔离：demoa 新增类型，demob 仍是 10 类预置（每院一个库，A-ATT-018 按院配置）。 */
    @Test
    void tenantConfigStaysIsolated() {
        String tenantA = hospitalToken("demoa");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/types",
                                tenantA,
                                Map.of(
                                        "code",
                                        "IT_TYPE",
                                        "name",
                                        "IT A 院新增（示例）",
                                        "formCode",
                                        "RPT_OTHER"))));
        assertEquals(11, itemsOf(get(BASE + "/types", tenantA)).size());

        String tenantB = hospitalToken("demob");
        assertEquals(10, itemsOf(get(BASE + "/types", tenantB)).size(), "demob 不受 demoa 配置影响");
    }

    /** 菜单链：平台库 V53 菜单 39 + followup V62 / 种子 V124 授予，TENANT_ADMIN 下发报告单设置菜单。 */
    @Test
    void menuGrantedToTenantAdmin() {
        String token = hospitalToken("demoa");
        String menus =
                str(
                        rest.exchange(
                                        "/api/hospital/menus",
                                        HttpMethod.GET,
                                        new HttpEntity<>(auth(token)),
                                        String.class)
                                .getBody());
        assertTrue(menus.contains("FollowupReportConfig"), "菜单含报告单设置：" + menus);
        assertTrue(menus.contains("/system/report-config"));
    }

    // ---------------------------------------------------------------- 帮助方法

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

    private Map<?, ?> delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, auth(token)), Map.class)
                        .getBody();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static int code(Map<?, ?> response) {
        assertNotNull(response, "响应体不为空");
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

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
