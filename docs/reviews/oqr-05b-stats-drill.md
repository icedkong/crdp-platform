# OQR-05B 统计集合与钻取实施记录

日期：2026-11-19。关联 E19 统计、A-DASH-001 / 006 / 010 / 011 / 012、C-DASH-004 / 009 / 010，以及 OQ-106 / 107 / 109 / 110 的指定子项；顺序见 [执行清单](../tasks/oq-resolution-plan.md)。

状态：本工作区已实现并通过本地验证，分支 `codex/oqr-05b-stats-drill`。未提交 / 推送 CI，未执行生产迁移，人工界面及医院 UAT 待验收。此前修复保留，本文仅记录本项。

## 问题与最终行为

旧本月应随访只数已物化任务，会漏掉尚未建单的计划次数；以患者列表代替节点或年度记录明细，会丢失同患者的多个计数单位。旧每日汇总只有逻辑日期，不能表示实际刷新时刻。

- KPI 与医护负责患者数共用 MonthlyDueService，按原计划版本、起算日期、模式和滚动 / 指定日期规则投影月内次数，与实际任务按入组、节点、次数去重。实际任务日期优先，已移动到下月的次数不会在本月重新出现；查询不创建任务。暂停、切换、移除、死亡等沿用已有规则。
- 新增 POST `/api/hospital/followup/stats/drill` 及统计明细抽屉。月度按次数、年度随访率按患者、节点随访率按入组 / 节点 / 次数、随访完整度按年度记录呈现；保留对应计划、年度、记录与任务上下文。分页默认 20 条。
- 完整度数字与明细复用求值服务，随访缺失记录同患者有两条即显示两条；无年度锚点补录仍不强行计入 Q40。患者类型标签通过表单元数据的业务只读字典来源读取，未知旧码回落编码，无需字典管理权限。
- 其他 KPI 列表钻取保留所选病区。医护月度明细按患者责任医生筛选，同月度计数一致；其他医护列仍沿用 OQ-118 暂行口径，本项不重定义任务负责人语义。
- 新增 followup V144：st_refresh_state 保存逻辑日和真实成功刷新时刻，与事实重建同事务提交或回滚。旧汇总实际时刻未知时返回 null，不从逻辑日伪造。卡片和明细区分实时 / 每日事实并显示真实时刻或未知；配置模板明确不导出运行时刷新元数据。没有修改旧迁移或新增 tenant_id。

## 权限、范围与审计

明细需要 `followup:dashboard:view` 与 `followup:patient:view` 同时具备；节点明细继续检查 node_follow_rate，关闭返回 40301。受控查询均声明 patient 数据范围，空范围返回空列表，每次请求和翻页重新检查权限及范围，不缓存旧患者集合。

姓名默认脱敏并遵守 HIDDEN 与租户视角字段策略，不新增明文查看入口；记录跳转继续独立检查原 task:view 权限。新增 STATS_DRILL 读取审计，固定描述不包含请求载荷、姓名或临床明文。抽屉关闭、查询及权限版本变化时清理旧数据，丢弃过时响应。expectedCount 仅用于提示两次请求间数据变化，不能替代权限校验。

## 本地验证

- 后端专项及兼容回归 **90 / 90 通过**：StatsDrillIT 8，DashboardKpiServiceTest 10，DoctorWorkloadStatsServiceTest 6，NodeOccurrenceStatsServiceTest 7，NodeOccurrenceRefreshServiceTest 7，CompletenessStatsServiceTest 9，DashboardCardsIT 5，DoctorWorkloadIT 5，DashboardRateCardsIT 5，StatsQ38CaliberIT 5，CompletenessCardsIT 5，BackfillTaskLinkIT 10，ConfigTemplateTenantInitMySqlIT 3，PlatformModuleRegistryMySqlIT 5。
- 新真实数据库用例覆盖双租户、未来月份 / 闰日、实际任务覆盖与跨月移动、暂停 / 切换 / 移除 / 死亡、年度患者与节点次数的不同粒度、对应年度记录、旧未知刷新时刻与失败回滚、权限交集 / 空范围 / 同会话范围收紧、参数错误、开关关闭、数量变化提示及责任医生归口。补录联动前后核对月度集合不重复。
- 旧统计集成测试曾依赖本地全院数据恰好为空，扩展运行时被其他示例患者干扰。本次让三个夹具使用独立病区，并只收紧实际测试会话的范围，保留原手算断言，不删除其他数据。标红夹具显式写入已知首次提交时间；任务夹具选实际 NODE。最终扩展回归通过。
- 后端全量 spotless:check / test 通过：**3528 项，其中 2682 项运行通过，846 项按环境门控跳过**。上述专项真实 MySQL 用例已另行启用执行，其他跳过项不作为验收通过。
- 前端 format:check / lint、**234 文件 / 1884 项测试**及医院端 / 平台端 / H5 构建通过。新抽屉测试覆盖分页、关闭清理、无权限清理和过时响应；构建仍有既有大包提示，上游测试仍有 mock hoist 等提示，以最终通过结果为准。
- Flyway 版本、git diff --check、文档链接与 OQ 编号唯一性检查通过。

最终日志位于本机 `/tmp/crdp-oqr05b-final-it.log`、`/tmp/crdp-oqr05b-final-backend.log`、`/tmp/crdp-oqr05b-frontend-format-check.log`、`/tmp/crdp-oqr05b-frontend-lint.log`、`/tmp/crdp-oqr05b-final-frontend-test.log`、`/tmp/crdp-oqr05b-frontend-build.log`。早期夹具失败不计为通过记录。

## 部署与下一项

按既有 Flyway 模块升级流程逐租户应用 V144；本次仅在本地测试库执行。首次成功刷新前旧汇总显示实际时刻未知，不能把上线时间批量填作历史刷新事实。上线核对月度数字与次数明细、年度患者去重、节点次数和缺失记录定位，以及权限收紧后的明细清理。

旧本地测试库仍有既有审计哈希链异常提示，本项没有改写历史审计，也不将审计链、生产环境或医院 UAT 标为验收通过。

下一项为 **OQR-06 当前器械与指定排期**。清单尚余四项代码整改（06–09）和一项部署证据（10）。
