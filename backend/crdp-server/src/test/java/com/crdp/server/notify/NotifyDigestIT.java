package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.app.NotifyDigestService;
import com.crdp.framework.notify.sms.SmsChannelException;
import com.crdp.framework.notify.sms.TencentSmsClient;
import com.crdp.framework.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E09-T05 端到端 / A-NTF-005、C-NTF-003、C-NTF-005（真实 MySQL + 完整应用，服务商调用 mock， 绝不触真实腾讯云）。验收原文：「08:30
 * 汇总；21:00–08:00 不发」；A 规格 §7：「某医生当天有 5 项待随访、2 项逾期，只收到 1 条汇总短信， 内容不含任何患者信息」「21:30 触发的逾期短信推迟到次日 08:00
 * 后发送」；notify.md §7「第 4 条并入次日」。
 *
 * <p>覆盖：按人合并为一条汇总短信（参数只有数量）+ 台账 SENT + 平台短信台账 SUBMITTED（手机号脱敏）、 免打扰整轮不发（行保持
 * QUEUED、站内信照投）、每日上限并入次日、发送失败记 FAILED、双租户互不影响。 演示手机号 1390000xxxx 为虚构段；门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.notify.digest.tick-ms=3600000",
            "crdp.sms.enabled=true",
            "crdp.sms.secret-id=AKIDit0000000000000000000000000",
            "crdp.sms.secret-key=itSecretKey000000000000000000000000",
            "crdp.sms.sdk-app-id=1400006666",
            "crdp.sms.sign-name=示例签名",
            "crdp.sms.endpoint=127.0.0.1:1"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifyDigestIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String TENANT_B = "demob";
    private static final long TENANT_B_ID = 2L;
    private static final String DOCTOR = "doctor.demo";
    private static final LocalDateTime AT_0830 = LocalDateTime.of(2026, 11, 2, 8, 30);
    private static final LocalDateTime AT_2130 = LocalDateTime.of(2026, 11, 1, 21, 30);

    @MockitoBean private TencentSmsClient smsClient;

    @Autowired private NotifyDigestService digestService;
    @Autowired private JdbcTemplate jdbc;

    private JdbcTemplate platform;

    @BeforeAll
    void connectPlatform() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        // 模拟 E01-T13 报备完成：给每日汇总模板回填腾讯云模板 ID（AfterAll 还原为 NULL）
        platform.update(
                "UPDATE plt_sms_template SET provider_template_id = '9990001'"
                        + " WHERE code = 'NOTIFY_DAILY_SUMMARY'");
    }

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        runIn(TENANT_A_ID, TENANT_A, this::cleanTenant);
        runIn(TENANT_B_ID, TENANT_B, this::cleanTenant);
        platform.update(
                "UPDATE plt_sms_template SET provider_template_id = NULL"
                        + " WHERE code = 'NOTIFY_DAILY_SUMMARY'");
        platform.update("DELETE FROM plt_sms_send_log WHERE serial_no LIKE 'it-digest-%'");
    }

    @BeforeEach
    void cleanState() {
        runIn(TENANT_A_ID, TENANT_A, this::cleanTenant);
        // 恢复默认设置（21:00–08:00 / 上限 3 / 逾期短信不可个人关闭）
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () ->
                        jdbc.update(
                                "UPDATE ntf_setting SET dnd_start = '21:00:00', dnd_end ="
                                        + " '08:00:00', sms_daily_limit = 3,"
                                        + " overdue_sms_off_allowed = 0, updated_by = NULL"
                                        + " WHERE id = 1"));
        when(smsClient.send(anyString(), anyString(), anyString(), any()))
                .thenReturn("it-digest-1");
    }

    private void cleanTenant() {
        jdbc.update("DELETE FROM ntf_send_log WHERE recipient_id = " + doctorId());
        jdbc.update("DELETE FROM ntf_message WHERE recipient_id = " + doctorId());
    }

    // ---------------------------------------------------------------- 08:30 合并发送（C-NTF-003）

    /** 验收主链路：5 项待随访 + 2 项逾期（7 行 QUEUED）→ 只 1 条汇总短信，参数只有总数，台账与平台台账入库。 */
    @Test
    void mergesSevenQueuedRowsIntoOneSmsAt0830() {
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    seedQueuedRows(5, 2);
                    NotifyDigestService.DigestOutcome outcome = digestService.digest(AT_0830);
                    assertEquals(
                            NotifyDigestService.DigestOutcome.RunStatus.DONE, outcome.status());
                    assertEquals(1, outcome.smsSent(), "7 行合并为 1 条");
                    assertEquals(0, outcome.smsFailed());

                    // 服务商只收到 1 次调用：NOTIFY 场景、平台模板、参数只有数量（不含患者信息）
                    var sends =
                            org.mockito.Mockito.mockingDetails(smsClient).getInvocations().stream()
                                    .filter(i -> "send".equals(i.getMethod().getName()))
                                    .toList();
                    assertEquals(1, sends.size(), "每个接收人每天只发 1 条汇总短信");
                    Object[] args = sends.get(0).getArguments();
                    assertEquals("+8613900000002", args[0], "doctor.demo 的演示号（虚构段）");
                    assertEquals("9990001", args[2], "NOTIFY_DAILY_SUMMARY 的腾讯云模板 ID");
                    assertEquals(List.of("7"), args[3], "参数只有待办总数");

                    // 租户台账：7 行全部 SENT 且带投递时间
                    assertEquals(7, countItSms("SENT"), "并入行全部记 SENT");
                    assertEquals(
                            7,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = "
                                            + doctorId()
                                            + " AND channel = 'SMS' AND send_status = 'SENT'"
                                            + " AND sent_at = ?",
                                    Integer.class,
                                    Timestamp.valueOf(AT_0830)));

                    // 站内信汇总（每日汇总默认渠道 站内信+短信）
                    assertEquals(1, countDigestInboxMessages(), "作业投一条「每日汇总」站内信，数量口径与短信一致");
                    assertEquals(1, countDigestInboxLedger(), "台账记 DAILY_DIGEST INBOX SENT");
                    return null;
                });

        // 平台短信台账：SUBMITTED、手机号只存脱敏值
        assertEquals(
                1,
                (int)
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_sms_send_log WHERE serial_no ="
                                        + " 'it-digest-1' AND purpose = 'NOTIFY' AND template_code ="
                                        + " 'NOTIFY_DAILY_SUMMARY' AND phone_masked = '139****0002'"
                                        + " AND status = 'SUBMITTED'",
                                Integer.class),
                "平台发送台账入库且手机号脱敏");
    }

    // ---------------------------------------------------------------- 免打扰（A-NTF-005 / C-NTF-005）

    /** 验收「21:00–08:00 不发」：21:30 一轮不触服务商、行保持 QUEUED（次日 08:30 后合并发出）；站内信照投。 */
    @Test
    void quietHoursSendsNothingAndKeepsRowsQueued() {
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    seedQueuedRows(0, 2);
                    NotifyDigestService.DigestOutcome outcome = digestService.digest(AT_2130);
                    assertTrue(outcome.quietHours());
                    assertEquals(0, outcome.smsSent());
                    assertEquals(1, outcome.smsDeferred());

                    verifyNoInteractions(smsClient);
                    assertEquals(2, countItSms("QUEUED"), "行保持 QUEUED 不丢");
                    assertEquals(1, countDigestInboxMessages(), "免打扰只约束短信，站内信汇总照投");

                    // 次日 08:30（免打扰结束后）合并发出
                    when(smsClient.send(anyString(), anyString(), anyString(), any()))
                            .thenReturn("it-digest-2");
                    NotifyDigestService.DigestOutcome nextDay =
                            digestService.digest(LocalDateTime.of(2026, 11, 2, 8, 30));
                    assertEquals(1, nextDay.smsSent(), "推迟到次日 08:00 后发送");
                    assertEquals(2, countItSms("SENT"));
                    assertEquals(0, countItSms("QUEUED"));
                    return null;
                });
    }

    // ---------------------------------------------------------------- 频控（每人每日上限）

    /** 「第 4 条并入次日」口径：当日已发满上限（压到 1 验证）→ 本轮不发、行保持 QUEUED 并入次日。 */
    @Test
    void dailyLimitDefersQueuedRowsToNextDay() {
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    jdbc.update("UPDATE ntf_setting SET sms_daily_limit = 1 WHERE id = 1");
                    seedQueuedRows(0, 1);
                    // 当日已有 1 条成功短信（sent_at 同日）
                    jdbc.update(
                            "INSERT INTO ntf_send_log (event_type, recipient_id, channel,"
                                    + " send_status, title, dedup_key, sent_at) VALUES"
                                    + " ('TASK_OVERDUE', ?, 'SMS', 'SENT', '已逾期：第 5 年随访',"
                                    + " ?, ?)",
                            doctorId(),
                            "it.sent." + System.nanoTime(),
                            Timestamp.valueOf(AT_0830.plusMinutes(1)));

                    NotifyDigestService.DigestOutcome outcome = digestService.digest(AT_0830);
                    assertEquals(0, outcome.smsSent());
                    assertEquals(1, outcome.smsDeferred());
                    verifyNoInteractions(smsClient);
                    assertEquals(1, countItSms("QUEUED"), "并入次日汇总");
                    return null;
                });
    }

    // ---------------------------------------------------------------- 失败路径

    /** 服务商受理失败：并入行整体记 FAILED（detail 只含失败类别，可经 E09-T07 重试回 QUEUED）。 */
    @Test
    void providerFailureMarksRowsFailedWithReason() {
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    seedQueuedRows(0, 1);
                    when(smsClient.send(anyString(), anyString(), anyString(), any()))
                            .thenThrow(
                                    new SmsChannelException("PROVIDER_UNREACHABLE", "接口调用失败（示例）"));

                    NotifyDigestService.DigestOutcome outcome = digestService.digest(AT_0830);
                    assertEquals(1, outcome.smsFailed());
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT send_status, detail FROM ntf_send_log WHERE"
                                            + " recipient_id = "
                                            + doctorId()
                                            + " AND channel = 'SMS' AND dedup_key LIKE 'it.digest.%'"
                                            + " LIMIT 1");
                    assertEquals("FAILED", row.get("send_status"));
                    assertEquals("汇总短信发送失败：PROVIDER_UNREACHABLE", row.get("detail"));
                    return null;
                });
    }

    // ---------------------------------------------------------------- 租户隔离

    /** 双租户（AGENTS §5.1）：demoa 的汇总不触碰 demob 的队列。 */
    @Test
    void digestInTenantADoesNotTouchTenantB() {
        runIn(
                TENANT_B_ID,
                TENANT_B,
                () -> {
                    jdbc.update(
                            "INSERT INTO ntf_send_log (event_type, recipient_id, channel,"
                                    + " send_status, title, dedup_key) VALUES ('TASK_OVERDUE', ?,"
                                    + " 'SMS', 'QUEUED', '已逾期：第 5 年随访', 'it.digest.b.1')",
                            doctorId());
                    return null;
                });
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    seedQueuedRows(1, 1);
                    digestService.digest(AT_0830);
                    assertEquals(2, countItSms("SENT"), "demoa 正常发出");
                    return null;
                });
        runIn(
                TENANT_B_ID,
                TENANT_B,
                () -> {
                    assertEquals(1, countItSms("QUEUED"), "demob 的队列原样保留（各自租户库互不影响）");
                    cleanTenant();
                    return null;
                });
    }

    // ---------------------------------------------------------------- 工具

    /** 造待汇总短信行：pending 项 DAILY_DIGEST + overdue 项 TASK_OVERDUE（模拟规则引擎落的 QUEUED 行）。 */
    private void seedQueuedRows(int pending, int overdue) {
        long doctor = doctorId();
        for (int i = 0; i < pending; i++) {
            jdbc.update(
                    "INSERT INTO ntf_send_log (event_type, recipient_id, channel, send_status,"
                            + " title, dedup_key) VALUES ('DAILY_DIGEST', ?, 'SMS', 'QUEUED',"
                            + " '每日汇总', ?)",
                    doctor,
                    "it.digest." + System.nanoTime() + ".p" + i);
        }
        for (int i = 0; i < overdue; i++) {
            jdbc.update(
                    "INSERT INTO ntf_send_log (event_type, recipient_id, channel, send_status,"
                            + " title, dedup_key) VALUES ('TASK_OVERDUE', ?, 'SMS', 'QUEUED',"
                            + " '已逾期：第 5 年随访', ?)",
                    doctor,
                    "it.digest." + System.nanoTime() + ".o" + i);
        }
    }

    /** 本套件造的短信行按状态计数（dedup 前缀 it.digest. / it.sent.）。 */
    private int countItSms(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = "
                        + doctorId()
                        + " AND channel = 'SMS' AND send_status = '"
                        + status
                        + "' AND (dedup_key LIKE 'it.digest.%' OR dedup_key LIKE 'it.sent.%')",
                Integer.class);
    }

    private int countDigestInboxMessages() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_message WHERE recipient_id = "
                        + doctorId()
                        + " AND event_type = 'DAILY_DIGEST' AND title = '每日汇总'",
                Integer.class);
    }

    private int countDigestInboxLedger() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = "
                        + doctorId()
                        + " AND channel = 'INBOX' AND event_type = 'DAILY_DIGEST' AND"
                        + " send_status = 'SENT'",
                Integer.class);
    }

    private long doctorId() {
        return jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = '" + DOCTOR + "'", Long.class);
    }

    private <T> T runIn(long tenantId, String tenantCode, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, tenantCode, action);
    }

    private void runIn(long tenantId, String tenantCode, Runnable action) {
        TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () -> {
                    action.run();
                    return null;
                });
    }
}
