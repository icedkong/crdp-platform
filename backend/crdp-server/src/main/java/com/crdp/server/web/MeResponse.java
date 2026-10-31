package com.crdp.server.web;

import java.util.List;

/**
 * 当前会话信息。E04-T05 / PLT-AUTH-006、010：mustChangePassword 为真时前端跳 /password/change， 其余接口返回
 * 40305（MustChangePasswordFilter）；passwordExpiresInDays 在到期提醒窗口（默认 7 天）内返回，供前端提示。
 */
public record MeResponse(
        long userId,
        String username,
        String displayName,
        String sessionType,
        String tenantCode,
        String tenantName,
        List<String> roles,
        long permissionVersion,
        boolean mustChangePassword,
        Integer passwordExpiresInDays) {

    /** 到期提醒窗口内的剩余天数（向上取整），窗口外返回 null。nowEpochSecond 便于测试注入。 */
    static Integer expiryDays(Long expiresAtEpochSecond, long nowEpochSecond, int remindDays) {
        if (expiresAtEpochSecond == null) {
            return null;
        }
        long remaining = expiresAtEpochSecond - nowEpochSecond;
        long days = (remaining + 86_399) / 86_400;
        if (days > remindDays) {
            return null;
        }
        return (int) days;
    }
}
