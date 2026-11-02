package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.notice.app.NoticeInboxService;
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
 * E09-T02 / A-NTF-002、C-NTF-002 端到端（验收：点击消息跳到对应任务——target 落点随消息下发， 前端按 target 路由；此处锁定通道与收件箱）。
 *
 * <p>覆盖：列表只看本人（他人消息 40400 + DENIED 审计）、患者姓名读取时按接收人策略脱敏（默认 MASKED， 密文落库）、未读数与已读流转、投递幂等（outbox
 * 至少一次语义）、双租户收件箱隔离、分页校验 40001、匿名 40100。 全部虚构数据。门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoticeInboxIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String TENANT_B = "demob";
    private static final long TENANT_B_ID = 2L;
    private static final String PASSWORD = "Demo@2026";

    private static final String USER_A = "itr.inbox.a.demo";
    private static final String USER_B = "itr.inbox.b.demo";

    /** 虚构患者姓名（示例）：默认 MASKED 策略按长度掩码（张*）——姓名带（示例）后缀是仓库测试数据规范。 */
    private static final String PLAIN_NAME = "张小明（示例）";

    private static final String MASKED_NAME = "张*）";
    private static final long TASK_ID = 4242L;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NoticeInboxService inbox;

    @BeforeEach
    void cleanState() {
        runInTenantA(() -> deleteItrRows(jdbc));
        runInTenantB(() -> deleteItrRows(jdbc));
    }

    @AfterAll
    void cleanUp() {
        runInTenantA(() -> deleteItrRows(jdbc));
        runInTenantB(() -> deleteItrRows(jdbc));
    }

    /** 删除本套件用户与其站内信、DENIED 审计行（走维护开关，链上空洞属预期）。 */
    private static void deleteItrRows(JdbcTemplate jdbc) {
        jdbc.update(
                "DELETE FROM ntf_message WHERE recipient_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'itr.inbox.%')");
        com.crdp.server.AuditTableMaintenance.delete(
                jdbc, "DELETE FROM aud_log WHERE action = 'NOTICE_INBOX_READ'");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'itr.inbox.%'");
    }

    // ---------------------------------------------------------------- 用例

    /** 正常链路：投递带任务落点的消息 → 列表只看本人、姓名按接收人策略脱敏、 target 下发（前端据此跳到对应任务，验收标准）； 未读过滤、已读流转、全部已读。 */
    @Test
    void inboxListsMaskedNameWithTaskTargetAndTracksUnread() {
        long userA = userInTenantA(USER_A);
        userInTenantA(USER_B);
        deliverInTenantA(
                new NoticeInboxService.Delivery(
                        userA,
                        "DAILY_DIGEST",
                        "今日随访汇总",
                        "您有 2 项随访任务待处理（示例）",
                        null,
                        null,
                        null,
                        null,
                        "it-case1-digest:" + userA));
        deliverInTenantA(
                new NoticeInboxService.Delivery(
                        userA,
                        "TASK_OPENED",
                        "随访任务已开启",
                        "第 4 年随访窗口已开启（示例）",
                        990_001L,
                        PLAIN_NAME,
                        "TASK",
                        TASK_ID,
                        "it-case1:" + userA));

        String tokenA = login(TENANT_A, USER_A);

        // 列表：本人 2 条、id 倒序；姓名脱敏；TASK 落点随消息下发
        Map<?, ?> page = get("/api/hospital/notify/messages", tokenA);
        assertEquals(0, code(page));
        assertEquals(2L, ((Number) data(page).get("total")).longValue());
        List<?> items = (List<?>) data(page).get("items");
        Map<?, ?> taskMessage = (Map<?, ?>) items.get(0);
        assertEquals("随访任务已开启", taskMessage.get("title"));
        assertEquals(MASKED_NAME, taskMessage.get("patientName"), "姓名读取时按接收人策略脱敏");
        assertEquals("TASK", ((Map<?, ?>) taskMessage.get("target")).get("type"));
        assertEquals(
                String.valueOf(TASK_ID),
                String.valueOf(((Map<?, ?>) taskMessage.get("target")).get("id")));
        assertFalse((Boolean) taskMessage.get("read"));
        Map<?, ?> digest = (Map<?, ?>) items.get(1);
        assertFalse(digest.containsKey("patientName"), "无患者姓名的消息不出现该键");

        // 未读数：2 → 标 1 条已读 → 1 → 全部已读 → 0
        assertEquals(2L, unread(tokenA));
        long taskMessageId = ((Number) taskMessage.get("id")).longValue();
        assertEquals(
                0, code(post("/api/hospital/notify/messages/" + taskMessageId + "/read", tokenA)));
        assertEquals(1L, unread(tokenA));

        Map<?, ?> unreadPage = get("/api/hospital/notify/messages?unread=true", tokenA);
        List<?> unreadItems = (List<?>) data(unreadPage).get("items");
        assertEquals(1, unreadItems.size());
        assertEquals("今日随访汇总", ((Map<?, ?>) unreadItems.getFirst()).get("title"));

        Map<?, ?> markedAll = post("/api/hospital/notify/messages/read-all", tokenA);
        assertEquals(1, ((Number) data(markedAll).get("marked")).intValue());
        assertEquals(0L, unread(tokenA));
    }

    /** 越范围（40400）：别人的消息 / 不存在的消息一律 40400 不暴露存在，并写 DENIED 操作审计。 */
    @Test
    void markingOthersMessageGets40400AndDeniedAudit() {
        long userA = userInTenantA(USER_A);
        long userB = userInTenantA(USER_B);
        deliverInTenantA(
                new NoticeInboxService.Delivery(
                        userA,
                        "TASK_OPENED",
                        "随访任务已开启",
                        null,
                        null,
                        null,
                        "TASK",
                        TASK_ID,
                        "it-case2:" + userA));

        long messageId =
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ntf_message WHERE recipient_id = ?",
                                        Long.class,
                                        userA));
        String tokenB = login(TENANT_A, USER_B);

        assertEquals(
                40400,
                code(post("/api/hospital/notify/messages/" + messageId + "/read", tokenB)),
                "别人的消息不暴露存在");
        assertEquals(40400, code(post("/api/hospital/notify/messages/999999/read", tokenB)));

        Integer denied =
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log"
                                                + " WHERE action = 'NOTICE_INBOX_READ'"
                                                + " AND result = 'DENIED' AND error_code = 40400",
                                        Integer.class));
        assertTrue(denied != null && denied >= 1, "越权标已读应写 DENIED 审计");

        // 收件人本人仍可正常标已读
        String tokenA = login(TENANT_A, USER_A);
        assertEquals(0, code(post("/api/hospital/notify/messages/" + messageId + "/read", tokenA)));
    }

    /** 幂等（outbox 至少一次语义）：同一 dedup_key 重复投递只落一条。 */
    @Test
    void deliverIsIdempotentPerDedupKey() {
        long userA = userInTenantA(USER_A);
        String dedup = "it-case3:" + userA;
        NoticeInboxService.Delivery delivery =
                new NoticeInboxService.Delivery(
                        userA, "TASK_OPENED", "随访任务已开启", null, null, null, "TASK", TASK_ID, dedup);

        assertTrue(deliverInTenantA(delivery), "首次投递是新消息");
        assertFalse(deliverInTenantA(delivery), "重复投递不报错不重发");

        String tokenA = login(TENANT_A, USER_A);
        assertEquals(
                1L,
                ((Number) data(get("/api/hospital/notify/messages", tokenA)).get("total"))
                        .longValue());
    }

    /** 双租户：站内信落各自租户库，A 院用户看不到 B 院投给同名账号的消息。 */
    @Test
    void tenantIsolationKeepsInboxesSeparate() {
        long userA = userInTenantA(USER_A);
        long userB = userInTenantB(USER_A);
        deliverInTenantA(
                new NoticeInboxService.Delivery(
                        userA,
                        "TASK_OPENED",
                        "医院 A 的消息（示例）",
                        null,
                        null,
                        null,
                        "TASK",
                        TASK_ID,
                        "it-case4-a:" + userA));
        deliverInTenantB(
                new NoticeInboxService.Delivery(
                        userB,
                        "TASK_OVERDUE",
                        "医院 B 的消息（示例）",
                        null,
                        null,
                        null,
                        "TASK",
                        TASK_ID + 1,
                        "it-case4-b:" + userB));

        Map<?, ?> pageA = data(get("/api/hospital/notify/messages", login(TENANT_A, USER_A)));
        assertEquals(1L, ((Number) pageA.get("total")).longValue());
        assertEquals(
                "医院 A 的消息（示例）",
                ((Map<?, ?>) ((List<?>) pageA.get("items")).getFirst()).get("title"));

        Map<?, ?> pageB = data(get("/api/hospital/notify/messages", login(TENANT_B, USER_A)));
        assertEquals(1L, ((Number) pageB.get("total")).longValue());
        assertEquals(
                "医院 B 的消息（示例）",
                ((Map<?, ?>) ((List<?>) pageB.get("items")).getFirst()).get("title"));
    }

    /** 校验失败（40001）：pageNo 从 1 起、pageSize 上限 100（与审计查询同口径）。 */
    @Test
    void invalidPagingReturns40001() {
        userInTenantA(USER_A);
        String token = login(TENANT_A, USER_A);
        assertEquals(40001, code(get("/api/hospital/notify/messages?pageNo=0", token)));
        assertEquals(40001, code(get("/api/hospital/notify/messages?pageSize=101", token)));
    }

    /** 匿名（40100）：站内信是登录接口，不带令牌拒绝。 */
    @Test
    void anonymousRequestRejected() {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/hospital/notify/messages",
                        HttpMethod.GET,
                        new HttpEntity<>(headers(null)),
                        Map.class);
        assertEquals(40100, ((Number) body(response).get("code")).intValue());
    }

    // ---------------------------------------------------------------- 基础设施

    /** 建无角色的 IT 用户（站内信登录即可，不需要权限码），口令哈希复用种子医生。 */
    private long userInTenantA(String username) {
        return runInTenantA(() -> createUser(jdbc, username));
    }

    private long userInTenantB(String username) {
        return runInTenantB(() -> createUser(jdbc, username));
    }

    private static long createUser(JdbcTemplate jdbc, String username) {
        jdbc.update(
                "INSERT INTO sys_user (username, display_name, password_hash, status,"
                        + " must_change_password) SELECT ?, 'IT 站内信用户（示例）', password_hash,"
                        + " 'ENABLED', 0 FROM sys_user WHERE username = 'doctor.demo'",
                username);
        return jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private boolean deliverInTenantA(NoticeInboxService.Delivery delivery) {
        return runInTenantA(() -> inbox.deliver(delivery));
    }

    private boolean deliverInTenantB(NoticeInboxService.Delivery delivery) {
        return runInTenantB(() -> inbox.deliver(delivery));
    }

    private String login(String tenantCode, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                tenantCode,
                                "username",
                                username,
                                "password",
                                PASSWORD));
        assertEquals(0, code(response), username + "@" + tenantCode + " 应能登录");
        return (String) data(response).get("token");
    }

    private long unread(String token) {
        Map<?, ?> count = data(get("/api/hospital/notify/messages/unread-count", token));
        return ((Number) count.get("unread")).longValue();
    }

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class)
                .getBody();
    }

    private Map<?, ?> post(String path, String token) {
        return rest.exchange(
                        path,
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of(), headers(token)),
                        Map.class)
                .getBody();
    }

    private Map<?, ?> post(String path, Object tokenIgnored, Map<String, Object> body) {
        return rest.exchange(
                        path, HttpMethod.POST, new HttpEntity<>(body, headers(null)), Map.class)
                .getBody();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static Map<?, ?> body(ResponseEntity<Map> response) {
        return response.getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private void runInTenantA(Runnable action) {
        TenantContext.runAsTenant(TENANT_A_ID, TENANT_A, action);
    }

    private void runInTenantB(Runnable action) {
        TenantContext.runAsTenant(TENANT_B_ID, TENANT_B, action);
    }

    private <T> T runInTenantA(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_A_ID, TENANT_A, action::get);
    }

    private <T> T runInTenantB(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_B_ID, TENANT_B, action::get);
    }
}
