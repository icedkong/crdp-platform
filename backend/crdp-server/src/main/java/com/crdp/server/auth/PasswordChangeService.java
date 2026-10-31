package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PasswordPolicy;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * E04-T05 / PLT-AUTH-006、010：自助修改密码（两端同构）。
 *
 * <p>校验顺序：原密码 → 策略（长度 / 复杂度 / 不含账号，租户可在平台下限上加严最小长度）→ 历史密码（不得与最近 5 次相同， 含当前密码）。通过后：落新哈希、清强制改密标记、刷新
 * password_updated_at、旧哈希追加历史表、 <b>该账号其他会话全部失效</b>（规格：修改或重置密码后其他会话全部失效）、当前会话刷新为已改密状态、清登录失败计数、写审计。
 * 审计不落新旧口令与哈希（AGENTS.md 第 5 节）。
 */
@Service
public class PasswordChangeService {

    private final HospitalDirectory hospitalDirectory;
    private final PlatformMapper platformMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final AuditLogger auditLogger;
    private final SecurityProperties properties;

    public PasswordChangeService(
            HospitalDirectory hospitalDirectory,
            PlatformMapper platformMapper,
            PasswordEncoder passwordEncoder,
            SessionStore sessionStore,
            LoginRateLimiter rateLimiter,
            AuditLogger auditLogger,
            SecurityProperties properties) {
        this.hospitalDirectory = hospitalDirectory;
        this.platformMapper = platformMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
        this.properties = properties;
    }

    /** 医院端改密。会话所在租户即目标租户库；越权改他人密码不在此接口（重置属用户管理，E06+）。 */
    public void changeHospital(SessionPrincipal principal, String oldPassword, String newPassword) {
        HospitalAccount account =
                TenantContext.callAsTenant(
                        principal.tenantId(),
                        principal.tenantCode(),
                        () -> hospitalDirectory.findById(principal.userId()).orElse(null));
        if (account == null) {
            throw new ApiException(404, 40400, "账号不存在");
        }
        verifyOldPassword(oldPassword, account.passwordHash());
        checkPolicy(newPassword, account.username(), hospitalMinLength(principal));
        checkHistory(
                newPassword,
                account.passwordHash(),
                TenantContext.callAsTenant(
                        principal.tenantId(),
                        principal.tenantCode(),
                        () ->
                                hospitalDirectory.passwordHistory(
                                        principal.userId(),
                                        properties.getPasswordHistoryCount() - 1)));
        TenantContext.runAsTenant(
                principal.tenantId(),
                principal.tenantCode(),
                () ->
                        hospitalDirectory.changePassword(
                                principal.userId(),
                                passwordEncoder.encode(newPassword),
                                LocalDateTime.now()));
        TenantContext.runAsTenant(
                principal.tenantId(),
                principal.tenantCode(),
                () ->
                        hospitalDirectory.recordPasswordHistory(
                                principal.userId(), account.passwordHash()));
        finish(
                principal,
                "H:" + principal.tenantCode() + ":" + principal.username().toLowerCase(Locale.ROOT),
                "sys_user:" + account.username());
    }

    /** 平台端改密（plt_account，无租户上下文）。 */
    public void changePlatform(SessionPrincipal principal, String oldPassword, String newPassword) {
        String currentHash =
                TenantContext.callAsPlatform(
                        () -> {
                            var row = platformMapper.findAccount(principal.username());
                            return row == null ? null : row.getPasswordHash();
                        });
        if (currentHash == null) {
            throw new ApiException(404, 40400, "账号不存在");
        }
        verifyOldPassword(oldPassword, currentHash);
        checkPolicy(newPassword, principal.username(), properties.getPasswordMinLength());
        checkHistory(
                newPassword,
                currentHash,
                TenantContext.callAsPlatform(
                        () ->
                                platformMapper.accountPasswordHistory(
                                        principal.userId(),
                                        properties.getPasswordHistoryCount() - 1)));
        TenantContext.runAsPlatform(
                () ->
                        platformMapper.updateAccountPassword(
                                principal.userId(),
                                passwordEncoder.encode(newPassword),
                                LocalDateTime.now()));
        TenantContext.runAsPlatform(
                () -> platformMapper.insertAccountPasswordHistory(principal.userId(), currentHash));
        finish(
                principal,
                "P:" + principal.username().toLowerCase(Locale.ROOT),
                "plt_account:" + principal.username());
    }

    // ---- 私有步骤 ----

    /** 租户加严后的最小长度：cfg_param 只能比平台下限更长（PLT-AUTH-006「租户可加严」）。 */
    private int hospitalMinLength(SessionPrincipal principal) {
        int override =
                TenantContext.callAsTenant(
                        principal.tenantId(),
                        principal.tenantCode(),
                        hospitalDirectory::passwordMinLengthOverride);
        return Math.max(properties.getPasswordMinLength(), override);
    }

    private void verifyOldPassword(String oldPassword, String currentHash) {
        if (oldPassword == null
                || currentHash == null
                || !passwordEncoder.matches(oldPassword, currentHash)) {
            throw new ApiException(422, 42200, "原密码不正确");
        }
    }

    private void checkPolicy(String newPassword, String username, int minLength) {
        List<String> violations =
                PasswordPolicy.violations(
                        newPassword, username, minLength, properties.getPasswordMinCategories());
        if (!violations.isEmpty()) {
            throw new ApiException(
                    422,
                    42200,
                    "新密码不满足策略：" + String.join("；", violations),
                    Map.of(
                            "fieldErrors",
                            List.of(
                                    Map.of(
                                            "field",
                                            "newPassword",
                                            "message",
                                            String.join("；", violations)))));
        }
    }

    /** 候选密码命中当前哈希或最近 N-1 个历史哈希即拒绝（规格：不得与最近 5 次相同）。 */
    private void checkHistory(String newPassword, String currentHash, List<String> recentHashes) {
        List<String> candidates = new ArrayList<>();
        if (currentHash != null) {
            candidates.add(currentHash);
        }
        if (recentHashes != null) {
            candidates.addAll(recentHashes);
        }
        for (String hash : candidates) {
            if (passwordEncoder.matches(newPassword, hash)) {
                throw new ApiException(
                        422,
                        42200,
                        "新密码不能与最近 " + properties.getPasswordHistoryCount() + " 次使用过的密码相同");
            }
        }
    }

    /** 改密落库后的收尾：吊销其他会话、刷新当前会话、清失败计数、写审计。 */
    private void finish(SessionPrincipal principal, String bucket, String auditTarget) {
        sessionStore.revokeAllForUser(principal, principal.token());
        long expiresAt =
                LocalDateTime.now()
                        .plusDays(properties.getPasswordValidDays())
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond();
        SessionPrincipal refreshed = principal.with(principal.token(), false, expiresAt);
        sessionStore.save(refreshed);
        rateLimiter.clear(bucket);
        if (principal.hospital()) {
            TenantContext.runAsTenant(
                    principal.tenantId(),
                    principal.tenantCode(),
                    () ->
                            auditLogger.record(
                                    new AuditEvent(
                                            "USER_PASSWORD_CHANGE",
                                            principal.userId(),
                                            "UPDATE",
                                            auditTarget,
                                            "自助修改密码，其他会话已失效")));
        } else {
            TenantContext.runAsPlatform(
                    () ->
                            auditLogger.record(
                                    new AuditEvent(
                                            "USER_PASSWORD_CHANGE",
                                            principal.userId(),
                                            "UPDATE",
                                            auditTarget,
                                            "自助修改密码，其他会话已失效")));
        }
    }
}
