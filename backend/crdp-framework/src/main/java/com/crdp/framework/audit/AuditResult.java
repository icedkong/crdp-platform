package com.crdp.framework.audit;

/** 审计结果（platform-spec §9 通用日志字段：结果、失败原因）。 */
public enum AuditResult {
    /** 成功执行。 */
    SUCCESS,
    /** 被拒绝：无权限（40300）、模块未开通（40301）、数据范围缺陷（40302/40303）、宽限期只读（40306）等。 */
    DENIED,
    /** 执行失败（异常、校验不通过等）。 */
    FAIL
}
