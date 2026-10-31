package com.crdp.framework.job;

import java.time.LocalDate;

/** 作业执行体。在目标租户的上下文内被调用，可直接访问该院数据库。 */
@FunctionalInterface
public interface TenantJob {

    void run(LocalDate logicalDate) throws Exception;
}
