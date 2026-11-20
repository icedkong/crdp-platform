package com.crdp.server.followup;

import com.crdp.framework.security.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 统计验收夹具使用独立病区和更窄的测试会话，避免本地其他示例患者改变手算表；不删除其他数据。 */
final class StatsFixtureScope {
    private StatsFixtureScope() {}

    static long ward(JdbcTemplate jdbc, String code) {
        jdbc.update(
                "INSERT INTO ward(code,name,status) VALUES (?,'统计病区（示例）','ENABLED') ON DUPLICATE KEY UPDATE code=VALUES(code)",
                code);
        return jdbc.queryForObject("SELECT id FROM ward WHERE code=?", Long.class, code);
    }

    static String restrict(SessionStore store, String token, List<Long> wards) {
        var p = store.find(token).orElseThrow();
        store.save(
                new SessionPrincipal(
                        p.token(),
                        p.sessionType(),
                        p.tenantId(),
                        p.tenantCode(),
                        p.tenantName(),
                        p.userId(),
                        p.username(),
                        p.displayName(),
                        p.roles(),
                        p.menuIds(),
                        p.permissionCodes(),
                        p.openedModules(),
                        p.permissionVersion(),
                        ScopeSnapshot.restricted(wards, List.of(), false),
                        p.mustChangePassword(),
                        p.passwordExpiresAtEpochSecond(),
                        p.ip(),
                        p.issuedAtEpochSecond(),
                        p.lastActiveAtEpochSecond(),
                        p.idleSeconds(),
                        p.absoluteSeconds(),
                        p.fieldPolicies()));
        return token;
    }
}
