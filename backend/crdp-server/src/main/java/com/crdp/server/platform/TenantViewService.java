package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户视角会话（E20-T01 / PLT-VIEW-001、004、005、006，docs/api/platform.md §6）。
 *
 * <p>进入（PLT-VIEW-001）：平台账号在租户列表选择医院，选目的（排障 / 配置核对 / 培训支持 / 其他）并填说明， 签发视角令牌——会话类型 {@code
 * TENANT_VIEW}，平台账号以自身身份访问，不借用任何医院用户；菜单 / 脱敏 / 设盲 / 只读的执行 随 E20-T03 / T04，本任务先落授权闸门与生命周期。
 *
 * <p>授权（PLT-VIEW-005、006）：能否进入由平台角色的视角六字段决定，多角色取并集（已确认）——任一可进入角色 租户范围为「全部」→ 任意租户可进；「负责的租户」→ 只能进
 * {@code plt_account_tenant} 分配的租户（PLT-VIEW-006）； 「指定租户」→ 只能进 {@code plt_role_view_tenant}
 * 清单内的租户。已注销租户不可进入；停用 / 宽限 / 注销中可进入排障。
 *
 * <p>会话时长（PLT-VIEW-004）：单次最长 2 小时（absoluteSeconds=7200 冻结进会话）、空闲 15 分钟（平台端口径， 含租户视角会话）——由 Redis 会话
 * TTL 执行，令牌到时即失效（下一次请求 40100），到时可重新进入；台账（plt_view_session）的 收尾（到时 / 空闲）由 {@link
 * #finalizeExpiredSessions} 每分钟扫一次补记结束方式。负责关系或视角权限变更即时生效： 进行中的会话被立即结束（{@link
 * #endForAccount}，结束方式=权限变更）。
 *
 * <p>同一平台账号同时只能有一个视角会话，切换租户须先退出（已确认）。进入与退出（含被拒的进入尝试）写 平台访问审计（aud_platform_access_log，action 前缀
 * VIEW_，PLT-AUD-004 / OQ-58 ⑥），只存平台库。
 */
@Service
public class TenantViewService {

    private static final Logger log = LoggerFactory.getLogger(TenantViewService.class);

    /** 查看目的（PLT-VIEW-001，platform-spec §8 视角会话「目的」字典）。 */
    static final Set<String> PURPOSES = Set.of("TROUBLESHOOT", "CONFIG_CHECK", "TRAINING", "OTHER");

    /** 单次视角会话最长 2 小时（PLT-VIEW-004，已确认）。 */
    static final long MAX_SESSION_SECONDS = 2 * 3600;

    /** 结束方式（platform-spec §8 视角会话「结束方式」枚举）。 */
    static final String END_LOGOUT = "LOGOUT";

    static final String END_TIMEOUT = "TIMEOUT";

    static final String END_IDLE = "IDLE";

    static final String END_PERM_CHANGE = "PERM_CHANGE";

    /** 已注销租户不可进入（platform-spec §8 业务规则；停用 / 宽限 / 注销中可进入排障）。 */
    static final String TENANT_CANCELLED = "CANCELLED";

    private static final int NOTE_MAX = 500;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String SELECT_ROW =
            "SELECT id, session_no, account_id, account_name, display_name, role_codes,"
                    + " tenant_id, purpose, note, started_at_epoch, started_at, ends_at FROM"
                    + " plt_view_session";

    private final JdbcTemplate platform;

    private final SessionStore sessionStore;

    private final TokenGenerator tokens;

    private final SecurityProperties securityProperties;

    private final AuditLogger audit;

    /** 医院端入口（进入成功后前端用视角令牌打开；视角界面本体随 E20-T04）。 */
    private final String hospitalBaseUrl;

    public TenantViewService(
            JdbcTemplate platform,
            SessionStore sessionStore,
            TokenGenerator tokens,
            SecurityProperties securityProperties,
            AuditLogger audit,
            @Value("${crdp.tenant-view.hospital-base-url:http://127.0.0.1:14173}")
                    String hospitalBaseUrl) {
        this.platform = platform;
        this.sessionStore = sessionStore;
        this.tokens = tokens;
        this.securityProperties = securityProperties;
        this.audit = audit;
        this.hospitalBaseUrl = hospitalBaseUrl;
    }

    // ---------------------------------------------------------------- DTO

    /** 进入请求（PLT-VIEW-001）：tenantId + 目的 + 说明（说明必填，≤500）。 */
    public record EnterRequest(long tenantId, String purpose, String note) {}

    /** 角色上的视角授权（plt_role 行 + SPECIFIED 清单；只含启用角色）。 */
    public record RoleViewGrant(
            boolean canEnter, String tenantScope, Set<Long> specifiedTenantIds) {}

    /**
     * 账号的进入授权（多角色取并集，已确认）：可进入 = 任一启用角色 view_can_enter=1； 可进租户 = 任一可进入角色为「全部」，否则 「负责的租户 ∪ 各角色指定清单」。
     */
    public record EnterAuthorization(
            boolean canEnter, boolean allTenants, Set<Long> allowedTenantIds) {

        /** 该租户能否进入（供进入校验与租户列表 viewEnterable 共用）。 */
        public boolean enterable(long tenantId) {
            return canEnter && (allTenants || allowedTenantIds.contains(tenantId));
        }

        /** 纯函数（供单测）：角色授权 + 账号负责租户 → 进入授权。 */
        public static EnterAuthorization of(
                List<RoleViewGrant> grants, Set<Long> responsibleTenantIds) {
            boolean canEnter = false;
            boolean all = false;
            Set<Long> allowed = new HashSet<>();
            for (RoleViewGrant grant : grants) {
                if (!grant.canEnter()) {
                    continue;
                }
                canEnter = true;
                switch (grant.tenantScope()) {
                    case "ALL" -> all = true;
                    case "RESPONSIBLE" -> allowed.addAll(responsibleTenantIds);
                    case "SPECIFIED" -> allowed.addAll(grant.specifiedTenantIds());
                    default -> {
                        // 未知枚举按最严处理：不放宽（迁移与保存校验已保证枚举合法）
                    }
                }
            }
            return new EnterAuthorization(canEnter, all, allowed);
        }
    }

    /** 进入成功（PLT-VIEW-001）：会话号与最晚结束时间；令牌本体只存 Redis、不在任何响应回显。 */
    public record EnterResult(
            long sessionId,
            String sessionNo,
            long tenantId,
            String tenantCode,
            String tenantName,
            String purpose,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            long expiresInSeconds,
            String hospitalUrl) {}

    /** 进行中的视角会话（倒计时与「一键退出」用；remainingSeconds 按剩余空闲窗口与 2 小时上限取小）。 */
    public record ActiveSession(
            long sessionId,
            String sessionNo,
            long tenantId,
            String tenantCode,
            String tenantName,
            String purpose,
            String note,
            List<String> roleCodes,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            long remainingSeconds) {}

    /** 退出结果（结束方式回显，前端提示用）。 */
    public record ExitResult(long sessionId, String sessionNo, String endMethod) {}

    /**
     * 台账行（内部读取用）。{@code startedAtEpoch} 是到时判定的唯一依据（epoch 秒， 跨时区安全，见 V51 迁移注释）；DATETIME 字段只做展示回显。
     */
    private record SessionRow(
            long id,
            String sessionNo,
            long accountId,
            String accountName,
            String displayName,
            String roleCodes,
            long tenantId,
            String purpose,
            String note,
            long startedAtEpoch,
            LocalDateTime startedAt,
            LocalDateTime endsAt) {}

    // ---------------------------------------------------------------- 进入 / 查询 / 退出

    /** 进入租户视角（PLT-VIEW-001）：校验目的与说明、租户状态、角色授权与租户范围，签发视角会话。 */
    public EnterResult enter(EnterRequest request) {
        String purpose = requirePurpose(request == null ? null : request.purpose());
        String note = requireNote(request == null ? null : request.note());
        long tenantId = request.tenantId();
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> tenant = requireTenant(tenantId);
                    SessionPrincipal caller = currentCaller();
                    EnterAuthorization authorization = authorizationOf(caller.userId());
                    if (!authorization.canEnter()) {
                        denyEnter(caller, tenantId, purpose, "平台角色未授权进入租户视角");
                        throw new ApiException(403, 40300, "平台角色未授权进入租户视角，请联系平台管理员");
                    }
                    if (TENANT_CANCELLED.equals(str(tenant.get("status")))) {
                        denyEnter(caller, tenantId, purpose, "已注销的租户不可进入");
                        throw new ApiException(400, 40001, "已注销的租户不可进入租户视角");
                    }
                    if (!authorization.enterable(tenantId)) {
                        denyEnter(caller, tenantId, purpose, "租户不在可进入范围内");
                        throw new ApiException(403, 40300, "该租户不在你的可进入范围内（负责的租户 / 指定租户）");
                    }
                    if (findActiveRow(caller.userId()) != null) {
                        throw new ApiException(409, 40901, "已有进行中的租户视角会话，切换租户须先退出（PLT-VIEW-001）");
                    }
                    return doEnter(caller, tenant, purpose, note);
                });
    }

    private EnterResult doEnter(
            SessionPrincipal caller, Map<String, Object> tenant, String purpose, String note) {
        long tenantId = ((Number) tenant.get("id")).longValue();
        SessionPrincipal view = viewDraft(caller, tenant);
        sessionStore.save(view);
        String sessionNo = view.sessionId();
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime endsAt = now.plusSeconds(MAX_SESSION_SECONDS);
        long startedAtEpoch = view.issuedAtEpochSecond();
        platform.update(
                "INSERT INTO plt_view_session (session_no, account_id, account_name, display_name,"
                        + " role_codes, tenant_id, purpose, note, started_at_epoch, rule_version,"
                        + " status, started_at, ends_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DEFAULT', 'ACTIVE', ?, ?)",
                sessionNo,
                caller.userId(),
                caller.username(),
                caller.displayName(),
                String.join(",", caller.roles()),
                tenantId,
                purpose,
                note,
                startedAtEpoch,
                now,
                endsAt);
        Long rowId =
                platform.queryForObject(
                        "SELECT id FROM plt_view_session WHERE session_no = ?",
                        Long.class,
                        sessionNo);
        audit.append(
                AuditEntry.platformAccess(tenantId, "base", "VIEW_ENTER")
                        .userId(caller.userId())
                        .username(caller.username())
                        .displayName(caller.displayName())
                        .roleCodes(caller.roles())
                        .description(
                                "进入租户视角："
                                        + str(tenant.get("name"))
                                        + "（"
                                        + str(tenant.get("code"))
                                        + "），目的 "
                                        + purposeText(purpose))
                        .viewSessionId(sessionNo)
                        .success()
                        .build());
        log.info(
                "tenant view entered account={} tenant={} purpose={} session={}",
                caller.username(),
                tenant.get("code"),
                purpose,
                sessionNo);
        return new EnterResult(
                rowId == null ? 0L : rowId,
                sessionNo,
                tenantId,
                str(tenant.get("code")),
                str(tenant.get("name")),
                purpose,
                offsetDateTime(now),
                offsetDateTime(endsAt),
                sessionStore.effectiveTtl(view).toSeconds(),
                hospitalBaseUrl);
    }

    /** 本人进行中的视角会话（倒计时）。顺带收尾：台账还 ACTIVE 但 Redis 令牌已消失（空闲超时被逐出）或 2 小时已到 ——先按实际结束方式关账再返回空。 */
    public ActiveSession current() {
        SessionPrincipal caller = currentCaller();
        return TenantContext.callAsPlatform(
                () -> {
                    finalizeAccount(caller.userId());
                    SessionRow row = findActiveRow(caller.userId());
                    if (row == null) {
                        return null;
                    }
                    SessionPrincipal alive = viewTokenAlive(row);
                    long remaining =
                            alive == null ? 0L : sessionStore.effectiveTtl(alive).toSeconds();
                    return new ActiveSession(
                            row.id(),
                            row.sessionNo(),
                            row.tenantId(),
                            tenantColumnOf(row.tenantId(), "code"),
                            tenantColumnOf(row.tenantId(), "name"),
                            row.purpose(),
                            row.note(),
                            List.of(row.roleCodes().split(",")),
                            offsetDateTime(row.startedAt()),
                            offsetDateTime(row.endsAt()),
                            remaining);
                });
    }

    /** 一键退出（PLT-VIEW-003 的会话侧）：只允许退出自己的进行中会话，未知 / 他人会话 40400（不暴露存在）。 */
    public ExitResult exit(long sessionId) {
        SessionPrincipal caller = currentCaller();
        return TenantContext.callAsPlatform(
                () -> {
                    SessionRow row = findActiveRow(caller.userId());
                    if (row == null || row.id() != sessionId) {
                        throw new ApiException(404, 40400, "视角会话不存在或已结束");
                    }
                    closeSession(row, END_LOGOUT, LocalDateTime.now(ZONE));
                    return new ExitResult(row.id(), row.sessionNo(), END_LOGOUT);
                });
    }

    /** 权限变更即时生效（platform-spec §8 业务规则）：负责关系、平台角色视角权限、账号角色 / 状态或密码 重置变化时调用——视角令牌立即失效，台账按「权限变更」收尾。 */
    public void endForAccount(long accountId) {
        TenantContext.runAsPlatform(
                () -> {
                    finalizeAccount(accountId);
                    SessionRow row = findActiveRow(accountId);
                    if (row != null) {
                        closeSession(row, END_PERM_CHANGE, LocalDateTime.now(ZONE));
                    }
                });
    }

    /**
     * 到时 / 空闲超时收尾（PLT-VIEW-004）：每分钟由调度触发。到时（ends_at 已过）按最晚结束时间关账； 令牌已被 Redis 逐出（空闲 15
     * 分钟，无请求再触发判定）按当前时间关账。逐行收尾（进入接口已保证一账号一会话， 多行属异常态也一并处理）。收尾只改台账与写审计，不影响令牌生效（令牌由 Redis TTL 自行失效）。
     */
    public void finalizeExpiredSessions() {
        TenantContext.runAsPlatform(
                () -> {
                    for (SessionRow row : activeRows()) {
                        finalizeRow(row);
                    }
                });
    }

    /**
     * 单行收尾（epoch 判定，跨时区安全）：到时（started_at_epoch + 2 小时 ≤ 当前 epoch）按最晚结束 时间收尾； 2 小时未到但令牌已被 Redis
     * 逐出（空闲 15 分钟无请求）按当前时间收尾。end_at 是展示值， 取台账的 ends_at 钟面。
     */
    private void finalizeRow(SessionRow row) {
        long nowEpoch = Instant.now().getEpochSecond();
        long endsAtEpoch = row.startedAtEpoch() + MAX_SESSION_SECONDS;
        if (row.startedAtEpoch() > 0 && nowEpoch < endsAtEpoch) {
            if (viewTokenAlive(row) == null) {
                closeSession(row, END_IDLE, LocalDateTime.now(ZONE));
            }
        } else {
            // 到时（含 started_at_epoch=0 的历史行：按最老处理，到时收尾）
            closeSession(row, END_TIMEOUT, row.endsAt());
        }
    }

    // ---------------------------------------------------------------- 授权计算（PLT-VIEW-005、006）

    /** 账号的进入授权：启用角色的视角字段 + 账号负责租户 → 并集（供进入校验与租户列表 viewEnterable 共用）。 */
    public EnterAuthorization authorizationOf(long accountId) {
        return TenantContext.callAsPlatform(
                () ->
                        EnterAuthorization.of(
                                viewGrants(accountId), responsibleTenantIds(accountId)));
    }

    /** 启用角色的视角授权（SPECIFIED 清单按账号合并为并集——多角色并集口径下等价）。 */
    private List<RoleViewGrant> viewGrants(long accountId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT r.view_can_enter, r.view_tenant_scope FROM plt_role r"
                                + " JOIN plt_account_role ar ON ar.role_id = r.id"
                                + " WHERE ar.account_id = ? AND r.status = 'ENABLED'",
                        accountId);
        Set<Long> specifiedUnion = specifiedTenantIdsOfAccount(accountId);
        List<RoleViewGrant> grants = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean canEnter = ((Number) row.get("view_can_enter")).intValue() == 1;
            String scope = str(row.get("view_tenant_scope"));
            grants.add(
                    "SPECIFIED".equals(scope)
                            ? new RoleViewGrant(canEnter, scope, specifiedUnion)
                            : new RoleViewGrant(canEnter, scope, Set.of()));
        }
        return grants;
    }

    /** 账号名下启用角色的 SPECIFIED 指定租户并集。 */
    private Set<Long> specifiedTenantIdsOfAccount(long accountId) {
        return new HashSet<>(
                platform.queryForList(
                        "SELECT DISTINCT rvt.tenant_id FROM plt_role_view_tenant rvt"
                                + " JOIN plt_role r ON r.id = rvt.role_id"
                                + " JOIN plt_account_role ar ON ar.role_id = r.id"
                                + " WHERE ar.account_id = ? AND r.status = 'ENABLED'",
                        Long.class,
                        accountId));
    }

    /** 账号负责租户（PLT-VIEW-006，plt_account_tenant）。 */
    public Set<Long> responsibleTenantIds(long accountId) {
        return new LinkedHashSet<>(
                platform.queryForList(
                        "SELECT tenant_id FROM plt_account_tenant WHERE account_id = ?"
                                + " ORDER BY tenant_id",
                        Long.class,
                        accountId));
    }

    // ---------------------------------------------------------------- 会话草稿与收尾（内部）

    /**
     * 视角会话草稿（PLT-VIEW-001、004）：平台账号身份 + 被查看租户；空闲 15 分钟（平台端口径）与 2 小时上限 冻结进会话；菜单 / 权限码为空（医院端菜单与执行随
     * E20-T03 / T04，视角令牌当前不落任何业务接口）。
     */
    SessionPrincipal viewDraft(SessionPrincipal caller, Map<String, Object> tenant) {
        long now = Instant.now().getEpochSecond();
        return new SessionPrincipal(
                tokens.generate(),
                "TENANT_VIEW",
                ((Number) tenant.get("id")).longValue(),
                str(tenant.get("code")),
                str(tenant.get("name")),
                caller.userId(),
                caller.username(),
                caller.displayName(),
                caller.roles(),
                List.of(),
                List.of(),
                List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                caller.ip(),
                now,
                now,
                securityProperties.getPlatformSessionIdleSeconds(),
                MAX_SESSION_SECONDS,
                FieldPolicySnapshot.defaults());
    }

    /** 单账号收尾（current / 权限变更前调用）：先按到时 / 空闲收尾，剩余行由调用方按权限变更结束。 */
    private void finalizeAccount(long accountId) {
        SessionRow row = findActiveRow(accountId);
        if (row != null) {
            finalizeRow(row);
        }
    }

    /** 全部 ACTIVE 台账行（finalizeExpiredSessions 逐行收尾用）。 */
    private List<SessionRow> activeRows() {
        return platform.query(
                SELECT_ROW + " WHERE status = 'ACTIVE' ORDER BY id", TenantViewService::rowOf);
    }

    private static SessionRow rowOf(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getLong("id"),
                rs.getString("session_no"),
                rs.getLong("account_id"),
                rs.getString("account_name"),
                rs.getString("display_name"),
                rs.getString("role_codes"),
                rs.getLong("tenant_id"),
                rs.getString("purpose"),
                rs.getString("note"),
                rs.getLong("started_at_epoch"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("ends_at").toLocalDateTime());
    }

    /** 关账：吊销令牌（如仍在）、台账置 ENDED、写 VIEW_EXIT 审计。 */
    private void closeSession(SessionRow row, String endMethod, LocalDateTime endAt) {
        SessionPrincipal alive = viewTokenAlive(row);
        if (alive != null) {
            sessionStore.delete(alive.token());
        }
        platform.update(
                "UPDATE plt_view_session SET status = 'ENDED', end_at = ?, end_method = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                endAt,
                endMethod,
                row.id());
        audit.append(
                AuditEntry.platformAccess(row.tenantId(), "base", "VIEW_EXIT")
                        .userId(row.accountId())
                        .username(row.accountName())
                        .displayName(row.displayName())
                        .description(
                                "退出租户视角（" + endMethodText(endMethod) + "），会话号 " + row.sessionNo())
                        .viewSessionId(row.sessionNo())
                        .success()
                        .build());
        log.info(
                "tenant view ended account={} tenant={} session={} method={}",
                row.accountId(),
                row.tenantId(),
                row.sessionNo(),
                endMethod);
    }

    private void denyEnter(SessionPrincipal caller, long tenantId, String purpose, String reason) {
        audit.append(
                AuditEntry.platformAccess(tenantId, "base", "VIEW_ENTER")
                        .userId(caller.userId())
                        .username(caller.username())
                        .displayName(caller.displayName())
                        .roleCodes(caller.roles())
                        .description("进入租户视角被拒绝，目的 " + purposeText(purpose))
                        .result(AuditResult.DENIED)
                        .reason(reason)
                        .build());
    }

    private SessionRow findActiveRow(long accountId) {
        List<SessionRow> rows =
                platform.query(
                        SELECT_ROW + " WHERE account_id = ? AND status = 'ACTIVE' ORDER BY id",
                        TenantViewService::rowOf,
                        accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requireTenant(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, code, name, status FROM plt_tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private String tenantColumnOf(long tenantId, String column) {
        List<String> values =
                platform.queryForList(
                        "SELECT " + column + " FROM plt_tenant WHERE id = ?",
                        String.class,
                        tenantId);
        return values.isEmpty() ? "" : values.get(0);
    }

    private SessionPrincipal currentCaller() {
        return CurrentSession.requirePlatform();
    }

    /** 找回该台账行对应的 Redis 会话（按「端别 + 租户 + 账号」索引枚举后按会话号匹配）。 */
    private SessionPrincipal viewTokenAlive(SessionRow row) {
        SessionPrincipal probe =
                new SessionPrincipal(
                        null,
                        "TENANT_VIEW",
                        row.tenantId(),
                        null,
                        null,
                        row.accountId(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        for (SessionPrincipal session : sessionStore.listForUser(probe)) {
            if (row.sessionNo().equals(session.sessionId())) {
                return session;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 校验与文案（纯函数，供单测）

    /** 目的校验（PLT-VIEW-001 字典）：只允许四个枚举值。 */
    static String requirePurpose(String purpose) {
        String value = purpose == null ? "" : purpose.trim();
        if (!PURPOSES.contains(value)) {
            throw new ApiException(
                    400, 40001, "查看目的只允许 TROUBLESHOOT / CONFIG_CHECK / TRAINING / OTHER");
        }
        return value;
    }

    /** 说明必填 ≤500（platform-spec §8 视角会话「目的、说明」必填）。 */
    static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new ApiException(400, 40001, "请填写查看说明（必填）");
        }
        String trimmed = note.trim();
        if (trimmed.length() > NOTE_MAX) {
            throw new ApiException(400, 40001, "查看说明过长（最多 " + NOTE_MAX + " 个字符）");
        }
        return trimmed;
    }

    /** 目的文案（审计与前端展示同口径）。 */
    public static String purposeText(String purpose) {
        return switch (purpose == null ? "" : purpose) {
            case "TROUBLESHOOT" -> "排障";
            case "CONFIG_CHECK" -> "配置核对";
            case "TRAINING" -> "培训支持";
            case "OTHER" -> "其他";
            default -> purpose;
        };
    }

    /** 结束方式文案。 */
    static String endMethodText(String method) {
        return switch (method == null ? "" : method) {
            case END_LOGOUT -> "主动退出";
            case END_TIMEOUT -> "到时";
            case END_IDLE -> "空闲超时";
            case END_PERM_CHANGE -> "权限变更";
            default -> method;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static OffsetDateTime offsetDateTime(LocalDateTime value) {
        return value.atZone(ZONE).toOffsetDateTime();
    }
}
