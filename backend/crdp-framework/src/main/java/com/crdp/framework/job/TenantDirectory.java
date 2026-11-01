package com.crdp.framework.job;

import java.util.List;

/** 调度框架获取应执行作业的租户清单的入口。实现负责在平台库上下文里查询。 */
public interface TenantDirectory {

    /** 状态为启用、试用或宽限期的租户（宽限期默认正常使用；停用即「暂停定时任务」，PLT-TEN-016，E05-T03 口径）。 */
    List<TenantRef> enabledTenants();
}
