package com.crdp.server.auth;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.AuthMessages;
import com.crdp.framework.security.LoginRateLimiter;
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
import com.crdp.platform.menu.MenuRow;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录用例：账号密码（E04-T02）、平台端独立登录（E04-T03）、短信验证码登录与双因素（E04-T04 / PLT-AUTH-002、003）。
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
            SecurityProperties securityProperties) {
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
    }

    public LoginResult loginHospital(String tenantCode, String username, String password) {
        String bucket = "H:" + tenantCode + ":" + username.toLowerCase(Locale.ROOT);
        rateLimiter.check(bucket);
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
            SessionPrincipal draft = hospitalDraft(tenant, account);
            rateLimiter.clear(bucket);
            return challengeTwoFactor("HOSPITAL", tenant, username, draft, account.phone());
        }
        return issueHospitalSession(tenant, account, bucket);
    }

    /** E04-T04 / PLT-AUTH-002：发送医院端登录短信验证码。无论手机号是否绑定都返回成功（防探测）。 */
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
    public LoginResult loginHospitalWithSms(String tenantCode, String phone, String code) {
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
        return issueHospitalSession(tenant, account, bucket);
    }

    public LoginResult loginPlatform(String username, String password) {
        String bucket = "P:" + username.toLowerCase(Locale.ROOT);
        rateLimiter.check(bucket);
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
            SessionPrincipal draft = platformDraft(account);
            rateLimiter.clear(bucket);
            return challengeTwoFactor("PLATFORM", null, username, draft, account.getPhone());
        }
        return issuePlatformSession(account, bucket);
    }

    /** E04-T04 / PLT-AUTH-003：双因素第二步。医院端与平台端共用，端别由 challenge 里暂挂的会话决定。 */
    public LoginResult verifyTwoFactor(String challengeId, String code) {
        TwoFactorChallengeStore.PendingLogin pending =
                challengeStore
                        .find(challengeId)
                        .orElseThrow(() -> new ApiException(401, 40100, "未登录或登录已失效"));
        // challenge 保持可用直到验证码输错上限或验证成功，避免一次手误就要重来整个密码步骤
        smsCodeStore.verify("2fa:" + challengeId, code);
        SessionPrincipal principal = pending.principal();
        SessionPrincipal withToken =
                new SessionPrincipal(
                        tokenGenerator.generate(),
                        principal.sessionType(),
                        principal.tenantId(),
                        principal.tenantCode(),
                        principal.tenantName(),
                        principal.userId(),
                        principal.username(),
                        principal.displayName(),
                        principal.roles(),
                        principal.menuIds(),
                        principal.permissionCodes(),
                        principal.openedModules(),
                        principal.permissionVersion(),
                        principal.scope());
        challengeStore.consume(challengeId);
        sessionStore.save(withToken);
        if (withToken.hospital()) {
            TenantContext.runAsTenant(
                    withToken.tenantId(),
                    withToken.tenantCode(),
                    () -> auditLogger.loginAttempt(pending.username(), true, "2fa-ok"));
        } else {
            TenantContext.runAsPlatform(
                    () -> auditLogger.loginAttempt(pending.username(), true, "2fa-ok"));
        }
        return LoginResult.issued(withToken.token(), "Bearer", sessionStore.ttl().toSeconds());
    }

    public void logout(String token) {
        sessionStore.delete(token);
    }

    // ---- 私有步骤 ----

    private LoginResult issueHospitalSession(
            TenantRecord tenant, HospitalAccount account, String bucket) {
        SessionPrincipal principal =
                withToken(hospitalDraft(tenant, account), tokenGenerator.generate());
        sessionStore.save(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsTenant(
                tenant.id(),
                tenant.code(),
                () -> auditLogger.loginAttempt(account.username(), true, "ok"));
        return LoginResult.issued(principal.token(), "Bearer", sessionStore.ttl().toSeconds());
    }

    private LoginResult issuePlatformSession(PlatformAccountRow account, String bucket) {
        SessionPrincipal principal = withToken(platformDraft(account), tokenGenerator.generate());
        sessionStore.save(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsPlatform(
                () -> auditLogger.loginAttempt(account.getUsername(), true, "ok"));
        return LoginResult.issued(principal.token(), "Bearer", sessionStore.ttl().toSeconds());
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

    private SessionPrincipal hospitalDraft(TenantRecord tenant, HospitalAccount account) {
        List<String> opened =
                TenantContext.callAsPlatform(() -> platformMapper.openedModules(tenant.id()));
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("HOSPITAL"));
        Granted granted = grant(menus, account.menuIds(), opened);
        return new SessionPrincipal(
                null,
                "HOSPITAL",
                tenant.id(),
                tenant.code(),
                tenant.name(),
                account.id(),
                account.username(),
                account.displayName(),
                account.roles(),
                granted.menuIds(),
                granted.permissionCodes(),
                opened,
                1L,
                account.scope());
    }

    private SessionPrincipal platformDraft(PlatformAccountRow account) {
        long accountId = account.getId();
        List<String> roles =
                TenantContext.callAsPlatform(() -> platformMapper.accountRoles(accountId));
        List<Long> menuIds =
                TenantContext.callAsPlatform(() -> platformMapper.accountMenuIds(accountId));
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("PLATFORM"));
        Granted granted = grant(menus, menuIds, List.of("base", "followup"));
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
                1L,
                ScopeSnapshot.allowAll());
    }

    private static SessionPrincipal withToken(SessionPrincipal draft, String token) {
        return new SessionPrincipal(
                token,
                draft.sessionType(),
                draft.tenantId(),
                draft.tenantCode(),
                draft.tenantName(),
                draft.userId(),
                draft.username(),
                draft.displayName(),
                draft.roles(),
                draft.menuIds(),
                draft.permissionCodes(),
                draft.openedModules(),
                draft.permissionVersion(),
                draft.scope());
    }

    private String phoneIdx(String phone) {
        // 盲索引必须在租户上下文里算（密钥在租户库）
        return cryptoService.blindIndex(HOSPITAL_PHONE_PURPOSE, phone);
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
        rateLimiter.recordFailure(bucket);
        log.info(
                "login rejected side={} tenant={} username={} reason={}",
                side,
                tenantCode,
                username,
                reason);
        if (tenantId == null) {
            TenantContext.runAsPlatform(
                    () -> auditLogger.loginAttempt(username, false, "rejected"));
        } else {
            TenantContext.runAsTenant(
                    tenantId,
                    tenantCode,
                    () -> auditLogger.loginAttempt(username, false, "rejected"));
        }
        return new ApiException(401, 40101, AuthMessages.BAD_CREDENTIALS);
    }

    private static Granted grant(
            List<MenuRow> menus, List<Long> grantedIds, List<String> openedModules) {
        Set<Long> wanted = new HashSet<>(grantedIds);
        Set<String> opened = new HashSet<>(openedModules);
        List<MenuRow> effective =
                menus.stream()
                        .filter(row -> wanted.contains(row.getId()))
                        .filter(row -> opened.contains(row.getModuleCode()))
                        .toList();
        List<Long> ids = effective.stream().map(MenuRow::getId).toList();
        List<String> codes =
                effective.stream()
                        .map(MenuRow::getPermCode)
                        .filter(Objects::nonNull)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        return new Granted(ids, codes);
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

    private record Granted(List<Long> menuIds, List<String> permissionCodes) {}
}
