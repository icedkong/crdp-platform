package com.crdp.server.platform;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户侧配额拦截（E05-T09 / PLT-ORG-010）：达到用户数上限禁止新建或启用用户； 存储上限经 {@code TenantStorageQuota}
 * 端口供文件服务在上传前校验（达到上限禁止上传）。
 *
 * <p><b>连接口径</b>（与 {@link PlatformConfigCache} 同款）：直接持平台连接池（ {@link
 * TenantRoutingDataSource#platform()}）的 JdbcTemplate，不走路由——拦截发生在租户事务里（Spring
 * 已把路由数据源的连接绑到线程），走路由会错误复用租户连接去查平台表。
 *
 * <p><b>读不到配额不拦截</b>：平台库瞬时不可达或无租户上下文（如平台侧作业）时按不限处理并告警日志，
 * 配额是商业限制不是安全边界，不因控制面抖动阻断租户正常操作；上限本身由平台侧维护接口保证写入。
 */
@Component
public class TenantQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaGuard.class);

    private final JdbcTemplate platform;

    @org.springframework.beans.factory.annotation.Autowired
    public TenantQuotaGuard(TenantRoutingDataSource routing) {
        this(new JdbcTemplate(routing.platform()));
    }

    /** 测试注入替身用（包内可见）。 */
    TenantQuotaGuard(JdbcTemplate platform) {
        this.platform = platform;
    }

    /** 当前租户的用户数上限；不限 / 平台上下文 / 读不到时 null。 */
    public Integer currentUserLimit() {
        return column("user_limit", Integer.class);
    }

    /** 当前租户的存储上限（字节）；不限 / 平台上下文 / 读不到时 null。 */
    public Long currentStorageLimitBytes() {
        return column("storage_limit_bytes", Long.class);
    }

    /** 新建 / 启用 / 导入用户前的配额校验（PLT-ORG-010）：启用中用户数达到上限即 40901。 tenantDb 为当前租户库连接（计数与拦截同事务可见）。 */
    public void assertUserQuotaAvailable(JdbcTemplate tenantDb, String action) {
        Integer limit = currentUserLimit();
        if (limit == null) {
            return;
        }
        Long enabled =
                tenantDb.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE status = 'ENABLED'", Long.class);
        if (enabled != null && enabled >= limit) {
            throw new ApiException(409, 40901, "已达用户数上限（" + limit + "），无法" + action + "，请联系平台调整配额");
        }
    }

    private <T> T column(String column, Class<T> type) {
        return TenantContext.current()
                .filter(state -> !state.platform())
                .map(
                        state -> {
                            try {
                                return platform.queryForObject(
                                        "SELECT " + column + " FROM plt_tenant WHERE id = ?",
                                        type,
                                        state.tenantId());
                            } catch (DataAccessException ex) {
                                log.warn("读取租户配额失败，按不限处理（tenantId={}）", state.tenantId(), ex);
                                return null;
                            }
                        })
                .orElse(null);
    }
}
