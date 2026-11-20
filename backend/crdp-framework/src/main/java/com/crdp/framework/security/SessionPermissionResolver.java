package com.crdp.framework.security;

/** 普通登录与一次性上传入口共用的权限版本刷新，避免从会话存储取出的旧快照继续授权。 */
public final class SessionPermissionResolver {
    private final SessionStore sessions;
    private final PermissionVersionStore versions;
    private final SessionRefresher refresher;

    public SessionPermissionResolver(
            SessionStore sessions, PermissionVersionStore versions, SessionRefresher refresher) {
        this.sessions = sessions;
        this.versions = versions;
        this.refresher = refresher;
    }

    /** 账号已停用时吊销会话并返回 null；权限已变化时保存最新快照。 */
    public SessionPrincipal resolve(SessionPrincipal principal) {
        if (principal.permissionVersion() >= versions.current(principal)) return principal;
        var refreshed = refresher.refresh(principal);
        if (refreshed.isEmpty()) {
            sessions.delete(principal.token());
            return null;
        }
        SessionPrincipal active = refreshed.get();
        sessions.save(active);
        return active;
    }
}
