package com.fu.framework.audit;

import com.fu.framework.tenant.TenantContext;
import com.fu.framework.web.TraceIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAuditLogger implements AuditLogger {

    private final JdbcTemplate jdbc;

    public JdbcAuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEvent event) {
        TenantContext.State state = TenantContext.require();
        String detail = clip(event.detail());
        if (state.platform()) {
            jdbc.update(
                    """
                    INSERT INTO plt_audit_log
                      (event_type, actor_id, tenant_id, action, target, detail, trace_id)
                    VALUES (?, ?, NULL, ?, ?, ?, ?)
                    """,
                    event.eventType(),
                    event.actorId(),
                    event.action(),
                    clip(event.target()),
                    detail,
                    TraceIds.current());
            return;
        }
        jdbc.update(
                """
                INSERT INTO aud_log (event_type, actor_id, action, target, detail, trace_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                event.eventType(),
                event.actorId(),
                event.action(),
                clip(event.target()),
                detail,
                TraceIds.current());
    }

    @Override
    public void loginAttempt(String username, boolean success, String message) {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            record(new AuditEvent("LOGIN", null, success ? "SUCCESS" : "FAIL", username, message));
            return;
        }
        jdbc.update(
                """
                INSERT INTO aud_login_log (username, success, message, trace_id)
                VALUES (?, ?, ?, ?)
                """,
                username,
                success ? 1 : 0,
                clip(message),
                TraceIds.current());
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
