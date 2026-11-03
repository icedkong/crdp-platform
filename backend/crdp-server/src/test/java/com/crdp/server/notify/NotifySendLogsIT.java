package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
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
 * E09-T07 端到端 / A-NTF-006、A-NTF-007、C-NTF-006、C-NTF-007。验收原文：可查每条发送结果；个人可关闭部分事件。
 *
 * <p>覆盖：发送台账条件查询（事件 / 渠道 / 结果 / 日期 / 接收人 + 分页）与批量查询审计、失败重试（FAILED → QUEUED + NOTICE_SEND_RETRY
 * 审计；非失败 40001、不存在 40400）、权限 40300、 个人通知偏好 GET/PUT（登录即可）、 逾期类默认不可关（租户设置放开后可关）、偏好对规则引擎生效（本人关闭的事件短信记
 * SKIPPED、站内信照发）。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.outbox.dispatch.retry-base-ms=50"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifySendLogsIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    private static final String ADMIN = "nurse.demo";
    private static final String DOCTOR = "doctor.demo";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxDispatcher dispatcher;

    @BeforeEach
    void cleanState() {
        runInTenantA(this::cleanTenant);
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(this::cleanTenant);
    }

    private void cleanTenant() {
        deleteITRows(jdbc);
        // 恢复默认设置（单行 id=1：21:00–08:00 / 3 / 逾期短信不可个人关闭）
        jdbc.update(
                "UPDATE ntf_setting SET dnd_start = '21:00:00', dnd_end = '08:00:00',"
                        + " sms_daily_limit = 3, overdue_sms_off_allowed = 0, updated_by = NULL"
                        + " WHERE id = 1");
    }

    /** 删除本套件台账、偏好、站内信、事件与临时用户。 */
    private static void deleteITRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE FROM ntf_send_log WHERE recipient_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.log.%')"
                        + " OR recipient_id = (SELECT id FROM sys_user WHERE username = 'doctor.demo')");
        jdbc.update(
                "DELETE FROM ntf_user_pref WHERE user_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.log.%')"
                        + " OR user_id = (SELECT id FROM sys_user WHERE username = 'doctor.demo')");
        jdbc.update(
                "DELETE FROM ntf_message WHERE recipient_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.log.%')"
                        + " OR recipient_id = (SELECT id FROM sys_user WHERE username = 'doctor.demo')");
        jdbc.update(
                "DELETE FROM sys_outbox WHERE event_type = 'DAILY_DIGEST' OR event_type = 'TASK_OVERDUE'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.log.%'");
    }

    // ---------------------------------------------------------------- 发送记录查询（A-NTF-006）

    /** 验收主链路：每条发送结果可查——条件过滤、分页、接收人展示名、批量查询审计。 */
    @Test
    void searchReturnsEachSendResultWithFilters() {
        // 种子行的 created_at 是数据库 now()（V22 默认值），日期过滤窗口必须动态取当天
        // （曾硬编码 2026-11-02，次日起任何分支都 0 命中）；取种前 / 查询前各取一次，
        // 跨零点执行时窗口 [种下日, 查询日] 仍覆盖种子行（无条件查询已断言总共只有 3 条）。
        LocalDate seededOn = LocalDate.now();
        long user = seedUser("itr.log.owner");
        seedSendLog(user, "TASK_OVERDUE", "SMS", "FAILED", "通道未配置");
        seedSendLog(user, "TASK_OVERDUE", "INBOX", "SENT", null);
        seedSendLog(user, "DAILY_DIGEST", "SMS", "QUEUED", null);
        LocalDate queriedOn = LocalDate.now();

        String adminToken = login(ADMIN);
        // 无条件：3 条
        Map<?, ?> all = post("/api/hospital/notify/send-logs/search", adminToken, "{}").getBody();
        assertEquals(0, code(all));
        assertEquals(3L, ((Number) data(all).get("total")).intValue(), "全部台账可见");

        // 条件：事件 + 渠道 + 结果
        Map<?, ?> filtered =
                post(
                                "/api/hospital/notify/send-logs/search",
                                adminToken,
                                "{\"eventType\":\"TASK_OVERDUE\",\"channel\":\"SMS\","
                                        + "\"sendStatus\":\"FAILED\"}")
                        .getBody();
        assertEquals(0, code(filtered));
        List<?> items = (List<?>) data(filtered).get("items");
        assertEquals(1, ((Number) data(filtered).get("total")).intValue());
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals("已逾期", row.get("eventLabel"), "事件中文名");
        assertEquals("张医生（示例）", row.get("recipientName"), "接收人展示名");
        assertEquals("FAILED", row.get("sendStatus"));
        assertEquals("通道未配置", row.get("detail"));
        assertEquals("已逾期：第 5 年随访", row.get("title"), "标题与站内信一致，不含患者信息");

        // 日期过滤（闭区间按日，动态取当天——种子行 created_at = 数据库 now()）
        Map<?, ?> byDate =
                post(
                                "/api/hospital/notify/send-logs/search",
                                adminToken,
                                "{\"dateFrom\":\""
                                        + seededOn
                                        + "\",\"dateTo\":\""
                                        + queriedOn
                                        + "\"}")
                        .getBody();
        assertEquals(0, code(byDate));
        assertEquals(3, ((Number) data(byDate).get("total")).intValue(), "按当日过滤");

        // 接收人过滤 + 分页
        Map<?, ?> byRecipient =
                post(
                                "/api/hospital/notify/send-logs/search",
                                adminToken,
                                "{\"recipientId\":" + user + ",\"pageNo\":1,\"pageSize\":2}")
                        .getBody();
        assertEquals(0, code(byRecipient));
        assertEquals(3, ((Number) data(byRecipient).get("total")).intValue());
        assertEquals(2, ((List<?>) data(byRecipient).get("items")).size(), "pageSize=2 只回一页");

        // 批量查询写数据访问审计
        assertTrue(
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_data_access_log WHERE"
                                                        + " access_method = 'BATCH_QUERY' AND object_type ="
                                                        + " 'NTF_SEND_LOG'",
                                                Integer.class)
                                        >= 1),
                "发送记录查询写批量查询审计");

        // 无权限医生 → 40300；校验失败（未知渠道）→ 40001
        String doctorToken = login(DOCTOR);
        assertEquals(
                40300,
                code(post("/api/hospital/notify/send-logs/search", doctorToken, "{}").getBody()));
        assertEquals(
                40001,
                code(
                        post(
                                        "/api/hospital/notify/send-logs/search",
                                        adminToken,
                                        "{\"channel\":\"EMAIL\"}")
                                .getBody()));
    }

    /** 失败可重试：FAILED → QUEUED（等待 E09-T05 汇总发送）+ 操作审计；非失败 40001；不存在 40400。 */
    @Test
    void retryRequeuesFailedSendLog() {
        long user = seedUser("itr.log.retry");
        long failed = seedSendLog(user, "TASK_OVERDUE", "SMS", "FAILED", "通道未配置");
        long queued = seedSendLog(user, "DAILY_DIGEST", "SMS", "QUEUED", null);

        String adminToken = login(ADMIN);
        Map<?, ?> retried =
                post("/api/hospital/notify/send-logs/" + failed + "/retry", adminToken, null)
                        .getBody();
        assertEquals(0, code(retried));
        Map<?, ?> retriedItem = (Map<?, ?>) data(retried).get("item");
        assertEquals(Boolean.TRUE, data(retried).get("requeued"));
        assertEquals("QUEUED", retriedItem.get("sendStatus"));
        assertTrue(String.valueOf(retriedItem.get("detail")).contains("通道未配置"), "保留原失败原因");
        assertEquals(
                "QUEUED",
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT send_status FROM ntf_send_log WHERE id = " + failed,
                                        String.class)),
                "落库回 QUEUED");
        assertTrue(
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'NOTICE_SEND_RETRY'",
                                                Integer.class)
                                        >= 1),
                "重试写操作审计");

        assertEquals(
                40001,
                code(
                        post(
                                        "/api/hospital/notify/send-logs/" + queued + "/retry",
                                        adminToken,
                                        null)
                                .getBody()),
                "非失败记录不可重试");
        assertEquals(
                40400,
                code(
                        post("/api/hospital/notify/send-logs/999999999/retry", adminToken, null)
                                .getBody()),
                "不存在的记录不暴露存在");
    }

    // ---------------------------------------------------------------- 个人通知偏好（A-NTF-007）

    /** 个人可关闭部分事件：普通医生登录即可读偏好；非逾期类可关、逾期类默认 40001、租户放开后可关。 */
    @Test
    void personalPreferenceBlocksNonOverdueSmsOnly() {
        String doctorToken = login(DOCTOR);
        Map<?, ?> view = get("/api/hospital/profile/notify-pref", doctorToken).getBody();
        assertEquals(0, code(view));
        assertEquals(false, data(view).get("overdueSmsOffAllowed"), "默认不允许关闭逾期类短信");
        List<?> options = (List<?>) data(view).get("options");
        assertTrue(options.size() >= 2, "默认规则下已逾期与每日汇总都在可选列表：" + options);
        assertEquals(List.of(), data(view).get("smsOff"));

        // 非逾期类（每日汇总）可关
        Map<?, ?> saved =
                put(
                                "/api/hospital/profile/notify-pref",
                                doctorToken,
                                "{\"smsOff\":[\"DAILY_DIGEST\"]}")
                        .getBody();
        assertEquals(0, code(saved));
        assertEquals(List.of("DAILY_DIGEST"), data(saved).get("smsOff"));

        // 逾期类默认不可关 → 40001
        assertEquals(
                40001,
                code(
                        put(
                                        "/api/hospital/profile/notify-pref",
                                        doctorToken,
                                        "{\"smsOff\":[\"TASK_OVERDUE\"]}")
                                .getBody()));

        // 租户放开后可关（A-NTF-007「逾期类是否允许关闭由租户配置」）
        String adminToken = login(ADMIN);
        Map<?, ?> settings =
                put(
                                "/api/hospital/notify/settings",
                                adminToken,
                                "{\"dndStart\":\"21:00\",\"dndEnd\":\"08:00\","
                                        + "\"smsDailyLimit\":3,\"overdueSmsOffAllowed\":true}")
                        .getBody();
        assertEquals(0, code(settings));
        assertEquals(true, data(settings).get("overdueSmsOffAllowed"));
        Map<?, ?> overdueSaved =
                put(
                                "/api/hospital/profile/notify-pref",
                                doctorToken,
                                "{\"smsOff\":[\"TASK_OVERDUE\"]}")
                        .getBody();
        assertEquals(0, code(overdueSaved));
        assertEquals(List.of("TASK_OVERDUE"), data(overdueSaved).get("smsOff"));
    }

    /** 偏好对规则引擎生效：本人已关闭的事件短信记 SKIPPED 不并入汇总，站内信照发。 */
    @Test
    void preferenceSkipsSmsInRuleEngine() {
        long doctor = userId(DOCTOR);
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO ntf_user_pref (user_id, sms_off) VALUES (?, 'DAILY_DIGEST')"
                                        + " ON DUPLICATE KEY UPDATE sms_off = 'DAILY_DIGEST'",
                                doctor));

        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT INTO sys_outbox (event_type, payload) VALUES ('DAILY_DIGEST', ?)",
                                "{\"recipientIds\":[" + doctor + "]}"));
        runInTenantA(() -> dispatcher.dispatchForCurrentTenant());

        assertEquals(1, countSendLog(doctor, "DAILY_DIGEST", "INBOX", "SENT"), "站内信不受个人短信偏好影响");
        assertEquals(
                1, countSendLog(doctor, "DAILY_DIGEST", "SMS", "SKIPPED"), "本人已关闭的事件短信记 SKIPPED");
        assertEquals(
                "个人通知偏好已关闭该事件短信",
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT detail FROM ntf_send_log WHERE recipient_id = "
                                                + doctor
                                                + " AND channel = 'SMS' AND event_type ="
                                                + " 'DAILY_DIGEST'",
                                        String.class)));
        assertNotNull(
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ntf_message WHERE recipient_id = "
                                                + doctor
                                                + " AND event_type = 'DAILY_DIGEST'",
                                        Integer.class)),
                "站内信已落收件箱");
    }

    /** 发送设置 GET：默认 21:00–08:00 / 每日 3 条 / 逾期不可关；修改校验（起止相同 40001）。 */
    @Test
    void settingsApiReturnsDefaultsAndValidates() {
        String adminToken = login(ADMIN);
        Map<?, ?> view = get("/api/hospital/notify/settings", adminToken).getBody();
        assertEquals(0, code(view));
        assertEquals("21:00", data(view).get("dndStart"));
        assertEquals("08:00", data(view).get("dndEnd"));
        assertEquals(3, ((Number) data(view).get("smsDailyLimit")).intValue());
        assertEquals(false, data(view).get("overdueSmsOffAllowed"));

        assertEquals(
                40001,
                code(
                        put(
                                        "/api/hospital/notify/settings",
                                        adminToken,
                                        "{\"dndStart\":\"21:00\",\"dndEnd\":\"21:00\","
                                                + "\"smsDailyLimit\":3,\"overdueSmsOffAllowed\":false}")
                                .getBody()),
                "免打扰起止相同无意义");
        assertEquals(
                40300,
                code(get("/api/hospital/notify/settings", login(DOCTOR)).getBody()),
                "医生无权限");
    }

    // ---------------------------------------------------------------- 工具

    private long seedUser(String username) {
        return runInTenantA(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status)"
                                    + " VALUES (?, '张医生（示例）', 'x', 'ENABLED')",
                            username);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
                });
    }

    private long seedSendLog(
            long userId, String eventType, String channel, String status, String detail) {
        return runInTenantA(
                () -> {
                    jdbc.update(
                            "INSERT INTO ntf_send_log (event_type, recipient_id, channel,"
                                    + " send_status, title, detail, dedup_key) VALUES (?, ?, ?, ?,"
                                    + " '已逾期：第 5 年随访', ?, ?)",
                            eventType,
                            userId,
                            channel,
                            status,
                            detail,
                            "itr." + System.nanoTime() + "." + userId + "." + channel);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM ntf_send_log WHERE recipient_id = " + userId,
                            Long.class);
                });
    }

    private long userId(String username) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private int countSendLog(long userId, String eventType, String channel, String status) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = ? AND"
                                        + " event_type = ? AND channel = ? AND send_status = ?",
                                Integer.class,
                                userId,
                                eventType,
                                channel,
                                status));
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

    private String login(String username) {
        Map<?, ?> body = Map.of("tenantCode", TENANT_A, "username", username, "password", PASSWORD);
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
    private ResponseEntity<Map> post(String path, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), Map.class);
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
}
