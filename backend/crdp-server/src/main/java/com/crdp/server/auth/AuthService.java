package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.AuthMessages;
import com.crdp.framework.security.CaptchaStore;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.security.TwoFactorChallengeStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAccountRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.server.platform.TenantScopeService;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录用例：账号密码（E04-T02）、平台端独立登录（E04-T03）、短信验证码登录与双因素（E04-T04 / PLT-AUTH-002、003）。
 *
 * <p>E04-T05 / PLT-AUTH-006、007、010：密码登录在连续失败 3 次后必须携带图形验证码、连续失败 5 次锁定 30 分钟（{@link
 * LoginRateLimiter} + {@link CaptchaStore}，失败提示统一 40101 不外露原因，data 带剩余次数与验证码要求）； 初始 / 重置 /
 * 到期密码在会话里带 mustChangePassword=true，除改密 / 登出 / me 外一律 40305（MustChangePasswordFilter）。
 *
 * <p>E04-T06 / PLT-AUTH-008、PLT-PERM-010：会话带登录 IP、空闲窗口（医院端 30 分钟、租户可经 cfg_param 加严； 平台端 15
 * 分钟）与当前权限版本号；并发会话默认多端，租户经 cfg_param security.session.single-login=true 改为单端登录（新登录顶掉旧会话）。权限版本号由
 * {@link PermissionVersionStore} 维护， 授权变化后递增，旧令牌下一次请求即按新权限重算。
 *
 * <p>双因素：平台账号按配置强制（PLT-AUTH-005）；医院端按租户 cfg_param「security.two-factor.roles」配置的角色强制。
 * 密码第一步通过后不发会话令牌，只返回 5 分钟有效的一次性 challengeId，短信验证码通过后（/auth/2fa/verify）才发令牌。
 * 短信验证码登录本身就是「持有手机」凭据，不再叠加第二因素。
 */
@Service
public class AuthService {

    /** 租户库账号手机号加密用途（信封加密 + 盲索引）。 */
    static final String HOSPITAL_PHONE_PURPOSE = "sys-user-phone";

    /** 平台库账号手机号加密用途。 */
    static final String PLATFORM_PHONE_PURPOSE = "plt-account-phone";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final PlatformMapper platformMapper;
    private final TenantLookupCache tenantLookup;
    private final HospitalDirectory hospitalDirectory;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;
    private final TokenGenerator tokenGenerator;
    private final LoginRateLimiter rateLimiter;
    private final AuditLogger auditLogger;
    private final TenantRoutingDataSource routing;
    private final SmsCodeStore smsCodeStore;
    private final TwoFactorChallengeStore challengeStore;
    private final SmsCodeSender smsCodeSender;
    private final CryptoService cryptoService;
    private final SecurityProperties securityProperties;
    private final CaptchaStore captchaStore;
    private final PermissionVersionStore permissionVersions;
    private final TenantScopeService scopeService;
    private final EffectivePermissionService effectivePermissions;

    public AuthService(
            PlatformMapper platformMapper,
            TenantLookupCache tenantLookup,
            HospitalDirectory hospitalDirectory,
            PasswordEncoder passwordEncoder,
            SessionStore sessionStore,
            TokenGenerator tokenGenerator,
            LoginRateLimiter rateLimiter,
            AuditLogger auditLogger,
            TenantRoutingDataSource routing,
            SmsCodeStore smsCodeStore,
            TwoFactorChallengeStore challengeStore,
            SmsCodeSender smsCodeSender,
            CryptoService cryptoService,
            SecurityProperties securityProperties,
            CaptchaStore captchaStore,
            PermissionVersionStore permissionVersions,
            TenantScopeService scopeService,
            EffectivePermissionService effectivePermissions) {
        this.platformMapper = platformMapper;
        this.tenantLookup = tenantLookup;
        this.hospitalDirectory = hospitalDirectory;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
        this.routing = routing;
        this.smsCodeStore = smsCodeStore;
        this.challengeStore = challengeStore;
        this.smsCodeSender = smsCodeSender;
        this.cryptoService = cryptoService;
        this.securityProperties = securityProperties;
        this.captchaStore = captchaStore;
        this.permissionVersions = permissionVersions;
        this.scopeService = scopeService;
        this.effectivePermissions = effectivePermissions;
    }

    public LoginResult loginHospital(
            String tenantCode, String username, String password, CaptchaInput captcha, String ip) {
        return loginHospital(tenantCode, username, password, captcha, ip, null);
    }

    /**
     * @param tenantIdentifyMethod 审计字段（PLT-AUD-002）：SUBDOMAIN / INPUT / null（未知）
     */
    public LoginResult loginHospital(
            String tenantCode,
            String username,
            String password,
            CaptchaInput captcha,
            String ip,
            String tenantIdentifyMethod) {
        String bucket = "H:" + tenantCode + ":" + username.toLowerCase(Locale.ROOT);
        rateLimiter.check(bucket);
        requireCaptchaIfNeeded(bucket, captcha);
        TenantRecord tenant = tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            throw rejected(
                    bucket,
                    "HOSPITAL",
                    null,
                    null,
                    username,
                    tenant == null ? "unknown-tenant" : "inactive-tenant");
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
        if (account == null
                || !"ENABLED".equals(account.status())
                || !passwordMatches(password, account.passwordHash())) {
            String reason = account == null ? "unknown-user" : "bad-password";
            if (account != null && !"ENABLED".equals(account.status())) {
                reason = "disabled-user";
            }
            throw rejected(bucket, "HOSPITAL", tenant.id(), tenant.code(), username, reason);
        }
        List<String> twoFactorRoles =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), hospitalDirectory::twoFactorRoles);
        boolean twoFactor = twoFactorRoles.stream().anyMatch(account.roles()::contains);
        if (twoFactor) {
            if (account.phone() == null) {
                // 角色要求双因素但未绑定手机号：按统一口径拒绝，原因只进日志与审计摘要
                throw rejected(
                        bucket,
                        "HOSPITAL",
                        tenant.id(),
                        tenant.code(),
                        username,
                        "2fa-without-phone");
            }
            SessionPrincipal draft = hospitalDraft(tenant, account, ip);
            rateLimiter.clear(bucket);
            return challengeTwoFactor("HOSPITAL", tenant, username, draft, account.phone());
        }
        return issueHospitalSession(tenant, account, bucket, ip, "PASSWORD", tenantIdentifyMethod);
    }

    /** E04-T04 / PLT-AUTH-002：发送医院端登录短信验证码。无论手机号是否已绑定都返回成功（防探测）。 */
    public void sendHospitalLoginSms(String tenantCode, String phone) {
        TenantRecord tenant = tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            // 未知/停用租户：不签发也不触达，但对调用方与正常路径不可区分
            log.info("sms send skipped reason=unknown-tenant");
            return;
        }
        String code = smsCodeStore.issue(loginCodeIdentifier(tenantCode, phone), phone);
        smsCodeSender.sendLoginCode(phone, code);
    }

    /** E04-T04 / PLT-AUTH-002：短信验证码登录（手机号已绑定且本院唯一）。 */
    public LoginResult loginHospitalWithSms(
            String tenantCode, String phone, String code, String ip) {
        return loginHospitalWithSms(tenantCode, phone, code, ip, null);
    }

    /**
     * @param tenantIdentifyMethod 审计字段（PLT-AUD-002）：SUBDOMAIN / INPUT / null（未知）
     */
    public LoginResult loginHospitalWithSms(
            String tenantCode, String phone, String code, String ip, String tenantIdentifyMethod) {
        String bucket = "HS:" + tenantCode + ":" + phone;
        rateLimiter.check(bucket);
        // 先校验验证码再查账号：验证码错误与手机号未绑定互不可区分
        smsCodeStore.verify(loginCodeIdentifier(tenantCode, phone), code);
        TenantRecord tenant = tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            throw rejected(
                    bucket,
                    "HOSPITAL",
                    null,
                    null,
                    maskedPhone(phone),
                    tenant == null ? "unknown-tenant" : "inactive-tenant");
        }
        if (!routing.hasTenant(tenant.id())) {
            log.error("tenant datasource missing code={}", tenant.code());
            throw new ApiException(500, 50002, "租户数据源未就绪");
        }
        String phoneIdx =
                TenantContext.callAsTenant(tenant.id(), tenant.code(), () -> phoneIdx(phone));
        HospitalAccount account =
                TenantContext.callAsTenant(
                        tenant.id(),
                        tenant.code(),
                        () -> hospitalDirectory.findByPhoneIdx(phoneIdx).orElse(null));
        if (account == null || !"ENABLED".equals(account.status())) {
            throw rejected(
                    bucket,
                    "HOSPITAL",
                    tenant.id(),
                    tenant.code(),
                    maskedPhone(phone),
                    account == null ? "unknown-phone" : "disabled-user");
        }
        return issueHospitalSession(tenant, account, bucket, ip, "SMS", tenantIdentifyMethod);
    }

    public LoginResult loginPlatform(
            String username, String password, CaptchaInput captcha, String ip) {
        String bucket = "P:" + username.toLowerCase(Locale.ROOT);
        rateLimiter.check(bucket);
        requireCaptchaIfNeeded(bucket, captcha);
        PlatformAccountRow account =
                TenantContext.callAsPlatform(() -> platformMapper.findAccount(username));
        if (account == null
                || account.getId() == null
                || !"ENABLED".equals(account.getStatus())
                || !passwordMatches(password, account.getPasswordHash())) {
            throw rejected(
                    bucket,
                    "PLATFORM",
                    null,
                    null,
                    username,
                    account == null ? "unknown-user" : "bad-password");
        }
        long accountId = account.getId();
        if (securityProperties.isPlatformTwoFactorEnabled()) {
            if (account.getPhone() == null) {
                throw rejected(bucket, "PLATFORM", null, null, username, "2fa-without-phone");
            }
            SessionPrincipal draft = platformDraft(account, ip);
            rateLimiter.clear(bucket);
            return challengeTwoFactor("PLATFORM", null, username, draft, account.getPhone());
        }
        return issuePlatformSession(account, bucket, ip);
    }

    /** E04-T04 / PLT-AUTH-003：双因素第二步。医院端与平台端共用，端别由 challenge 里暂挂的会话决定。 */
    public LoginResult verifyTwoFactor(String challengeId, String code) {
        TwoFactorChallengeStore.PendingLogin pending =
                challengeStore
                        .find(challengeId)
                        .orElseThrow(() -> new ApiException(401, 40100, "未登录或登录已失效"));
        // challenge 保持可用直到验证码输错上限或验证成功，避免一次手误就要重来整个密码步骤
        smsCodeStore.verify("2fa:" + challengeId, code);
        SessionPrincipal principal = pending.principal().withToken(tokenGenerator.generate());
        challengeStore.consume(challengeId);
        sessionStore.save(principal);
        enforceConcurrentSessionPolicy(principal);
        if (principal.hospital()) {
            TenantContext.runAsTenant(
                    principal.tenantId(),
                    principal.tenantCode(),
                    () ->
                            appendLoginAudit(
                                    principal,
                                    pending.username(),
                                    "双因素验证通过，登录成功",
                                    null,
                                    "PASS",
                                    "PASSWORD"));
        } else {
            TenantContext.runAsPlatform(
                    () ->
                            appendLoginAudit(
                                    principal,
                                    pending.username(),
                                    "双因素验证通过，登录成功",
                                    null,
                                    "PASS",
                                    "PASSWORD"));
        }
        return LoginResult.issued(
                principal.token(), "Bearer", sessionStore.effectiveTtl(principal).toSeconds());
    }

    /** 登出（PLT-AUD-002「登出」）：先取会话写登录日志，再删除令牌。 */
    public void logout(String token) {
        SessionPrincipal principal = sessionStore.find(token).orElse(null);
        sessionStore.delete(token);
        if (principal == null) {
            return;
        }
        if (principal.hospital()) {
            TenantContext.runAsTenant(
                    principal.tenantId(),
                    principal.tenantCode(),
                    () -> appendLogoutAudit(principal));
        } else {
            TenantContext.runAsPlatform(() -> appendLogoutAudit(principal));
        }
    }

    // ---- 私有步骤 ----

    /**
     * E04-T05 / PLT-AUTH-001、007：连续失败 3 次后必须携带图形验证码。 验证码先于口令校验，输错与过期统一 40102
     * 且计入失败（防暴力尝试），此时不区分口令对错，避免借验证码错误探测口令。
     */
    private void requireCaptchaIfNeeded(String bucket, CaptchaInput captcha) {
        if (!rateLimiter.captchaRequired(bucket)) {
            return;
        }
        String username = bucket.substring(bucket.lastIndexOf(':') + 1);
        try {
            captchaStore.verify(
                    captcha == null ? null : captcha.captchaId(),
                    captcha == null ? null : captcha.code());
        } catch (ApiException ex) {
            rateLimiter.recordFailure(bucket);
            log.info("login rejected reason=bad-captcha");
            // 审计第 4 节：登录失败事件全部落审计；此时租户尚未解析，按平台口径记（与未知租户的 rejected 一致）
            TenantContext.runAsPlatform(
                    () -> auditLogger.loginAttempt(username, false, "bad-captcha"));
            throw ex;
        }
    }

    private LoginResult issueHospitalSession(
            TenantRecord tenant, HospitalAccount account, String bucket, String ip) {
        return issueHospitalSession(tenant, account, bucket, ip, "PASSWORD", null);
    }

    private LoginResult issueHospitalSession(
            TenantRecord tenant,
            HospitalAccount account,
            String bucket,
            String ip,
            String loginMethod,
            String tenantIdentifyMethod) {
        SessionPrincipal principal =
                hospitalDraft(tenant, account, ip).withToken(tokenGenerator.generate());
        sessionStore.save(principal);
        enforceConcurrentSessionPolicy(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsTenant(
                tenant.id(),
                tenant.code(),
                () ->
                        appendLoginAudit(
                                principal,
                                account.username(),
                                "登录成功",
                                tenantIdentifyMethod,
                                "SKIP",
                                loginMethod));
        return LoginResult.issued(
                principal.token(), "Bearer", sessionStore.effectiveTtl(principal).toSeconds());
    }

    private LoginResult issuePlatformSession(PlatformAccountRow account, String bucket, String ip) {
        SessionPrincipal principal =
                platformDraft(account, ip).withToken(tokenGenerator.generate());
        sessionStore.save(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsPlatform(
                () ->
                        appendLoginAudit(
                                principal,
                                account.getUsername(),
                                "登录成功",
                                null,
                                "SKIP",
                                "PASSWORD"));
        return LoginResult.issued(
                principal.token(), "Bearer", sessionStore.effectiveTtl(principal).toSeconds());
    }

    /**
     * E04-T06 / PLT-AUTH-008 并发会话策略：默认允许同一账号多端登录； 租户配置 cfg_param security.session.single-login=true
     * 后单端登录，新登录使旧会话全部下线（当前会话保留）。
     */
    private void enforceConcurrentSessionPolicy(SessionPrincipal principal) {
        if (!principal.hospital()) {
            return; // 平台端默认多端，单端开关随平台安全策略（E06+）再接
        }
        boolean singleLogin =
                TenantContext.callAsTenant(
                        principal.tenantId(),
                        principal.tenantCode(),
                        hospitalDirectory::singleLoginEnabled);
        if (singleLogin) {
            long revoked = sessionStore.revokeAllForUser(principal, principal.token());
            if (revoked > 0) {
                log.info(
                        "single-login kicked old sessions tenant={} username={} count={}",
                        principal.tenantCode(),
                        principal.username(),
                        revoked);
            }
        }
    }

    private LoginResult challengeTwoFactor(
            String side,
            TenantRecord tenant,
            String username,
            SessionPrincipal draft,
            String phoneCipher) {
        String challengeId = challengeStore.create(username, draft);
        String phone =
                tenant == null
                        ? TenantContext.callAsPlatform(() -> cryptoService.decrypt(phoneCipher))
                        : TenantContext.callAsTenant(
                                tenant.id(),
                                tenant.code(),
                                () -> cryptoService.decrypt(phoneCipher));
        String code = smsCodeStore.issue("2fa:" + challengeId, phone);
        smsCodeSender.sendLoginCode(phone, code);
        log.info(
                "login challenged side={} tenant={} username={} challengeIssued=true",
                side,
                tenant == null ? null : tenant.code(),
                username);
        return LoginResult.twoFactor(challengeId, List.of("SMS"));
    }

    /** 医院端会话草稿（登录与权限重算共用）：授权快照 + 登录 IP + 生命周期 + 当前权限版本号。 */
    SessionPrincipal hospitalDraft(TenantRecord tenant, HospitalAccount account, String ip) {
        // E06-T06 / PLT-ROLE-006：用户最终权限 = 已开通模块 ∩ 可分配范围 ∩ 角色并集（EffectivePermissionService 统一口径）。
        // 会话层交集是兜底：租户库不可达导致自动收回被跳过时，超范围授权也在下次重算时失效。
        EffectivePermissionService.Granted granted =
                effectivePermissions.hospitalGrants(tenant.id(), account);
        long now = Instant.now().getEpochSecond();
        return new SessionPrincipal(
                null,
                "HOSPITAL",
                tenant.id(),
                tenant.code(),
                hospitalDisplayName(tenant),
                account.id(),
                account.username(),
                account.displayName(),
                account.roles(),
                granted.menuIds(),
                granted.permissionCodes(),
                granted.openedModules(),
                permissionVersions.current("HOSPITAL", tenant.id(), account.id()),
                account.scope(),
                mustChangePassword(account.mustChangePassword(), account.passwordUpdatedAt()),
                passwordExpiresAt(account.passwordUpdatedAt()),
                ip,
                now,
                now,
                hospitalIdleSeconds(tenant, account));
    }

    /** 平台端会话草稿（登录与权限重算共用）。平台端空闲超时固定 15 分钟（等保参数表）。 */
    SessionPrincipal platformDraft(PlatformAccountRow account, String ip) {
        long accountId = account.getId();
        List<String> roles =
                TenantContext.callAsPlatform(() -> platformMapper.accountRoles(accountId));
        List<Long> menuIds =
                TenantContext.callAsPlatform(() -> platformMapper.accountMenuIds(accountId));
        EffectivePermissionService.Granted granted = effectivePermissions.platformGrants(menuIds);
        boolean mustFlag =
                Boolean.TRUE.equals(account.getMustChangePassword())
                        || passwordExpired(account.getPasswordUpdatedAt());
        long now = Instant.now().getEpochSecond();
        return new SessionPrincipal(
                null,
                "PLATFORM",
                null,
                null,
                null,
                accountId,
                account.getUsername(),
                account.getDisplayName(),
                roles,
                granted.menuIds(),
                granted.permissionCodes(),
                List.of("base"),
                permissionVersions.current("PLATFORM", null, accountId),
                ScopeSnapshot.allowAll(),
                mustFlag,
                passwordExpiresAt(account.getPasswordUpdatedAt()),
                ip,
                now,
                now,
                securityProperties.getPlatformSessionIdleSeconds());
    }

    /**
     * 医院端空闲超时：平台默认 30 分钟，租户可经 cfg_param security.session.idle-minutes
     * 加严（只能更短，PLT-AUTH-006「租户策略只能比平台下限更严」同口径）。
     */
    private long hospitalIdleSeconds(TenantRecord tenant, HospitalAccount account) {
        int override =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), hospitalDirectory::sessionIdleOverrideSeconds);
        if (override <= 0) {
            return securityProperties.getSessionIdleSeconds();
        }
        return Math.min(securityProperties.getSessionIdleSeconds(), override);
    }

    /** PLT-AUTH-006：密码有效期 90 天，到期强制修改（与初始 / 重置标记同口径进会话）。 */
    private boolean mustChangePassword(boolean flagged, LocalDateTime passwordUpdatedAt) {
        return flagged || passwordExpired(passwordUpdatedAt);
    }

    private boolean passwordExpired(LocalDateTime passwordUpdatedAt) {
        if (passwordUpdatedAt == null) {
            // 未知改密时间不强行拦截（历史账号迁移时的兜底），由标记位决定
            return false;
        }
        return Duration.between(passwordUpdatedAt, LocalDateTime.now()).toDays()
                >= securityProperties.getPasswordValidDays();
    }

    /** 密码到期时间（epoch 秒），me 据此算「到期前 7 天提醒」；未知改密时间为 null。 */
    private Long passwordExpiresAt(LocalDateTime passwordUpdatedAt) {
        if (passwordUpdatedAt == null) {
            return null;
        }
        return passwordUpdatedAt
                .plusDays(securityProperties.getPasswordValidDays())
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
    }

    private String phoneIdx(String phone) {
        // 盲索引必须在租户上下文里算（密钥在租户库）
        return cryptoService.blindIndex(HOSPITAL_PHONE_PURPOSE, phone);
    }

    /**
     * 会话里的医院名称（E06-T11 / PLT-CFG-008）：租户个性化「显示名称」优先， 未配置回落平台库租户名称。水印（姓名（账号）· 医院名称 ·
     * 预览时间）与顶栏用户信息据此展示； 改名后重新登录或会话权限重算时生效。
     */
    private String hospitalDisplayName(TenantRecord tenant) {
        String override =
                TenantContext.callAsTenant(
                        tenant.id(), tenant.code(), hospitalDirectory::brandDisplayName);
        return override == null || override.isBlank() ? tenant.name() : override;
    }

    private static String loginCodeIdentifier(String tenantCode, String phone) {
        // 每租户独立命名空间：A 院的验证码不能在 B 院用（不提供跨租户按手机号找账号）
        return "login:" + tenantCode + ":" + phone;
    }

    /** 审计与日志里的手机号一律脱敏（AGENTS.md 第 5 节）。 */
    static String maskedPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private boolean passwordMatches(String raw, String hash) {
        if (hash == null || hash.equals("PENDING_DEMO_HASH")) {
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    private ApiException rejected(
            String bucket,
            String side,
            Long tenantId,
            String tenantCode,
            String username,
            String reason) {
        boolean lockedNow = rateLimiter.recordFailure(bucket);
        log.info(
                "login rejected side={} tenant={} username={} reason={}",
                side,
                tenantCode,
                username,
                reason);
        Runnable auditWrite =
                () -> {
                    // E08-T01 / PLT-AUD-002：失败原因按规格落扩展字段 fail_reason
                    auditLogger.loginAttempt(username, false, reason);
                    if (lockedNow) {
                        // 连续失败达到阈值触发锁定：登录日志记 LOCK 事件（PLT-AUD-002「锁定」）
                        auditLogger.append(
                                AuditEntry.login(username)
                                        .action("LOCK")
                                        .description("连续失败达到阈值，账号锁定")
                                        .success()
                                        .build());
                    }
                };
        if (tenantId == null) {
            TenantContext.runAsPlatform(auditWrite);
        } else {
            TenantContext.runAsTenant(tenantId, tenantCode, auditWrite);
        }
        // 提示不外露失败原因；data 只带计数派生信息（防探测：未知账号同样计数、同样提示）
        return new ApiException(
                401,
                40101,
                AuthMessages.BAD_CREDENTIALS,
                Map.of(
                        "captchaRequired", rateLimiter.captchaRequired(bucket),
                        "remainingAttempts", rateLimiter.remainingAttempts(bucket)));
    }

    /**
     * 登录成功 / 登出 / 双因素通过的登录日志（PLT-AUD-002 扩展字段： 登录方式、租户识别方式、第二因素结果、会话 ID）。 须在正确的租户 /
     * 平台上下文里调用，由写入服务决定落租户库还是平台库。
     */
    private void appendLoginAudit(
            SessionPrincipal principal,
            String username,
            String description,
            String tenantIdentifyMethod,
            String secondFactorResult,
            String loginMethod) {
        auditLogger.append(
                AuditEntry.login(username)
                        .success()
                        .description(description)
                        .userId(principal.userId())
                        .displayName(principal.displayName())
                        .userType(principal.sessionType())
                        .roleCodes(principal.roles())
                        .ip(principal.ip())
                        .loginMethod(loginMethod)
                        .tenantIdentifyMethod(tenantIdentifyMethod)
                        .secondFactorResult(secondFactorResult)
                        .sessionId(principal.sessionId())
                        .build());
    }

    private void appendLogoutAudit(SessionPrincipal principal) {
        auditLogger.append(
                AuditEntry.login(principal.username())
                        .action("LOGOUT")
                        .description("登出")
                        .success()
                        .userId(principal.userId())
                        .displayName(principal.displayName())
                        .userType(principal.sessionType())
                        .roleCodes(principal.roles())
                        .sessionId(principal.sessionId())
                        .build());
    }

    /**
     * 登录结果：直接通过时是 token；需要双因素时 token 为空，改发 challengeId 与可用方式 （docs/api/auth.md：login 需要双因素时返回 {
     * challengeId, methods[] } 而不是 token）。
     */
    public record LoginResult(
            String token,
            String tokenType,
            Long expiresInSeconds,
            String challengeId,
            List<String> methods) {

        public static LoginResult issued(String token, String tokenType, long expiresInSeconds) {
            return new LoginResult(token, tokenType, expiresInSeconds, null, null);
        }

        public static LoginResult twoFactor(String challengeId, List<String> methods) {
            return new LoginResult(null, null, null, challengeId, methods);
        }
    }

    /** E04-T05 / PLT-AUTH-001、007：密码登录可携带的图形验证码（连续失败 3 次后必填）。 */
    public record CaptchaInput(String captchaId, String code) {}
}
