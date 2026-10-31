package com.crdp.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 租户访问地址（PLT-TEN-015：租户子域名 {租户编码}.平台域名，与统一登录页 + 租户编码并存）。 */
@ConfigurationProperties(prefix = "crdp.tenant")
public class TenantAccessProperties {

    /** 平台域名（不含协议与端口），如 crdp.example.com。留空表示未启用子域名识别，仅用登录页编码。 */
    private String baseDomain = "";

    public String getBaseDomain() {
        return baseDomain;
    }

    public void setBaseDomain(String baseDomain) {
        this.baseDomain = baseDomain;
    }
}
