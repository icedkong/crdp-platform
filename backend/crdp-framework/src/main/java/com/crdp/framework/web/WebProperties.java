package com.crdp.framework.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web 层取值口径（G9 / M3 审查，OQ-42 定论）。
 *
 * <p>{@code crdp.web.trust-xff}（默认 **false**）：限流与公开口取客户端 IP 时是否信任 {@code X-Forwarded-For} 首段。默认只信
 * {@code RemoteAddr}——客户端若能直连应用（未经 反代 / WAF 覆盖该头），可以随便换一个转发头刷新限流次数；生产经 ELB / WAF 覆盖并由
 * 部署侧确认后（上线检查单，OQ-42）才置 true。审计记录 IP 的口径见 {@link
 * com.crdp.framework.audit.AuditRequestContext}（与限流不同，保持转发头优先以便追溯）。
 */
@ConfigurationProperties(prefix = "crdp.web")
public class WebProperties {

    /** 是否信任 X-Forwarded-For 首段作为客户端 IP（限流口径；默认 false 只信 RemoteAddr）。 */
    private boolean trustXff = false;

    public boolean isTrustXff() {
        return trustXff;
    }

    public void setTrustXff(boolean trustXff) {
        this.trustXff = trustXff;
    }
}
