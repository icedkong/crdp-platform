package com.crdp.framework.security;

import java.util.List;

/**
 * 放进 Redis 的会话。令牌本身是 256 位随机值，不透明，不使用 JWT。 sessionType：HOSPITAL 医院端，PLATFORM 平台端。租户视角与手机上传留到后续
 * Epic。
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
        ScopeSnapshot scope) {

    public boolean hospital() {
        return "HOSPITAL".equals(sessionType);
    }

    public boolean platform() {
        return "PLATFORM".equals(sessionType);
    }
}
