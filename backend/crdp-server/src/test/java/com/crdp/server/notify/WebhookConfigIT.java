package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E09-T06 端到端（一）· A-NTF-008、C-NTF-008、PLT-CFG-007：Webhook 配置面（生产口径 SSRF 防护，未开联调开关）。
 *
 * <p>覆盖：验收「内网地址拒绝」（10/8、127/8、169.254 在保存与修改时都 40001；只接受 HTTPS）、签名密钥系统生成只回显一次、 换钥 / 停用、40300（无
 * tenant:webhook:edit）、40400（不存在）、事件目录、校验失败 40001、WEBHOOK_* 操作审计（secret 明文不落审计）。 投递链路（签名、重试）在
 * {@link WebhookDeliveryIT}（联调开关 + 本地接收方）。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookConfigIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String DOCTOR = "doctor.demo";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenantA(this::cleanTenant);
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(this::cleanTenant);
    }

    private void cleanTenant() {
        jdbc.update("DELETE FROM sys_webhook_delivery");
        jdbc.update("DELETE FROM sys_webhook");
        // 审计只读（PLT-AUD-011）：测试自清理走维护开关
        com.crdp.server.AuditTableMaintenance.delete(
                jdbc, "DELETE FROM aud_log WHERE event_type LIKE 'WEBHOOK_%'");
    }

    // ---------------------------------------------------------------- 验收：内网地址拒绝

    @Test
    void intranetAndPlainTextUrlsAreRejectedOnSaveAndUpdate() {
        String admin = login(ADMIN);
        // 新建：内网 / 回环 / 链路本地 / 明文 http 全部 40001（公网用字面量 IP，不依赖 DNS）
        assertEquals(
                40001, code(post(admin, body("内网（示例）", "https://10.0.0.5/it", null))), "10/8 拒绝");
        assertEquals(
                40001, code(post(admin, body("回环（示例）", "https://127.0.0.1/it", null))), "回环拒绝");
        assertEquals(
                40001,
                code(post(admin, body("元数据（示例）", "https://169.254.169.254/it", null))),
                "链路本地（云元数据）拒绝");
        assertEquals(
                40001, code(post(admin, body("明文（示例）", "http://8.8.8.8/it", null))), "只接受 HTTPS");
        assertEquals(0, webhookCount(), "拒绝的配置不落库");

        // 先建一个公网的，再改成内网：同样拒绝
        long id = createWebhook(admin, "随访集成（示例）", "https://8.8.8.8/it");
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/webhooks/" + id,
                                admin,
                                body("改成内网（示例）", "https://192.168.1.5/it", null))),
                "修改成内网同样拒绝");
    }

    // ---------------------------------------------------------------- 配置维护

    @Test
    @SuppressWarnings("unchecked")
    void crudKeepsSecretHiddenAndRotatesOnDemand() {
        String admin = login(ADMIN);
        ResponseEntity<Map> created = post(admin, body("随访集成（示例）", "https://8.8.8.8/it", null));
        assertEquals(0, code(created));
        Map<String, Object> createdData = (Map<String, Object>) data(created).get("webhook");
        long id = ((Number) createdData.get("id")).longValue();
        String secretOnce = (String) data(created).get("secretShownOnce");
        assertEquals(43, secretOnce.length(), "系统生成 32 字节 Base64URL 是 43 字符");
        assertTrue(
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM sys_webhook WHERE id = "
                                                        + id
                                                        + " AND secret_cipher LIKE 'v1.%'",
                                                Integer.class)
                                        > 0),
                "secret 只存 CryptoService 信封密文");

        // 列表不回显 secret
        ResponseEntity<Map> list = get("/api/hospital/system/webhooks", admin);
        assertEquals(0, code(list));
        List<Map<String, Object>> items = dataList(list);
        assertEquals(1, items.size());
        assertNull(items.get(0).get("secretShownOnce"), "secret 只写不读");
        assertFalse(items.get(0).containsKey("secret"), "视图不携带 secret 字段");
        assertEquals(List.of("TASK_OPENED", "TASK_OVERDUE"), items.get(0).get("events"));

        // PUT：改名 + 停用，不带 secret → 保持不变、不回显
        ResponseEntity<Map> updated =
                put(
                        "/api/hospital/system/webhooks/" + id,
                        admin,
                        "{\"name\":\"随访集成改名（示例）\",\"url\":\"https://8.8.8.8/it2\","
                                + "\"events\":[\"PATIENT_DEAD\"],\"enabled\":false}");
        assertEquals(0, code(updated));
        assertNull(secretOf(updated));
        assertEquals(false, ((Map<String, Object>) data(updated).get("webhook")).get("enabled"));

        // PUT：带新 secret → 回显一次
        ResponseEntity<Map> rotated =
                put(
                        "/api/hospital/system/webhooks/" + id,
                        admin,
                        "{\"secret\":\"it-rotated-secret-32-bytes-example\",\"events\":"
                                + "[\"TASK_OPENED\",\"RECORD_SUBMITTED\"],\"enabled\":true}");
        assertEquals(0, code(rotated));
        assertEquals("it-rotated-secret-32-bytes-example", secretOf(rotated));

        // 删除：配置与投递台账一起清
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_webhook_delivery (webhook_id, event_type,"
                                        + " object_type, object_id, payload, status, attempt,"
                                        + " next_attempt_at) VALUES ("
                                        + id
                                        + ", 'TASK_OPENED', 'TASK', '1', '{}', 'PENDING', 0, NOW(3))"));
        assertEquals(0, code(delete("/api/hospital/system/webhooks/" + id, admin)));
        assertEquals(0, webhookCount());
        assertEquals(0, deliveryCount());

        // 审计：CREATE / UPDATE×2 / DELETE 各一条，且不含 secret 明文
        assertEquals(1, auditCount("WEBHOOK_CREATE"));
        assertEquals(2, auditCount("WEBHOOK_UPDATE"));
        assertEquals(1, auditCount("WEBHOOK_DELETE"));
        assertFalse(auditContains("it-rotated-secret-32-bytes-example"), "审计不落 secret 明文");
    }

    // ---------------------------------------------------------------- 权限 / 存在性 / 校验

    @Test
    void permissionAndExistenceGuards() {
        String admin = login(ADMIN);
        String doctor = login(DOCTOR);
        assertEquals(40300, code(get("/api/hospital/system/webhooks", doctor)), "无权限 40300");
        assertEquals(
                40300, code(post(doctor, body("越权（示例）", "https://8.8.8.8/it", null))), "写同样 40300");

        long id = createWebhook(admin, "随访集成（示例）", "https://8.8.8.8/it");
        assertEquals(
                40400,
                code(
                        put(
                                "/api/hospital/system/webhooks/999",
                                admin,
                                body("不存在（示例）", "https://8.8.8.8/it", null))));
        assertEquals(40400, code(delete("/api/hospital/system/webhooks/999", admin)));
        assertEquals(40400, code(post("/api/hospital/system/webhooks/999/test", admin, null)));
        assertEquals(40400, code(get("/api/hospital/system/webhooks/999/deliveries", admin)));
        assertEquals(
                40400,
                code(
                        post(
                                "/api/hospital/system/webhooks/999/deliveries/1/redeliver",
                                admin,
                                null)));

        // 校验失败：空事件 / 不可订阅事件 / 未知事件 / 空名称
        assertEquals(
                40001,
                code(
                        post(
                                admin,
                                "{\"name\":\"空事件（示例）\",\"url\":\"https://8.8.8.8/it\",\"events\":[]}")));
        assertEquals(
                40001,
                code(
                        post(
                                admin,
                                body("测试类型（示例）", "https://8.8.8.8/it", null)
                                        .replace("\"TASK_OPENED\"", "\"WEBHOOK_TEST\""))));
        assertEquals(
                40001,
                code(
                        post(
                                admin,
                                body("未知事件（示例）", "https://8.8.8.8/it", null)
                                        .replace("\"TASK_OPENED\"", "\"NOT_AN_EVENT\""))));
        assertEquals(
                40001,
                code(
                        post(
                                admin,
                                body("空名称（示例）", "https://8.8.8.8/it", null)
                                        .replace("\"空名称（示例）\"", "\"\""))));
    }

    /** 事件目录全集（A / C 差异由本院勾选体现，目录不分院）。 */
    @Test
    @SuppressWarnings("unchecked")
    void eventCatalogListsSubscribableEvents() {
        String admin = login(ADMIN);
        ResponseEntity<Map> catalog = get("/api/hospital/system/webhooks/events", admin);
        assertEquals(0, code(catalog));
        List<Map<String, Object>> items = dataList(catalog);
        assertEquals(4, items.size());
        assertEquals("PATIENT_DEAD", items.get(3).get("code"));
        assertEquals("患者死亡", items.get(3).get("label"));
        assertEquals(40300, code(get("/api/hospital/system/webhooks/events", login(DOCTOR))));
    }

    // ---------------------------------------------------------------- 工具

    private static String body(String name, String url, String secret) {
        String secretJson = secret == null ? "" : ("\"secret\":\"" + secret + "\",");
        return "{\"name\":\""
                + name
                + "\",\"url\":\""
                + url
                + "\","
                + secretJson
                + "\"events\":[\"TASK_OPENED\",\"TASK_OVERDUE\"]}";
    }

    private long createWebhook(String token, String name, String url) {
        ResponseEntity<Map> created = post(token, body(name, url, null));
        assertEquals(0, code(created));
        return ((Number) ((Map<?, ?>) data(created).get("webhook")).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static String secretOf(ResponseEntity<Map> response) {
        return (String) data(response).get("secretShownOnce");
    }

    private static int code(ResponseEntity<Map> response) {
        return ((Number) ((Map<?, ?>) response.getBody()).get("code")).intValue();
    }

    private String login(String username) {
        Map<?, ?> body = Map.of("tenantCode", TENANT_A, "username", username, "password", PASSWORD);
        Map<?, ?> response =
                rest.postForEntity("/api/hospital/auth/login", body, Map.class).getBody();
        assertEquals(0, ((Number) response.get("code")).intValue(), "登录成功：" + username);
        return (String) ((Map<?, ?>) response.get("data")).get("token");
    }

    private ResponseEntity<Map> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    private ResponseEntity<Map> post(String token, String jsonBody) {
        return exchange("/api/hospital/system/webhooks", HttpMethod.POST, token, jsonBody);
    }

    private ResponseEntity<Map> post(String path, String token, String jsonBody) {
        return exchange(path, HttpMethod.POST, token, jsonBody);
    }

    private ResponseEntity<Map> put(String path, String token, String jsonBody) {
        return exchange(path, HttpMethod.PUT, token, jsonBody);
    }

    private ResponseEntity<Map> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, token, null);
    }

    private ResponseEntity<Map> exchange(
            String path, HttpMethod method, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(jsonBody, headers), Map.class);
    }

    private int webhookCount() {
        return runInTenantA(
                () -> jdbc.queryForObject("SELECT COUNT(*) FROM sys_webhook", Integer.class));
    }

    private int deliveryCount() {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_webhook_delivery", Integer.class));
    }

    private int auditCount(String action) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM aud_log WHERE event_type = ?",
                                Integer.class,
                                action));
    }

    private boolean auditContains(String secret) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type LIKE 'WEBHOOK_%'"
                                                + " AND (detail LIKE ? OR after_json LIKE ? OR before_json LIKE ?)",
                                        Integer.class,
                                        "%" + secret + "%",
                                        "%" + secret + "%",
                                        "%" + secret + "%")
                                > 0);
    }

    private void runInTenantA(Runnable action) {
        com.crdp.framework.tenant.TenantContext.callAsTenant(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T runInTenantA(java.util.function.Supplier<T> action) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                TENANT_A_ID, TENANT_A, action::get);
    }
}
