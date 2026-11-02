package com.crdp.server.notify;

import com.crdp.framework.notify.sms.SmsReceiptHandler;
import com.crdp.framework.notify.sms.TencentSmsProperties;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 */
@RestController
public class SmsReceiptController {

    private static final Logger log = LoggerFactory.getLogger(SmsReceiptController.class);

    /** 回执报文大小上限（一批最多几十条状态，64KB 足够；超限直接拒）。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final SmsReceiptHandler handler;
    private final StringRedisTemplate redis;
    private final TencentSmsProperties properties;

    public SmsReceiptController(
            SmsReceiptHandler handler, StringRedisTemplate redis, TencentSmsProperties properties) {
        this.handler = handler;
        this.redis = redis;
        this.properties = properties;
    }

    @PublicApi
    @PostMapping(value = "/api/notify/sms/receipt", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReceiptAck receive(@RequestBody String rawBody, HttpServletRequest request) {
        rateLimit(clientIp(request));
        if (rawBody == null || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new ApiException(400, 40001, "参数不合法：body");
        }
        int stored = handler.handle(rawBody);
        log.info("sms receipt received stored={}", stored);
        return new ReceiptAck(0, "OK");
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

    /** 反代后取真实 IP：优先 X-Forwarded-For 首段，兜底 remoteAddr（与 HospitalAuthController 同口径）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 腾讯云约定的回执应答：result=0 表示接收成功，非 0 会触发重推。 */
    record ReceiptAck(int result, String errmsg) {}
}
