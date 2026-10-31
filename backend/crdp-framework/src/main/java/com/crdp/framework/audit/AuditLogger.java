package com.crdp.framework.audit;

/** 审计写入接口。哈希链封存与 OBS 归档在 E08，M0 只追加插入。 */
public interface AuditLogger {

    void record(AuditEvent event);

    void loginAttempt(String username, boolean success, String message);
}
