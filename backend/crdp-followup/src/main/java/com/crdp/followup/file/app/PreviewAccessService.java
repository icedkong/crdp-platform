package com.crdp.followup.file.app;

import com.crdp.framework.security.*;
import com.crdp.framework.web.ApiException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 取流前后重载同一会话和最新权限，不以预览短链代替登录或沿用旧范围。 */
@Component
public class PreviewAccessService {
    private final SessionStore sessions;
    private final SessionPermissionResolver permissions;

    public PreviewAccessService(
            SessionStore sessions, PermissionVersionStore versions, SessionRefresher refresher) {
        this.sessions = sessions;
        this.permissions = new SessionPermissionResolver(sessions, versions, refresher);
    }

    public SessionPrincipal current() {
        var original = CurrentSession.requireHospital();
        var principal =
                sessions.find(original.token())
                        .map(permissions::resolve)
                        .orElseThrow(() -> new ApiException(401, 40100, "未登录或登录已失效"));
        if (!(principal.hospital() || principal.tenantView())
                || !java.util.Objects.equals(principal.tenantId(), original.tenantId())
                || !java.util.Objects.equals(principal.userId(), original.userId()))
            throw new ApiException(404, 40400, "文件不存在或无权访问");
        if (principal.mustChangePassword()) throw new ApiException(403, 40305, "请先修改密码");
        if (!principal.openedModules().contains("followup"))
            throw new ApiException(403, 40301, "随访模块未开通");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, authentication.getAuthorities()));
        return principal;
    }
}
