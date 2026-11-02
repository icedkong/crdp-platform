package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** ntf_rule 一行（E09-T04）。channels / recipients 为 CSV，编解码见 NotifyRuleCodec。 */
public record NotifyRuleRow(
        Long id,
        String eventType,
        boolean enabled,
        Integer advanceDays,
        LocalTime sendTime,
        String channels,
        String recipients,
        String smsTemplateCode,
        Long updatedBy,
        LocalDateTime updatedAt) {}
