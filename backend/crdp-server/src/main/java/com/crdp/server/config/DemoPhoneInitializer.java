package com.crdp.server.config;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 只在 local/dev：把种子里「PENDING_DEMO_PHONE:手机号」标记换成信封加密密文并回填盲索引（SQL 里算不了 HMAC）。与 {@link
 * DemoPasswordInitializer} 同一套路；演示号码均为 1390000xxxx 虚构段（E04-T04）。 不记录明文。
 */
@Component
@Profile({"local", "dev"})
@Order(1)
public class DemoPhoneInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPhoneInitializer.class);

    private static final String MARKER_PREFIX = "PENDING_DEMO_PHONE:";
    private static final String HOSPITAL_PURPOSE = "sys-user-phone";
    private static final String PLATFORM_PURPOSE = "plt-account-phone";

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;

    public DemoPhoneInitializer(JdbcTemplate jdbc, CryptoService cryptoService) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int platformRows = TenantContext.callAsPlatform(this::migratePlatform);
        List<TenantRef> tenants =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.query(
                                        "SELECT id, code FROM plt_tenant WHERE status IN ('ENABLED', 'TRIAL')",
                                        (rs, row) ->
                                                new TenantRef(
                                                        rs.getLong("id"), rs.getString("code"))));
        int tenantRows = 0;
        for (TenantRef tenant : tenants) {
            tenantRows +=
                    TenantContext.callAsTenant(tenant.id(), tenant.code(), this::migrateTenant);
        }
        log.info("演示手机号已初始化 platformRows={} tenantRows={}", platformRows, tenantRows);
    }

    private int migratePlatform() {
        return migrate("plt_account", PLATFORM_PURPOSE);
    }

    private int migrateTenant() {
        return migrate("sys_user", HOSPITAL_PURPOSE);
    }

    private int migrate(String table, String purpose) {
        List<PhoneRef> pending =
                jdbc.query(
                        "SELECT id, phone FROM " + table + " WHERE phone LIKE ?",
                        (rs, row) -> new PhoneRef(rs.getLong("id"), rs.getString("phone")),
                        MARKER_PREFIX + "%");
        int updated = 0;
        for (PhoneRef ref : pending) {
            String phone = ref.phone().substring(MARKER_PREFIX.length());
            String cipher = cryptoService.encrypt(purpose, phone);
            String blindIndex = cryptoService.blindIndex(purpose, phone);
            updated +=
                    jdbc.update(
                            "UPDATE " + table + " SET phone = ?, phone_idx = ? WHERE id = ?",
                            cipher,
                            blindIndex,
                            ref.id());
        }
        return updated;
    }

    private record PhoneRef(long id, String phone) {}

    private record TenantRef(long id, String code) {}
}
