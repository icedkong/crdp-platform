package com.crdp.server.notify;

import com.crdp.framework.notify.sms.SmsReceiptHandler;
import com.crdp.framework.notify.sms.TencentSmsProperties;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ClientIpResolver;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 腾讯云短信状态回执回调（E09-T03 验收「回执入库」；平台通道，非医院 / 平台端接口）。
 *
 * <p>路径 {@code POST /api/notify/sms/receipt}：控制台「短信状态回执推送」配置的回调地址，调用方是腾讯云推送器， 不能登录——公开口按
 * {@code @PublicApi} 登记（ApiResourceGuardFilter 放行）、SecurityConfig permitAll， 并按 IP 每小时限流（AGENTS.md
 * 第 6 节：新增公开接口必须限流）。响应体按腾讯云约定返回 {@code {"result":0,"errmsg":"OK"}}， 不走平台统一 {@code ApiResponse} 外壳。
 *
 * <p>回执只含发送流水号（sid）、状态、时间与手机号（入库脱敏）——本身不含患者信息；sid 对不上发送行也入库为 UNMATCHED_RECEIPT，不丢对账依据。
 *
 * <p>来源校验（E22-T10 / 渗透修复自查 PT-SELF-02）：公开口此前只有 IP 限流，任意来源可伪造回执污染对账数据。 配置了回执凭据 （{@code
 * crdp.sms.receipt-auth-username/password}，与腾讯云控制台「状态回执推送」Basic Auth 一致，环境变量注入）后 强制校验 Authorization
 * 头，不匹配 40103（常量时间比较，凭据不进日志）；凭据未配置时保持迁移期行为（仅限流）， 通道启用而凭据缺失在启动时告警（docs/ops/pentest-remediation.md §5
 * 运维清单兜底）。
 */
@RestController
public class SmsReceiptController {

    private static final Logger log = LoggerFactory.getLogger(SmsReceiptController.class);

    /** 回执报文大小上限（一批最多几十条状态，64KB 足够；超限直接拒）。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final SmsReceiptHandler handler;
    private final StringRedisTemplate redis;
    private final TencentSmsProperties properties;
    private final ClientIpResolver clientIps;

    public SmsReceiptController(
            SmsReceiptHandler handler,
            StringRedisTemplate redis,
            TencentSmsProperties properties,
            ClientIpResolver clientIps) {
        this.handler = handler;
        this.redis = redis;
        this.properties = properties;
        this.clientIps = clientIps;
    }

    /** 通道启用而回执凭据未配置 = 公开口只剩限流一道闸：启动告警提醒运维补控制台 + 环境变量配置。 */
    @PostConstruct
    void warnIfReceiptAuthMissing() {
        if (properties.isEnabled() && !properties.receiptAuthConfigured()) {
            log.warn(
                    "短信通道已启用但回执回调凭据未配置（crdp.sms.receipt-auth-username/password），"
                            + "公开回执口仅剩 IP 限流；请在腾讯云控制台配置状态回执 Basic Auth 并经"
                            + " CRDP_SMS_RECEIPT_AUTH_USER / CRDP_SMS_RECEIPT_AUTH_PASSWORD 注入");
        }
    }

    @PublicApi
    @PostMapping(value = "/api/notify/sms/receipt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReceiptAck receive(@RequestBody String rawBody, HttpServletRequest request) {
        rateLimit(clientIps.resolve(request));
        if (properties.receiptAuthConfigured()) {
            requireReceiptAuth(request.getHeader("Authorization"));
        }
        if (rawBody == null || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ApiException(400, 40001, "参数不合法：body");
        }
        int stored = handler.handle(rawBody);
        log.info("sms receipt received stored={}", stored);
        return new ReceiptAck(0, "OK");
    }

    /**
     * 回执 Basic Auth 校验：凭据 Base64 后常量时间比较（用户名:密码 整串），不匹配一律 40103—— 报文不进
     * handler、不落对账表；错误响应不带差异提示（凭据错误 / 缺头同响应，不探测配置）。
     */
    private void requireReceiptAuth(String authorizationHeader) {
        boolean matches = false;
        if (authorizationHeader != null && authorizationHeader.startsWith("Basic ")) {
            String expected =
                    Base64.getEncoder()
                            .encodeToString(
                                    (properties.getReceiptAuthUsername()
                                                    + ":"
                                                    + properties.getReceiptAuthPassword())
                                            .getBytes(StandardCharsets.UTF_8));
            byte[] provided =
                    authorizationHeader
                            .substring("Basic ".length())
                            .trim()
                            .getBytes(StandardCharsets.UTF_8);
            matches = MessageDigest.isEqual(provided, expected.getBytes(StandardCharsets.UTF_8));
        }
        if (!matches) {
            throw new ApiException(401, 40103, "回执回调未授权");
        }
    }

    private void rateLimit(String clientIp) {
        if (clientIp == null) {
            return;
        }
        String key = "crdp:sms-receipt:ip:" + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > properties.getReceiptIpHourlyLimit()) {
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
        }
    }

    /** 腾讯云约定的回执应答：result=0 表示接收成功，非 0 会触发重推。 */
    record ReceiptAck(int result, String errmsg) {}
}
