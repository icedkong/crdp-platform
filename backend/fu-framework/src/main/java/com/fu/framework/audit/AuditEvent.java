package com.fu.framework.audit;

/** 审计事件。detail 不得含口令、令牌或患者明文。 */
public record AuditEvent(
        String eventType, Long actorId, String action, String target, String detail) {}
