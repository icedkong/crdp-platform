package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * E04-T06 / PLT-AUTH-008、009 在线会话管理：
 *
 * <ul>
 *   <li>管理员视角：列出本院全部在线会话（账号、IP、登录时间、最后活跃），按会话号强制下线（写审计）；
 *   <li>本人视角：列出并下线自己的会话（个人中心「在线会话」，PLT-ORG-011）。
 * </ul>
 *
 * <p>对外只暴露 {@link SessionPrincipal#sessionIdOf 由令牌单向推导的会话号}，不外露令牌本身。 下线他人的会话与下线自己的会话都走 {@link
 * SessionStore#delete}，被下线的令牌下一次请求即 40100。
 */
@Service
public class SessionAdminService {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final SessionStore sessionStore;
    private final AuditLogger auditLogger;

    public SessionAdminService(SessionStore sessionStore, AuditLogger auditLogger) {
        this.sessionStore = sessionStore;
        this.auditLogger = auditLogger;
    }

    /** 本院全部在线会话（权限 tenant:session:view；PLT-AUTH-009「在线用户」页面数据）。 */
    public List<OnlineSessionView> listTenantSessions() {
        SessionPrincipal viewer = CurrentSession.requireHospital();
        return sessionStore.listForTenant(viewer.tenantId()).stream()
                .map(OnlineSessionView::of)
                .toList();
    }

    /** 强制下线（权限 tenant:session:kick）：按会话号定位本院会话并删除，写审计（PLT-AUD-002「强制下线」）。 */
    public OnlineSessionView kick(String sessionId) {
        SessionPrincipal admin = CurrentSession.requireHospital();
        SessionPrincipal target = findInTenant(admin, sessionId);
        sessionStore.delete(target.token());
        auditLogger.record(
                new AuditEvent(
                        "SESSION_KICK",
                        admin.userId(),
                        "DELETE",
                        "sys_session:" + sessionId,
                        "强制下线：" + target.username()));
        return OnlineSessionView.of(target);
    }

    /** 本人在线会话（登录即可，PLT-ORG-011 个人中心「在线会话」）。 */
    public List<OnlineSessionView> listMySessions(SessionPrincipal principal) {
        String currentSessionId = principal.sessionId();
        return sessionStore.listForUser(principal).stream()
                .map(
                        session ->
                                OnlineSessionView.of(
                                        session, currentSessionId.equals(session.sessionId())))
                .toList();
    }

    /** 下线自己的某个会话。会话号不属于本人时 40400（不暴露他人会话是否存在）。 */
    public OnlineSessionView revokeMySession(SessionPrincipal principal, String sessionId) {
        SessionPrincipal target =
                sessionStore.listForUser(principal).stream()
                        .filter(session -> sessionId.equals(session.sessionId()))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(404, 40400, "会话不存在或已下线"));
        sessionStore.delete(target.token());
        auditLogger.record(
                new AuditEvent(
                        "SESSION_REVOKE",
                        principal.userId(),
                        "DELETE",
                        "sys_session:" + sessionId,
                        "本人下线在线会话"));
        return OnlineSessionView.of(target, sessionId.equals(principal.sessionId()));
    }

    private SessionPrincipal findInTenant(SessionPrincipal admin, String sessionId) {
        return sessionStore.listForTenant(admin.tenantId()).stream()
                .filter(session -> sessionId.equals(session.sessionId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, 40400, "会话不存在或已下线"));
    }

    /** 在线会话视图：账号、IP、登录时间、最后活跃；current 仅本人列表返回。 */
    public record OnlineSessionView(
            String sessionId,
            Long userId,
            String username,
            String displayName,
            String ip,
            String issuedAt,
            String lastActiveAt,
            boolean current) {

        static OnlineSessionView of(SessionPrincipal session) {
            return of(session, false);
        }

        static OnlineSessionView of(SessionPrincipal session, boolean current) {
            return new OnlineSessionView(
                    session.sessionId(),
                    session.userId(),
                    session.username(),
                    session.displayName(),
                    session.ip(),
                    time(session.issuedAtEpochSecond()),
                    time(session.lastActiveAtEpochSecond()),
                    current);
        }

        private static String time(long epochSecond) {
            return TIME.format(Instant.ofEpochSecond(epochSecond));
        }
    }
}
