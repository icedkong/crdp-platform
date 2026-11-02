package com.crdp.framework.notify.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 腾讯云短信 API 客户端（E09-T03 / PLT-CFG-005：HTTPS 调用 SendSms，平台统一账号、签名与模板）。
 *
 * <p>按腾讯云 API 3.0 的 TC3-HMAC-SHA256 规范自行签名（不引入 SDK：仓库依赖保持克制，签名只有十来行）。 异常只带服务商错误码与类别，
 * <b>不带手机号与模板参数</b>。HTTP 传输经 {@link Transport} 抽象，测试注入桩即可离线验证请求与解析。
 */
@Component
public class TencentSmsClient {

    /** 可替换的 HTTP 传输（POST 返回响应体文本），生产为 JDK HttpClient，测试用桩。 */
    @FunctionalInterface
    public interface Transport {
        String post(String url, Map<String, String> headers, String body) throws Exception;
    }

    private static final String SERVICE = "sms";
    private static final String API_VERSION = "2021-01-11";
    private static final String ACTION = "SendSms";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final DateTimeFormatter UTC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TencentSmsProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Transport transport;

    /** Spring 装配：JDK HttpClient 传输 + 系统时钟（另有包内测试构造器，须标明装配用哪个）。 */
    @Autowired
    public TencentSmsClient(TencentSmsProperties properties, ObjectMapper mapper) {
        this(properties, mapper, Clock.systemDefaultZone(), defaultTransport(properties));
    }

    TencentSmsClient(
            TencentSmsProperties properties,
            ObjectMapper mapper,
            Clock clock,
            Transport transport) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.transport = transport;
    }

    private static Transport defaultTransport(TencentSmsProperties properties) {
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                        .build();
        return (url, headers, body) -> {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                            .header("Content-Type", headers.get("Content-Type"))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            body, StandardCharsets.UTF_8));
            headers.forEach(
                    (name, value) -> {
                        if (!"Content-Type".equalsIgnoreCase(name)) {
                            request.header(name, value);
                        }
                    });
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        };
    }

    /**
     * 调用 SendSms 发一条短信，返回流水号（SerialNo，回执关联键）。
     *
     * @param phoneE164 E.164 号码（如 +8613800000000）
     * @param signName 签名内容（模板级或通道默认）
     * @param providerTemplateId 腾讯云模板 ID
     * @param params 模板参数（已过敏感信息白名单）
     */
    public String send(
            String phoneE164, String signName, String providerTemplateId, List<String> params)
            throws SmsChannelException {
        Instant now = clock.instant();
        String body = buildBody(phoneE164, signName, providerTemplateId, params);
        Map<String, String> headers = signedHeaders(now, body);
        String url = "https://" + properties.getEndpoint() + "/";
        String responseBody;
        try {
            responseBody = transport.post(url, headers, body);
        } catch (Exception failure) {
            // 网络 / 超时 / 非 200：不带请求体（内含手机号）进异常
            throw new SmsChannelException(
                    "PROVIDER_UNREACHABLE",
                    "腾讯云短信接口调用失败：" + failure.getClass().getSimpleName(),
                    failure);
        }
        return parseSerialNo(responseBody);
    }

    // ------------------------------------------------------------------ 请求

    private Map<String, String> signedHeaders(Instant now, String body) {
        long timestamp = now.getEpochSecond();
        String date = UTC_DATE.format(now.atZone(ZoneOffset.UTC));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Host", properties.getEndpoint());
        headers.put("X-TC-Action", ACTION);
        headers.put("X-TC-Timestamp", String.valueOf(timestamp));
        headers.put("X-TC-Version", API_VERSION);
        headers.put("X-TC-Region", properties.getRegion());
        headers.put("Authorization", authorization(timestamp, date, body));
        return headers;
    }

    private String authorization(long timestamp, String date, String body) {
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String canonicalRequest =
                "POST\n/\n\n"
                        + "content-type:"
                        + CONTENT_TYPE
                        + "\nhost:"
                        + properties.getEndpoint()
                        + "\n\ncontent-type;host\n"
                        + sha256Hex(body);
        String stringToSign =
                "TC3-HMAC-SHA256\n"
                        + timestamp
                        + "\n"
                        + credentialScope
                        + "\n"
                        + sha256Hex(canonicalRequest);
        byte[] secretDate =
                hmacSha256(
                        ("TC3" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, SERVICE);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = hex(hmacSha256(secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential="
                + properties.getSecretId()
                + "/"
                + credentialScope
                + ", SignedHeaders=content-type;host, Signature="
                + signature;
    }

    private String buildBody(
            String phoneE164, String signName, String providerTemplateId, List<String> params) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode phones = root.putArray("PhoneNumberSet");
        phones.add(phoneE164);
        root.put("SmsSdkAppId", properties.getSdkAppId());
        root.put("SignName", signName);
        root.put("TemplateId", providerTemplateId);
        if (params != null && !params.isEmpty()) {
            ArrayNode paramArray = root.putArray("TemplateParamSet");
            params.forEach(paramArray::add);
        }
        return root.toString();
    }

    // ------------------------------------------------------------------ 响应

    private String parseSerialNo(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException badJson) {
            throw new SmsChannelException("PROVIDER_BAD_RESPONSE", "腾讯云短信响应不是合法 JSON");
        }
        JsonNode response = root.path("Response");
        JsonNode error = response.path("Error");
        if (!error.isMissingNode()) {
            throw new SmsChannelException(
                    "PROVIDER_REJECTED",
                    "腾讯云短信拒绝发送：" + error.path("Code").asText(error.path("code").asText("UNKNOWN")));
        }
        JsonNode status = response.path("SendStatusSet").path(0);
        String code = status.path("Code").asText("");
        if (!"Ok".equals(code)) {
            throw new SmsChannelException(
                    "PROVIDER_REJECTED", "腾讯云短信发送失败：" + (code.isBlank() ? "UNKNOWN" : code));
        }
        String serialNo = status.path("SerialNo").asText("");
        if (serialNo.isBlank()) {
            throw new SmsChannelException("PROVIDER_BAD_RESPONSE", "腾讯云短信响应缺少流水号");
        }
        return serialNo;
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("HmacSHA256 不可用", failure);
        }
    }

    private static String sha256Hex(String data) {
        try {
            return hex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
