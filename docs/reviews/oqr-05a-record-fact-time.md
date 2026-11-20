# OQR-05A 记录时间事实实施记录

日期：2026-11-18。关联 E19 统计时间口径、A-DASH-001、C-DASH-001 / 006、OQ-106 / 107 / 109 / 110；原整改顺序见 [执行清单](../tasks/oq-resolution-plan.md)。

状态：本工作区已实现并通过本地验证，分支 `codex/oqr-05a-record-fact-time`。未提交 / 推送 CI，未执行生产迁移，人工界面与医院 UAT 尚待验收。此前修复保留，本文只记录本项。

## 问题与最终行为

旧实现以 fu_record.updated_at 近似「提交时间」。保存修改、报告单映射、标红和序号重算会触碰该列，导致旧记录重新进入「近 N 天提交」窗口，且明细的 submittedAt 实际显示修改时间。

新增租户 followup V142：可空的 first_submitted_at（DATETIME(3)）及 status / deleted / first_submitted_at 索引。独立保存平台首次提交事实，不修改既有迁移、不增加 tenant_id。临床实际随访日期、平台创建时间、首次提交时间和最后更新时间分别保留，字段字典见 [followup.md §3.1](../api/followup.md)。首次提交事实属于实体技术元数据，不进入临床表单 JSON，不增加可更正入口。

- 普通提交在 DRAFT → SUBMITTED 的版本 CAS 中原子写入，已有首次提交值保留；重复提交 / 版本不符不改写。后续流程失败时随状态、版本和数据一起回滚。
- 新直接导入的 SUBMITTED 记录写本次平台接纳时刻；历史未随访草稿首次升级提交时写入。该时刻不代表原纸质记录的历史提交时间。重复导入已提交行保留已有值；旧已提交行未知时继续未知。
- 修改、映射、标红 / 序号重算及软删除都不改写首次提交时间。旧数据没有可靠的首次提交来源，V142 不回填，不能从 updated_at / created_at 猜测。
- 记录详情增加 createdAt / updatedAt / firstSubmittedAt，提交响应增加 firstSubmittedAt。时间按北京时间 ISO-8601 带时区返回，修正了记录时间格式依赖 JVM 默认时区的问题，UTC 默认环境也能正确返回 +08:00。
- 指标卡 highlightRecent 与 abnormal_records 明细统一按 first_submitted_at 筛选和排序；未知时间不进入任何日期窗口。异常卡片另返回当前范围 / 病区内的 unknownSubmittedAtCount，并显示未计入近 N 天的提示。未知数量不归到某个日期，因为没有事实依据。
- 记录页区分「未提交」「未知（历史记录未留存首次提交时间）」与真实首次提交时间；提交成功使用服务端事实，不使用客户端时间或最后修改时间兜底。

现有临床实际日期解析与任务状态机未在本项重定义；完整应随访集合、统计钻取和医院 C 补录关联仍按 OQR-01 / OQR-05B 实施。

## 权限、范围与审计

沿用现有记录 / 首页权限及功能开关，未新增权限、公开接口或明文读取能力。首次提交列的读取跟随患者 / 记录范围；未知时间计数新增 patient 数据范围声明，默认拒绝仍适用。未知数量也不能暴露范围外数据。

写入沿用 RECORD_SUBMIT / 导入批次等已有审计，与原事务一起提交；不新增可单独修改事实时间的操作，不从历史审计近似恢复时间。新增时间字段不含患者敏感明文，未新增敏感载荷或脱敏豁免。

## 本地验证

- 后端专项与兼容回归 75 / 75 通过：RecordFactTimeIT 4、FollowupRecordServiceTest 14、RecordEntryServiceTest 29、AbnormalRecordsServiceTest 7、CDashboardCardsIT 6、CDashboardCardsCaliberIT 4、RecordEditDeleteIT 3、PlatformModuleRegistryMySqlIT 5、ConfigTemplateTenantInitMySqlIT 3。
- 新真实数据库用例覆盖双租户、草稿无提交事实、首次提交、重复提交、修改 / 映射 / 重算 / 删除保留事实、导入新建 / 草稿升级 / 重复导入、旧已提交未知值保留、统计依据提交事实而非修改时间、未知数量、空范围拒绝与提交后续失败整体回滚。
- 医院 C 既有窗口回归改为回拨首次提交事实，验证 30 / 90 天窗口中指标与列表一致，窗口外记录仍保留在详情和时间轴。模块最大迁移断言同步至 V142；全新租户全迁移初始化通过。
- 器械分布回归原先假设本地全院恰好只有 4 名患者，在已有额外示例数据时失败。本次改为快照基准后验证新增患者 / 器械 / 品牌增量，仍核对分母与指标一致，不删除或改动其他患者；最终通过复测。新空范围测试早期夹具只改 ThreadLocal 而被真实会话切面覆盖，已改为同步收紧测试会话；最终真实范围用例通过。
- 后端全量 spotless:check / test 通过：3509 项，其中 2681 项运行通过、828 项按环境门控跳过；上述门控真实 MySQL 用例已另行启用执行，不将其他跳过项计为验收通过。
- 前端 format:check / lint、233 文件 / 1879 项测试、医院端 / 平台端 / H5 构建通过。新增业务显示测试区分草稿、未知历史与真实时间；构建仍有既有大包提示，上游测试仍有 mock hoist / happy-dom 清理提示，不阻断本次最终退出结果。
- Flyway 版本检查、git diff --check、文档相对链接与 OQ 编号唯一性检查通过。

日志：`/tmp/crdp-oqr05a-final-it.log`、`/tmp/crdp-oqr05a-final-backend.log`、`/tmp/crdp-oqr05a-frontend-format.log`、`/tmp/crdp-oqr05a-frontend-lint.log`、`/tmp/crdp-oqr05a-frontend-test.log`、`/tmp/crdp-oqr05a-frontend-build.log`。早期夹具失败和审批超时不计为通过记录，以上结论取最终复测结果。

## 部署与下一项

平台继续按既有 Flyway 模块升级流程逐租户应用 V142；本工作区只在本地测试库执行。上线后旧记录的近期标红统计可能下降，这是删除了更新时间近似带来的错误计入；未知部分通过独立数量提示，不伪造历史时间补齐数字。部署前后核对范围与未知数量，不把 null 批量设为上线时间。

本地旧测试库的既有审计哈希链异常提示仍存在，本项没有修改历史审计，也不将审计链或真实云环境标为验收通过。

下一项按依赖进入 OQR-01 医院 C 补录与任务联动；其后才是 OQR-05B 统计集合与钻取。
