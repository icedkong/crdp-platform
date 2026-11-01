package com.crdp.framework.audit;

/**
 * 审计写入接口（E08-T01 / PLT-AUD-001~004）。四类日志统一走 {@link #append(AuditEntry)}， 规格字段见 {@link
 * AuditEntry}；存储为只追加（INSERT），无修改与删除（PLT-AUD-011）。哈希链封存（PLT-AUD-008/009）在 E08-T02。
 *
 * <p>遗留入口 {@link #record(AuditEvent)} 与 {@link #loginAttempt(String, boolean, String)} 保留为桥接：
 * 自动补全规格字段后写入新口径，旧调用方不感知；新代码一律用 {@link #append(AuditEntry)}。
 */
public interface AuditLogger {

    /** 写一条审计记录。操作者、IP、终端信息、traceId 缺省时自动补全。 */
    void append(AuditEntry entry);

    /**
     * 遗留桥接：写操作日志（M0 事件结构）。事件类型落 event_type，target 拆出 对象类型 / 对象 ID。
     *
     * @deprecated 用 {@link #append(AuditEntry)}（如 {@link AuditEntry#operation(String, String)}）
     */
    @Deprecated
    void record(AuditEvent event);

    /**
     * 遗留桥接：写登录日志。message 作为描述与失败原因。
     *
     * @deprecated 用 {@link AuditEntry#login(String)} 填登录方式、第二因素结果、会话 ID 等扩展字段
     */
    @Deprecated
    void loginAttempt(String username, boolean success, String message);
}
