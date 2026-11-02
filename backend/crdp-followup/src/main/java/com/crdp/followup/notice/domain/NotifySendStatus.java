package com.crdp.followup.notice.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 发送台账状态（ntf_send_log.send_status，A-NTF-006）。 QUEUED 短信待 E09-T05 汇总作业按人合并发送； SENT 已投递（站内信落
 * ntf_message）；FAILED 发送失败（可手动重试回 QUEUED）；SKIPPED 按规则或个人偏好跳过（不发送）。
 */
public enum NotifySendStatus {
    QUEUED,
    SENT,
    FAILED,
    SKIPPED;

    /** 台账状态编码解析；未知编码返回空（查询条件校验用，40001）。 */
    public static Optional<NotifySendStatus> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equals(code.trim()))
                .findFirst();
    }
}
