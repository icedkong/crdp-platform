package com.crdp.system.user;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;

/**
 * 租户库中的账号。phone 为绑定手机号的信封加密密文（purpose=sys-user-phone），未绑定为 null； 短信验证码登录与双因素认证（E04-T04 /
 * PLT-AUTH-002、003）要求手机号已绑定且本院唯一。
 */
public record HospitalAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String status,
        String phone,
        List<String> roles,
        List<Long> menuIds,
        List<String> permissionCodes,
        ScopeSnapshot scope) {}
