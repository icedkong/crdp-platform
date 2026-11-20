# OQR-01 医院 C 补录与任务联动实施记录

日期：2026-11-18。关联 C-FU-013 / 015、Q14、C-ACC-27、OQ-148；依赖 OQR-05A 事实时间。

状态：本工作区已实现并通过本地自动化验证，分支 `codex/oqr-01-backfill-task-link`；未提交 / 推送 CI，未执行生产迁移或开关激活，医院 UAT 与人工选择界面验收待执行。此前整改保留，本文只记录本项。

## 实际行为

租户 followup V143 新增 backfill_task_link，默认关闭，由医院 C 的配置激活流程显式开启；A / B 继续保持关闭。没有按租户名称分支，没有新增权限。补录创建仍不需要任务；联动只在 BACKFILL 提交时执行，计划外和远程监测不参与。

新增 GET records/{id}/backfill-candidates，沿用 followup 模块、record:edit 权限与 patient 数据范围。只返回同患者、同表单、当前在用器械日期区间的普通 FORM 主任务：患者及入组 ACTIVE，入组起算快照不早于当前器械植入日，任务为可完成状态、occurrence > 0 / followup_seq = 0，创建类型 NORMAL / BACKFILL，实际随访日期处于窗口闭区间。提醒、临时、终态任务、历史器械、已有草稿 / 记录及归属不明的旧起算快照保守排除。

| 候选情况 | 提交行为 |
|---|---|
| 无候选 | 独立补录，保留来源与实际日期 |
| 唯一候选 | 自动关联完成；前端预检有唯一候选时传明确 ID，提交失配要求重新确认 |
| 多候选 | 医生必须选择，不默选；服务端未选择时 40901 并附范围内候选 |
| 指定其他患者 / 越范围任务 | 40400，不暴露对象存在 |
| 当前患者任务已变化或已有记录 | 40901，保留原表单，重新加载候选 |

原 POST records/{id}/submit 增加可选 backfillTaskId。患者 / 记录 / 候选任务与入组在事务内加锁重验；fu_record 的 task_id / 锚点与 fu_task.record_id 双向关联，随后执行原校验、字段历史、任务完成、死亡联动及转录流程。竞争守卫或唯一约束失配不覆盖已有记录；数据库锁竞争在 HTTP 边界转为明确冲突。

补录完成方式按实际随访日期计算，窗口内为 ON_TIME；首次提交事实和平台完成时刻仍为本次提交，与过去临床日期分开。模板中的 followup_date 与补录登记日期不一致时 40001，不能用不同日期进行匹配。提交后的计划联动只处理锁定复核后的 ACTIVE 入组，历史器械 / 暂停 / 终止入组不借补录改变当前排期。

## 幂等、审计与统计

V143 增加 backfill_submit_digest / backfill_submit_result：仅保存请求摘要与结果，不存原始载荷。开启联动的 BACKFILL 同一命令（包括原 baseVersion、data / dataMeta、转录选择及任务选择）重试返回首次结果，不重复完成、写字段历史或触发实际联动；Map 键顺序不影响摘要。请求变化、后续记录版本变化、软删除以及关闭功能后的行为不能借幂等覆盖当前值。

新增 BACKFILL_CANDIDATES 查询审计、RECORD_SUBMIT_REQUEST 尝试审计与成功的 BACKFILL_TASK_LINK；后两者分别记录尝试及真实关联 / 独立提交，原 RECORD_SUBMIT / TASK_COMPLETE 等继续沿用。重复请求保留尝试留痕，不重复真实完成。描述不含临床数据载荷或敏感明文。

已关联补录不再进入无锚点补录集合，避免重复归属。删除仍软删并清理任务反向链接，任务完成终态不回退；统计查询排除软删锚点 / 浮动记录，对仅有已删记录的 FORM 任务向计算器提供非完成标记 RECORD_DELETED，使分子不因任务仍 COMPLETED 而保留。该标记仅为内部统计输入，不写入任务状态机。提醒类无表单任务沿用原完成口径。

联动回归还暴露固定 NODE 缺少 repeat_mode 时年度判定 SQL 可返回 NULL，无法映射 boolean，导致提交后的摘要重算失败。已用 COALESCE 将不满足年度条件归为 false；没有把固定节点伪造成年度节点来绕过问题。

## 界面与配置

记录页在完整校验后读取候选；多个候选使用 Ant Design Vue Modal + Select，显示计划、节点、计划日和窗口，无默认值。取消或查询 / 提交失败保留录入数据，候选查询失败不会降级跳过；服务端冲突后下次提交重新取候选。切换记录关闭选择框，并丢弃旧候选和提交响应。

配置与验收同步到 [医院 C 激活说明](../ops/hospital-c-plan-preset.md)、[C UAT](../testing/uat-hospital-c.md)、[C-ACC-27 留痕](../testing/hospital-c-next-visit-backfill-acceptance.md)、[接口 §3.2](../api/followup.md)、设计与审计文档。C UAT 激活清单增加为九项开关，文档防漂移检查同步新口径；默认关闭不能被当作生产已经启用。

## 本地验证

最终专项 / 兼容回归 135 / 135 通过：

- 新 BackfillTaskLinkIT 10 项：双租户、窗口首尾、多候选、唯一自动、无候选、开关关闭、已有记录与错误任务类型排除、历史器械 / 暂停保护、HTTP 权限与空范围、HTTP 同请求重试、并发竞争、后续失败整事务回滚、删除后的统计排除。
- 原 CNextVisitBackfillAcceptanceIT 6、RecordFactTimeIT 4、NodeOccurrenceStatsIT 1、PlatformModuleRegistryMySqlIT 5、ConfigTemplateTenantInitMySqlIT 3，验证 C 原补录行为、首次提交事实、统计输入、模块版本与全新初始化。
- RecordEntryServiceTest 29、FollowupTaskStatusServiceTest 36、PlanSwitchServiceTest 22、NodeOccurrenceFactCalculatorTest 19。

后端全量 spotless:check / test 通过：3519 项，其中 2681 项运行通过、838 项按环境门控跳过；上述真实 MySQL 专项已另行开启执行。收尾仅更新注释后再次执行 spotless apply / check，通过。前端格式 / lint、233 文件 / 1880 项测试与医院端 / 平台端 / H5 构建通过。Flyway 版本检查、git diff --check 与文档链接 / OQ 编号唯一性检查通过；UAT 文档校验 157 / 157、C 整改文档校验 54 / 54 通过。

早期联动测试因上述固定节点摘要空值失败，修复后通过最终复测。自动权限审批超时的命令均未启动，重试后正常完成，不将超时或早期失败记为通过。

日志：`/tmp/crdp-oqr01-final-it.log`、`/tmp/crdp-oqr01-final-backend.log`、`/tmp/crdp-oqr01-final-format.log`、`/tmp/crdp-oqr01-frontend-format-check.log`、`/tmp/crdp-oqr01-frontend-lint.log`、`/tmp/crdp-oqr01-frontend-test.log`、`/tmp/crdp-oqr01-frontend-build.log`、`/tmp/crdp-oqr01-uat-docs.log`、`/tmp/crdp-oqr01-uat-remediation.log`。

## 验收边界与下一项

C-ACC-27 联动条款的本地机器面复测通过；CI、生产升级 / 开关、实际医院多候选操作与签字未执行。生产须先逐租户升级 V143，再仅对目标医院 C 配置开启并核对零 / 唯一 / 多候选、历史器械 / 暂停边界，保留 A / B 关闭回归。不得把配置模板重导后的默认关闭误记为已激活。

既有本地审计哈希链异常提示与上游前端测试 / 构建提示仍按此前记录处理，本项未修改历史审计或执行真实云操作。

下一项：OQR-05B 统计集合与钻取。每日事实刷新时机及完整的数字 / 钻取一致性尚未由本项替代验收。
