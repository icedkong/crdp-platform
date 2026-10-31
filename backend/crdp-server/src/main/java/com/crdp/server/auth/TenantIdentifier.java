package com.crdp.server.auth;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 登录时识别租户（PLT-AUTH-004 / E04-T01）：租户子域名优先（{租户编码}.{平台域名}，PLT-TEN-015），
 * 其次统一登录页输入的租户编码；识别结果交给登录流程路由到该院库校验账号。令牌与子域名的一致性 校验属会话链路（E04-T02）。
 */
@Component
public class TenantIdentifier {

    /** 子域即租户编码：小写字母开头，小写字母、数字、下划线（平台规格"租户编码"）。 */
    private static final Pattern TENANT_CODE = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private final String baseDomain;

    public TenantIdentifier(TenantAccessProperties properties) {
        this.baseDomain =
                properties.getBaseDomain() == null
                        ? ""
                        : properties.getBaseDomain().trim().toLowerCase(Locale.ROOT);
    }

    /** 子域名识别出的编码优先；否则用统一登录页输入的编码（去空白并小写化）；都无法识别返回 null， 由登录流程按"租户、账号或密码不正确"统一拒绝。 */
    public String resolve(String host, String bodyTenantCode) {
        String fromHost = codeFromHost(host);
        if (fromHost != null) {
            return fromHost;
        }
        if (bodyTenantCode == null) {
            return null;
        }
        String normalized = bodyTenantCode.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** 从 Host 提取租户编码；不是本平台子域名、前缀不是合法编码时返回 null。 */
    public String codeFromHost(String host) {
        if (baseDomain.isEmpty() || host == null) {
            return null;
        }
        String value = host.toLowerCase(Locale.ROOT);
        int port = value.indexOf(':');
        if (port >= 0) {
            value = value.substring(0, port);
        }
        String suffix = "." + baseDomain;
        if (!value.endsWith(suffix)) {
            return null;
        }
        String sub = value.substring(0, value.length() - suffix.length());
        return TENANT_CODE.matcher(sub).matches() ? sub : null;
    }
}
