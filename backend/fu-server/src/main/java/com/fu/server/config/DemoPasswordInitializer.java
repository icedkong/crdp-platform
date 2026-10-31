package com.fu.server.config;

import com.fu.framework.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 只在 local/dev 把演示账号的占位哈希换成 Argon2id。不记录明文。 */
@Component
@Profile({"local", "dev"})
@Order(0)
public class DemoPasswordInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPasswordInitializer.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String password;
    private final String marker;

    public DemoPasswordInitializer(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value("${fu.demo.password}")
                    String password,
            @org.springframework.beans.factory.annotation.Value("${fu.demo.hash-marker}")
                    String marker) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
        this.marker = marker;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hash = passwordEncoder.encode(password);
        int platformRows =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.update(
                                        "UPDATE plt_account SET password_hash = ? WHERE password_hash = ?",
                                        hash,
                                        marker));
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
                    TenantContext.callAsTenant(
                            tenant.id(),
                            tenant.code(),
                            () ->
                                    jdbc.update(
                                            "UPDATE sys_user SET password_hash = ? WHERE password_hash = ?",
                                            hash,
                                            marker));
        }
        log.info("演示口令已初始化 platformRows={} tenantRows={}", platformRows, tenantRows);
    }

    private record TenantRef(long id, String code) {}
}
