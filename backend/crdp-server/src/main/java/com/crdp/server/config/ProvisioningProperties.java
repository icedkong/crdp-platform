package com.crdp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开通编排用的特权数据库连接（建库、建账号、授权）。运行账号 crdp_app 没有这些权限；本地用 docker-compose 的 root，生产注入独立的迁移账号（技术方案"运行账号 /
 * 迁移账号拆分"）。租户库 JDBC 地址用模板拼接（生产 RDS 域名与 SSL 参数不同）。
 */
@ConfigurationProperties(prefix = "crdp.provisioning")
public class ProvisioningProperties {

    /** 特权连接（本地 root；生产为最小权限的迁移账号）。 */
    private String jdbcUrl = "";

    private String username = "";

    private String password = "";

    /** 租户库 JDBC 地址模板，{db} 为库名占位符，如 jdbc:mysql://host:3306/{db}?useSSL=false。 */
    private String tenantJdbcUrlTemplate = "";

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTenantJdbcUrlTemplate() {
        return tenantJdbcUrlTemplate;
    }

    public void setTenantJdbcUrlTemplate(String tenantJdbcUrlTemplate) {
        this.tenantJdbcUrlTemplate = tenantJdbcUrlTemplate;
    }
}
