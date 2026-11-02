package com.crdp.followup.notice.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 规则列的 CSV 编解码（ntf_rule.channels / recipients）。未知编码直接忽略（老库残留、 平台新增选项不炸租户），保持顺序去重。 */
public final class NotifyRuleCodec {

    private NotifyRuleCodec() {}

    /** 编码集合为 CSV（如 {@code INBOX,SMS}），空集合为空串。 */
    public static String join(List<? extends Enum<?>> values) {
        StringBuilder joined = new StringBuilder();
        for (Enum<?> value : values) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(value.name());
        }
        return joined.toString();
    }

    /** 解析渠道列；未知编码忽略。 */
    public static List<NotifyChannel> parseChannels(String csv) {
        Set<NotifyChannel> parsed = new LinkedHashSet<>();
        for (String token : tokens(csv)) {
            try {
                parsed.add(NotifyChannel.valueOf(token));
            } catch (IllegalArgumentException unknown) {
                // 未知渠道编码忽略（向前兼容）
            }
        }
        return List.copyOf(parsed);
    }

    /** 解析接收人规则列；未知编码忽略。 */
    public static List<RecipientOption> parseRecipients(String csv) {
        Set<RecipientOption> parsed = new LinkedHashSet<>();
        for (String token : tokens(csv)) {
            try {
                parsed.add(RecipientOption.valueOf(token));
            } catch (IllegalArgumentException unknown) {
                // 未知接收人规则编码忽略（向前兼容）
            }
        }
        return List.copyOf(parsed);
    }

    private static List<String> tokens(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
