package com.crdp.server.auth;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.AuthMessages;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.security.TokenGenerator;
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

@Service
public class AuthService {

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

    public AuthService(
            PlatformMapper platformMapper,
            TenantLookupCache tenantLookup,
            HospitalDirectory hospitalDirectory,
            PasswordEncoder passwordEncoder,
            SessionStore sessionStore,
            TokenGenerator tokenGenerator,
            LoginRateLimiter rateLimiter,
            AuditLogger auditLogger,
            TenantRoutingDataSource routing) {
        this.platformMapper = platformMapper;
        this.tenantLookup = tenantLookup;
        this.hospitalDirectory = hospitalDirectory;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
        this.routing = routing;
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
        List<String> opened =
                TenantContext.callAsPlatform(() -> platformMapper.openedModules(tenant.id()));
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("HOSPITAL"));
        Granted granted = grant(menus, account.menuIds(), opened);
        SessionPrincipal principal =
                new SessionPrincipal(
                        tokenGenerator.generate(),
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
        sessionStore.save(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsTenant(
                tenant.id(), tenant.code(), () -> auditLogger.loginAttempt(username, true, "ok"));
        return new LoginResult(principal.token(), "Bearer", sessionStore.ttl().toSeconds());
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
        List<String> roles =
                TenantContext.callAsPlatform(() -> platformMapper.accountRoles(accountId));
        List<Long> menuIds =
                TenantContext.callAsPlatform(() -> platformMapper.accountMenuIds(accountId));
        List<MenuRow> menus =
                TenantContext.callAsPlatform(() -> platformMapper.listMenus("PLATFORM"));
        Granted granted = grant(menus, menuIds, List.of("base", "followup"));
        SessionPrincipal principal =
                new SessionPrincipal(
                        tokenGenerator.generate(),
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
        sessionStore.save(principal);
        rateLimiter.clear(bucket);
        TenantContext.runAsPlatform(() -> auditLogger.loginAttempt(username, true, "ok"));
        return new LoginResult(principal.token(), "Bearer", sessionStore.ttl().toSeconds());
    }

    public void logout(String token) {
        sessionStore.delete(token);
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

    public record LoginResult(String token, String tokenType, long expiresInSeconds) {}

    private record Granted(List<Long> menuIds, List<String> permissionCodes) {}
}
