package com.crdp.framework.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 按 {@link TenantContext} 选择连接池。每院一个 Hikari 池，开通时热注册，停用时注销。 无上下文时不回落到平台库。 */
public class TenantRoutingDataSource extends AbstractDataSource implements DisposableBean {

    private final Map<String, DataSource> dataSources = new ConcurrentHashMap<>();
    private DataSource platform;

    public void registerPlatform(DataSource dataSource) {
        this.platform = dataSource;
        dataSources.put(TenantContext.PLATFORM_KEY, dataSource);
    }

    public void registerTenant(long tenantId, DataSource dataSource) {
        dataSources.put("tenant:" + tenantId, dataSource);
    }

    public void unregisterTenant(long tenantId) {
        DataSource removed = dataSources.remove("tenant:" + tenantId);
        closeQuietly(removed);
    }

    public DataSource platform() {
        if (platform == null) {
            throw new MissingTenantContextException();
        }
        return platform;
    }

    /** 测试与拦截器共用的解析入口。 */
    public DataSource current() {
        String key = TenantContext.lookupKey();
        if (key == null) {
            throw new MissingTenantContextException();
        }
        DataSource dataSource = dataSources.get(key);
        if (dataSource == null) {
            throw new UnknownTenantDataSourceException(key);
        }
        return dataSource;
    }

    public boolean hasTenant(long tenantId) {
        return dataSources.containsKey("tenant:" + tenantId);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current().getConnection(username, password);
    }

    @Override
    public void destroy() {
        dataSources.values().forEach(this::closeQuietly);
        dataSources.clear();
        platform = null;
    }

    private void closeQuietly(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭连接池失败不掩盖业务异常
            }
        }
    }
}
