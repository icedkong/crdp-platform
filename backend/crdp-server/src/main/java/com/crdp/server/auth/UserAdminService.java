package com.crdp.server.auth;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.LoginRateLimiter;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * E04-T05 / PLT-AUTH-007：管理员解锁登录锁定（权限 tenant:user:reset，docs/api/system.md §2）。 锁定计数在 Redis（按「端 +
 * 租户编码 + 账号」分桶），解锁即清桶；用户管理界面属 E06+，本任务先提供接口并写审计。
 */
@Service
public class UserAdminService {

    private final HospitalDirectory hospitalDirectory;
    private final LoginRateLimiter rateLimiter;
    private final AuditLogger auditLogger;

    public UserAdminService(
            HospitalDirectory hospitalDirectory,
            LoginRateLimiter rateLimiter,
            AuditLogger auditLogger) {
        this.hospitalDirectory = hospitalDirectory;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
    }

    /** 在当前租户内解锁指定用户，返回其账号名（审计目标）。 */
    public String unlockHospitalUser(long userId) {
        HospitalAccount account =
                hospitalDirectory
                        .findById(userId)
                        .orElseThrow(() -> new ApiException(404, 40400, "用户不存在或不在本院"));
        // 桶名按「端:租户:账号」重建（与登录侧同一口径）
        String loginBucket =
                "H:" + currentTenantCode() + ":" + account.username().toLowerCase(Locale.ROOT);
        rateLimiter.unlock(loginBucket);
        auditLogger.record(
                new AuditEvent(
                        "USER_UNLOCK",
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        "UPDATE",
                        "sys_user:" + account.username(),
                        "管理员解锁登录锁定"));
        // PLT-AUD-002「解锁」：账号生命周期事件同时落登录日志（操作者由写入服务补全）
        auditLogger.append(
                AuditEntry.login(account.username())
                        .action("UNLOCK")
                        .description("管理员解锁登录锁定")
                        .success()
                        .build());
        return account.username();
    }

    private static String currentTenantCode() {
        return TenantContext.current()
                .map(state -> state.tenantCode() == null ? "" : state.tenantCode())
                .orElse("");
    }
}
