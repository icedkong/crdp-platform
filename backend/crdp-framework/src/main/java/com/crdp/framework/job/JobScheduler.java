package com.crdp.framework.job;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 按租户扇出的调度入口（E02-T06）。框架负责逐租户执行、写 job_tenant_run 执行记录、隔离单租户失败； 业务作业只提供 {@link TenantJob} 执行体并按
 * {@link #lastSuccessDate} 计算补跑区间。签名与取舍见 ADR 0003；每日定时的实际注册是 E14-T04。
 */
public interface JobScheduler {

    /**
     * 对全部启用 / 试用租户执行作业。每个租户在自己的租户上下文里跑，先写 RUNNING 记录再执行，结果写 SUCCESS 或
     * FAILED。单租户失败（业务抛错、租户库不可达）记入返回值后继续下一个租户。
     *
     * @param jobCode 作业编码，作为 job_tenant_run 的按日幂等键
     * @param logicalDate 逻辑执行日；补跑时对缺失区间逐日调用
     * @param job 作业体，已在租户上下文内，可访问该院数据库
     */
    List<TenantJobResult> runForEachTenant(String jobCode, LocalDate logicalDate, TenantJob job);

    /** 当前租户库中该作业最近一次成功执行日，作为补跑区间的起点（上次成功日 + 1 至今天）。必须在租户 上下文内调用。 */
    Optional<LocalDate> lastSuccessDate(String jobCode);
}
