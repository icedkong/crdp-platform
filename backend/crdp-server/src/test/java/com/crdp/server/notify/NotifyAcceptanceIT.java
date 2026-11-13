package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.app.NoticeInboxService;
import com.crdp.followup.notice.app.NotifyDigestService;
import com.crdp.followup.notice.infra.mapper.NotifyRecipientMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifySendLogMapper;
import com.crdp.followup.notice.infra.mapper.NotifySettingMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.notify.sms.TencentSmsChannel;
import com.crdp.framework.notify.sms.TencentSmsClient;
import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
 * E09-T08 通知链路验收（完整应用 + 真实 MySQL/Redis，服务商调用 mock，绝不触真实腾讯云）。验收原文：「只发测试号码，免打扰生效」。
 *
 * <p>与 E09-T04 `NotifyRulesIT`（事件 → 规则 → QUEUED）和 E09-T05 `NotifyDigestIT`（手工造 QUEUED 行 →
 * digest）不同，本套件把两半接成一条链且走<b>真实定时入口</b>： sys_outbox 落逾期事件 → OutboxDispatcher →
 * NotifyEventSubscriber（短信只落 QUEUED）→ {@code NotifyDigestScheduler.tick()}（真实 TenantDirectory 逐租户）→
 * NotifyDigestService.poll（DAILY_DIGEST 规则 send_time 到点判定）→ 免打扰 / 频控（ntf_setting 现行值）→
 * TencentSmsChannel → 服务商（mock）。
 *
 * <p>「只发测试号码」：本套件所有接收人只绑定 1390000xxxx
 * 虚构段测试号码，断言服务商收到的<b>全部</b>调用只落在这些测试号码上、逐人各一条合并短信（参数只有数量，不含患者信息）；未绑定手机号记 FAILED、停用记 SKIPPED，都不触服务商。
 *
 * <p>「免打扰生效」：把 ntf_setting 免打扰窗口压到覆盖「现在」→ 定时入口整轮不触服务商、行保持 QUEUED（次日 /
 * 补跑合并发出），站内信汇总照投；窗口挪开后同一批行经补跑口径（{@code digest(now)}，与定时到点共用实现）合并发出。
 *
 * <p>逐测试方法用生产构造器自建 NotifyDigestService / NotifyDigestScheduler 实例（注入的全部是真实
 * bean）：服务内的「每租户每日只触发一次」内存标记随上下文后台 tick 漂移，独立实例保证到点判定确定可重复；构造与生产装配同参（@Autowired 构造器即
 * Clock.system(Asia/Shanghai)）。门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.notify.digest.tick-ms=3600000",
            "crdp.sms.provider=tencent",
            "crdp.sms.enabled=true",
            "crdp.sms.secret-id=AKIDit0000000000000000000000000",
            "crdp.sms.secret-key=itSecretKey000000000000000000000000",
            "crdp.sms.sdk-app-id=1400006666",
            "crdp.sms.sign-name=示例签名",
            "crdp.sms.endpoint=127.0.0.1:1"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotifyAcceptanceIT {

    /** 业务时区（与 NotifyDigestService.BUSINESS_ZONE 同值：poll 的「现在」按北京时间）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String TENANT_B = "demob";
    private static final long TENANT_B_ID = 2L;

    /** 本套件测试号码（1390000 虚构段，AGENTS §5.5：测试数据不得含真实号码）。 */
    private static final String PHONE_A1 = "13900008801";

    private static final String PHONE_B1 = "13900008803";
    private static final String PHONE_B2 = "13900008804";

    @MockitoBean private TencentSmsClient smsClient;

    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotifySendLogMapper sendLogMapper;
    @Autowired private NotifyRuleMapper ruleMapper;
    @Autowired private NotifySettingMapper settingMapper;
    @Autowired private NotifyRecipientMapper recipientMapper;
    @Autowired private NoticeInboxService inboxService;
    @Autowired private TencentSmsChannel smsChannel;
    @Autowired private CryptoService crypto;
    @Autowired private TenantDirectory tenantDirectory;

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
        platform.update("DELETE FROM plt_sms_send_log WHERE serial_no LIKE 'it-n8-%'");
    }

    @BeforeEach
    void cleanState() {
        runIn(TENANT_A_ID, TENANT_A, this::cleanTenant);
        runIn(TENANT_B_ID, TENANT_B, this::cleanTenant);
        when(smsClient.send(anyString(), anyString(), anyString(), any()))
                .thenReturn("it-n8-1", "it-n8-2", "it-n8-3");
    }

    /** 清理本套件数据并恢复被改过的规则 / 设置 / 汇总发送时间（两院各自租户库内执行）。 */
    private void cleanTenant() {
        // 隔离修复（E21-T25 CI 发现）：本套件断言租户级汇总计数（digest / tick 全队列口径），须整租户重置通知
        // 发送态——早前 IT 残留的 PENDING 任务事件（TASK_OVERDUE 等）会被本类 dispatcher 消费成他人接收人的
        // ntf 行、残留 QUEUED 行也会直接并入补跑，smsSent / 队列计数被顶掉（main #242 起三次 CI 红即此因）
        jdbc.update("DELETE FROM ntf_send_log");
        jdbc.update("DELETE FROM ntf_message");
        jdbc.update(
                "DELETE FROM sys_outbox WHERE status = 'PENDING' AND event_type IN"
                        + " ('TaskOpened', 'DUE_SOON', 'TASK_OVERDUE', 'TASK_REASSIGNED',"
                        + " 'CALLBACK_DUE', 'UNREACHED_FOLLOWUP', 'LOST_CANDIDATE')");
        jdbc.update(
                "DELETE FROM sys_user_ward WHERE user_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE 'it.n8.%')");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'it.n8.%'");
        jdbc.update("UPDATE ward SET default_owner_id = NULL");
        // 恢复默认：已逾期 INBOX+SMS 抄送病区负责人、汇总 08:30、免打扰 21:00–08:00、上限 3
        jdbc.update(
                "UPDATE ntf_rule SET enabled = 1, channels = 'INBOX,SMS',"
                        + " recipients = 'OWNER,WARD_STAFF,CC_WARD_LEADER', send_time = '08:30:00',"
                        + " sms_template_code = 'NOTIFY_OVERDUE', updated_by = NULL"
                        + " WHERE event_type = 'TASK_OVERDUE'");
        jdbc.update(
                "UPDATE ntf_rule SET enabled = 1, send_time = '08:30:00', updated_by = NULL"
                        + " WHERE event_type = 'DAILY_DIGEST'");
        jdbc.update(
                "UPDATE ntf_setting SET dnd_start = '21:00:00', dnd_end = '08:00:00',"
                        + " sms_daily_limit = 3, overdue_sms_off_allowed = 0, updated_by = NULL"
                        + " WHERE id = 1");
    }

    // ---------------------------------------------------------------- 免打扰（验收「免打扰生效」）

    /**
     * 免打扰整轮（真实定时入口）：窗口覆盖现在 → tick 到点触发但短信一条不发（服务商零调用）、行保持 QUEUED；
     * 站内信汇总照投。窗口挪开后同一批行经补跑口径合并发出——只发到接收人自己的测试号码。
     */
    @Test
    void quietHoursDefersSmsThenCatchUpSendsOnlyToTestPhones() {
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    long owner = userWithPhone("it.n8.a.owner", PHONE_A1);
                    long noPhone = userWithPhone("it.n8.a.nophone", null);
                    long toDisable = userWithPhone("it.n8.a.disable", "13900008802");
                    publishOverdueEvents(owner, 8001, 8002);
                    publishOverdueEvents(noPhone, 8003);
                    publishOverdueEvents(toDisable, 8004);
                    dispatcher.dispatchForCurrentTenant();

                    // 免打扰窗口覆盖「现在」；汇总发送时间设为「现在」，tick 即到点
                    LocalTime[] quiet = windowCoveringNow();
                    jdbc.update(
                            "UPDATE ntf_setting SET dnd_start = ?, dnd_end = ? WHERE id = 1",
                            quiet[0],
                            quiet[1]);
                    jdbc.update(
                            "UPDATE ntf_rule SET send_time = ? WHERE event_type = 'DAILY_DIGEST'",
                            dueSendTime());
                    // 队列生成后停用 toDisable（接收人停用记 SKIPPED 的入口）
                    jdbc.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = " + toDisable);

                    NotifyDigestScheduler scheduler = freshScheduler();
                    scheduler.tick();

                    // 免打扰整轮：服务商零调用（消息 0 条 = 一条都不发）
                    verifyNoInteractions(smsClient);
                    assertEquals(2, countSms("QUEUED", owner), "有测试号码的接收人行保持 QUEUED 不丢");
                    assertEquals(1, countSms("QUEUED", noPhone), "未绑定手机号的行同样保持 QUEUED");
                    assertEquals(1, countInboxDigest(owner), "免打扰只约束短信，站内信汇总照投");
                    assertEquals(1, countInboxDigest(noPhone));
                    assertEquals(1, countInboxDigest(toDisable));

                    // 窗口挪开（不再覆盖现在）→ 同一批行补跑合并发出；服务实例内当日已触发，
                    // 补跑走 digest(now)（与定时到点共用实现，见服务 javadoc）
                    LocalTime[] awake = windowAwayFromNow();
                    jdbc.update(
                            "UPDATE ntf_setting SET dnd_start = ?, dnd_end = ? WHERE id = 1",
                            awake[0],
                            awake[1]);
                    NotifyDigestService.DigestOutcome catchUp =
                            schedulerService().digest(LocalDateTime.now(ZONE));

                    assertEquals(1, catchUp.smsSent(), "补跑只发出合并后的 1 条");
                    assertEquals(1, catchUp.smsFailed(), "未绑定手机号记 FAILED");
                    assertEquals(1, catchUp.smsSkipped(), "接收人停用记 SKIPPED");
                    // 「只发测试号码」：全部服务商调用只落在唯一绑定了测试号码的接收人
                    assertEquals(List.of("+86" + PHONE_A1), sentPhones());
                    List<Object[]> smsArgs = sentArgs();
                    assertEquals(1, smsArgs.size());
                    assertEquals("9990001", smsArgs.get(0)[2], "NOTIFY_DAILY_SUMMARY 的腾讯云模板 ID");
                    assertEquals(List.of("2"), smsArgs.get(0)[3], "两行合并为一条，参数只有待办总数（不含患者信息）");

                    assertEquals(2, countSms("SENT", owner), "并入行整体记 SENT");
                    assertEquals(1, countSms("FAILED", noPhone), "未绑定手机号 FAILED 可重试");
                    assertEquals(1, countSms("SKIPPED", toDisable), "停用接收人 SKIPPED");
                    assertEquals(1, countInboxDigest(owner), "补跑不重复投「每日汇总」站内信（按日幂等）");
                    return null;
                });

        // 平台短信台账：只记测试号码的脱敏值
        assertEquals(
                1,
                (int)
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_sms_send_log WHERE serial_no ="
                                        + " 'it-n8-1' AND purpose = 'NOTIFY' AND phone_masked ="
                                        + " '139****8801' AND status = 'SUBMITTED'",
                                Integer.class),
                "平台台账只落测试号码的脱敏值");
    }

    // ---------------------------------------------------------------- 汇总与「只发测试号码」（验收主链路）

    /** 两接收人各自合并一条：服务商只收到两条调用、分别发到各自测试号码，数量参数与各自队列一致。 */
    @Test
    void digestThroughSchedulerSendsOneSmsPerRecipientToOwnTestPhone() {
        runIn(
                TENANT_B_ID,
                TENANT_B,
                () -> {
                    long busy = userWithPhone("it.n8.b.busy", PHONE_B1);
                    long light = userWithPhone("it.n8.b.light", PHONE_B2);
                    publishOverdueEvents(busy, 8101, 8102, 8103);
                    publishOverdueEvents(light, 8104);
                    dispatcher.dispatchForCurrentTenant();
                    assertEquals(3, countSms("QUEUED", busy));
                    assertEquals(1, countSms("QUEUED", light));

                    // 汇总发送时间设为「现在」，免打扰窗口挪开：tick 即到点即发
                    LocalTime[] awake = windowAwayFromNow();
                    jdbc.update(
                            "UPDATE ntf_setting SET dnd_start = ?, dnd_end = ? WHERE id = 1",
                            awake[0],
                            awake[1]);
                    jdbc.update(
                            "UPDATE ntf_rule SET send_time = ? WHERE event_type = 'DAILY_DIGEST'",
                            dueSendTime());

                    freshScheduler().tick();

                    // 「只发测试号码」：两条调用、各到各的测试号码（按 recipient_id 序稳定）
                    assertEquals(List.of("+86" + PHONE_B1, "+86" + PHONE_B2), sentPhones());
                    List<Object[]> smsArgs = sentArgs();
                    assertEquals(List.of("3"), smsArgs.get(0)[3], "busy 的 3 行合并一条、参数只有总数");
                    assertEquals(List.of("1"), smsArgs.get(1)[3], "light 的 1 行单独一条");
                    assertEquals(3, countSms("SENT", busy));
                    assertEquals(1, countSms("SENT", light));
                    assertEquals(0, countSms("QUEUED", busy) + countSms("QUEUED", light));
                    return null;
                });
        // 甲院（另一租户）未被触达：同一轮 tick 只处理了乙院到点的队列
        runIn(
                TENANT_A_ID,
                TENANT_A,
                () -> {
                    assertEquals(
                            0,
                            (int)
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM ntf_send_log WHERE channel ="
                                                    + " 'SMS' AND send_status IN ('SENT',"
                                                    + " 'FAILED', 'SKIPPED')",
                                            Integer.class),
                            "甲院队列原样保留（未到点不处理）");
                    return null;
                });
    }

    // ---------------------------------------------------------------- 工具

    /** 生产构造器自建服务实例：内存触发标记独立于上下文后台 tick（见类注释），组件全部为真实 bean。 */
    private NotifyDigestService schedulerService() {
        return new NotifyDigestService(
                sendLogMapper,
                ruleMapper,
                settingMapper,
                recipientMapper,
                inboxService,
                smsChannel,
                crypto);
    }

    /** 定时入口用同一实例包一层（NotifyDigestScheduler 的生产装配就是 callAsTenant(digest::poll)）。 */
    private NotifyDigestScheduler freshScheduler() {
        return new NotifyDigestScheduler(schedulerService(), tenantDirectory);
    }

    /** 免打扰窗口（覆盖「现在」，跨零点时段 inQuietHours 同样判定免打扰）。 */
    private static LocalTime[] windowCoveringNow() {
        LocalTime now = LocalTime.now(ZONE).withSecond(0).withNano(0);
        return new LocalTime[] {now.minusMinutes(10), now.plusMinutes(30)};
    }

    /** 清醒窗口（不覆盖「现在」：起点在 2 小时后，任何当前时刻都落窗外）。 */
    private static LocalTime[] windowAwayFromNow() {
        LocalTime now = LocalTime.now(ZONE).withSecond(0).withNano(0);
        return new LocalTime[] {now.plusHours(2), now.plusHours(2).plusMinutes(30)};
    }

    /** 汇总发送时间：截到分钟再回退 1 分钟，留 4 分钟到点容差（poll 的判定是 [send_time, +5min)）。 */
    private static LocalTime dueSendTime() {
        return LocalTime.now(ZONE).withSecond(0).withNano(0).minusMinutes(1);
    }

    /** 建测试用户并绑测试号码（密文落库，purpose=sys-user-phone，与生产同路）；phone 为 null 则不绑定。加入本院第一个病区（负责人按病区复核）。 */
    private long userWithPhone(String username, String phone) {
        String cipher = phone == null ? null : crypto.encrypt("sys-user-phone", phone);
        jdbc.update(
                "INSERT INTO sys_user (username, display_name, password_hash, status, phone)"
                        + " VALUES (?, '医生（示例）', 'x', 'ENABLED', ?)",
                username,
                cipher);
        long user =
                jdbc.queryForObject(
                        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        long wardId = jdbc.queryForObject("SELECT id FROM ward LIMIT 1", Long.class);
        jdbc.update("INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)", user, wardId);
        return user;
    }

    /** 逐个落逾期事件（同一负责人多条 → 多行 QUEUED，验证按人合并）。带 wardId：负责人按病区复核通过，不再兜底扩散到病区全员。 */
    private void publishOverdueEvents(long ownerId, int... taskIds) {
        long wardId = jdbc.queryForObject("SELECT id FROM ward LIMIT 1", Long.class);
        for (int taskId : taskIds) {
            jdbc.update(
                    "INSERT INTO sys_outbox (event_type, payload) VALUES (?, ?)",
                    "TASK_OVERDUE",
                    "{\"taskId\":"
                            + taskId
                            + ",\"ownerId\":"
                            + ownerId
                            + ",\"wardId\":"
                            + wardId
                            + ",\"patientId\":9902"
                            + taskId
                            + ",\"patientName\":\"测试患者（示例）\","
                            + "\"nodeName\":\"第 5 年随访\",\"windowEndDate\":\"2026-10-31\"}");
        }
    }

    /** 服务商 send 调用的全部目的号码（E-164 形态，按调用序）。 */
    private List<String> sentPhones() {
        List<String> phones = new ArrayList<>();
        sentArgs().forEach(args -> phones.add((String) args[0]));
        return phones;
    }

    /** 服务商 send 调用的全部参数（手机号 / 签名 / 模板 ID / 参数表）。 */
    private List<Object[]> sentArgs() {
        return mockingDetails(smsClient).getInvocations().stream()
                .filter(i -> "send".equals(i.getMethod().getName()))
                .map(i -> (Object[]) i.getArguments())
                .toList();
    }

    private int countSms(String status, long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_send_log WHERE recipient_id = ? AND channel = 'SMS'"
                        + " AND send_status = ?",
                Integer.class,
                userId,
                status);
    }

    private int countInboxDigest(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_message WHERE recipient_id = ? AND event_type ="
                        + " 'DAILY_DIGEST'",
                Integer.class,
                userId);
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
