package com.crdp.framework.audit;

/**
 * 一条审计记录写入成功后的应用事件（E08-T05 / PLT-AUD-012 安全告警入口）。
 *
 * <p>由 {@link JdbcAuditLogger} 在 INSERT 成功（含失败重试后成功）后同步发布， 携带<b>补全后</b>的入口（result、traceId、IP
 * 等缺省值已填）。订阅方（安全告警、后续指标）自行兜异常—— 审计已落库，派生处理失败不得影响业务线程。
 */
public record AuditAppendedEvent(AuditEntry entry) {}
