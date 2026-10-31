package com.crdp.framework.security;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * E04-T04 / PLT-AUTH-002、PLT-AUTH-003：登录短信验证码（Redis）。
 *
 * <p>策略来自 platform-spec 第 7 章安全参数表：6 位数字、有效期 {@code smsCodeTtlSeconds}（默认 5 分钟）、输错 {@code
 * smsMaxVerifyFailures}（默认 5）次作废、重发间隔 {@code smsResendCooldownSeconds}（默认 60 秒）、同一手机号每日 {@code
 * smsDailyLimitPerPhone}（默认 10）条。冷却与每日上限按手机号计数；验证码本体按调用方给的
 * identifier（如「login:租户:手机号」「2fa:challengeId」）隔离，租户之间、登录与双因素之间互不通用。
 *
 * <p>Redis 只存 SHA-256 摘要，不落验证码明文；错误与过期统一 40102，不区分存在性。
 */
@Component
public class SmsCodeStore {

    private static final String TOO_MANY = "尝试次数过多，请稍后再试";

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeStore(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 签发验证码并返回明文（只交给发送通道，不落日志）。超冷却或超每日上限抛 42900。 */
    public String issue(String identifier, String phone) {
        if (redis.opsForValue().get(cooldownKey(phone)) != null) {
            throw new ApiException(429, 42900, TOO_MANY);
        }
        Long sent = redis.opsForValue().increment(dailyKey(phone));
        if (sent != null && sent == 1L) {
            redis.expire(dailyKey(phone), Duration.ofDays(1));
        }
        if (sent != null && sent > properties.getSmsDailyLimitPerPhone()) {
            throw new ApiException(429, 42900, TOO_MANY);
        }
        String code = sixDigits();
        redis.opsForValue()
                .set(
                        codeKey(identifier),
                        digest(code),
                        Duration.ofSeconds(properties.getSmsCodeTtlSeconds()));
        redis.delete(failKey(identifier));
        redis.opsForValue()
                .set(
                        cooldownKey(phone),
                        "1",
                        Duration.ofSeconds(properties.getSmsResendCooldownSeconds()));
        return code;
    }

    /** 校验并消费验证码。错误或过期 40102；连续输错达到上限后作废并 42900。 */
    public void verify(String identifier, String code) {
        String expected = redis.opsForValue().get(codeKey(identifier));
        if (expected == null) {
            throw new ApiException(401, 40102, AuthMessages.BAD_SMS_CODE);
        }
        if (!expected.equals(digest(code))) {
            Long failures = redis.opsForValue().increment(failKey(identifier));
            if (failures != null && failures == 1L) {
                redis.expire(
                        failKey(identifier), Duration.ofSeconds(properties.getSmsCodeTtlSeconds()));
            }
            if (failures != null && failures >= properties.getSmsMaxVerifyFailures()) {
                redis.delete(codeKey(identifier));
                redis.delete(failKey(identifier));
                throw new ApiException(429, 42900, TOO_MANY);
            }
            throw new ApiException(401, 40102, AuthMessages.BAD_SMS_CODE);
        }
        redis.delete(codeKey(identifier));
        redis.delete(failKey(identifier));
    }

    private String sixDigits() {
        // 从 256 位随机值取模，6 位数字（000000–999999），不做可预测计数
        String digits = String.format("%06d", random.nextInt(1_000_000));
        return digits;
    }

    private static String digest(String code) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(sha256.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }

    private static String codeKey(String identifier) {
        return "crdp:sms:code:" + identifier;
    }

    private static String failKey(String identifier) {
        return "crdp:sms:fail:" + identifier;
    }

    private static String cooldownKey(String phone) {
        return "crdp:sms:cd:" + phone;
    }

    private static String dailyKey(String phone) {
        return "crdp:sms:daily:" + phone;
    }
}
