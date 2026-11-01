package com.crdp.framework.audit;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.TraceIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 审计写入服务（E08-T01 / PLT-AUD-001~004）：四类日志按规格字段写入只追加表。 E08-T02 起每条写入经 {@link AuditChainWriter}
 * 同时完成哈希链封存（prev_hash / hash，PLT-AUD-008）。
 *
 * <p>存储路由（audit.md §1）：操作日志——租户上下文写租户库 {@code aud_log}、平台上下文写平台库 {@code plt_audit_log}（平台操作 /
 * 租户控制面日志，tenantId 支持按租户维度检索 PLT-TEN-022）； 登录日志——医院登录写租户库 {@code aud_login_log}、平台登录与租户未识别的失败写平台库
 * {@code aud_login_log}； 数据访问日志写租户库 {@code aud_data_access_log}（平台账号在租户视角里的访问按 PLT-AUD-004 改落平台库）；
 * 平台访问日志只写平台库 {@code aud_platform_access_log}，即使在租户上下文里调用也自路由到平台库（医院端不可见）。
 *
 * <p>失败语义（audit.md §1 / platform-spec §9）：写入失败先重试一次；仍失败时， 登录、平台访问与敏感数据访问（明文、导出、打印、下载） 整体失败抛
 * 50000——宁可业务不通，不可审计缺行； 普通操作日志只告警不回滚（记录告警，业务照常）。
 *
 * <p>自动补全：操作者四件套（ID、账号、姓名、用户类型、角色）取当前会话， IP 与终端信息取当前请求（X-Forwarded-For 首段 + remoteAddr
 * 兜底，OQ-42），traceId 取 MDC。
 */
@Component
public class JdbcAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditLogger.class);

    /** 批量查询是常规读行为，写失败不阻断业务；明文 / 导出 / 打印 / 下载是敏感操作，必须保证留痕。 */
    private static final String NON_SENSITIVE_ACCESS = "BATCH_QUERY";

    private final JdbcTemplate jdbc;

    private final AuditChainWriter chain;

    public JdbcAuditLogger(JdbcTemplate jdbc, AuditChainWriter chain) {
        this.jdbc = jdbc;
        this.chain = chain;
    }

    @Override
    public void append(AuditEntry raw) {
        AuditEntry entry = enrich(raw);
        try {
            write(entry);
            return;
        } catch (DataAccessException firstFailure) {
            log.warn(
                    "audit write failed (will retry) type={} action={} traceId={}",
                    entry.type(),
                    entry.action(),
                    entry.traceId());
        }
        try {
            write(entry);
        } catch (DataAccessException secondFailure) {
            if (strict(entry)) {
                log.error(
                        "audit write failed for critical event type={} action={}",
                        entry.type(),
                        entry.action());
                throw new ApiException(500, 50000, "审计写入失败，操作已中止");
            }
            log.error(
                    "audit write failed (event lost, alert) type={} action={} traceId={} reason={}",
                    entry.type(),
                    entry.action(),
                    entry.traceId(),
                    secondFailure.getMostSpecificCause().getMessage());
        }
    }

    @Deprecated
    @Override
    public void record(AuditEvent event) {
        String objectType = null;
        String objectId = null;
        if (event.target() != null && event.target().contains(":")) {
            int split = event.target().indexOf(':');
            objectType = event.target().substring(0, split);
            objectId = event.target().substring(split + 1);
        }
        append(
                AuditEntry.operation(null, event.action())
                        // event_type 列保留 M0 事件名（如 ROLE_UPDATE），action 列保留 CRUD 动词
                        .eventType(event.eventType())
                        .userId(event.actorId())
                        .description(event.detail())
                        .objectType(objectType)
                        .objectId(objectId)
                        .build());
    }

    @Deprecated
    @Override
    public void loginAttempt(String username, boolean success, String message) {
        append(
                AuditEntry.login(username)
                        .result(success ? AuditResult.SUCCESS : AuditResult.FAIL)
                        .description(message)
                        .reason(success ? null : message)
                        .build());
    }

    // ---------------------------------------------------------------- 写入路由与语句

    private void write(AuditEntry entry) {
        switch (entry.type()) {
            case PLATFORM_ACCESS -> TenantContext.runAsPlatform(() -> insertPlatformAccess(entry));
            case DATA_ACCESS -> {
                TenantContext.State state = TenantContext.require();
                if (state.platform()) {
                    // 平台账号在租户视角里的数据访问按 PLT-AUD-004 记平台访问（仅存平台侧）
                    TenantContext.runAsPlatform(() -> insertPlatformAccess(entry));
                } else {
                    insertDataAccess(entry);
                }
            }
            case LOGIN -> {
                Optional<TenantContext.State> state = TenantContext.current();
                if (state.isPresent() && !state.get().platform()) {
                    insertLogin(entry);
                } else {
                    TenantContext.runAsPlatform(() -> insertPlatformLogin(entry));
                }
            }
            default -> {
                TenantContext.State state = TenantContext.require();
                if (state.platform()) {
                    insertPlatformOperation(entry);
                } else {
                    insertTenantOperation(entry);
                }
            }
        }
    }

    /** 租户库操作日志（PLT-AUD-001）。event_type 与 action：新口径同为 action，遗留桥接分别是事件名与 CRUD 动词。 */
    private void insertTenantOperation(AuditEntry e) {
        chain.insert(
                AuditChainTable.TENANT_OPERATION,
                """
                INSERT INTO aud_log
                  (event_type, actor_id, action, target, detail, trace_id,
                   user_name, display_name, user_type, role_codes, module,
                   object_type, object_id, result, error_code, reason,
                   before_json, after_json, ip, user_agent, view_session_id,
                   prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        eventTypeOf(e),
                        e.userId(),
                        e.action(),
                        targetOf(e),
                        clip(e.description(), 512),
                        e.traceId(),
                        clip(e.username(), 64),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.objectType(), 64),
                        clip(e.objectId(), 128),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.reason(), 512),
                        e.beforeJson(),
                        e.afterJson(),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255),
                        clip(e.viewSessionId(), 64)));
    }

    /** 平台库操作日志（平台操作 / 租户控制面，PLT-TEN-022 按 tenantId 维度检索）。 */
    private void insertPlatformOperation(AuditEntry e) {
        chain.insert(
                AuditChainTable.PLATFORM_OPERATION,
                """
                INSERT INTO plt_audit_log
                  (event_type, actor_id, tenant_id, action, target, detail, trace_id,
                   user_name, display_name, user_type, role_codes, module,
                   object_type, object_id, result, error_code, reason,
                   before_json, after_json, ip, user_agent, view_session_id,
                   prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        eventTypeOf(e),
                        e.userId(),
                        e.tenantId(),
                        e.action(),
                        targetOf(e),
                        clip(e.description(), 512),
                        e.traceId(),
                        clip(e.username(), 64),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.objectType(), 64),
                        clip(e.objectId(), 128),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.reason(), 512),
                        e.beforeJson(),
                        e.afterJson(),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255),
                        clip(e.viewSessionId(), 64)));
    }

    /** 租户库登录日志（PLT-AUD-002）。success/message 是 M0 既有列，扩展列为规格登录字段。 */
    private void insertLogin(AuditEntry e) {
        chain.insert(
                AuditChainTable.TENANT_LOGIN,
                """
                INSERT INTO aud_login_log
                  (username, success, message, trace_id, user_id, display_name, user_type,
                   role_codes, module, action, result, error_code, login_method,
                   tenant_identify_method, second_factor_result, fail_reason, session_id,
                   ip, user_agent, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        clip(e.username(), 64),
                        e.result() == AuditResult.SUCCESS ? 1 : 0,
                        clip(e.description(), 128),
                        e.traceId(),
                        e.userId(),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.action(), 64),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.loginMethod(), 16),
                        clip(e.tenantIdentifyMethod(), 16),
                        clip(e.secondFactorResult(), 16),
                        e.result() == AuditResult.SUCCESS ? null : clip(e.reason(), 128),
                        clip(e.sessionId(), 64),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255)));
    }

    /** 平台库登录日志：平台账号登录与租户未识别的医院登录失败（写不进租户库）。 */
    private void insertPlatformLogin(AuditEntry e) {
        chain.insert(
                AuditChainTable.PLATFORM_LOGIN,
                """
                INSERT INTO aud_login_log
                  (tenant_id, username, success, message, trace_id, user_id, display_name,
                   user_type, role_codes, module, action, result, error_code, login_method,
                   tenant_identify_method, second_factor_result, fail_reason, session_id,
                   ip, user_agent, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        e.tenantId(),
                        clip(e.username(), 64),
                        e.result() == AuditResult.SUCCESS ? 1 : 0,
                        clip(e.description(), 128),
                        e.traceId(),
                        e.userId(),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.action(), 64),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.loginMethod(), 16),
                        clip(e.tenantIdentifyMethod(), 16),
                        clip(e.secondFactorResult(), 16),
                        e.result() == AuditResult.SUCCESS ? null : clip(e.reason(), 128),
                        clip(e.sessionId(), 64),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255)));
    }

    /** 租户库数据访问日志（PLT-AUD-003）：访问方式、涉及记录数、查询条件摘要、导出文件哈希。 */
    private void insertDataAccess(AuditEntry e) {
        chain.insert(
                AuditChainTable.TENANT_DATA_ACCESS,
                """
                INSERT INTO aud_data_access_log
                  (user_id, user_name, display_name, user_type, role_codes, module, action,
                   access_method, object_type, object_id, record_count, query_digest, file_hash,
                   result, error_code, reason, description, ip, user_agent, trace_id,
                   view_session_id, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        e.userId(),
                        clip(e.username(), 64),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.action(), 64),
                        clip(e.accessMethod(), 32),
                        clip(e.objectType(), 64),
                        clip(e.objectId(), 128),
                        e.recordCount(),
                        clip(e.queryDigest(), 512),
                        clip(e.fileHash(), 128),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.reason(), 512),
                        clip(e.description(), 255),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255),
                        e.traceId(),
                        clip(e.viewSessionId(), 64)));
    }

    /** 平台库平台访问日志（PLT-AUD-004）：租户视角会话、页面、查询、被拒、代为操作与视角规则变更，医院端不可见。 */
    private void insertPlatformAccess(AuditEntry e) {
        chain.insert(
                AuditChainTable.PLATFORM_ACCESS,
                """
                INSERT INTO aud_platform_access_log
                  (tenant_id, user_id, user_name, display_name, user_type, role_codes, module,
                   action, description, object_type, object_id, view_session_id, page,
                   access_method, record_count, query_digest, file_hash, before_json, after_json,
                   result, error_code, reason, ip, user_agent, trace_id, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args(
                        e.tenantId(),
                        e.userId(),
                        clip(e.username(), 64),
                        clip(e.displayName(), 128),
                        e.userType(),
                        clip(e.roleCodes(), 512),
                        clip(e.module(), 64),
                        clip(e.action(), 64),
                        clip(e.description(), 255),
                        clip(e.objectType(), 64),
                        clip(e.objectId(), 128),
                        clip(e.viewSessionId(), 64),
                        clip(e.page(), 128),
                        clip(e.accessMethod(), 32),
                        e.recordCount(),
                        clip(e.queryDigest(), 512),
                        clip(e.fileHash(), 128),
                        e.beforeJson(),
                        e.afterJson(),
                        resultOf(e),
                        e.errorCode(),
                        clip(e.reason(), 512),
                        clip(e.ip(), 64),
                        clip(e.userAgent(), 255),
                        e.traceId()));
    }

    private static List<Object> args(Object... values) {
        // 参数里大量 null（可选字段），不能用 List.of
        List<Object> list = new ArrayList<>(values.length);
        java.util.Collections.addAll(list, values);
        return list;
    }

    // ---------------------------------------------------------------- 补全与工具

    /** 缺省字段自动补全：操作者（会话）、请求上下文（IP、终端）、traceId、结果、模块与描述兜底。 */
    private AuditEntry enrich(AuditEntry raw) {
        AuditEntry.Builder builder =
                new AuditEntry.Builder(raw.type())
                        .tenantId(raw.tenantId())
                        .userId(raw.userId())
                        .username(raw.username())
                        .displayName(raw.displayName())
                        .userType(raw.userType())
                        .roleCodes(raw.roleCodes())
                        .module(raw.module())
                        .action(raw.action())
                        .eventType(raw.eventType())
                        .description(raw.description())
                        .objectType(raw.objectType())
                        .objectId(raw.objectId())
                        .result(raw.result())
                        .errorCode(raw.errorCode())
                        .reason(raw.reason())
                        .beforeJson(raw.beforeJson())
                        .afterJson(raw.afterJson())
                        .ip(raw.ip())
                        .userAgent(raw.userAgent())
                        .traceId(raw.traceId())
                        .viewSessionId(raw.viewSessionId())
                        .loginMethod(raw.loginMethod())
                        .tenantIdentifyMethod(raw.tenantIdentifyMethod())
                        .secondFactorResult(raw.secondFactorResult())
                        .sessionId(raw.sessionId())
                        .accessMethod(raw.accessMethod())
                        .recordCount(raw.recordCount())
                        .queryDigest(raw.queryDigest())
                        .fileHash(raw.fileHash())
                        .page(raw.page());

        Optional<SessionPrincipal> session = CurrentSession.current();
        session.ifPresent(
                principal -> {
                    // 逐字段补全：调用方已给的（如登录流程带 username）不覆盖
                    if (raw.userId() == null) {
                        builder.userId(principal.userId());
                    }
                    if (raw.username() == null) {
                        builder.username(principal.username());
                    }
                    if (raw.displayName() == null) {
                        builder.displayName(principal.displayName());
                    }
                    if (raw.userType() == null) {
                        builder.userType(principal.sessionType());
                    }
                    if (raw.roleCodes() == null) {
                        builder.roleCodes(principal.roles());
                    }
                });
        boolean platformSide =
                TenantContext.current().map(TenantContext.State::platform).orElse(false);
        if (raw.userType() == null) {
            builder.userType(platformSide ? "PLATFORM" : "HOSPITAL");
        }
        if (raw.module() == null) {
            builder.module(platformSide ? "platform" : "system");
        }
        if (raw.description() == null && raw.action() != null) {
            builder.description(raw.action());
        }
        if (raw.result() == null) {
            builder.result(AuditResult.SUCCESS);
        }
        if (raw.ip() == null) {
            builder.ip(AuditRequestContext.clientIp());
        }
        if (raw.userAgent() == null) {
            builder.userAgent(AuditRequestContext.userAgent());
        }
        if (raw.traceId() == null || raw.traceId().isBlank()) {
            builder.traceId(TraceIds.current());
        }
        // PLT-TEN-022：平台操作日志按租户维度检索。M0 遗留调用方只给 target（plt_tenant:编码），
        // 写入服务在平台库反查编码补 tenant_id；反查失败不阻断审计（留空，仍可按 target 检索）。
        if (raw.tenantId() == null
                && raw.type() == AuditType.OPERATION
                && platformSide
                && "plt_tenant".equals(raw.objectType())
                && raw.objectId() != null) {
            try {
                Long tenantId =
                        jdbc.queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?",
                                Long.class,
                                raw.objectId());
                if (tenantId != null) {
                    builder.tenantId(tenantId);
                }
            } catch (DataAccessException lookupFailure) {
                log.warn(
                        "audit tenant id lookup failed code={} reason={}",
                        raw.objectId(),
                        lookupFailure.getMostSpecificCause().getMessage());
            }
        }
        return builder.build();
    }

    /** 关键事件（登录、平台访问、敏感数据访问）写失败必须让业务整体失败（50000）。 */
    private static boolean strict(AuditEntry entry) {
        return entry.type() == AuditType.LOGIN
                || entry.type() == AuditType.PLATFORM_ACCESS
                || (entry.type() == AuditType.DATA_ACCESS
                        && !NON_SENSITIVE_ACCESS.equals(entry.accessMethod()));
    }

    private static String resultOf(AuditEntry entry) {
        return entry.result() == null ? AuditResult.SUCCESS.name() : entry.result().name();
    }

    private static String eventTypeOf(AuditEntry entry) {
        return entry.eventType() == null ? entry.action() : entry.eventType();
    }

    private static String targetOf(AuditEntry entry) {
        if (entry.objectType() == null) {
            return entry.objectId();
        }
        if (entry.objectId() == null) {
            return entry.objectType();
        }
        return entry.objectType() + ":" + entry.objectId();
    }

    private static String clip(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String clip(List<String> values, int maxLength) {
        if (values == null) {
            return null;
        }
        return clip(String.join(",", values), maxLength);
    }
}
