# ADR 0003：每租户调度执行记录与补跑框架（job_tenant_run）

日期：2026-10-31

状态：已接受

## 背景

E02-T06 要求"单租户失败不影响其他租户，可补跑"。`crdp-framework` 里的 `JobScheduler` 是 M0 占位接口（`runForEachTenant(String jobName)`），没有执行体、逻辑日期与结果返回，无法支撑技术方案第 5 章的每日调度与补跑语义（补跑区间 = 上次成功日 + 1 至今天）。按 AGENTS.md 约定，改接口签名先落 ADR。

## 决定

1. **`job_tenant_run` 放租户库**（技术方案第 15 章表清单）。每租户自己记录执行历史，补跑起点（`MAX(run_date) WHERE status='SUCCESS'`）在本院库即可读到，不引入平台侧集中表。唯一键 `(job_code, run_date)` 保证按日幂等，重复执行覆盖当天记录。
2. **`JobScheduler` 新签名**：
   - `runForEachTenant(jobCode, logicalDate, TenantJob)` → 返回逐租户结果列表。`TenantJob` 在该租户上下文内执行（复用 E02-T01 的 `runAsTenant`），框架负责写 RUNNING / SUCCESS / FAILED 记录。
   - `lastSuccessDate(jobCode)` → 当前租户库中该作业最近成功日，业务作业据此算补跑区间；**必须在租户上下文内调用**。
3. **失败隔离**：单租户失败（业务抛错或租户库可达但出错）写 FAILED 记录后继续下一个租户；租户库不可达时记录应用日志并继续——集中告警是 E22（CES/LTS）的事。
4. **不引入 ShedLock**：M1 单实例部署。锁表 `shedlock` 已在平台库预留，多实例时再加锁。
5. **租户清单经 `TenantDirectory` 注入**（`JdbcTenantDirectory` 查平台库 `status IN ('ENABLED','TRIAL')`），调度器不直接耦合平台库查询，测试可注入替身。

## 后果

- `UnsupportedJobScheduler` 占位删除，由 `JdbcTenantJobScheduler` 替换。
- 作业执行时间建议、每日 00:10 的实际调度注册与业务作业（任务生成、通知汇总）是 E14-T04 / E09 的事，本框架只提供逐租户执行与记录原语。
- 租户停用期间的"记录未执行日期区间"由停用补偿（E14-T06）基于该表推导，本框架不单独记区间。
