package com.crdp.framework.security;

import java.util.List;

/**
 * 放进 Redis 的会话。令牌本身是 256 位随机值，不透明，不使用 JWT。 sessionType：HOSPITAL 医院端，PLATFORM 平台端。租户视角与手机上传留到后续
 * Epic。
 *
 * <p>E04-T05 / PLT-AUTH-010：{@code mustChangePassword} 为真（初始密码、管理员重置后、密码到期）时， 除改密 / 登出 / me 外的接口一律
 * 40305（MustChangePasswordFilter）。 {@code passwordExpiresAtEpochSecond} 供 me 计算到期提醒（规格：到期前 7 天提醒）。
 */
public record SessionPrincipal(
        String token,
        String sessionType,
        Long tenantId,
        String tenantCode,
        String tenantName,
        Long userId,
        String username,
        String displayName,
        List<String> roles,
        List<Long> menuIds,
        List<String> permissionCodes,
        List<String> openedModules,
        long permissionVersion,
        ScopeSnapshot scope,
        boolean mustChangePassword,
        Long passwordExpiresAtEpochSecond) {

    public boolean hospital() {
        return "HOSPITAL".equals(sessionType);
    }

    public boolean platform() {
        return "PLATFORM".equals(sessionType);
    }

    /** 换令牌、其余字段不变（双因素第二步换发会话）。 */
    public SessionPrincipal withToken(String newToken) {
        return with(newToken, mustChangePassword, passwordExpiresAtEpochSecond);
    }

    /** 换令牌或改密后刷新会话：只替换 token / mustChangePassword / 密码到期时间，其余字段不变。 */
    public SessionPrincipal with(
            String newToken, boolean newMustChangePassword, Long newPasswordExpiresAtEpochSecond) {
        return new SessionPrincipal(
                newToken,
                sessionType,
                tenantId,
                tenantCode,
                tenantName,
                userId,
                username,
                displayName,
                roles,
                menuIds,
                permissionCodes,
                openedModules,
                permissionVersion,
                scope,
                newMustChangePassword,
                newPasswordExpiresAtEpochSecond);
    }
}
