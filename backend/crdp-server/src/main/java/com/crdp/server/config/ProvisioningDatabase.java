package com.crdp.server.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/** 在特权连接上执行开通 DDL（建库、建账号、授权），全部幂等。连接懒加载：生产未配置 crdp.provisioning.* 时不影响启动，只在真正执行开通时报错。 */
@Component
public class ProvisioningDatabase {

    private final ProvisioningProperties properties;
    private volatile JdbcTemplate jdbc;

    public ProvisioningDatabase(ProvisioningProperties properties) {
        this.properties = properties;
    }

    /** 执行单条幂等 DDL。 */
    public synchronized void execute(String sql) {
        if (jdbc == null) {
            if (properties.getJdbcUrl().isBlank()
                    || properties.getUsername().isBlank()
                    || properties.getTenantJdbcUrlTemplate().isBlank()) {
                throw new IllegalStateException(
                        "开通编排需要 crdp.provisioning.jdbc-url / username / tenant-jdbc-url-template");
            }
            jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    properties.getJdbcUrl(),
                                    properties.getUsername(),
                                    properties.getPassword()));
        }
        jdbc.execute(sql);
    }
}
