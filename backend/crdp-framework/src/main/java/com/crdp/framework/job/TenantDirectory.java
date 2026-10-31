package com.crdp.framework.job;

import java.util.List;

/** 调度框架获取应执行作业的租户清单的入口。实现负责在平台库上下文里查询。 */
public interface TenantDirectory {

    /** 状态为启用或试用的租户。 */
    List<TenantRef> enabledTenants();
}
