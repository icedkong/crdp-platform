package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.AuthMessages;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PasswordPolicy;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * E04-T07 / PLT-AUTH-011：忘记密码（医院端，免登录流程）。
 *
 * <p>两步：① 输入租户编码 + 账号，重置验证码发到该账号<b>绑定的手机号</b>（不是请求里的任意手机号）； ② 提交验证码与新密码，验证通过后按密码策略重置。
 *
 * <p>防探测口径与登录一致（E04-T04 / PLT-AUTH-004、007）：
 *
 * <ul>
 *   <li>第一步对未知租户、未知账号、停用账号、未绑定手机号一律「静默成功」——不触达短信通道、不报错； 同时对未知账号也消耗同样的 60 秒冷却位（探针键），否则「第二次请求是否
 *       42900」会暴露账号存在性；
 *   <li>第二步的未知租户 / 未知账号 / 停用账号 / 未绑定手机号 / 验证码错误 / 过期统一 40102「验证码错误或已过期」，不区分存在性；
 *   <li>重置验证码与登录验证码分命名空间（{@code reset:} vs {@code login:}）：钓鱼拿到的登录验证码不能重置密码。
 * </ul>
 *
 * <p>重置验证通过后：新密码按平台下限 + 租户加严校验、不得与最近 5 次相同； 落新哈希、旧哈希入历史表、<b>该账号全部会话失效</b>（免登录流程没有「当前会话」可保留， 规格第 7
 * 章「修改或重置密码后，该账号其他会话全部失效」）、清登录失败计数、写审计 {@code USER_PASSWORD_RESET}（不落新旧口令与哈希）。 用户自选新密码已满足策略，
 * 不再置「首次登录强制改密」标记（该标记用于管理员下发的随机密码，见 E04-T05）。
 *
 * <p>平台端暂无忘记密码入口（pages.md §1 平台端登录页无此链接）：平台账号由平台管理员在「平台账号管理」重置（PLT-ROLE-001），见 open-questions
 * OQ-41。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** 重置验证码的 Redis 命名空间（与登录验证码 login: 隔离）。 */
    static final String RESET_CODE_PREFIX = "reset";

    private final TenantLookupCache tenantLookup;
    private final TenantRoutingDataSource routing;
    private final HospitalDirectory hospitalDirectory;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final AuditLogger auditLogger;
    private final SecurityProperties properties;
    private final SmsCodeStore smsCodeStore;
    private final SmsCodeSender smsCodeSender;
    private final CryptoService cryptoService;

    public PasswordResetService(
            TenantLookupCache tenantLookup,
            TenantRoutingDataSource routing,
            HospitalDirectory hospitalDirectory,
            PasswordEncoder passwordEncoder,
            SessionStore sessionStore,
            LoginRateLimiter rateLimiter,
            AuditLogger auditLogger,
            SecurityProperties properties,
            SmsCodeStore smsCodeStore,
            SmsCodeSender smsCodeSender,
            CryptoService cryptoService) {
        this.tenantLookup = tenantLookup;
        this.routing = routing;
        this.hospitalDirectory = hospitalDirectory;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
        this.properties = properties;
        this.smsCodeStore = smsCodeStore;
        this.smsCodeSender = smsCodeSender;
        this.cryptoService = cryptoService;
    }

    /**
     * 第一步：把重置验证码发到账号绑定手机号。 无论账号是否存在、是否绑定手机号，对调用方都表现为成功（防探测，与 /auth/sms 同口径）； 冷却（60 秒）与每日上限超限返回
     * 42900。
     */
    public void requestHospitalReset(String tenantCode, String username) {
        TenantRecord tenant = tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            // 未知/停用租户：不签发也不触达，但对调用方与正常路径不可区分
            log.info("password reset sms skipped reason=unknown-tenant");
            return;
        }
        if (!routing.hasTenant(tenant.id())) {
            log.error("tenant datasource missing code={}", tenant.code());
            throw new ApiException(500, 50002, "租户数据源未就绪");
        }
        HospitalAccount account =
                TenantContext.callAsTenant(
                        tenant.id(),
                        tenant.code(),
                        () -> hospitalDirectory.find(username).orElse(null));
        if (account == null || !"ENABLED".equals(account.status()) || account.phone() == null) {
            // 静默成功；但同样消耗冷却位，否则「短时间内第二次请求是否 42900」就成了账号存在性探针
            String reason =
                    account == null
                            ? "unknown-user"
                            : !"ENABLED".equals(account.status()) ? "disabled-user" : "no-phone";
            smsCodeStore.issue(
                    probeIdentifier(tenant.code(), username),
                    probeIdentifier(tenant.code(), username));
            log.info("password reset sms skipped reason={}", reason);
            return;
        }
        String phone =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), () -> cryptoService.decrypt(account.phone()));
        String code = smsCodeStore.issue(resetCodeIdentifier(tenant.code(), phone), phone);
        smsCodeSender.sendPasswordResetCode(phone, code);
    }

    /**
     * 第二步：验证码 + 新密码重置。 校验顺序：失败计数 → 租户与账号（统一 40102）→ 验证码（一次一用）→ 密码策略 → 历史密码。 策略与历史的 42200
     * 只在验证码通过后给出，避免借「弱密码是否被报 42200」探测账号存在。
     */
    public void resetHospitalPassword(
            String tenantCode, String username, String code, String newPassword) {
        String bucket = "HR:" + tenantCode + ":" + username.toLowerCase(Locale.ROOT);
        rateLimiter.check(bucket);
        TenantRecord tenant = tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            recordRejection(bucket, null, null, username, "unknown-tenant");
            throw badResetCode();
        }
        if (!routing.hasTenant(tenant.id())) {
            log.error("tenant datasource missing code={}", tenant.code());
            throw new ApiException(500, 50002, "租户数据源未就绪");
        }
        HospitalAccount account =
                TenantContext.callAsTenant(
                        tenant.id(),
                        tenant.code(),
                        () -> hospitalDirectory.find(username).orElse(null));
        if (account == null || !"ENABLED".equals(account.status()) || account.phone() == null) {
            String reason =
                    account == null
                            ? "unknown-user"
                            : !"ENABLED".equals(account.status()) ? "disabled-user" : "no-phone";
            recordRejection(bucket, tenant.id(), tenant.code(), username, reason);
            throw badResetCode();
        }
        String phone =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), () -> cryptoService.decrypt(account.phone()));
        try {
            smsCodeStore.verify(resetCodeIdentifier(tenant.code(), phone), code);
        } catch (ApiException ex) {
            recordRejection(
                    bucket,
                    tenant.id(),
                    tenant.code(),
                    username,
                    ex.code() == 42900 ? "reset-code-throttled" : "bad-reset-code");
            throw ex;
        }
        checkPolicy(newPassword, account.username(), hospitalMinLength(tenant));
        checkHistory(
                newPassword,
                account.passwordHash(),
                TenantContext.callAsTenant(
                        tenant.id(),
                        tenant.code(),
                        () ->
                                hospitalDirectory.passwordHistory(
                                        account.id(), properties.getPasswordHistoryCount() - 1)));
        TenantContext.runAsTenant(
                tenant.id(),
                tenant.code(),
                () ->
                        hospitalDirectory.changePassword(
                                account.id(),
                                passwordEncoder.encode(newPassword),
                                LocalDateTime.now()));
        TenantContext.runAsTenant(
                tenant.id(),
                tenant.code(),
                () ->
                        hospitalDirectory.recordPasswordHistory(
                                account.id(), account.passwordHash()));
        long revoked = sessionStore.revokeAllForUser(sessionOwner(tenant.id(), account.id()), null);
        rateLimiter.clear(bucket);
        TenantContext.runAsTenant(
                tenant.id(),
                tenant.code(),
                () ->
                        auditLogger.record(
                                new AuditEvent(
                                        "USER_PASSWORD_RESET",
                                        account.id(),
                                        "UPDATE",
                                        "sys_user:" + account.username(),
                                        "忘记密码：短信验证通过后自助重置，该账号全部会话已失效（" + revoked + " 个）")));
        log.info(
                "password reset ok tenant={} username={} revokedSessions={}",
                tenant.code(),
                account.username(),
                revoked);
    }

    // ---- 私有步骤 ----

    /** 每租户独立命名空间，且与登录验证码（login:）互不通用。 */
    private static String resetCodeIdentifier(String tenantCode, String phone) {
        return RESET_CODE_PREFIX + ":" + tenantCode + ":" + phone;
    }

    /**
     * 未知账号的探针键：借用 SmsCodeStore 的冷却位（60 秒）与每日上限， 使「快速重发第二次是否 42900」与真实账号不可区分。
     * 该键上签发的验证码永远不会被校验通过（第二步先查账号，查不到直接 40102）。
     */
    private static String probeIdentifier(String tenantCode, String username) {
        return "reset-probe:" + tenantCode + ":" + username.toLowerCase(Locale.ROOT);
    }

    private static ApiException badResetCode() {
        return new ApiException(401, 40102, AuthMessages.BAD_SMS_CODE);
    }

    /** 租户在平台下限之上加严的最小长度（cfg_param 只能更长）。 */
    private int hospitalMinLength(TenantRecord tenant) {
        int override =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), hospitalDirectory::passwordMinLengthOverride);
        return Math.max(properties.getPasswordMinLength(), override);
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

    /** 候选密码命中当前哈希或最近 N-1 个历史哈希即拒绝（与改密同口径）。 */
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

    /** 被拒绝的重置尝试：计失败（连续多次 42900 锁定）、写登录日志（AGENTS.md 第 5 节：被拒绝的操作写审计）。 */
    private void recordRejection(
            String bucket, Long tenantId, String tenantCode, String username, String reason) {
        rateLimiter.recordFailure(bucket);
        log.info(
                "password reset rejected tenant={} username={} reason={}",
                tenantCode,
                username,
                reason);
        if (tenantId == null) {
            TenantContext.runAsPlatform(
                    () -> auditLogger.loginAttempt(username, false, "password-reset-rejected"));
        } else {
            TenantContext.runAsTenant(
                    tenantId,
                    tenantCode,
                    () -> auditLogger.loginAttempt(username, false, "password-reset-rejected"));
        }
    }

    /** 会话吊销用的账号定位（端别 + 租户 + 用户，SessionStore 的用户索引键只取这三个字段）； 免登录流程没有令牌可保留，keepToken 传 null 全部吊销。 */
    private static SessionPrincipal sessionOwner(long tenantId, long userId) {
        return new SessionPrincipal(
                null,
                "HOSPITAL",
                tenantId,
                null,
                null,
                userId,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null);
    }
}
