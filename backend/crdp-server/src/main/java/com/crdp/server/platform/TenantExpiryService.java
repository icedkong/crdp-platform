package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.auth.TenantLookupCache;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 到期提醒与自动流转（E05-T03 / PLT-TEN-012，验收：到期前提醒，宽限期满自动停用）。
 *
 * <p>每日作业（00:20，北京时间）按租户扇出（E02-T06 的 {@link JobScheduler}，job_tenant_run 记执行与补跑）， 每租户按逻辑日依次做三件事：
 *
 * <ol>
 *   <li>提醒：逻辑日命中租户配置的提醒节点（到期前 N 天）时，解析接收人（租户管理员 TENANT_ADMIN 与平台负责人）落 plt_tenant_expire_notice
 *       派发行（站内信 + 短信，PENDING；实际通道随 E09 接入，见 OQ-44）；
 *   <li>到期：试用 / 启用且有到期日的租户，到期当天（含，覆盖停机补跑）转宽限期；
 *   <li>自动停用：宽限期覆盖 [到期日, 到期日 + 宽限天数)，期满仍未续期自动停用——吊销在线会话并记停用时段（PLT-TEN-016、017 的底座）。
 * </ol>
 *
 * <p>长期有效租户不提醒、不进入宽限期（已确认）。自动流转同样写 TENANT_STATUS 审计（操作者为空，注明自动原因）。 补跑区间 = 该租户最近成功日 + 1
 * 至逻辑日（首次执行从租户创建日起），当天重复执行幂等重跑（提醒唯一键、状态条件更新）。
 */
@Service
public class TenantExpiryService {

    static final String JOB_CODE = "plt-tenant-expire";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final Logger log = LoggerFactory.getLogger(TenantExpiryService.class);

    private final JobScheduler jobScheduler;

    private final JdbcTemplate jdbc;

    private final TenantSuspensionService suspensions;

    private final AuditLogger audit;

    private final TenantLookupCache tenantLookup;

    public TenantExpiryService(
            JobScheduler jobScheduler,
            JdbcTemplate jdbc,
            TenantSuspensionService suspensions,
            AuditLogger audit,
            TenantLookupCache tenantLookup) {
        this.jobScheduler = jobScheduler;
        this.jdbc = jdbc;
        this.suspensions = suspensions;
        this.audit = audit;
        this.tenantLookup = tenantLookup;
    }

    // ---------------------------------------------------------------- 作业入口

    /** 对「作业应执行」的租户（试用 / 启用 / 宽限期；停用即暂停）执行到期处理。 单租户失败不影响其他租户（框架保证），失败摘要记 WARN 日志。逻辑日取北京时间当天。 */
    public List<TenantJobResult> runForDate(LocalDate logicalDate) {
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(
                        JOB_CODE,
                        logicalDate,
                        date -> {
                            TenantContext.State tenant = TenantContext.require();
                            processTenant(tenant.tenantId(), tenant.tenantCode(), date);
                        });
        long failed = results.stream().filter(result -> !result.success()).count();
        if (failed > 0) {
            log.warn("到期处理有失败租户 count={} 详情={}", failed, results);
        }
        return results;
    }

    /** 定时入口（北京时间 00:20）：当天逻辑日。 */
    public List<TenantJobResult> runDaily() {
        return runForDate(LocalDate.now(ZONE));
    }

    // ---------------------------------------------------------------- 纯函数（单测见
    // TenantExpiryServiceTest）

    /** 逻辑日是否命中提醒节点：到期前 N 天（N 为租户配置，只看未来的到期日；未配置节点不提醒）。 */
    static boolean isRemindNode(LocalDate expireAt, List<Integer> remindDays, LocalDate today) {
        if (expireAt == null
                || !expireAt.isAfter(today)
                || remindDays == null
                || remindDays.isEmpty()) {
            return false;
        }
        long daysLeft = daysLeft(expireAt, today);
        return remindDays.contains((int) daysLeft);
    }

    /** 到期转宽限：试用 / 启用且有到期日，到期日不晚于逻辑日（当天与补跑都算）。 */
    static boolean entersGrace(
            String status, String expireType, LocalDate expireAt, LocalDate today) {
        if (!"FIXED".equals(expireType) || expireAt == null) {
            return false;
        }
        return switch (status) {
            case "TRIAL", "ENABLED" -> !expireAt.isAfter(today);
            default -> false;
        };
    }

    /** 宽限期满自动停用：宽限期覆盖 [到期日, 到期日 + 宽限天数)，逻辑日到达到期日 + 宽限天数即停用。 */
    static boolean autoDisable(
            String status,
            String expireType,
            LocalDate expireAt,
            Integer graceDays,
            LocalDate today) {
        if (!"GRACE".equals(status)
                || !"FIXED".equals(expireType)
                || expireAt == null
                || graceDays == null) {
            return false;
        }
        return !today.isBefore(expireAt.plusDays(graceDays));
    }

    /** 距到期天数（含到期当天为 0）；已过期或无到期日返回 null，前端不展示倒计时。 */
    static Integer daysLeft(LocalDate expireAt, LocalDate today) {
        if (expireAt == null || expireAt.isBefore(today)) {
            return null;
        }
        return (int) (expireAt.toEpochDay() - today.toEpochDay());
    }

    // ---------------------------------------------------------------- 单租户处理

    private void processTenant(long tenantId, String tenantCode, LocalDate logicalDate) {
        LocalDate from = firstUnprocessedDate(tenantId, logicalDate);
        for (LocalDate date = from; !date.isAfter(logicalDate); date = date.plusDays(1)) {
            processDate(tenantId, tenantCode, date);
        }
    }

    /** 补跑起点：最近成功日 + 1；首次执行从租户创建日起；起点不晚于逻辑日（当天重复执行 = 幂等重跑）。 */
    private LocalDate firstUnprocessedDate(long tenantId, LocalDate logicalDate) {
        // job_tenant_run 在租户库（ADR 0003）：lastSuccessDate 必须在租户上下文里读（作业体当前即租户上下文）
        LocalDate lastSuccess = jobScheduler.lastSuccessDate(JOB_CODE).orElse(null);
        LocalDate from =
                lastSuccess != null
                        ? lastSuccess.plusDays(1)
                        : TenantContext.callAsPlatform(
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT DATE(created_at) FROM plt_tenant WHERE id = ?",
                                                LocalDate.class,
                                                tenantId));
        if (from == null || from.isAfter(logicalDate)) {
            return logicalDate;
        }
        return from;
    }

    private void processDate(long tenantId, String tenantCode, LocalDate date) {
        Map<String, Object> row =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForMap(
                                        "SELECT status, expire_type, expire_at, remind_days, grace_days"
                                                + " FROM plt_tenant WHERE id = ?",
                                        tenantId));
        String status = str(row.get("status"));
        String expireType = str(row.get("expire_type"));
        LocalDate expireAt = localDate(row.get("expire_at"));
        Integer graceDays =
                row.get("grace_days") == null ? null : ((Number) row.get("grace_days")).intValue();

        // 1) 到期前提醒（PLT-TEN-012）：只对试用 / 启用且未到期的租户
        if (("TRIAL".equals(status) || "ENABLED".equals(status))
                && isRemindNode(expireAt, parseRemindDays(str(row.get("remind_days"))), date)) {
            recordNotices(tenantId, expireAt, date);
        }

        // 2) 到期转宽限期（当天或补跑日）
        String currentStatus = status;
        if (entersGrace(currentStatus, expireType, expireAt, date)) {
            int updated =
                    TenantContext.callAsPlatform(
                            () ->
                                    jdbc.update(
                                            "UPDATE plt_tenant SET status = 'GRACE'"
                                                    + " WHERE id = ? AND status = ?",
                                            tenantId,
                                            currentStatus));
            if (updated > 0) {
                log.info("租户进入宽限期 tenant={} expireAt={} date={}", tenantCode, expireAt, date);
                tenantLookup.evict(tenantCode);
                // 平台审计落在平台库（JdbcAuditLogger 按上下文路由），作业体当前是租户上下文，须切回平台侧
                TenantContext.runAsPlatform(
                        () ->
                                audit.record(
                                        new AuditEvent(
                                                "TENANT_STATUS",
                                                CurrentSession.current()
                                                        .map(principal -> principal.userId())
                                                        .orElse(null),
                                                "UPDATE",
                                                "plt_tenant:" + tenantCode,
                                                "状态 "
                                                        + currentStatus
                                                        + "→GRACE（"
                                                        + trialOrService(currentStatus)
                                                        + "到期自动，到期日 "
                                                        + expireAt
                                                        + "）")));
                status = "GRACE";
            }
        }

        // 3) 宽限期满自动停用（PLT-TEN-016：吊销会话 + 记停用时段）
        if (autoDisable(status, expireType, expireAt, graceDays, date)) {
            int updated =
                    TenantContext.callAsPlatform(
                            () ->
                                    jdbc.update(
                                            "UPDATE plt_tenant SET status = 'DISABLED'"
                                                    + " WHERE id = ? AND status = 'GRACE'",
                                            tenantId));
            if (updated > 0) {
                tenantLookup.evict(tenantCode);
                long revoked =
                        suspensions.onDisabled(
                                tenantId,
                                TenantSuspensionService.CAUSE_AUTO_GRACE_END,
                                "宽限期结束未续期，自动停用（到期日 " + expireAt + "，宽限 " + graceDays + " 天）");
                log.info(
                        "宽限期满自动停用 tenant={} expireAt={} graceDays={} revokedSessions={}",
                        tenantCode,
                        expireAt,
                        graceDays,
                        revoked);
                TenantContext.runAsPlatform(
                        () ->
                                audit.record(
                                        new AuditEvent(
                                                "TENANT_STATUS",
                                                CurrentSession.current()
                                                        .map(principal -> principal.userId())
                                                        .orElse(null),
                                                "UPDATE",
                                                "plt_tenant:" + tenantCode,
                                                "状态 GRACE→DISABLED（宽限期结束自动，到期日 "
                                                        + expireAt
                                                        + "，宽限 "
                                                        + graceDays
                                                        + " 天；吊销会话 "
                                                        + revoked
                                                        + " 个）")));
            }
        }
    }

    // ---------------------------------------------------------------- 提醒派发

    private void recordNotices(long tenantId, LocalDate expireAt, LocalDate date) {
        int daysLeft = daysLeft(expireAt, date);
        List<Object[]> rows = new ArrayList<>();
        // 租户管理员：租户库里 TENANT_ADMIN 角色的启用用户（作业已在租户上下文内）
        jdbc.query(
                """
                SELECT u.id, u.display_name FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id = u.id
                JOIN sys_role r ON r.id = ur.role_id
                WHERE r.code = 'TENANT_ADMIN' AND u.status = 'ENABLED'
                """,
                rs -> {
                    rows.add(
                            new Object[] {
                                tenantId,
                                date,
                                daysLeft,
                                "TENANT_ADMIN",
                                rs.getLong("id"),
                                rs.getString("display_name")
                            });
                });
        // 平台负责人：负责租户多选随 PLT-VIEW-006 落地，当前回落到全部启用的 PLATFORM_ADMIN（OQ-44）
        TenantContext.runAsPlatform(
                () ->
                        jdbc.query(
                                """
                                SELECT a.id, a.display_name FROM plt_account a
                                JOIN plt_account_role ar ON ar.account_id = a.id
                                JOIN plt_role r ON r.id = ar.role_id
                                WHERE r.code = 'PLATFORM_ADMIN' AND a.status = 'ENABLED'
                                """,
                                rs -> {
                                    rows.add(
                                            new Object[] {
                                                tenantId,
                                                date,
                                                daysLeft,
                                                "PLATFORM_OWNER",
                                                rs.getLong("id"),
                                                rs.getString("display_name")
                                            });
                                }));
        if (rows.isEmpty()) {
            log.warn("到期提醒无接收人 tenantId={} date={}", tenantId, date);
            return;
        }
        int inserted =
                TenantContext.callAsPlatform(
                        () -> {
                            int count = 0;
                            for (Object[] row : rows) {
                                // 唯一键 (tenant_id, remind_date, recipient_type, recipient_id)：同节点不重发
                                count +=
                                        jdbc.update(
                                                """
                                                INSERT INTO plt_tenant_expire_notice
                                                  (tenant_id, remind_date, days_left, recipient_type,
                                                   recipient_id, recipient_name, channels, send_status)
                                                VALUES (?, ?, ?, ?, ?, ?, 'INBOX,SMS', 'PENDING')
                                                ON DUPLICATE KEY UPDATE id = id
                                                """,
                                                row);
                            }
                            return count;
                        });
        log.info(
                "到期提醒已登记 tenantId={} date={} daysLeft={} recipients={} inserted={}",
                tenantId,
                date,
                daysLeft,
                rows.size(),
                inserted);
    }

    // ---------------------------------------------------------------- 内部

    private static String trialOrService(String previousStatus) {
        return "TRIAL".equals(previousStatus) ? "试用" : "服务";
    }

    /** 存储格式 → 列表；复用 E05-T02 的存储约定（逗号分隔降序，空 = 未配置）。 */
    private static List<Integer> parseRemindDays(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        List<Integer> days = new ArrayList<>();
        for (String part : stored.split(",")) {
            days.add(Integer.valueOf(part.trim()));
        }
        return days;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
