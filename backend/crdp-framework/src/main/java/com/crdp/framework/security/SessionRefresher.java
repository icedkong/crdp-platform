package com.crdp.framework.security;

import java.util.Optional;

/**
 * 会话权限重算（E04-T06 / PLT-PERM-010）：令牌中的权限版本号落后于 {@link PermissionVersionStore}
 * 的当前版本时，按数据库里的最新授权（角色、菜单、数据范围、账号状态）重建会话内容。
 *
 * <p>返回 empty 表示账号已停用或不存在，会话随之删除、下一次请求按未登录处理（40100）。 实现方负责自行建立租户 / 平台上下文取数。
 */
public interface SessionRefresher {

    Optional<SessionPrincipal> refresh(SessionPrincipal stale);
}
