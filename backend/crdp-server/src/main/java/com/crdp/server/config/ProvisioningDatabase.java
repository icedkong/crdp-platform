package com.crdp.server.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * 在特权连接上执行开通 DDL（建库、建账号、授权），全部幂等。连接懒加载：生产未配置 crdp.provisioning.* 时不影响启动，只在真正执行开通时报错。 E05-T08
 * 起注销销毁也在本连接执行（DROP DATABASE / DROP USER）并做销毁后校验（{@link #query}）。
 */
@Component
public class ProvisioningDatabase {

    private final ProvisioningProperties properties;
    private volatile JdbcTemplate jdbc;

    public ProvisioningDatabase(ProvisioningProperties properties) {
        this.properties = properties;
    }

    /** 执行单条幂等 DDL。 */
    public synchronized void execute(String sql) {
        jdbc().execute(sql);
    }

    /** 特权连接上的单值整数查询（销毁后校验：库 / 账号已不存在）。 */
    public synchronized int query(String sql) {
        Integer value = jdbc().queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            if (properties.getJdbcUrl().isBlank()
                    || properties.getUsername().isBlank()
                    || properties.getTenantJdbcUrlTemplate().isBlank()) {
                throw new IllegalStateException(
                        "开通 / 销毁编排需要 crdp.provisioning.jdbc-url / username /"
                                + " tenant-jdbc-url-template");
            }
            jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    properties.getJdbcUrl(),
                                    properties.getUsername(),
                                    properties.getPassword()));
        }
        return jdbc;
    }
}
