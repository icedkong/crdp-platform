package com.crdp.framework.security;

import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 放进 Redis 的会话。令牌本身是 256 位随机值，不透明，不使用 JWT。 sessionType：HOSPITAL 医院端，PLATFORM 平台端。租户视角与手机上传留到后续
 * Epic。
 *
 * <p>E04-T05 / PLT-AUTH-010：{@code mustChangePassword} 为真（初始密码、管理员重置后、密码到期）时， 除改密 / 登出 / me 外的接口一律
 * 40305（MustChangePasswordFilter）。 {@code passwordExpiresAtEpochSecond} 供 me 计算到期提醒（规格：到期前 7 天提醒）。
 *
 * <p>E04-T06 / PLT-AUTH-008：会话生命周期字段。{@code issuedAtEpochSecond} 是绝对会话时长的锚点（默认 12 小时， 到时重新登录）；
 * {@code lastActiveAtEpochSecond} 配合 {@code idleSeconds} 做空闲超时（医院端默认 30 分钟、平台端 15 分钟，
 * 租户可加严——按登录时的快照存在会话里）；每次请求滑动续期（{@code SessionStore#touch}）。 {@code ip} 是登录来源 IP（在线用户列表展示，取
 * X-Forwarded-For 首段）。
 *
 * <p>E07-T04 / PLT-PERM-009：{@code fieldPolicies} 是账号的字段策略快照（多角色显式覆盖「取最宽松」合并，无覆盖回落 平台默认）。医院端会话由登录装配
 * 填充，角色策略保存递增权限版本后旧会话下一次请求即重算；平台端会话不消费字段策略（平台 视角的同字段合并取更严，随 E20 落地），兜底填平台默认（全部脱敏， fail closed）。旧会话
 * JSON 反序列化缺该属性时为 null，重算后补齐。
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
        Long passwordExpiresAtEpochSecond,
        String ip,
        long issuedAtEpochSecond,
        long lastActiveAtEpochSecond,
        long idleSeconds,
        FieldPolicySnapshot fieldPolicies) {

    /** 兼容旧调用方的构造器：不限制空闲超时（idleSeconds=0，只受部署配置的绝对时长约束）， 时间戳取当前时刻，不带登录 IP，字段策略取平台默认。 */
    public SessionPrincipal(
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
        this(
                token,
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
                mustChangePassword,
                passwordExpiresAtEpochSecond,
                null,
                Instant.now().getEpochSecond(),
                Instant.now().getEpochSecond(),
                0L,
                FieldPolicySnapshot.defaults());
    }

    /**
     * 兼容旧调用方的构造器（E07-T04 前的 20 参形式）：字段策略取平台默认（敏感字段全部脱敏， fail closed）。登录与会话重算请使用带 {@code
     * fieldPolicies} 的完整构造器。
     */
    public SessionPrincipal(
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
            Long passwordExpiresAtEpochSecond,
            String ip,
            long issuedAtEpochSecond,
            long lastActiveAtEpochSecond,
            long idleSeconds) {
        this(
                token,
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
                mustChangePassword,
                passwordExpiresAtEpochSecond,
                ip,
                issuedAtEpochSecond,
                lastActiveAtEpochSecond,
                idleSeconds,
                FieldPolicySnapshot.defaults());
    }

    public boolean hospital() {
        return "HOSPITAL".equals(sessionType);
    }

    public boolean platform() {
        return "PLATFORM".equals(sessionType);
    }

    /** 对外展示的会话号：令牌哈希前 16 个十六进制字符。单向推导，列表与踢线接口只 出会话号、绝不外露令牌本身（PLT-AUTH-009）。 */
    public static String sessionIdOf(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex =
                    HexFormat.of()
                            .formatHex(digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 会话号（从令牌单向推导）。 */
    public String sessionId() {
        return sessionIdOf(token);
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
                newPasswordExpiresAtEpochSecond,
                ip,
                issuedAtEpochSecond,
                lastActiveAtEpochSecond,
                idleSeconds,
                fieldPolicies);
    }

    /** 滑动续期（E04-T06 / PLT-AUTH-008）：只更新最后活跃时间，其余字段不变。 */
    public SessionPrincipal refreshedAt(long nowEpochSecond) {
        return new SessionPrincipal(
                token,
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
                mustChangePassword,
                passwordExpiresAtEpochSecond,
                ip,
                issuedAtEpochSecond,
                nowEpochSecond,
                idleSeconds,
                fieldPolicies);
    }

    /**
     * 权限版本落后时按新权限重算（E04-T06 / PLT-PERM-010）：保留令牌与生命周期（登录 IP、签发与 活跃时间、空闲窗口——绝对时长仍从原登录起算），
     * 接收草稿里的账号信息、角色、菜单、权限码、数据范围、字段策略与新的权限版本号。
     */
    public SessionPrincipal withFreshGrants(SessionPrincipal draft) {
        return new SessionPrincipal(
                token,
                sessionType,
                tenantId,
                tenantCode,
                tenantName,
                userId,
                draft.username(),
                draft.displayName(),
                draft.roles(),
                draft.menuIds(),
                draft.permissionCodes(),
                draft.openedModules(),
                draft.permissionVersion(),
                draft.scope(),
                draft.mustChangePassword(),
                draft.passwordExpiresAtEpochSecond(),
                ip,
                issuedAtEpochSecond,
                lastActiveAtEpochSecond,
                idleSeconds,
                draft.fieldPolicies());
    }
}
