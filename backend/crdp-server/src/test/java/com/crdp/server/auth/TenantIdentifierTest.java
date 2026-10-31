package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** E04-T01 / PLT-AUTH-004：租户子域名优先，统一登录页输入的租户编码兜底，均能定位租户。 */
class TenantIdentifierTest {

    private final TenantAccessProperties properties = new TenantAccessProperties();

    private TenantIdentifier identifier() {
        properties.setBaseDomain("crdp.example.com");
        return new TenantIdentifier(properties);
    }

    @Test
    void subdomainWinsOverTheBodyCode() {
        assertEquals(
                "demoa",
                identifier().resolve("demoa.crdp.example.com", "demob"),
                "子域名识别出的租户优先于统一登录页输入的编码");
    }

    @Test
    void bodyCodeIsUsedWhenHostIsTheBaseDomainItself() {
        assertEquals("demoa", identifier().resolve("crdp.example.com", "demoa"));
        assertEquals("demoa", identifier().resolve("crdp.example.com:443", "demoa"));
    }

    @Test
    void bodyCodeIsNormalizedForTheUnifiedLoginPage() {
        assertEquals("demoa", identifier().resolve("127.0.0.1:14173", " DemoA "));
    }

    @Test
    void ipOrLocalhostHostsNeverResolveToATenant() {
        assertNull(identifier().codeFromHost("127.0.0.1"));
        assertNull(identifier().codeFromHost("localhost:8080"));
        assertNull(identifier().codeFromHost(null));
    }

    @Test
    void portsAndCaseAreIgnored() {
        assertEquals("demoa", identifier().codeFromHost("DEMOA.CRDP.EXAMPLE.COM:443"));
    }

    @Test
    void invalidSubdomainsAreRejected() {
        assertNull(identifier().codeFromHost("bad-code!.crdp.example.com"));
        assertNull(identifier().codeFromHost("-demoa.crdp.example.com"));
        assertNull(identifier().codeFromHost("a.b.crdp.example.com"), "多级前缀不是租户编码");
        assertNull(identifier().codeFromHost("other.example.com"), "非平台域名不识别");
    }

    @Test
    void emptyBaseDomainDisablesSubdomainRecognition() {
        TenantAccessProperties unconfigured = new TenantAccessProperties();
        TenantIdentifier identifier = new TenantIdentifier(unconfigured);

        assertNull(identifier.codeFromHost("demoa.crdp.example.com"));
        assertEquals(
                "demoa",
                identifier.resolve("demoa.crdp.example.com", "demoa"),
                "未配置域名时仍可用统一登录页编码定位");
    }
}
