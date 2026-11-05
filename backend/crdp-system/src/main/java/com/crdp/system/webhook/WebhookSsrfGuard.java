package com.crdp.system.webhook;

import com.crdp.framework.web.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Webhook 回调地址防护（E09-T06；tech-plan §8「入侵防范」：Webhook 地址禁止内网与回环地址，防 SSRF）。
 *
 * <p>校验在两处执行：配置保存时（{@code validate}，验收「内网地址拒绝」）与每次投递前（{@code revalidate}， 缓解保存后 DNS 被改指向内网的 TOCTOU
 * / DNS rebinding）。规则：
 *
 * <ul>
 *   <li>只接受 <b>https</b>（tech-plan §9：Webhook 只接受 HTTPS）；
 *   <li>不允许 userinfo（{@code https://user:pass@host}）与非常规端口写法由 URI 解析兜底；
 *   <li>主机名解析出的<b>全部</b>地址必须是公网单播地址——回环（127/8、::1）、任意本地（0.0.0.0/8）、
 *       链路本地（169.254/16、fe80::/10，含云厂商元数据端点）、站点本地（10/8、172.16/12、 192.168/16）、IPv6
 *       唯一本地（fc00::/7）、IPv4 映射 IPv6（::ffff:a.b.c.d）、组播任一命中即拒绝；
 *   <li>解析不出的主机名拒绝（不给「先保存投递时再失败」的机会）。
 * </ul>
 *
 * <p>{@code crdp.webhook.allow-private-addresses=true}（默认 false）只供本机联调 / 集成测试把接收方起在 127.0.0.1，
 * 生产不得打开——与 {@code crdp.sms.enabled} 同口径的显式开发后门。
 */
public class WebhookSsrfGuard {

    /** 40001 的失败类别（detail 只写类别，不回显完整 URL）。 */
    public static final String REJECT_NON_HTTPS = "回调地址只支持 HTTPS";

    public static final String REJECT_PRIVATE = "回调地址不能是内网、回环或链路本地地址";

    public static final String REJECT_UNRESOLVED = "回调地址的主机名无法解析";

    private final boolean allowPrivateForLocalTest;

    public WebhookSsrfGuard(boolean allowPrivateForLocalTest) {
        this.allowPrivateForLocalTest = allowPrivateForLocalTest;
    }

    /** 配置保存口：URL 结构与解析结果全部过闸，违规 40001。 */
    public void validate(String url) {
        URI uri = parse(url);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw bad("回调地址缺少主机名");
        }
        if (uri.getRawUserInfo() != null || url.contains("@")) {
            throw bad("回调地址不允许携带用户名密码");
        }
        String scheme =
                uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (allowPrivateForLocalTest) {
            // 本机联调 / 集成测试开关（默认关）：放行内网地址与明文 http（本地 HttpServer 起不了 TLS），
            // 只保结构合法。生产默认路径走下方两道闸。
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw bad(REJECT_NON_HTTPS);
            }
            return;
        }
        if (!"https".equals(scheme)) {
            throw bad(REJECT_NON_HTTPS);
        }
        requirePublicHost(host);
    }

    /** 投递前复核（保存后 DNS 可能被改指向内网）：结构与地址段再过一遍，失败按投递失败记台账，不抛配置错。 */
    public boolean isDeliverable(String url) {
        try {
            validate(url);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw bad("回调地址不能为空");
        }
        String trimmed = url.trim();
        if (trimmed.length() > 500) {
            throw bad("回调地址不超过 500 个字符");
        }
        // 控制字符 / 空白防走私头（CRLF 注入），与 URI 解析异常同口径 40001
        if (trimmed.codePoints().anyMatch(cp -> cp <= 0x20 || cp == 0x7F)) {
            throw bad("回调地址含有非法字符");
        }
        try {
            return URI.create(trimmed);
        } catch (IllegalArgumentException malformed) {
            throw bad("回调地址格式不正确");
        }
    }

    private static void requirePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolved) {
            throw bad(REJECT_UNRESOLVED);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                // 主机名解析出任一内网 / 保留地址即拒绝（防「夹带」一条内网 A 记录绕过）
                throw bad(REJECT_PRIVATE);
            }
        }
    }

    /** 内网 / 保留 / 回环 / 链路本地 / 组播判定（IPv4 与 IPv6 同口径）。 */
    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        // IPv6 唯一本地 fc00::/7（isSiteLocalAddress 只覆盖 fec0::/10 站点本地，已废弃但保留判定）
        if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
            return true;
        }
        // IPv4 映射 IPv6 ::ffff:a.b.c.d —— 先归一再按 IPv4 段判定
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            byte[] v4 = Arrays.copyOfRange(raw, 12, 16);
            return isPrivateV4(v4[0] & 0xFF, v4[1] & 0xFF);
        }
        if (raw.length == 4) {
            return isPrivateV4(raw[0] & 0xFF, raw[1] & 0xFF);
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }

    private static boolean isPrivateV4(int firstOctet, int secondOctet) {
        // 0/8（本网络）、10/8、100.64/10（运营商级 NAT，也拿不到公网服务）、127/8、169.254/16、
        // 172.16/12、192.168/16、198.18/15（基准测试保留）
        return firstOctet == 0
                || firstOctet == 10
                || firstOctet == 127
                || (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127)
                || (firstOctet == 169 && secondOctet == 254)
                || (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31)
                || (firstOctet == 192 && secondOctet == 168)
                || (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19));
    }

    private static ApiException bad(String reason) {
        return new ApiException(400, 40001, reason);
    }
}
