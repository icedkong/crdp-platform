# 医院 A 导入演练 · 回滚方案与演练记录（E23-T07）

> 任务 E23-T07（全栈，2 人天，依赖 E23-T05；验收：**演练耗时与回滚方案记录**——本方案即「回滚方案记录」，§6 / §7 即「演练耗时记录」）。
> 需求依据：`docs/requirements/hospital-a-spec.md` A-PAT-018（宽表历史数据导入，流程 5「可多次重新导入」）与 A-NFR-001（约 5000 名历史患者；**不设专项性能指标**，耗时只记录不做阈值）。
> 回滚语义实现：E18-T05（`ImportCommitService` 按批写数 + 写入逆序删除，followup V73 `imp_batch` / `imp_batch_write`）。
> 与 E23-T06 数据核对手册（`hospital-a-import-rehearsal.md`）的分工：核对一致与否由该手册判定；**发现不一致后怎么重来、重来与清场各花多久、证据在哪**归本方案。
> 自动化对照：`backend/crdp-server/src/test/java/com/crdp/server/followup/ImportRehearsalOpsIT.java`（门控 `CRDP_IT_MYSQL_PORT`，CI 全量恒跑）——测试环境人工执行按 §6 模板留痕，本仓库不留现场执行记录。

## 1. 回滚决策树（发现问题时走哪条路）

| # | 现象（所处阶段） | 路径 | 操作入口 | 结果语义 |
|---|---|---|---|---|
| 1 | 预检报告有错误 / 想换文件（**尚未确认提交**） | 取消作业 | 向导「取消」或 `POST /api/hospital/followup/imports/{jobId}/cancel` | 作业 CANCELLED；预检不写业务表（E23-T05 已断言），无需回滚 |
| 2 | 预检 / 提交进行中想重来 | **不可打断，先等终态** | —— | 同一租户同一时间只允许一个导入作业（tech-plan §12）：`UPLOADED / PRECHECKING / IMPORTING` 期间再上传 → 40901；被拒上传不留孤儿对象文件（E23-T07 修复，`ImportJobServiceTest#uploadCleansStoredObjectWhenJobCreationRejected`） |
| 3 | 个别批次数据错（已提交 DONE） | 按批回滚 | 批次表「回滚本批」或 `POST .../imports/{jobId}/batches/{batchNo}/rollback` | 仅该批「等于没导过」（如第 25 批 = 文件第 4801–5000 行的 200 名），其余批次不动；修正源表后**重新上传整表**，重复病案号按策略 UPDATE / SKIP（A-PAT-018 流程 5） |
| 4 | 核对不一致 / 换样本 / 想整批重来 | 整作业回滚 | 结果页「回滚整个作业」或 `POST .../imports/{jobId}/rollback` | 全部已完成批次逐批写入逆序删除 → 作业 ROLLED_BACK；演练环境「等于没导过」，可立即重新上传演练（可多次） |
| 5 | 部分批次 FAILED（PARTIAL_FAILED） | 失败批次重试 | 向导「重试失败批次」或 `POST .../imports/{jobId}/retry` | 只重跑 FAILED 批，已成功批次不动；重试成功仍可再按 3 / 4 回滚 |
| 6 | 回滚后既有患者的主档值想恢复 | **不自动还原**（OQ-104②） | 字段历史人工核对 | 更新患者保留主档与既有基线值，只删导入新建的子行；本次导入改过的字段在 `fe_field_history`（source=IMPORT）留痕可查 |
| 7 | 误操作越出导入链路（如手工 SQL 改坏租户库） | 单院恢复 | `docs/ops/tenant-restore.md`（E22-T06） | RDS 实例级时间点恢复，超出导入回滚能力范围，属灾难恢复预案 |

选路原则：**能窄不宽**——单批错误用 3（影响面最小）；核对口径不一致或源表要改用 4（重来才有一致基线）；任何路径都不改写审计（IMPORT_ROLLBACK 只追加）。

## 2. 回滚语义与边界（E18-T05 落地口径）

- **写入逆序删除**（每批一个事务）：随访记录 → 联系方式版本 → 入组 → 基线分节 → 新建患者（连状态时间线 / 摘要一起删）。
- **新建患者连主档删；更新患者保留主档**（OQ-104），只删本导入新建的子行（记录 / 联系方式 / 分节 / 入组）。
- `imp_batch_write`（批次写数登记）随回滚删除；批次置 `ROLLED_BACK` 并落 `rolled_back_at`（回滚完成时刻）；**全部批次回滚后作业置 `ROLLED_BACK`**。
- 每回滚一批写一条 `IMPORT_ROLLBACK` 审计（含该批删除的五类计数）；提交一条 `IMPORT_COMMIT`、上传一条 `IMPORT_UPLOAD`——全程留痕。
- 不可回滚的状态：`PRECHECKING / IMPORTING`（进行中，等终态）；`UPLOADED / PRECHECK_FAILED / PRECHECKED`（无业务写数，直接取消）；`CANCELLED`（同前）；`ROLLED_BACK`（已回滚，幂等不可重复）。
- 历史导入**只入组不建任务**（A-PAT-018 流程 6），因此回滚不涉及 `fu_task`（当年任务由每日定时任务创建，不受影响）。

## 3. 「等于没导过」核对清单（每次整作业回滚后执行）

按演练队列标记圈定（虚构演练宽表用 `IT-` 前缀病案号 / 原系统编号；测试环境脱敏样本按样本标记）：

- [ ] `pt_patient` 演练队列 = 0；
- [ ] `fu_record` 按 source 分：HIST_IMPORT = 0、HIST_MISSED = 0；
- [ ] `fu_enrollment`（auto_enroll 预置计划入组）= 0；
- [ ] `pt_contact`（source=IMPORT，电话 1 / 电话 2 两槽位）= 0；
- [ ] `pt_baseline_section` = 0；`pt_status_history` = 0；`pt_patient_summary` = 0；
- [ ] `imp_batch_write`（该作业）= 0；`imp_batch` 全部 `ROLLED_BACK` 且 `rolled_back_at` 非空；
- [ ] `fu_task` 演练队列 = 0（导入本就不建任务，复核不变量）；
- [ ] 审计在册：`IMPORT_COMMIT` 恰 1 条、`IMPORT_ROLLBACK` = 批次数（按批回滚过的作业，每批恰好一条）。

以上与 `ImportRehearsalOpsIT#assertRehearsalCohortFullyRolledBack` 断言同款；人工执行可请开发带只读 SQL（或直接用 §5 取证 SQL）。

## 4. 回滚耗时取证（库内 SQL，stdout 不留档）

测试环境执行后，各阶段耗时可随时从库内重建（时间均为租户库 `DATETIME(3)`，北京时间）：

```sql
-- 上传 / 确认 / 回滚的作业级时刻（aud_log 只追加，object_id = imp_job.id）
SELECT action, created_at FROM aud_log
 WHERE object_type = 'IMPORT_JOB' AND object_id = ?   -- ? 换作业 id
 ORDER BY created_at;
-- ① 上传→确认提交间隔（含人工看校验报告的时间）= IMPORT_COMMIT − IMPORT_UPLOAD
-- ④+⑤ 回滚总耗时 = MAX(IMPORT_ROLLBACK) − MIN(IMPORT_ROLLBACK)

-- ② 提交耗时（prepare 同步置 IMPORTING 后各批推进）
SELECT MIN(created_at) AS 批次创建, MAX(updated_at) AS 全部完成
  FROM imp_batch WHERE job_id = ?;                    -- 差值 ≈ 提交耗时（DONE 批次的 updated_at）

-- 逐批回滚完成时刻（每批一条）
SELECT batch_no, rolled_back_at FROM imp_batch
 WHERE job_id = ? AND rolled_back_at IS NOT NULL ORDER BY batch_no;

-- 端到端（演练环境口径）：MAX(rolled_back_at) − imp_job.created_at
```

## 5. 演练环（技术执行步骤，与 CI 用例同序）

1. **主演练**：5000 行 × 761 列宽表（脱敏样本或虚构演练宽表）上传 → 预检 `PRECHECKED` 且 0 错误 → 确认提交（UPDATE 策略）→ 25 批全部 DONE；
2. **40901 闸复核**：提交进行中再上传一台 → 40901（「想整批重来」的第一道闸：必须先走终态）；
3. **确认前放弃路径**：另传小作业 → `PRECHECKED` → 取消 → `CANCELLED`（无写数、无需回滚）；
4. **按批回滚**：回滚最后一批（200 名）→ 该批「等于没导过」，前 24 批留存；
5. **整作业回滚**：剩余 24 批 → 作业 `ROLLED_BACK` → §3 清单逐项勾选；
6. **重新演练**：同一批病案号重新上传 → 预检 → 提交 DONE（A-PAT-018 流程 5「可多次重新导入」）；
7. **清场**：重新演练的作业也整作业回滚，演练环境按产品路径归零（不靠 DBA 手工清库）。

每阶段记录耗时（§6 模板）；A-NFR-001 不设性能指标，数字用于上线排期（E23-T11 生产导入窗口预估）与横向对比，不做阈值告警。

## 6. 演练记录模板（测试环境人工执行后归档运维 wiki / 上线评审材料）

> CI 用例结尾输出同列的「演练记录」表可直接粘贴；本仓库不留现场执行记录（与 E22-T08 口径一致）。

| 项 | 值 |
|---|---|
| 日期 / 执行人 / 复核人 / 环境（测试环境版本） | |
| 数据集（脱敏样本版本或虚构宽表种子 / 行数 × 列数） | |
| ① 上传 + 预检：耗时 / 错误数 / 警告数（→ 人工复核清单） | |
| ② 确认提交：耗时 / 批次 / 新建数 / 年度记录数 | |
| ③ 取消路径（如演练）：结果 | |
| ④ 按批回滚：批次号 / 耗时 / 删除数 | |
| ⑤ 整作业回滚：耗时 / §3 清单结果（未过项与处置） | |
| ⑥ 重新演练：耗时 / 结果（可多次重新导入） | |
| ⑦ 清场：结果（演练队列 = 0） | |
| 端到端合计 | |
| 发现的问题与修复（缺陷号 / 修复 PR） | |
| 结论（是否放行 E23-T11 正式导入） | |

## 7. 自动化对照（CI 恒跑）与实测参考

`ImportRehearsalOpsIT`（E23-T07）在 CI / 本机按 §5 全环执行并断言：40901 闸、取消路径、按批回滚 200 名「等于这批没导过」、整作业回滚后 §3 全清单、重新演练同病案号全部新建、清场归零、`rolled_back_at` 与逐批 `IMPORT_ROLLBACK` 审计在册（耗时取证前提），结尾输出「演练记录」表。运行：

```bash
CRDP_IT_MYSQL_PORT=13306 mvn -B -f backend/pom.xml -pl crdp-server test -Dtest=ImportRehearsalOpsIT
```

本机实测参考（2026-11-10，Apple silicon 本机 + docker MySQL 8 演示租户库；**CI 与测试环境数字以现场记录为准**，下表即该次运行用例结尾输出的「演练记录」）：

| 阶段 | 本机实测 |
|---|---|
| ① 上传 + 预检（5000 行 × 761 列） | 4,241 ms（1179 行/秒；PRECHECKED、0 错误） |
| ② 确认提交（25 批 × 200 名） | 51,154 ms（97.7 名患者/秒；DONE、15,000 条年度记录） |
| ③ 确认前放弃（2 行小作业） | 657 ms（CANCELLED，无业务写数） |
| ④ 按批回滚第 25 批（200 名） | 792 ms（253 名患者/秒；4800 名留存） |
| ⑤ 整作业回滚（剩余 24 批） | 20,342 ms（236.0 名患者/秒；作业 ROLLED_BACK） |
| ⑥ 重新演练（250 行同病案号） | 3,669 ms（DONE，2 批全部新建） |
| ⑦ 清场（重新演练作业回滚） | 1,418 ms（演练队列清零） |
| 端到端合计（含造数与断言核对） | 82,362 ms |

> 三个导入演练用例的分工：`ImportScale5000IT`（E18-T07）= 规模 + 可回滚；`ImportRehearsalVerifyIT`（E23-T06）= 数据核对（汇总数四路一致 + 抽样逐字段）；`ImportRehearsalOpsIT`（E23-T07，本任务）= 演练环运维面（40901 闸 / 取消 / 按批与整作业回滚 / 重新演练 / 清场）与耗时记录。

## 8. 由人执行的事项（按 `AGENTS.md` 第 9 节）

- 测试环境现场演练的排期与参加人员（开发 + 测试在场；医院脱敏样本版需实施 / 产品在场）；
- 用**医院脱敏样本**走 §5 同环演练（样本交接与保密按 `docs/ops/hospital-a-sample-intake.md` §4；真实数据只到测试环境、永不进仓库）；
- §6 演练记录的归档与放行结论（供 E23-T11 正式导入与上线切换引用）；
- E23-T11 生产导入前按本方案 §1 确认回滚预案已评审（生产导入只在核对不一致时才触发回滚，正常路径见 `docs/tasks/tasks.csv` E23-T11）。
