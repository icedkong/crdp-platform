package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.notify.sms.SmsChannelException;
import com.crdp.framework.notify.sms.SmsScene;
import com.crdp.framework.notify.sms.SmsSendCommand;
import com.crdp.framework.notify.sms.TencentSmsChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * E09-T03 端到端（完整应用 + 真实 MySQL/Redis）：腾讯云短信通道。验收两条：
 *
 * <ol>
 *   <li><b>短信不含患者敏感信息</b>：向通道塞手机号当参数 → SENSITIVE_CONTENT 拒绝、不触服务商、 台账只记类别不记明文；
 *   <li><b>回执入库</b>：公开回调口（免登录）POST 腾讯云回执形态 → 对得上发送行的回填 DELIVERED / RECEIVE_FAILED， 对不上的落
 *       UNMATCHED_RECEIPT；手机号只存脱敏值；公开口按 IP 限流 42900。
 * </ol>
 *
 * <p>通道密钥用虚构值、API 接入点指向本机不可达端口（127.0.0.1:1）——<b>绝不触真实腾讯云</b>； 服务商路径只验证到 PROVIDER_UNREACHABLE 台账。门控
 * CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。数据全部虚构（1390000 演示段）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.sms.enabled=true",
            "crdp.sms.secret-id=AKIDit0000000000000000000000000",
            "crdp.sms.secret-key=itSecretKey000000000000000000000000",
            "crdp.sms.sdk-app-id=1400006666",
            "crdp.sms.sign-name=示例签名",
            "crdp.sms.endpoint=127.0.0.1:1"
        })
@TestPropertySource(properties = "crdp.sms.receipt-ip-hourly-limit=3")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class SmsChannelMySqlIT {

    private static final String RECEIPT_URL = "/api/notify/sms/receipt";

    /** IT 专用模板（模拟 E01-T13 报备完成：已回填腾讯云模板 ID）。 */
    private static final String IT_TEMPLATE = "IT_SENSITIVE";

    @Autowired private TestRestTemplate rest;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TencentSmsChannel channel;

    private static JdbcTemplate platform;

    @BeforeAll
    static void connectPlatform() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        deleteItRows();
        platform.update("DELETE FROM plt_sms_template WHERE code = 'IT_SENSITIVE'");
    }

    /** IT 痕迹清理：回执行 serial_no 为 it-*；发送失败行没有流水号，按模板一并删。 */
    private static void deleteItRows() {
        platform.update(
                "DELETE FROM plt_sms_send_log WHERE serial_no LIKE 'it-%'"
                        + " OR template_code = 'IT_SENSITIVE'");
    }

    @BeforeEach
    void cleanState() {
        // 应用上下文（含平台库 Flyway V31）此时已就绪；IT 专用模板幂等插入（模拟 E01-T13 报备完成）
        platform.update(
                "INSERT INTO plt_sms_template (code, name, scene, content, provider_template_id,"
                        + " sign_name, status, built_in, remark) VALUES"
                        + " ('IT_SENSITIVE','IT敏感闸模板','AUTH_LOGIN','验证码{1}。','9999999',"
                        + " NULL,'APPROVED',0,'E09-T03 IT 专用，AfterAll 清理')"
                        + " ON DUPLICATE KEY UPDATE provider_template_id = '9999999', status = 'APPROVED'");
        deleteItRows();
        Set<String> keys = redis.keys("crdp:sms-receipt:ip:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void sensitiveParamIsRejectedWithoutProviderCallAndLoggedSafely() {
        SmsChannelException failure =
                expectChannelFailure(
                        () ->
                                channel.send(
                                        new SmsSendCommand(
                                                SmsScene.AUTH_LOGIN,
                                                "demoa",
                                                "13900001234",
                                                IT_TEMPLATE,
                                                List.of("13900001234"))));
        // 敏感信息闸在服务商调用之前：若闸失效，端点不可达会报 PROVIDER_UNREACHABLE 而不是 SENSITIVE_CONTENT
        assertEquals("SENSITIVE_CONTENT", failure.reason());
        Map<String, Object> row = latestSendLogRow();
        assertEquals("SUBMIT_FAILED", row.get("status"));
        assertEquals("SENSITIVE_CONTENT", row.get("error_code"));
        assertEquals("139****1234", row.get("phone_masked"));
        // 台账任何列都不含参数明文（手机号原值）
        row.values().stream()
                .filter(value -> value instanceof String)
                .map(Object::toString)
                .forEach(text -> assertFalse(text.contains("13900001234"), "台账不得存手机号明文"));
    }

    @Test
    void normalSendReachesProviderAndLogsUnreachable() {
        SmsChannelException failure =
                expectChannelFailure(
                        () ->
                                channel.send(
                                        new SmsSendCommand(
                                                SmsScene.AUTH_LOGIN,
                                                "demoa",
                                                "13900001234",
                                                IT_TEMPLATE,
                                                List.of("437090"))));
        // 参数合法 → 请求发向 127.0.0.1:1 → 连接失败（未触真实腾讯云）
        assertEquals("PROVIDER_UNREACHABLE", failure.reason());
        Map<String, Object> row = latestSendLogRow();
        assertEquals("SUBMIT_FAILED", row.get("status"));
        assertEquals("PROVIDER_UNREACHABLE", row.get("error_code"));
        assertEquals(IT_TEMPLATE, row.get("template_code"));
        assertEquals("139****1234", row.get("phone_masked"));
    }

    @Test
    void receiptCallbackIsPublicAndStoresUnmatchedReceipt() {
        String body =
                "{\"sms_status_callback\":[{\"user_receive_time\":\"2027-02-01 16:30:05\","
                        + "\"nation_code\":\"86\",\"mobile\":\"13700005678\","
                        + "\"report_status\":\"SUCCESS\",\"errmsg\":\"DELIVRD\","
                        + "\"description\":\"用户签收成功\",\"sid\":\"it-unmatched-1\"}]}";
        Map<?, ?> ack = postReceipt(body);
        assertEquals(0, ack.get("result"), "腾讯云约定应答 result=0");

        Map<String, Object> row = findRow("it-unmatched-1");
        assertNotNull(row, "对不上发送行的回执也入库（UNMATCHED_RECEIPT）");
        assertEquals("UNMATCHED", row.get("purpose"));
        assertEquals("UNMATCHED_RECEIPT", row.get("status"));
        assertEquals("SUCCESS", row.get("receipt_status"));
        assertEquals("137****5678", row.get("phone_masked"), "回执手机号只存脱敏值");
        assertNotNull(row.get("user_receive_time"));
        assertNotNull(row.get("receipt_time"));
    }

    @Test
    void receiptForKnownSerialUpdatesTheSendRow() {
        // 造一行「已受理」发送（模拟渠道成功发送后的台账状态）
        platform.update(
                "INSERT INTO plt_sms_send_log (purpose, tenant_code, template_code,"
                        + " provider_template_id, serial_no, phone_masked, status)"
                        + " VALUES ('AUTH_LOGIN','demoa','AUTH_LOGIN_CODE','1234567','it-known-1',"
                        + " '139****1234','SUBMITTED')");
        postReceipt(
                "{\"sms_status_callback\":[{\"sid\":\"it-known-1\",\"report_status\":\"SUCCESS\","
                        + "\"description\":\"deliver\",\"user_receive_time\":\"2027-02-01 16:30:05\"}]}");

        Map<String, Object> row = findRow("it-known-1");
        assertEquals("DELIVERED", row.get("status"), "回执回填发送行为 DELIVERED");
        assertEquals("SUCCESS", row.get("receipt_status"));
        assertEquals("deliver", row.get("receipt_description"));
        assertEquals("139****1234", row.get("phone_masked"));
        assertNotNull(row.get("user_receive_time"));
        assertNotNull(row.get("receipt_time"));
        // 回填是 UPDATE 不是新行
        assertEquals(
                1,
                platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_sms_send_log WHERE serial_no = 'it-known-1'",
                                Integer.class)
                        .intValue());
    }

    @Test
    void failedReceiptMarksReceiveFailed() {
        platform.update(
                "INSERT INTO plt_sms_send_log (purpose, serial_no, phone_masked, status)"
                        + " VALUES ('NOTIFY','it-fail-1','137****5678','SUBMITTED')");
        postReceipt(
                "{\"sms_status_callback\":[{\"sid\":\"it-fail-1\",\"report_status\":\"FAIL\","
                        + "\"description\":\"instance not exist\"}]}");
        Map<String, Object> row = findRow("it-fail-1");
        assertEquals("RECEIVE_FAILED", row.get("status"));
        assertEquals("FAIL", row.get("receipt_status"));
        assertEquals("instance not exist", row.get("receipt_description"));
    }

    @Test
    void receiptEndpointIsRateLimitedPerIp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    200,
                    postReceiptRaw(headers, "{\"sms_status_callback\":[]}")
                            .getStatusCode()
                            .value());
        }
        ResponseEntity<String> limited = postReceiptRaw(headers, "{\"sms_status_callback\":[]}");
        assertEquals(429, limited.getStatusCode().value(), "超出 IP 小时上限 → 429");
        assertTrue(limited.getBody() != null && limited.getBody().contains("42900"));
    }

    @Test
    void seededTemplatesExistForAuthAndNotifyScenes() {
        for (String code : List.of("AUTH_LOGIN_CODE", "AUTH_RESET_CODE", "AUTH_BIND_CODE")) {
            Map<String, Object> row = templateRow(code);
            assertNotNull(row, "种子模板存在：" + code);
            assertEquals("APPROVED", row.get("status"));
            assertTrue(((String) row.get("content")).contains("{1}"), "变量占位符");
            assertNull(row.get("provider_template_id"), "腾讯云模板 ID 待 E01-T13 报备后回填");
        }
        Integer notifyTemplates =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_sms_template WHERE scene = 'NOTIFY'",
                        Integer.class);
        assertTrue(notifyTemplates != null && notifyTemplates >= 2, "通知（汇总/逾期）模板已种子");
        // 模板内容与占位渲染不含敏感形态（手机号 / 长数字串）
        List<String> contents =
                platform.queryForList("SELECT content FROM plt_sms_template", String.class);
        for (String content : contents) {
            assertFalse(content.matches("(?s).*1[3-9]\\d{9}.*"), "模板不含手机号形态");
        }
    }

    // --------------------------------------------------------------- 工具

    private Map<String, Object> latestSendLogRow() {
        return platform.queryForMap("SELECT * FROM plt_sms_send_log ORDER BY id DESC LIMIT 1");
    }

    private Map<String, Object> findRow(String serialNo) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT * FROM plt_sms_send_log WHERE serial_no = ?", serialNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> templateRow(String code) {
        List<Map<String, Object>> rows =
                platform.queryForList("SELECT * FROM plt_sms_template WHERE code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SmsChannelException expectChannelFailure(
            org.junit.jupiter.api.function.Executable executable) {
        try {
            executable.execute();
        } catch (SmsChannelException expected) {
            return expected;
        } catch (Throwable other) {
            throw new AssertionError("期望 SmsChannelException，实际 " + other, other);
        }
        throw new AssertionError("期望 SmsChannelException，但正常返回");
    }

    private Map<?, ?> postReceipt(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response =
                rest.exchange(
                        RECEIPT_URL, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertEquals(200, response.getStatusCode().value(), "回执应答恒 200（腾讯云约定）");
        return response.getBody();
    }

    private ResponseEntity<String> postReceiptRaw(HttpHeaders headers, String body) {
        return rest.exchange(
                RECEIPT_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
