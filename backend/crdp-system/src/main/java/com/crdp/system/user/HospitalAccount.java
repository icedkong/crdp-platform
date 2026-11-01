package com.crdp.system.user;

import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.security.ScopeSnapshot;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户库中的账号。phone 为绑定手机号的信封加密密文（purpose=sys-user-phone），未绑定为 null； 短信验证码登录与双因素认证（E04-T04 /
 * PLT-AUTH-002、003）要求手机号已绑定且本院唯一。
 *
 * <p>E04-T05 / PLT-AUTH-006、010：mustChangePassword 为初始 / 重置标记，passwordUpdatedAt 供密码有效期（90
 * 天）计算，二者任一命中即强制改密。
 *
 * <p>E07-T04 / PLT-PERM-009：fieldPolicies 为账号的生效字段策略快照（启用角色显式覆盖「取最宽松」合并， 无覆盖回落平台默认），随登录装配冻结进会话；
 * 脱敏序列化与明文查看（E07-T05）据此逐字段判定。
 */
public record HospitalAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String status,
        String phone,
        boolean mustChangePassword,
        LocalDateTime passwordUpdatedAt,
        List<String> roles,
        List<Long> menuIds,
        List<String> permissionCodes,
        ScopeSnapshot scope,
        FieldPolicySnapshot fieldPolicies) {

    /** 兼容旧调用方的构造器：字段策略取平台默认（敏感字段全部脱敏，fail closed）。 */
    public HospitalAccount(
            long id,
            String username,
            String displayName,
            String passwordHash,
            String status,
            String phone,
            boolean mustChangePassword,
            LocalDateTime passwordUpdatedAt,
            List<String> roles,
            List<Long> menuIds,
            List<String> permissionCodes,
            ScopeSnapshot scope) {
        this(
                id,
                username,
                displayName,
                passwordHash,
                status,
                phone,
                mustChangePassword,
                passwordUpdatedAt,
                roles,
                menuIds,
                permissionCodes,
                scope,
                FieldPolicySnapshot.defaults());
    }
}
