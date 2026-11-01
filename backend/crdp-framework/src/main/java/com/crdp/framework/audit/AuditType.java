package com.crdp.framework.audit;

/** 审计日志四类（platform-spec §9 / PLT-AUD-001~004）。 */
public enum AuditType {
    /** 操作日志：增删改、配置 / 权限 / 状态变更（PLT-AUD-001）。 */
    OPERATION,
    /** 登录日志：登录成功与失败、登出、锁定、解锁、强制下线、令牌刷新异常（PLT-AUD-002）。 */
    LOGIN,
    /** 数据访问日志：查看敏感明文、导出、打印、下载、批量查询（PLT-AUD-003）。 */
    DATA_ACCESS,
    /** 平台访问日志：租户视角会话、页面、查询、被拒操作、代为操作与视角规则变更；仅存平台侧（PLT-AUD-004）。 */
    PLATFORM_ACCESS
}
