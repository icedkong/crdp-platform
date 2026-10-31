package com.crdp.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

/** 每租户连接池的统一入口：方案要求每池最大 5 个连接。注册表与启动装配共用，测试可注入替身。 */
@Component
public class DataSourceFactory {

    public HikariDataSource hikari(String poolName, String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setInitializationFailTimeout(5000);
        return new HikariDataSource(config);
    }
}
