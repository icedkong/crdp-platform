package com.crdp.followup.notice.infra.mapper;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ntf_setting 一行（E09-T07，迁移 V23；单行表 id=1，种子即默认值）。 免打扰时段与每人每日短信上限由 E09-T05
 * 汇总作业执行；overdue_sms_off_allowed 本任务即生效（个人偏好校验）。本对象不出租户库。
 */
public record NotifySettingRow(
        long id,
        LocalTime dndStart,
        LocalTime dndEnd,
        int smsDailyLimit,
        boolean overdueSmsOffAllowed,
        Long updatedBy,
        LocalDateTime updatedAt) {}
