package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * E06-T10 / PLT-CFG-001..004、011 端到端（完整应用 + 真实 MySQL，HTTP 直连），验收「租户覆盖平台默认值并留记录」：
 *
 * <ul>
 *   <li>租户参数覆盖：列表合并平台定义；覆盖在范围内生效、超范围 / 锁定 / 未知参数拒绝；覆盖与恢复都写 cfg_param_log 记录与租户审计（PARAM_OVERRIDE /
 *       PARAM_RESET）；跨租户隔离；
 *   <li>平台改参数（值或锁定）即时下发：缓存立即失效（不等 60 秒 TTL），锁定后租户覆盖失效并提示 （stale），重新放开自动恢复生效（覆盖行保留）；
 *   <li>租户字典：继承平台字典；可停用 / 恢复平台项（不能改文字）、可新增本院自有项；平台新增项即时出现； 本院自有字典可建；编码冲突 40902；写租户审计；
 *   <li>平台字典 / 参数管理：CRUD 与校验、写平台审计；权限（无码 40300）。
 * </ul>
 *
 * CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。全部虚构示例数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class DictParamOverrideIT {

    private static final String PASSWORD = "Demo@2026";

    /** 平台端在 IT 里创建的临时参数与字典（@BeforeEach / @AfterEach 清理）。 */
    private static final String PROBE_KEY = "it.report.watermark.opacity";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.crdp.server.platform.PlatformConfigCache configCache;

    @BeforeEach
    @AfterEach
    void cleanState() {
        // 平台库：恢复种子参数、清 IT 建的字典与项
        TenantContext.runAsPlatform(
                () -> {
                    jdbc.update(
                            "UPDATE plt_param SET platform_value = '20', tenant_overridable = 1,"
                                    + " value_range = '{\"min\":5,\"max\":50}', name ="
                                    + " '每租户连接池上限', description ="
                                    + " '该院数据库连接池的最大连接数（NFR-CAP-002 租户间资源互不影响）'"
                                    + " WHERE param_key = 'tenant.db.pool.max'");
                    jdbc.update("DELETE FROM plt_param WHERE param_key = ?", PROBE_KEY);
                    jdbc.update("DELETE FROM plt_dict_item WHERE item_code = '9'");
                    jdbc.update("DELETE FROM plt_dict WHERE code = 'it_dict'");
                    jdbc.update(
                            "DELETE FROM plt_audit_log WHERE event_type LIKE 'PARAM_%'"
                                    + " OR event_type LIKE 'DICT_%'");
                });
        // SQL 直改不经过保存口（不会触发缓存失效），这里手动 evict，避免下一用例读到 60 秒内的旧快照
        configCache.evict();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // cfg_param 不能全清：security.*（双因素角色、单端登录、密码下限等，
                        // V6/V7/V8 种子，HospitalDirectory 现读）与本任务的平台参数覆盖同表共存，
                        // 只清本用例动过的键
                        jdbc.update(
                                "DELETE FROM cfg_param WHERE param_key IN"
                                        + " ('tenant.db.pool.max','report.footer.note',?)",
                                PROBE_KEY);
                        jdbc.update("DELETE FROM cfg_param_log");
                        jdbc.update("DELETE FROM cfg_dict_item");
                        jdbc.update("DELETE FROM cfg_dict");
                        jdbc.update(
                                "DELETE FROM aud_log WHERE event_type LIKE 'PARAM_%'"
                                        + " OR event_type LIKE 'DICT_%'");
                    });
        }
    }

    // ---------------------------------------------------------------- 租户参数覆盖（验收主链路）

    @Test
    void tenantOverridesPlatformDefaultAndLeavesRecord() {
        String admin = hospitalToken("demoa");

        // 种子参数就位：平台值 20、可覆盖、未覆盖
        Map<?, ?> before = paramOf(listParams(admin), "tenant.db.pool.max");
        assertEquals("20", before.get("platformValue"));
        assertEquals(Boolean.TRUE, before.get("tenantOverridable"));
        assertEquals(Boolean.FALSE, before.get("overridden"));
        assertEquals("20", before.get("effectiveValue"));

        // 覆盖：范围内生效
        Map<?, ?> overridden =
                put(
                        "/api/hospital/system/params/tenant.db.pool.max",
                        admin,
                        Map.of("value", "30", "reason", "试点科室较多"));
        assertEquals(0, codeOf(overridden));
        assertEquals("30", data(overridden).get("effectiveValue"));
        assertEquals("30", data(overridden).get("tenantValue"));

        // 改覆盖值：旧值留痕
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                admin,
                                Map.of("value", "40"))));

        // 留记录（验收）：cfg_param_log 两条 OVERRIDE（null→30、30→40），原因在案
        List<Map<?, ?>> logs = logs(admin);
        assertEquals(2, logs.size(), "最近变更倒序：" + logs);
        assertEquals("OVERRIDE", logs.get(0).get("action"));
        assertEquals("40", logs.get(0).get("newValue"));
        assertEquals("30", logs.get(0).get("oldValue"));
        assertEquals("OVERRIDE", logs.get(1).get("action"));
        assertNull(logs.get(1).get("oldValue"), "首次覆盖无旧值");
        assertEquals("30", logs.get(1).get("newValue"));
        assertEquals("试点科室较多", logs.get(1).get("reason"));

        // 审计（PLT-CFG-011）
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'PARAM_OVERRIDE'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 2, "覆盖写审计，实际 " + audits);

        // 恢复默认：生效值回落平台值，写 RESET 记录
        Map<?, ?> reset =
                exchange(
                        HttpMethod.DELETE,
                        "/api/hospital/system/params/tenant.db.pool.max",
                        admin,
                        null);
        assertEquals(0, codeOf(reset));
        assertEquals("20", data(reset).get("effectiveValue"));
        assertEquals(Boolean.FALSE, data(reset).get("overridden"));
        assertEquals("RESET", logs(admin).get(0).get("action"));
    }

    @Test
    void overrideRejectsOutOfRangeLockedAndUnknown() {
        String admin = hospitalToken("demoa");
        // 超出范围 5..50
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                admin,
                                Map.of("value", "500"))));
        // 非数字
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                admin,
                                Map.of("value", "big"))));
        // 平台锁定（种子 platform.brand.locked）
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/params/platform.brand.locked",
                                admin,
                                Map.of("value", "true"))));
        // 未知参数
        assertEquals(
                40400,
                codeOf(
                        put(
                                "/api/hospital/system/params/nosuch.param",
                                admin,
                                Map.of("value", "1"))));
        // 被拒后无任何覆盖与记录（cfg_param 里 security.* 种子行不算覆盖，按键计数）
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM cfg_param WHERE"
                                                        + " param_key IN"
                                                        + " ('tenant.db.pool.max','platform.brand.locked',?)",
                                                Integer.class,
                                                PROBE_KEY))
                        == 0);
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM cfg_param_log",
                                                Integer.class))
                        == 0);
    }

    @Test
    void tenantOverrideIsolatedPerTenant() {
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                hospitalToken("demob"),
                                Map.of("value", "45"))));
        Map<?, ?> demoa = paramOf(listParams(hospitalToken("demoa")), "tenant.db.pool.max");
        assertEquals(Boolean.FALSE, demoa.get("overridden"), "demoa 不受 demob 覆盖影响（库隔离）");
        assertEquals("20", demoa.get("effectiveValue"));
        Map<?, ?> demob = paramOf(listParams(hospitalToken("demob")), "tenant.db.pool.max");
        assertEquals("45", demob.get("effectiveValue"));
    }

    // ---------------------------------------------------------------- 平台变更即时生效（PLT-CFG-011）

    @Test
    void platformParamChangeTakesEffectImmediately() {
        String platform = platformToken();
        String admin = hospitalToken("demoa");

        // 租户先覆盖，平台随后锁定：缓存立即失效，覆盖值失效回落平台值并提示
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                admin,
                                Map.of("value", "35"))));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/platform/params/tenant.db.pool.max",
                                platform,
                                Map.of(
                                        "name",
                                        "每租户连接池上限",
                                        "valueType",
                                        "NUMBER",
                                        "platformValue",
                                        "25",
                                        "tenantOverridable",
                                        false,
                                        "valueRange",
                                        "{\"min\":5,\"max\":50}"))));
        Map<?, ?> locked = paramOf(listParams(admin), "tenant.db.pool.max");
        assertEquals(Boolean.TRUE, locked.get("stale"), "锁定后覆盖失效并提示");
        assertEquals("25", locked.get("effectiveValue"), "生效值回落平台默认值（也是新值，即时可见）");
        assertEquals("35", locked.get("tenantValue"), "覆盖行保留");

        // 重新放开：覆盖自动恢复生效（不丢租户配置）
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/platform/params/tenant.db.pool.max",
                                platform,
                                Map.of(
                                        "name",
                                        "每租户连接池上限",
                                        "valueType",
                                        "NUMBER",
                                        "platformValue",
                                        "25",
                                        "tenantOverridable",
                                        true,
                                        "valueRange",
                                        "{\"min\":5,\"max\":50}"))));
        Map<?, ?> reopened = paramOf(listParams(admin), "tenant.db.pool.max");
        assertFalse(Boolean.TRUE.equals(reopened.get("stale")));
        assertEquals("35", reopened.get("effectiveValue"), "覆盖行保留，重新放开自动恢复");

        // 平台审计（PARAM_UPDATE 两条：锁定与放开）
        Integer audits =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                                + " 'PARAM_UPDATE' AND target ="
                                                + " 'plt_param:tenant.db.pool.max'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 2, "平台改参数写审计，实际 " + audits);
    }

    // ---------------------------------------------------------------- 租户字典（继承 / 停用 / 自有项）

    @Test
    void tenantDictInheritsPlatformAndHoldsLocalOverrides() {
        String admin = hospitalToken("demoa");

        // 继承：平台种子字典可见
        Map<?, ?> gender = dictOf(listDicts(admin), "gender");
        assertNotNull(gender, "平台字典 gender 已继承");
        assertEquals("PLATFORM", gender.get("source"));

        // 停用平台项：只能停用（改文字 40001）；停用后状态标记
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/dicts/gender/items/1",
                                admin,
                                Map.of("label", "男生", "status", "DISABLED"))));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/dicts/gender/items/1",
                                admin,
                                Map.of("status", "DISABLED"))));
        Map<?, ?> male = itemOf(items(admin, "gender"), "1");
        assertEquals("DISABLED", male.get("status"));
        assertEquals(Boolean.FALSE, male.get("editable"), "平台项不可改文字");
        assertEquals("男", male.get("label"), "文字仍取平台定义");

        // 新增本院自有项；与平台项编码冲突 40902
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/dicts/gender/items",
                                admin,
                                Map.of("itemCode", "1", "label", "重复项"))));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/dicts/gender/items",
                                admin,
                                Map.of("itemCode", "X_OTHER", "label", "其他（本院）"))));
        Map<?, ?> own = itemOf(items(admin, "gender"), "X_OTHER");
        assertEquals("TENANT", own.get("source"));
        assertEquals(Boolean.TRUE, own.get("editable"));

        // 本院自有字典：可建可改；与平台字典编码冲突 40902
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/system/dicts",
                                admin,
                                Map.of("code", "gender", "name", "本院性别"))));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/system/dicts",
                                admin,
                                Map.of("code", "local_referral", "name", "转诊来源（本院）"))));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/dicts/local_referral",
                                admin,
                                Map.of("name", "转诊来源（本院修订）", "status", "ENABLED"))));

        // 审计：DICT_ITEM_UPDATE / DICT_ITEM_ADD / DICT_CREATE / DICT_UPDATE
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type LIKE"
                                                + " 'DICT_%'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 4, "字典写操作留痕，实际 " + audits);
    }

    @Test
    void platformDictChangeReachesTenantImmediately() {
        String platform = platformToken();
        String admin = hospitalToken("demoa");

        // 平台新增字典项 → 租户立即看见（缓存失效，不等 TTL）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/platform/dicts/gender/items",
                                platform,
                                Map.of("itemCode", "9", "label", "不愿说明（示例）", "sortNo", 90))));
        Map<?, ?> added = itemOf(items(admin, "gender"), "9");
        assertNotNull(added, "平台新增项即时出现在租户字典");
        assertEquals("PLATFORM", added.get("source"));

        // 平台端校验：重复编码 40902、格式 40001；写平台审计
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/platform/dicts/gender/items",
                                platform,
                                Map.of("itemCode", "9", "label", "重复项"))));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/platform/dicts",
                                platform,
                                Map.of("code", "Bad Code", "name", "格式错误"))));
        Integer audits =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                                + " 'DICT_ITEM_CREATE'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1);
    }

    @Test
    void platformCreatesParamWithRangeAndValidation() {
        String platform = platformToken();
        // 可覆盖参数必须带范围（NUMBER）
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/platform/params",
                                platform,
                                Map.of(
                                        "paramKey", PROBE_KEY,
                                        "name", "水印不透明度",
                                        "valueType", "NUMBER",
                                        "platformValue", "0.8",
                                        "tenantOverridable", true))));
        // 平台默认值也要过范围校验
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/platform/params",
                                platform,
                                Map.of(
                                        "paramKey", PROBE_KEY,
                                        "name", "水印不透明度",
                                        "valueType", "NUMBER",
                                        "platformValue", "5",
                                        "tenantOverridable", true,
                                        "valueRange", "{\"min\":0,\"max\":1}"))));
        // 合法创建后租户可覆盖且范围生效；重复键 40902
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/platform/params",
                                platform,
                                Map.of(
                                        "paramKey", PROBE_KEY,
                                        "name", "水印不透明度",
                                        "valueType", "NUMBER",
                                        "platformValue", "0.8",
                                        "tenantOverridable", true,
                                        "valueRange", "{\"min\":0,\"max\":1}"))));
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/platform/params",
                                platform,
                                Map.of(
                                        "paramKey", PROBE_KEY,
                                        "name", "水印不透明度",
                                        "valueType", "NUMBER",
                                        "platformValue", "0.8",
                                        "tenantOverridable", false))));
        String admin = hospitalToken("demoa");
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/params/" + PROBE_KEY,
                                admin,
                                Map.of("value", "0.5"))));
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/params/" + PROBE_KEY,
                                admin,
                                Map.of("value", "2"))));
    }

    // ---------------------------------------------------------------- 权限

    @Test
    void permissionAndSideBoundaries() {
        // 医生无 tenant:dict:edit / tenant:param:edit → 40300
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, codeOf(get("/api/hospital/system/params", doctor)));
        assertEquals(
                40300,
                codeOf(
                        put(
                                "/api/hospital/system/params/tenant.db.pool.max",
                                doctor,
                                Map.of("value", "30"))));
        assertEquals(40300, codeOf(get("/api/hospital/system/dicts", doctor)));
        assertEquals(40300, codeOf(get("/api/hospital/system/dicts/gender/items", doctor)));

        // 医院账号无平台码 → 平台端接口 40300
        String admin = hospitalToken("demoa");
        assertEquals(40300, codeOf(get("/api/platform/params", admin)));
        assertEquals(40300, codeOf(get("/api/platform/dicts", admin)));
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
        assertEquals(0, codeOf(response));
        return (String) data(response).get("token");
    }

    private String platformToken() {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", "platform.admin", "password", PASSWORD));
        assertEquals(0, codeOf(response));
        return (String) data(response).get("token");
    }

    private List<Map<?, ?>> listParams(String token) {
        Map<?, ?> response = get("/api/hospital/system/params", token);
        assertEquals(0, codeOf(response));
        return itemsOf(response);
    }

    private List<Map<?, ?>> logs(String token) {
        Map<?, ?> response = get("/api/hospital/system/params/logs", token);
        assertEquals(0, codeOf(response));
        return itemsOf(response);
    }

    private List<Map<?, ?>> listDicts(String token) {
        Map<?, ?> response = get("/api/hospital/system/dicts", token);
        assertEquals(0, codeOf(response));
        return itemsOf(response);
    }

    private List<Map<?, ?>> items(String token, String dictCode) {
        Map<?, ?> response = get("/api/hospital/system/dicts/" + dictCode + "/items", token);
        assertEquals(0, codeOf(response));
        return itemsOf(response);
    }

    private static Map<?, ?> paramOf(List<Map<?, ?>> params, String key) {
        return params.stream()
                .filter(row -> key.equals(row.get("paramKey")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("参数不存在：" + key));
    }

    private static Map<?, ?> dictOf(List<Map<?, ?>> dicts, String code) {
        return dicts.stream().filter(row -> code.equals(row.get("code"))).findFirst().orElse(null);
    }

    private static Map<?, ?> itemOf(List<Map<?, ?>> items, String itemCode) {
        return items.stream()
                .filter(row -> itemCode.equals(row.get("itemCode")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> response) {
        return (List<Map<?, ?>>) response.get("data");
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

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
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
