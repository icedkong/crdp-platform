package com.crdp.server.platform;

import com.crdp.followup.patient.app.PlaintextRevealService;
import com.crdp.framework.audit.AuditAppendedEvent;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.audit.AuditType;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 安全告警（E08-T05 / PLT-AUD-012，验收：触发规则后站内信告警）。
 *
 * <p>实时订阅 {@link AuditAppendedEvent}（审计写入成功即评估，同步、同线程、保留租户上下文），命中规则就在平台库 {@code plt_security_alert}
 * 按「接收人 × 触发」落站内信派发行（channel=INBOX、send_status=PENDING）。 站内信收件箱与 PENDING → SENT 的实际投递随 E09
 * 通知域（NotifySender 仍为占位实现，与 OQ-44 到期提醒同口径）；本表即站内信的待发事实。
 *
 * <p>规则（PLT-AUD-012「异常登录、批量导出、权限变更、平台访问」，定义见 OQ-58）：
 *
 * <ul>
 *   <li>{@link #RULE_ABNORMAL_LOGIN 异常登录}：登录日志 {@code action=LOCK}——连续失败达到阈值账号锁定（E04-T05，
 *       锁定事件本身由登录流程去重，一次锁定只告警一次）；
 *   <li>{@link #RULE_BATCH_EXPORT 批量导出}：数据访问 {@code access_method=EXPORT} 且结果成功——提交 / 执行数据导出
 *       （E08-T04 审计导出，后续患者导出 E13、报告下载等同口径自动覆盖）；
 *   <li>{@link #RULE_PERMISSION_CHANGE 权限变更}：操作日志 event_type / action 前缀 {@code ROLE_}（角色、菜单授权、
 *       成员与字段策略调整，RoleAdminService 两代写入口径都命中）；
 *   <li>{@link #RULE_PLATFORM_ACCESS 平台访问}：平台访问日志 action 前缀 {@code VIEW_}（进入租户视角与视角规则变更； 写入侧随
 *       E20-T01，规则先行就绪）；
 *   <li>{@link #RULE_PLAINTEXT_ABUSE 明文查看滥用}（E07-T05）：数据访问 {@code VIEW_PLAIN} 被频控拒绝（DENIED +
 *       RATE_LIMITED，患者明文接口每分钟 30 次上限）——「只看 SUCCESS」的唯一例外，被拒本身就是信号。
 * </ul>
 *
 * <p>接收人：全部启用的 PLATFORM_ADMIN（plt_account × plt_role；按租户指定平台负责人随 PLT-VIEW-006，回落口径同 OQ-44）。
 * 告警派发失败只记 ERROR 日志，不阻断业务——审计已写入，告警可从审计日志补对账。
 */
@Service
public class SecurityAlertService {

    /** 异常登录：连续失败达到阈值锁定（登录日志 LOCK 事件）。 */
    public static final String RULE_ABNORMAL_LOGIN = "ABNORMAL_LOGIN";

    /** 批量导出：数据访问 EXPORT（导出是成批数据离站行为，一律实时告警）。 */
    public static final String RULE_BATCH_EXPORT = "BATCH_EXPORT";

    /** 权限变更：角色与授权调整（ROLE_* 操作）。 */
    public static final String RULE_PERMISSION_CHANGE = "PERMISSION_CHANGE";

    /** 平台访问：进入租户视角 / 视角规则变更（VIEW_* 平台访问，写入侧随 E20-T01）。 */
    public static final String RULE_PLATFORM_ACCESS = "PLATFORM_ACCESS";

    /**
     * 明文查看滥用（E07-T05 / patient.md §5）：数据访问 {@code VIEW_PLAIN} 被频控拒绝（DENIED + reason= {@link
     * PlaintextRevealService#REASON_RATE_LIMITED}）。 规则总体只看
     * SUCCESS，这是唯一例外——被频控拒绝本身就是滥用信号，成功次数由正常审计与频控兜底。
     */
    public static final String RULE_PLAINTEXT_ABUSE = "PLAINTEXT_ABUSE";

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    private static final String RECIPIENTS_SQL =
            """
            SELECT a.id, a.display_name FROM plt_account a
            JOIN plt_account_role ar ON ar.account_id = a.id
            JOIN plt_role r ON r.id = ar.role_id
            WHERE r.code = 'PLATFORM_ADMIN' AND a.status = 'ENABLED'
            """;

    private final JdbcTemplate jdbc;

    public SecurityAlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 审计写入成功后的实时评估入口。任何异常都不向上抛（见类注释）。 */
    @EventListener
    public void onAuditAppended(AuditAppendedEvent event) {
        try {
            evaluate(event.entry());
        } catch (Exception failure) {
            log.error(
                    "安全告警派发失败（不阻断业务）type={} action={} reason={}",
                    event.entry().type(),
                    event.entry().action(),
                    failure.getMessage());
        }
    }

    /** 命中规则即派发；未命中直接返回。 */
    void evaluate(AuditEntry entry) {
        String rule = match(entry);
        if (rule != null) {
            raise(rule, entry);
        }
    }

    /**
     * 规则匹配（纯函数）。只看「成功」的安全事件：被拒（DENIED）与失败（FAIL）由审批与审计兜底，不在这里告警； 唯一例外是 {@link #RULE_PLAINTEXT_ABUSE
     * 明文查看滥用}——被频控拒绝（DENIED + RATE_LIMITED）本身就是告警信号（patient.md §5）。
     */
    static String match(AuditEntry entry) {
        if (entry.result() != AuditResult.SUCCESS) {
            return entry.type() == AuditType.DATA_ACCESS
                            && "VIEW_PLAIN".equals(entry.accessMethod())
                            && PlaintextRevealService.REASON_RATE_LIMITED.equals(entry.reason())
                    ? RULE_PLAINTEXT_ABUSE
                    : null;
        }
        return switch (entry.type()) {
            case LOGIN -> "LOCK".equals(entry.action()) ? RULE_ABNORMAL_LOGIN : null;
            case DATA_ACCESS -> "EXPORT".equals(entry.accessMethod()) ? RULE_BATCH_EXPORT : null;
            case OPERATION ->
                    startsWithRole(entry.action()) || startsWithRole(entry.eventType())
                            ? RULE_PERMISSION_CHANGE
                            : null;
            case PLATFORM_ACCESS ->
                    startsWith(entry.action(), "VIEW_") ? RULE_PLATFORM_ACCESS : null;
        };
    }

    /** 平台库落派发行（每接收人一条，INBOX、PENDING；同 traceId 幂等）。 */
    private void raise(String rule, AuditEntry entry) {
        // 登录 / 租户侧数据访问日志不带 tenant_id（落租户库），租户取自触发时的上下文；平台侧事件保持 NULL
        Long tenantId =
                entry.tenantId() != null
                        ? entry.tenantId()
                        : TenantContext.current()
                                .filter(state -> !state.platform())
                                .map(TenantContext.State::tenantId)
                                .orElse(null);
        String detail = detailOf(rule, entry);
        // traceId 为空（程序化直写，无 MDC）时 alert_key 必须落 NULL：空串是真实的唯一键值，
        // 会把同规则所有无 traceId 的触发错误去重成一条（首次插入后其余全被 ON DUPLICATE 吞掉）
        String traceKey = traceIdOf(entry);
        TenantContext.runAsPlatform(
                () -> {
                    List<Map<String, Object>> recipients = jdbc.queryForList(RECIPIENTS_SQL);
                    if (recipients.isEmpty()) {
                        log.warn("安全告警无可用接收人（启用的 PLATFORM_ADMIN 为空）rule={}", rule);
                        return;
                    }
                    for (Map<String, Object> recipient : recipients) {
                        jdbc.update(
                                """
                                INSERT INTO plt_security_alert
                                  (rule_code, alert_key, tenant_id, user_id, user_name,
                                   display_name, user_type, module, action, object_type, object_id,
                                   detail, trace_id, recipient_id, recipient_name, channel,
                                   send_status)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INBOX',
                                 'PENDING')
                                ON DUPLICATE KEY UPDATE id = id
                                """,
                                rule,
                                traceKey,
                                tenantId,
                                entry.userId(),
                                clip(entry.username(), 64),
                                clip(entry.displayName(), 128),
                                clip(entry.userType(), 16),
                                clip(entry.module(), 64),
                                clip(entry.action(), 64),
                                clip(entry.objectType(), 64),
                                clip(entry.objectId(), 128),
                                clip(detail, 500),
                                traceKey,
                                ((Number) recipient.get("id")).longValue(),
                                clip(String.valueOf(recipient.get("display_name")), 64));
                    }
                    log.info(
                            "安全告警已派发 rule={} tenantId={} user={} detail={} recipients={}",
                            rule,
                            tenantId,
                            entry.username(),
                            detail,
                            recipients.size());
                });
    }

    /** 非空 traceId 原样（截断），空 / 缺省归 NULL——唯一键对 NULL 不去重，无 traceId 的触发不互相吞并。 */
    private static String traceIdOf(AuditEntry entry) {
        String traceId = entry.traceId();
        return traceId == null || traceId.isBlank() ? null : clip(traceId, 64);
    }

    /** 告警摘要（简体中文，不含手机号、证件号等敏感明文，AGENTS.md 第 5 节）。 */
    private static String detailOf(String rule, AuditEntry entry) {
        String tenant =
                TenantContext.current()
                        .filter(state -> !state.platform())
                        .map(state -> "租户「" + state.tenantCode() + "」")
                        .orElse("平台侧");
        return switch (rule) {
            case RULE_ABNORMAL_LOGIN ->
                    tenant
                            + "账号「"
                            + entry.username()
                            + "」连续登录失败达到阈值被锁定"
                            + (entry.ip() == null ? "" : "（IP " + entry.ip() + "）");
            case RULE_BATCH_EXPORT ->
                    tenant + "用户「" + actorOf(entry) + "」提交数据导出：" + orAction(entry);
            case RULE_PERMISSION_CHANGE ->
                    tenant + "权限变更（" + actorOf(entry) + "）：" + orAction(entry);
            case RULE_PLATFORM_ACCESS -> "平台访问租户视角（" + actorOf(entry) + "）：" + orAction(entry);
            case RULE_PLAINTEXT_ABUSE ->
                    tenant + "用户「" + actorOf(entry) + "」明文查看过于频繁被频控拒绝：" + orAction(entry);
            default -> tenant + "安全事件：" + orAction(entry);
        };
    }

    private static String actorOf(AuditEntry entry) {
        if (entry.displayName() != null) {
            return entry.displayName() + "（" + entry.username() + "）";
        }
        return entry.username() == null ? "系统" : entry.username();
    }

    private static String orAction(AuditEntry entry) {
        return entry.description() != null ? entry.description() : String.valueOf(entry.action());
    }

    private static boolean startsWithRole(String value) {
        return startsWith(value, "ROLE_");
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static String clip(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
