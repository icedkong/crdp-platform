package com.crdp.server.config;

import com.crdp.framework.tenant.TenantContext;
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

/** 只在 local/dev/demo 把演示账号的占位哈希换成 Argon2id。不记录明文。demo 仅用于虚构数据。 */
@Component
@Profile({"local", "dev", "demo"})
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
            @org.springframework.beans.factory.annotation.Value("${crdp.demo.password}")
                    String password,
            @org.springframework.beans.factory.annotation.Value("${crdp.demo.hash-marker}")
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
            tenantRows += migrateTenant(tenant.id(), tenant.code());
        }
        log.info("演示口令已初始化 platformRows={} tenantRows={}", platformRows, tenantRows);
    }

    /**
     * 单租户的占位哈希替换（E06-T12）：启动扫描之外，运行中开通的租户由开通编排在迁移后立即调用
     * （TenantOnboardingService），否则种子演示账号要等到下次重启才能登录。口令编码由本组件完成， 调用方不接触口令配置。
     *
     * @return 替换行数
     */
    public int migrateTenant(long tenantId, String code) {
        String hash = passwordEncoder.encode(password);
        return TenantContext.callAsTenant(
                tenantId,
                code,
                () ->
                        jdbc.update(
                                "UPDATE sys_user SET password_hash = ? WHERE password_hash = ?",
                                hash,
                                marker));
    }

    private record TenantRef(long id, String code) {}
}
