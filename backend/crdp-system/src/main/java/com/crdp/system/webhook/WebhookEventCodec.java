package com.crdp.system.webhook;

import java.util.ArrayList;
import java.util.List;

/** sys_webhook.events 的 CSV 编解码（订阅事件集合，与 ntf_rule 的 channels 同法）。 */
final class WebhookEventCodec {

    private WebhookEventCodec() {}

    static String join(List<WebhookEventType> events) {
        StringBuilder csv = new StringBuilder();
        for (WebhookEventType type : events) {
            if (!csv.isEmpty()) {
                csv.append(',');
            }
            csv.append(type.name());
        }
        return csv.toString();
    }

    static List<WebhookEventType> parse(String csv) {
        List<WebhookEventType> events = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return events;
        }
        for (String code : csv.split(",")) {
            WebhookEventType.fromCode(code.trim())
                    .filter(type -> !events.contains(type))
                    .ifPresent(events::add);
        }
        return events;
    }

    static List<String> split(String csv) {
        List<String> codes = new ArrayList<>();
        for (WebhookEventType type : parse(csv)) {
            codes.add(type.name());
        }
        return codes;
    }
}
