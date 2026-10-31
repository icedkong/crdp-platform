package com.fu.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class DataSourceFactory {

    private DataSourceFactory() {}

    /** 方案要求每池最大 5 个连接。 */
    public static HikariDataSource hikari(
            String poolName, String url, String username, String password) {
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
