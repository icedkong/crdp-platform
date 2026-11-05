package com.crdp.system.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/**
 * E09-T06 验收「内网地址拒绝」：SSRF 防护单元测试。 地址判定全部用字面量 IP（不触发 DNS）与 localhost（/etc/hosts 本地解析）， 避免 CI
 * 无外网时用例漂移。
 */
class WebhookSsrfGuardTest {

    private final WebhookSsrfGuard guard = new WebhookSsrfGuard(false);

    private final WebhookSsrfGuard localTestGuard = new WebhookSsrfGuard(true);

    // ---------------------------------------------------------------- 结构校验

    @Test
    void rejectsNonHttpsScheme() {
        assertRejected("http://hooks.example.com/callback", "非 https 必须拒绝");
        assertRejected("ftp://hooks.example.com/callback", "其他 scheme 同样拒绝");
        assertRejected("javascript:alert(1)", "非 URL 直接拒绝");
        assertRejected("   ", "空地址拒绝");
        assertRejected(null, "null 拒绝");
    }

    @Test
    void rejectsUserinfoAndControlCharacters() {
        assertRejected("https://user:pass@8.8.8.8/callback", "不允许携带用户名密码");
        assertRejected("https://8.8.8.8/cal\rlback", "控制字符（CRLF 走私头）拒绝");
        assertRejected("https://8.8.8.8/ca lback", "空白字符拒绝");
        assertRejected("https://" + "a".repeat(500) + ".com/", "超长地址拒绝");
    }

    // ---------------------------------------------------------------- 内网地址拒绝（验收）

    @Test
    void rejectsLoopbackAndLocalNames() {
        assertRejected("https://localhost/callback", "localhost 走 hosts 解析到回环，必须拒绝");
        assertRejected("https://127.0.0.1/callback", "IPv4 回环拒绝");
        assertRejected("https://127.1.2.3/callback", "整个 127/8 都是回环");
        assertRejected("https://0.0.0.0/callback", "任意本地地址拒绝");
        assertRejected("https://[::1]/callback", "IPv6 回环拒绝");
    }

    @Test
    void rejectsPrivateRanges() {
        assertRejected("https://10.0.0.5/callback", "10/8 拒绝");
        assertRejected("https://10.255.255.255/callback", "10/8 上界");
        assertRejected("https://172.16.0.1/callback", "172.16/12 拒绝");
        assertRejected("https://172.31.255.255/callback", "172.16/12 上界");
        assertRejected("https://192.168.1.10/callback", "192.168/16 拒绝");
        assertRejected("https://169.254.169.254/callback", "链路本地（云元数据端点）拒绝");
        assertRejected("https://100.64.0.1/callback", "运营商级 NAT 段拒绝");
        assertRejected("https://198.18.0.1/callback", "基准测试保留段拒绝");
    }

    @Test
    void rejectsIpv6PrivateAndMappedForms() {
        assertRejected("https://[fc00::1]/callback", "IPv6 唯一本地拒绝");
        assertRejected("https://[fd12:3456::1]/callback", "fd00::/8 同属 fc00::/7");
        assertRejected("https://[fe80::1]/callback", "IPv6 链路本地拒绝");
        assertRejected("https://[::ffff:10.0.0.1]/callback", "IPv4 映射 IPv6 归一后按 IPv4 段拒绝");
        assertRejected("https://[::ffff:127.0.0.1]/callback", "映射回环同样拒绝");
    }

    @Test
    void rejectsMulticastAndAcceptsPublicLiteral() {
        assertRejected("https://224.0.0.1/callback", "组播地址拒绝");
        // 公网字面量（不触发 DNS）应通过结构 + 地址两道闸
        assertDoesNotThrow(() -> guard.validate("https://8.8.8.8/callback"));
        assertDoesNotThrow(() -> guard.validate("https://[2606:4700:4700::1111]/callback"));
    }

    // ---------------------------------------------------------------- 本机联调后门（默认关）

    @Test
    void localTestSwitchAllowsLoopbackAndPlainHttpForIt() {
        assertDoesNotThrow(() -> localTestGuard.validate("https://127.0.0.1:18443/callback"));
        // 本地 HttpServer 起不了 TLS：联调开关放行明文 http（只在本机联调 / IT 打开，默认关）
        assertDoesNotThrow(() -> localTestGuard.validate("http://127.0.0.1:18443/callback"));
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> localTestGuard.validate("ftp://127.0.0.1/x"));
        assertEquals40001(ex);
        assertThrows(
                ApiException.class,
                () -> localTestGuard.validate("https://user:pass@127.0.0.1/x"),
                "联调开关不放开结构校验");
    }

    @Test
    void deliveryRecheckReportsUndeliverableInsteadOfThrowing() {
        assertFalse(guard.isDeliverable("https://127.0.0.1/callback"), "投递前复核内网地址不投");
        assertFalse(guard.isDeliverable("http://8.8.8.8/callback"), "投递前复核非 https 不投");
        assertTrue(guard.isDeliverable("https://8.8.8.8/callback"));
    }

    // ---------------------------------------------------------------- isPrivate 直测（覆盖非字面量构造）

    @Test
    void privateAddressPredicateCoversMappedAndRanges() throws Exception {
        assertTrue(WebhookSsrfGuard.isPrivate(InetAddress.getByName("172.20.1.1")));
        assertTrue(WebhookSsrfGuard.isPrivate(InetAddress.getByName("::ffff:192.168.0.1")));
        assertFalse(WebhookSsrfGuard.isPrivate(InetAddress.getByName("1.1.1.1")));
        assertFalse(WebhookSsrfGuard.isPrivate(InetAddress.getByName("2606:4700::6810:84e5")));
    }

    // ---------------------------------------------------------------- 工具

    private void assertRejected(String url, String because) {
        ApiException ex = assertThrows(ApiException.class, () -> guard.validate(url), because);
        assertEquals40001(ex);
    }

    private static void assertEquals40001(ApiException ex) {
        org.junit.jupiter.api.Assertions.assertEquals(40001, ex.code());
    }
}
