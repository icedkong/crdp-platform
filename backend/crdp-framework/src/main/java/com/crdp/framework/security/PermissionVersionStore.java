package com.crdp.framework.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 权限版本号（E04-T06 / PLT-PERM-010）：按「端别 + 租户 + 用户」记录当前权限版本， 登录时写进会话；角色、菜单、数据范围等授权变化后调用方 {@link #bump}
 * 递增，旧会话在下一次请求即按新权限重算（令牌里的旧版本作废）。
 *
 * <p>版本号从 1 起（从未递增过的用户 current=1，与 E04-T02 起登录会话的初始版本一致）。
 */
@Component
public class PermissionVersionStore {

    private final StringRedisTemplate redis;

    public PermissionVersionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long current(SessionPrincipal principal) {
        return current(principal.sessionType(), principal.tenantId(), principal.userId());
    }

    /** 当前权限版本号；键不存在（从未变过权限）时为 1。 */
    public long current(String sessionType, Long tenantId, long userId) {
        String value = redis.opsForValue().get(key(sessionType, tenantId, userId));
        if (value == null) {
            return 1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 1L;
        }
    }

    /**
     * 权限变更后递增（角色停用、授权调整、数据范围变化等由授权用例调用），返回新版本号。 首次递增直接到 2：1 是所有旧会话的初始版本，必须保证递增后 current 大于存量会话版本。
     */
    public long bump(String sessionType, Long tenantId, long userId) {
        String key = key(sessionType, tenantId, userId);
        Long next = redis.opsForValue().increment(key);
        if (next != null && next > 1L) {
            return next;
        }
        // 键此前不存在（INCR 从 0 起）：再递增一次，保证旧会话（版本 1）判定为落后
        Long bumped = redis.opsForValue().increment(key);
        return bumped == null ? 2L : bumped;
    }

    private static String key(String sessionType, Long tenantId, long userId) {
        String tenant = tenantId == null ? "0" : String.valueOf(tenantId);
        return "crdp:permver:" + sessionType + ":" + tenant + ":" + userId;
    }
}
