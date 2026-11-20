# 2026-11-17 业务逻辑审查整改

本轮整改对应同日代码审查的十项问题，并补齐患者摘要派生列写入。修改位于 `codex/business-review-remediation`；不改旧 Flyway 脚本，不新增权限码或表结构。

| 项目 | 修复行为 | 回归载体 |
|---|---|---|
| 导入原文件下载 | 含原始工作表的报告限上传人下载，他人统一 40400 | ImportJobServiceTest |
| 自动保存丢输入 | 冻结请求快照、保存期间新输入继续标脏并保留本地草稿；旧记录响应不更新新记录 | auto-save.spec / auto-save-multi-device.spec |
| H5 旧权限 | 与普通认证共用权限版本刷新，撤销 / 停用即时生效 | H5UploadTokenFilterTest / TokenAuthenticationFilterTest |
| 映射越权修改 | 已提交目标额外检查租户相应修改权限 | FollowupRecordServiceTest / ReportMappingIT |
| 旧记录影响当前计划 | 当前在用器械最新记录才可驱动 ERI 与医生指定日期 | PlanSwitchServiceTest / PlanSwitchIT |
| 本人任务漏行 | 责任医生与任务负责人按患者维度取并集 | DataScopeGuardTest / DataScopeRuntimeChainTest / PermissionScopeMaskingIT |
| LEFT JOIN 补录漏行 | 右表范围约束放 ON，保留无关联任务的补录 | DataScopeGuardTest / ReportMappingIT / PatientDeviceCardIT |
| 导入并发覆盖 | 状态抢占、选项 / 范围冻结、切批同事务；失败确认不写策略 | ImportCommitServiceTest / ImportClaimConcurrencyIT（双租户并发） |
| 删除后标红漂移 | 同事务重算该患者该表单的后续标红 | RecordEntryServiceTest |
| 已删位置可选 | 位置树与退回巡检过滤 deleted=1 | RecordEditDeleteIT |
| 摘要缺失 | 提交 / 修改 / 删除 / 映射 / 导入 / 回滚维护最近指标、累计终点与年度完整度 | PatientFollowupSummaryServiceTest / 各写链路 IT |

## 授权、范围与审计

- 原报告下载新增上传人约束，不扩大任何角色的权限；作业状态与不含原值的问题清单仍按既有租户级导入权限可见。
- 映射沿用 `record_edit_submitted` 开关区分 A / B 与 C 的权限码，既有 RPT_MAPPING_WRITE 审计与字段级 REPORT 来源留痕保持。
- SELF 范围遵循 A-WARD-003 的患者并集；空范围、越范围 40400、端别与租户路由隔离保持。
- 摘要重算输入使用定向、带说明的 IgnoreScope 查询，原因是导入异步线程没有用户会话；调用仅来自已通过患者范围或导入冻结范围校验的写链路，只写派生摘要，不向接口回传原始行。
- 敏感字段不进入新摘要；用药只取字典标签，终点只保存事件编码及日期。派生重算由触发操作的现有审计覆盖，不新增重复审计事件。

## 历史数据边界

已有历史摘要随下一次提交、修改、删除、映射、导入或回滚刷新，本轮不对医院历史数据静默执行批量重算。Q40 仍只统计年度任务或带年度锚点的导入记录；无任务、无年度锚点的补录不混入年度完整度（OQ-110③）。OQ-148 的补录自动完成任务暂行口径不变。

## 验证

- `python3 scripts/check_flyway_versions.py`：通过。
- 后端 `spotless:check test`：通过，2669 个测试通过，803 个环境门控用例跳过；JDK 21，使用本机依赖缓存与显式 Mockito agent（避免受限环境的动态 attach）。
- 独立开启 MySQL 门控的定向集成回归：38/38 通过，覆盖双租户并发抢占、数据范围、ERI 切换、报告单映射与删除位置。
- 5000 名虚构患者规模演练：25 批全部成功，生成 15000 条记录；预检约 4.2 秒、提交约 75.0 秒、回滚约 17.9 秒，包含按批回滚与整作业回滚。
- 前端 `pnpm format:check && pnpm lint && pnpm test && pnpm build`：通过，231 个测试文件、1875 个测试通过，医院端 / 平台端 / H5 均构建成功。
- `git diff --check`：通过。

所有集成测试使用本机 MySQL / Redis 与虚构数据，并沿用各 IT 的自建自清逻辑。没有执行云资源操作或历史患者数据批量重算。前端仍有上游测试环境和分包体积提示，不影响测试与构建退出状态。

自动保存复核额外覆盖数组原地修改、多选 / 成对数值按内容比较、保存基准独立复制，避免新快照机制引入复合字段持续标脏或共享引用漏检。
