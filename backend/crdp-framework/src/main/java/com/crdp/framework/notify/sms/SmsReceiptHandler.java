package com.crdp.framework.notify.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 腾讯云短信状态回执解析与入库（E09-T03 验收「回执入库」）。
 *
 * <p>腾讯云控制台配置的回执推送格式随版本演进过（根数组、{@code {"sms_status_callback":[...]}}、 {@code
 * {"sms_status_callback":{"content":[...]}}}），三种都兼容；每条回执按 sid（= SendSms 返回的 SerialNo） 入库 {@link
 * SmsSendLogStore#applyReceipt}——对得上发送行就回填 DELIVERED / RECEIVE_FAILED，对不上也落 UNMATCHED_RECEIPT 不丢。
 * 回执里的手机号只存脱敏值；解析失败跳过该条并记 WARN（不中断整批）。
 */
@Component
public class SmsReceiptHandler {

    private static final Logger log = LoggerFactory.getLogger(SmsReceiptHandler.class);

    private final ObjectMapper mapper;
    private final SmsSendLogStore sendLog;

    public SmsReceiptHandler(ObjectMapper mapper, SmsSendLogStore sendLog) {
        this.mapper = mapper;
        this.sendLog = sendLog;
    }

    /**
     * 处理一次回执推送。
     *
     * @return 成功入库的回执条数（含 UNMATCHED_RECEIPT）
     */
    public int handle(String rawBody) {
        List<JsonNode> items = extractItems(rawBody);
        int stored = 0;
        for (JsonNode item : items) {
            String sid = text(item, "sid");
            if (sid == null || sid.isBlank()) {
                log.warn("短信回执缺少 sid，跳过");
                continue;
            }
            try {
                sendLog.applyReceipt(
                        sid,
                        text(item, "report_status"),
                        text(item, "description") != null
                                ? text(item, "description")
                                : text(item, "errmsg"),
                        text(item, "user_receive_time"),
                        maskMobile(text(item, "mobile")));
                stored++;
            } catch (RuntimeException failure) {
                // 单条入库失败不影响整批（腾讯按 result!=0 重推）
                log.warn("短信回执入库失败 sid 长度={}", sid.length(), failure);
            }
        }
        return stored;
    }

    private List<JsonNode> extractItems(String rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (IOException badJson) {
            log.warn("短信回执报文不是合法 JSON，忽略");
            return List.of();
        }
        if (root == null) {
            return List.of();
        }
        if (root.isArray()) {
            return toList(root);
        }
        JsonNode callback = root.path("sms_status_callback");
        if (callback.isArray()) {
            return toList(callback);
        }
        if (callback.isObject()) {
            JsonNode content = callback.path("content");
            if (content.isArray()) {
                return toList(content);
            }
        }
        // 单对象形态
        if (root.hasNonNull("sid")) {
            List<JsonNode> single = new ArrayList<>(1);
            single.add(root);
            return single;
        }
        log.warn("短信回执报文不含可识别结构，忽略");
        return List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile == null ? null : "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
