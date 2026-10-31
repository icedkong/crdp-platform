package com.crdp.server.auth;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SessionRefresher;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.infra.mapper.PlatformAccountRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * E04-T06 / PLT-PERM-010 权限变更生效：令牌里的权限版本号落后时，按数据库当前状态重算会话内容（角色、 菜单、权限码、数据范围、账号状态）。
 *
 * <p>账号已停用 / 不存在、租户已不可路由 → 返回 empty，会话删除，下一次请求 40100（下次请求即失效）。 草稿构建复用 {@link AuthService}
 * 的登录草稿（同一套「角色菜单 ∩ 已开通模块」口径），再由 {@code withFreshGrants} 保留 令牌与生命周期字段。
 */
@Component
public class SessionPermissionRefresher implements SessionRefresher {

    private static final Logger log = LoggerFactory.getLogger(SessionPermissionRefresher.class);

    private final AuthService authService;
    private final HospitalDirectory hospitalDirectory;
    private final PlatformMapper platformMapper;
    private final TenantLookupCache tenantLookup;

    public SessionPermissionRefresher(
            AuthService authService,
            HospitalDirectory hospitalDirectory,
            PlatformMapper platformMapper,
            TenantLookupCache tenantLookup) {
        this.authService = authService;
        this.hospitalDirectory = hospitalDirectory;
        this.platformMapper = platformMapper;
        this.tenantLookup = tenantLookup;
    }

    @Override
    public Optional<SessionPrincipal> refresh(SessionPrincipal stale) {
        if (stale.hospital()) {
            return refreshHospital(stale);
        }
        if (stale.platform()) {
            return refreshPlatform(stale);
        }
        return Optional.empty();
    }

    private Optional<SessionPrincipal> refreshHospital(SessionPrincipal stale) {
        TenantRecord tenant = tenantLookup.findByCode(stale.tenantCode()).orElse(null);
        if (tenant == null || !tenant.routable()) {
            log.info(
                    "session refresh dropped reason=tenant-unavailable tenant={}",
                    stale.tenantCode());
            return Optional.empty();
        }
        HospitalAccount account =
                TenantContext.callAsTenant(
                        tenant.id(),
                        tenant.code(),
                        () -> hospitalDirectory.findById(stale.userId()).orElse(null));
        if (account == null || !"ENABLED".equals(account.status())) {
            log.info(
                    "session refresh dropped reason=account-unavailable tenant={} userId={}",
                    stale.tenantCode(),
                    stale.userId());
            return Optional.empty();
        }
        return Optional.of(
                stale.withFreshGrants(authService.hospitalDraft(tenant, account, stale.ip())));
    }

    private Optional<SessionPrincipal> refreshPlatform(SessionPrincipal stale) {
        PlatformAccountRow account =
                TenantContext.callAsPlatform(() -> platformMapper.findAccountById(stale.userId()));
        if (account == null || account.getId() == null || !"ENABLED".equals(account.getStatus())) {
            log.info(
                    "session refresh dropped reason=account-unavailable side=PLATFORM userId={}",
                    stale.userId());
            return Optional.empty();
        }
        return Optional.of(stale.withFreshGrants(authService.platformDraft(account, stale.ip())));
    }
}
