package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.ReauthStore;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证（两端同构，docs/api/auth.md §1）。
 *
 * <p>已登录会话内用密码再次验证身份，通过后签发 {@code reauthToken}（5 分钟有效、绑定当前会话， {@link ReauthStore}），供敏感接口经 {@code
 * X-Reauth-Token} 请求头携带（{@code ReauthInterceptor} 校验，缺失 40304）。 当前接入：审计导出提交与下载（两端）、重置用户密码。
 *
 * <p>防爆破：失败计入与登录同一只桶（{@code H:租户:账号} / {@code P:账号}，{@link LoginRateLimiter}）—— 连续失败达上限同样 42900 锁
 * 30 分钟，且锁定会波及登录本身；成功验证清零计数。 密码错误与账号中途消失统一 42200「密码不正确」（不外露存在性）。 通过 /
 * 失败各写一条登录日志（action=REAUTH，PLT-AUD-002 口径），不落口令材料。
 */
@Service
public class ReauthService {

    private final HospitalDirectory hospitalDirectory;
    private final PlatformMapper platformMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final ReauthStore reauthStore;
    private final AuditLogger auditLogger;
    private final SecurityProperties properties;

    public ReauthService(
            HospitalDirectory hospitalDirectory,
            PlatformMapper platformMapper,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter rateLimiter,
            ReauthStore reauthStore,
            AuditLogger auditLogger,
            SecurityProperties properties) {
        this.hospitalDirectory = hospitalDirectory;
        this.platformMapper = platformMapper;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.reauthStore = reauthStore;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    /** 医院端：在会话所在租户库校验账号密码。 */
    public ReauthResult reauthHospital(SessionPrincipal principal, String password) {
        String bucket = "H:" + principal.tenantCode() + ":" + normalized(principal.username());
        rateLimiter.check(bucket);
        HospitalAccount account =
                TenantContext.callAsTenant(
                        principal.tenantId(),
                        principal.tenantCode(),
                        () -> hospitalDirectory.findById(principal.userId()).orElse(null));
        if (account == null || !passwordMatches(password, account.passwordHash())) {
            rejectHospital(principal, bucket);
        }
        return pass(principal, bucket);
    }

    /** 平台端：平台库校验账号密码（plt_account）。 */
    public ReauthResult reauthPlatform(SessionPrincipal principal, String password) {
        String bucket = "P:" + normalized(principal.username());
        rateLimiter.check(bucket);
        var account =
                TenantContext.callAsPlatform(
                        () -> platformMapper.findAccount(principal.username()));
        if (account == null || !passwordMatches(password, account.getPasswordHash())) {
            rejectPlatform(principal, bucket);
        }
        return pass(principal, bucket);
    }

    // ---- 私有步骤 ----

    private ReauthResult pass(SessionPrincipal principal, String bucket) {
        rateLimiter.clear(bucket);
        audit(principal, true, null);
        return new ReauthResult(
                reauthStore.issue(principal), properties.getReauthTokenTtlSeconds());
    }

    private void rejectHospital(SessionPrincipal principal, String bucket) {
        reject(principal.hospital(), principal, bucket);
    }

    private void rejectPlatform(SessionPrincipal principal, String bucket) {
        reject(false, principal, bucket);
    }

    /** 记失败计数 + 失败登录日志，然后抛 42200（不区分账号消失与密码错误）。 */
    private void reject(boolean hospital, SessionPrincipal principal, String bucket) {
        rateLimiter.recordFailure(bucket);
        Runnable auditWrite = () -> audit(principal, false, "reauth-bad-password");
        if (hospital) {
            TenantContext.runAsTenant(principal.tenantId(), principal.tenantCode(), auditWrite);
        } else {
            TenantContext.runAsPlatform(auditWrite);
        }
        throw new ApiException(
                422,
                42200,
                "密码不正确",
                Map.of("remainingAttempts", rateLimiter.remainingAttempts(bucket)));
    }

    private void audit(SessionPrincipal principal, boolean success, String failReason) {
        AuditEntry.Builder entry =
                AuditEntry.login(principal.username())
                        .action("REAUTH")
                        .description(success ? "敏感操作二次认证通过" : "敏感操作二次认证失败")
                        .loginMethod("PASSWORD")
                        .sessionId(principal.sessionId());
        if (success) {
            entry.success();
        } else {
            entry.fail(failReason);
        }
        auditLogger.append(entry.build());
    }

    private boolean passwordMatches(String raw, String hash) {
        if (raw == null || hash == null || hash.equals("PENDING_DEMO_HASH")) {
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    private static String normalized(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /** 二次认证结果：reauthToken 随 X-Reauth-Token 头携带，expiresInSeconds 供前端做窗口提示。 */
    public record ReauthResult(String reauthToken, long expiresInSeconds) {}
}
