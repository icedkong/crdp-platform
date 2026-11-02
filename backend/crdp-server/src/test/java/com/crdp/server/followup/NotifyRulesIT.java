package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.outbox.OutboxDispatcher;
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
 * E09-T04 端到端 / A-NTF-001、A-NTF-004、C-NTF-001、C-NTF-004。验收原文：任务开启、逾期等事件按规则发给责任人。
 *
 * <p>链路：sys_outbox 落事件（任务域发布方式，E14+ 落地后由其写入）→ OutboxDispatcher → NotifyEventSubscriber 规则引擎 →
 * 接收人解析（负责人 / 病区复核 / 兜底 / 抄送）→ 站内信 ntf_message + 台账 ntf_send_log（短信 QUEUED）。
 *
 * <p>覆盖：负责人收到任务开启站内信（标题不含患者姓名、姓名密文落库）、已逾期短信只落 QUEUED、 任务无负责人 →
 * 病区成员兜底（其他病区医生收不到）、负责人失去病区后不再收到、事件停用不投递、 重放不重发（至少一次幂等）、双租户隔离、 规则接口 GET/PUT + 权限 40300 +
 * 短信模板只列审核过的。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
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
class NotifyRulesIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String TENANT_B = "demob";
    private static final long TENANT_B_ID = 2L;
    private static final String PASSWORD = "Demo@2026";

    private static final String ADMIN = "nurse.demo";
    private static final String DOCTOR = "doctor.demo";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxDispatcher dispatcher;

    @BeforeEach
    void cleanState() {
        runInTenantA(this::cleanTenant);
        TenantContext.runAsTenant(TENANT_B_ID, TENANT_B, () -> deleteITRows(jdbc));
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(this::cleanTenant);
        TenantContext.runAsTenant(TENANT_B_ID, TENANT_B, () -> deleteITRows(jdbc));
    }

    private void cleanTenant() {
        deleteITRows(jdbc);
        // 恢复被用例改过的默认规则（任务开启开、已逾期开）
        jdbc.update(
                "UPDATE ntf_rule SET enabled = 1, channels = 'INBOX,SMS',"
                        + " recipients = 'OWNER,WARD_STAFF,CC_WARD_LEADER',"
                        + " sms_template_code = 'NOTIFY_OVERDUE', updated_by = NULL"
                        + " WHERE event_type = 'TASK_OVERDUE'");
        jdbc.update(
                "UPDATE ntf_rule SET enabled = 1, channels = 'INBOX',"
                        + " recipients = 'OWNER,WARD_STAFF', sms_template_code = NULL,"
                        + " updated_by = NULL WHERE event_type = 'TASK_OPENED'");
    }

    /** 删除本套件站内信、台账、事件与临时用户 / 病区关联。 */
    private static void deleteITRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE FROM sys_outbox WHERE event_type LIKE 'TASK_%' OR event_type = 'DAILY_DIGEST'");
        jdbc.update(
                "DELETE FROM ntf_message WHERE recipient_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.notify.%')");
        jdbc.update(
                "DELETE FROM ntf_send_log WHERE recipient_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.notify.%')");
        jdbc.update(
                "DELETE FROM sys_user_ward WHERE user_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.notify.%')");
        jdbc.update(
                "DELETE FROM sys_user_role WHERE user_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.notify.%')");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.notify.%'");
        jdbc.update("UPDATE ward SET default_owner_id = NULL");
    }

    // ---------------------------------------------------------------- 用例

    /** 验收主链路：任务开启事件 → 责任人站内信（姓名密文落库、标题无姓名）+ 站内信台账 SENT；重放不重发。 */
    @Test
    void taskOpenedEventReachesOwnerByRule() {
        long owner = userInTenantA("itr.notify.owner");
        long other = userInTenantA("itr.notify.other");
        long ward = wardInTenantA();
        addToWard(owner, ward);
        addToWard(other, ward);

        long eventId =
                publishInTenantA(
                        "TASK_OPENED",
                        "{\"taskId\":5001,\"ownerId\":"
                                + owner
                                + ",\"wardId\":"
                                + ward
                                + ",\"patientId\":990101,\"patientName\":\"张小明（示例）\","
                                + "\"nodeName\":\"第 4 年随访\",\"windowStartDate\":\"2026-11-02\","
                                + "\"windowEndDate\":\"2026-11-16\"}");

        dispatchInTenantA();

        assertEquals(1, countInbox(owner, "TASK_OPENED"), "责任人收到任务开启站内信");
        assertEquals(0, countInbox(other, "TASK_OPENED"), "其他成员不收（有负责人不兜底）");
        Map<String, Object> row = inboxRow(owner, "TASK_OPENED");
        assertEquals("任务开启：第 4 年随访", row.get("title"));
        assertFalse(String.valueOf(row.get("title")).contains("张小明"), "标题不含患者姓名明文");
        assertNotNull(row.get("patient_name_cipher"), "患者姓名密文落库（读取时按接收人策略脱敏）");
        assertEquals("TASK", row.get("target_type"));
        assertEquals(1, countSendLog(owner, "INBOX", "SENT"), "站内信台账 SENT");

        // 重放（至少一次语义）：重复分发同一事件不重发
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE sys_outbox SET status = 'PENDING' WHERE id = " + eventId));
        dispatchInTenantA();
        assertEquals(1, countInbox(owner, "TASK_OPENED"), "重放不重复投递");
        assertEquals(1, countSendLog(owner, "INBOX", "SENT"), "重放不重复记台账");
    }

    /** 已逾期（默认 INBOX+SMS）：站内信即时，短信只落 QUEUED（并入每日汇总由 E09-T05 发送）。 */
    @Test
    void overdueEventQueuesSmsInsteadOfSendingImmediately() {
        long owner = userInTenantA("itr.notify.owner2");
        long ward = wardInTenantA();
        addToWard(owner, ward);

        publishInTenantA(
                "TASK_OVERDUE",
                "{\"taskId\":5002,\"ownerId\":"
                        + owner
                        + ",\"wardId\":"
                        + ward
                        + ",\"patientId\":990102,\"patientName\":\"李小红（示例）\","
                        + "\"nodeName\":\"第 5 年随访\",\"windowEndDate\":\"2026-10-31\"}");

        dispatchInTenantA();

        assertEquals(1, countInbox(owner, "TASK_OVERDUE"), "责任人收到已逾期站内信");
        assertEquals(1, countSendLog(owner, "SMS", "QUEUED"), "短信待每日汇总发送（免打扰 / 频控在 E09-T05）");
        assertEquals(0, countSendLog(owner, "SMS", "SENT"), "不立即发短信");
    }

    /** 无负责人：该病区全部成员收到；另一病区成员收不到（验收原文）。 */
    @Test
    void missingOwnerFansOutToWardStaffOnly() {
        long ward = wardInTenantA();
        long staffA = userInTenantA("itr.notify.staffa");
        long staffB = userInTenantA("itr.notify.staffb");
        long otherWard = otherWardInTenantA();
        long outsider = userInTenantA("itr.notify.outsider");
        addToWard(staffA, ward);
        addToWard(staffB, ward);
        addToWard(outsider, otherWard);

        publishInTenantA(
                "TASK_OPENED",
                "{\"taskId\":5003,\"wardId\":"
                        + ward
                        + ",\"patientId\":990103,\"nodeName\":\"第 2 年随访\"}");

        dispatchInTenantA();

        assertEquals(1, countInbox(staffA, "TASK_OPENED"), "病区成员 A 收到");
        assertEquals(1, countInbox(staffB, "TASK_OPENED"), "病区成员 B 收到");
        assertEquals(0, countInbox(outsider, "TASK_OPENED"), "其他病区医生收不到");
    }

    /** 负责人已不负责该病区（非全院范围）：不再收到，落到病区成员。 */
    @Test
    void ownerWhoLeftWardNoLongerReceives() {
        long ward = wardInTenantA();
        long staleOwner = userInTenantA("itr.notify.stale");
        long staff = userInTenantA("itr.notify.staffc");
        addToWard(staff, ward);
        // staleOwner 不加病区、无全院角色

        publishInTenantA(
                "TASK_OPENED",
                "{\"taskId\":5004,\"ownerId\":"
                        + staleOwner
                        + ",\"wardId\":"
                        + ward
                        + ",\"patientId\":990104,\"nodeName\":\"第 3 年随访\"}");

        dispatchInTenantA();

        assertEquals(0, countInbox(staleOwner, "TASK_OPENED"), "已不负责该病区的医生不再收到");
        assertEquals(1, countInbox(staff, "TASK_OPENED"), "病区成员兜底收到");
    }

    /** 已逾期抄送病区负责人（ward.default_owner_id，OQ-60 暂按默认随访负责人解析）。 */
    @Test
    void overdueCopiesWardLeader() {
        long ward = wardInTenantA();
        long owner = userInTenantA("itr.notify.owner3");
        long leader = userInTenantA("itr.notify.leader");
        addToWard(owner, ward);
        addToWard(leader, ward);
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE ward SET default_owner_id = "
                                        + leader
                                        + " WHERE id = "
                                        + ward));

        publishInTenantA(
                "TASK_OVERDUE",
                "{\"taskId\":5005,\"ownerId\":"
                        + owner
                        + ",\"wardId\":"
                        + ward
                        + ",\"patientId\":990105,\"nodeName\":\"第 6 年随访\"}");

        dispatchInTenantA();

        assertEquals(1, countInbox(owner, "TASK_OVERDUE"), "责任人收到");
        assertEquals(1, countInbox(leader, "TASK_OVERDUE"), "病区负责人抄送收到");
    }

    /** 租户停用某事件（通知设置页能力）：不投递不记台账；重新启用恢复。 */
    @Test
    void disabledEventIsSkippedAndReenabledWorks() {
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE ntf_rule SET enabled = 0 WHERE event_type = 'WINDOW_OPEN'"));
        long owner = userInTenantA("itr.notify.owner4");
        long ward = wardInTenantA();
        addToWard(owner, ward);

        publishInTenantA(
                "WINDOW_OPEN",
                "{\"taskId\":5006,\"ownerId\":"
                        + owner
                        + ",\"wardId\":"
                        + ward
                        + ",\"patientId\":990106,\"nodeName\":\"第 7 年随访\"}");
        dispatchInTenantA();
        assertEquals(0, countInbox(owner, "WINDOW_OPEN"), "事件停用不发");

        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE ntf_rule SET enabled = 1 WHERE event_type = 'WINDOW_OPEN'"));
        publishInTenantA(
                "WINDOW_OPEN",
                "{\"taskId\":5007,\"ownerId\":"
                        + owner
                        + ",\"wardId\":"
                        + ward
                        + ",\"patientId\":990106,\"nodeName\":\"第 7 年随访\"}");
        dispatchInTenantA();
        assertEquals(1, countInbox(owner, "WINDOW_OPEN"), "重新启用后恢复");
    }

    /** 双租户隔离：demob 的事件只投给 demob 的接收人，规则互不影响。 */
    @Test
    void tenantBEventsStayInTenantB() {
        Long tenantBUser =
                TenantContext.callAsTenant(
                        TENANT_B_ID,
                        TENANT_B,
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash,"
                                            + " status) VALUES ('itr.notify.b', '乙院医生（示例）',"
                                            + " 'x', 'ENABLED')");
                            long user =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_user WHERE username ="
                                                    + " 'itr.notify.b'",
                                            Long.class);
                            long wardId =
                                    jdbc.queryForObject("SELECT id FROM ward LIMIT 1", Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)",
                                    user,
                                    wardId);
                            jdbc.update(
                                    "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                                    "TASK_OPENED",
                                    "{\"taskId\":6001,\"ownerId\":"
                                            + user
                                            + ",\"wardId\":"
                                            + wardId
                                            + ",\"patientId\":880101,\"patientName\":\"王小刚（示例）\","
                                            + "\"nodeName\":\"术后 3 个月\"}");
                            return user;
                        });
        TenantContext.callAsTenant(
                TENANT_B_ID,
                TENANT_B,
                () -> {
                    dispatcher.dispatchForCurrentTenant();
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ntf_message WHERE recipient_id = ? AND"
                                            + " event_type = 'TASK_OPENED'",
                                    Integer.class,
                                    tenantBUser),
                            "乙院事件在乙院投递");
                    return null;
                });
        runInTenantA(
                () -> {
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ntf_message WHERE event_type ="
                                            + " 'TASK_OPENED' AND recipient_id = "
                                            + tenantBUser,
                                    Integer.class),
                            "甲院看不到乙院站内信");
                    return null;
                });
    }

    // ---------------------------------------------------------------- 规则接口（通知设置页）

    /** GET/PUT 规则与短信模板：权限 40300、未登录 40100、目录全集、修改生效并写审计。 */
    @Test
    void rulesApiGuardsAndPersists() {
        // 医生（无 followup:notice:config）→ 40300
        String doctorToken = login(TENANT_A, DOCTOR);
        assertEquals(40300, code(get("/api/hospital/notify/rules", doctorToken).getBody()));
        // 未登录 → 40100
        ResponseEntity<Map> anonymous = rest.getForEntity("/api/hospital/notify/rules", Map.class);
        assertEquals(40100, ((Number) anonymous.getBody().get("code")).intValue());

        // 租户管理员 → 目录全集 10 项
        String adminToken = login(TENANT_A, ADMIN);
        Map<?, ?> list = get("/api/hospital/notify/rules", adminToken).getBody();
        assertEquals(0, code(list));
        List<?> items = (List<?>) list.get("data");
        assertEquals(10, items.size(), "通知事件目录全集");

        // 修改：已逾期只留站内信（advanceDays / smsTemplateCode 留空）
        Map<?, ?> updated =
                put(
                                "/api/hospital/notify/rules/TASK_OVERDUE",
                                adminToken,
                                "{\"enabled\":true,\"advanceDays\":null,\"sendTime\":\"08:30\","
                                        + "\"channels\":[\"INBOX\"],\"recipients\":[\"OWNER\"],"
                                        + "\"smsTemplateCode\":null}")
                        .getBody();
        assertEquals(0, code(updated));
        assertEquals(List.of("INBOX"), data(updated).get("channels"));

        // 落库生效 + 审计
        runInTenantA(
                () -> {
                    assertEquals(
                            "INBOX",
                            jdbc.queryForObject(
                                    "SELECT channels FROM ntf_rule WHERE event_type ="
                                            + " 'TASK_OVERDUE'",
                                    String.class));
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'NOTICE_RULE_UPDATE'",
                                            Integer.class)
                                    >= 1,
                            "配置变更写审计");
                });

        // 短信模板只列平台审核过的 NOTIFY 模板
        Map<?, ?> templates = get("/api/hospital/notify/sms-templates", adminToken).getBody();
        assertEquals(0, code(templates));
        List<?> templateItems = (List<?>) templates.get("data");
        assertTrue(templateItems.size() >= 2, "内置 NOTIFY 模板在列：" + templateItems);
        assertTrue(
                templateItems.stream()
                        .allMatch(
                                item ->
                                        String.valueOf(((Map<?, ?>) item).get("code"))
                                                .startsWith("NOTIFY_")));

        // 校验失败：渠道含 SMS 必配审核模板
        assertEquals(
                40001,
                code(
                        put(
                                        "/api/hospital/notify/rules/TASK_OVERDUE",
                                        adminToken,
                                        "{\"enabled\":true,\"channels\":[\"INBOX\",\"SMS\"],"
                                                + "\"recipients\":[\"OWNER\"]}")
                                .getBody()));
    }

    // ---------------------------------------------------------------- 工具

    private long publishInTenantA(String eventType, String payload) {
        return runInTenantA(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                            eventType,
                            payload);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM sys_outbox WHERE event_type = '" + eventType + "'",
                            Long.class);
                });
    }

    private long userInTenantA(String username) {
        return runInTenantA(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status)"
                                    + " VALUES (?, '医生（示例）', 'x', 'ENABLED')",
                            username);
                    return jdbc.queryForObject(
                            "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
                });
    }

    private long wardInTenantA() {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
    }

    private long otherWardInTenantA() {
        return runInTenantA(
                () -> {
                    List<Long> existing =
                            jdbc.queryForList(
                                    "SELECT id FROM ward WHERE code = 'W-IT-OTHER'", Long.class);
                    if (!existing.isEmpty()) {
                        return existing.get(0);
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES ('W-IT-OTHER',"
                                    + " '虚构病区二（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-OTHER'", Long.class);
                });
    }

    private void addToWard(long userId, long wardId) {
        runInTenantA(
                () ->
                        jdbc.update(
                                "INSERT IGNORE INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)",
                                userId,
                                wardId));
    }

    private int countInbox(long userId, String eventType) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ntf_message WHERE recipient_id = ? AND"
                                        + " event_type = ?",
                                Integer.class,
                                userId,
                                eventType));
    }

    private Map<String, Object> inboxRow(long userId, String eventType) {
        return runInTenantA(
                () ->
                        jdbc.queryForMap(
                                "SELECT * FROM ntf_message WHERE recipient_id = ? AND event_type ="
                                        + " ? ORDER BY id DESC LIMIT 1",
                                userId,
                                eventType));
    }

    private int countSendLog(long userId, String channel, String status) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = ? AND"
                                        + " channel = ? AND send_status = ?",
                                Integer.class,
                                userId,
                                channel,
                                status));
    }

    private void dispatchInTenantA() {
        runInTenantA(
                () -> {
                    dispatcher.dispatchForCurrentTenant();
                    return null;
                });
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
}
