# 医院 C 下次随访时间与补录用例：执行留痕（W42 · E21-T26）

> 任务 E21-T26（测试，2 人天，依赖 **E21-T14** ✅；验收：**窗口算例通过**）。关联需求 **C-FU-005**（医生指定下次随访时间，Q05）、**C-FU-014**（补录时建议随访时间点）；验收面 = 评审用例集 `docs/reviews/hospital-c-acceptance-review.md` §3 指派本任务的 **C-ACC-16**（窗口算例，数字同 tasks.csv 验收列）与 **C-ACC-25…27**（补录算例）。
> 承接范围（评审 §6 移交）：C-ACC-16、25…27 的**机器 / 行为面**（生产侧动作不在本任务——C 上线随 E23-T23）。全部机器可判定用例 CI 与本机同口径执行；数据全部虚构（`IT-E21T26-` 前缀，自建自清）。

## 1. 验收口径

- 「窗口算例通过」= C-ACC-16 窗口算例**双面通过**：
  - **行为面**（`DoctorNextDateIT`，E21-T08 随实现交付）：自建滚动计划、可控参数下锁定调度数学本身（计划日期覆盖、窗口围绕展开、窗口开始日创建、消费清理、滚动续排）；
  - **产品配置链面**（`CNextVisitBackfillAcceptanceIT`，本任务新增）：按 runbook `docs/ops/hospital-c-plan-preset.md` §3 激活路径——发布 followup V91 两套预置（`C_PACER_FU_PLAN` / `C_ICD_FU_PLAN`，随包 UNPUBLISHED 草稿，OQ-128②）→ 保存植入记录按 followup V86 **预置映射**自动入组（不 PUT 改映射）→ **预置常规随访节点**（Q03 节点表：LAST_VISIT + 12 个月 ±30）真实排期，窗口宽度逐项落库核对。与 E21-T25 切换表套件同法做**时钟相对化**：医生日期 = 今天 + 2 个月，与规格验收原文「2027-01-15 → 窗口 2026-12-16 至 2027-02-14、窗口开始日建任务」数字同构（±30 天）。
- 门控 `CRDP_IT_MYSQL_PORT`（CI 起 docker compose 注入端口；本机先 `docker compose up -d`）。预置发布态与 `implant` 开关收尾还原（预置回 UNPUBLISHED、开关回种子默认），A / B 租户不受影响。

## 2. C-ACC-16 窗口算例逐条执行映射（规格 §5 验收原文拆条）

| # | 条款（预期） | 行为面（DoctorNextDateIT 等） | 配置链面（CNextVisitBackfillAcceptanceIT） |
|---|---|---|---|
| 1 | 医生填写下次随访时间 → **下一次任务的计划日期** = 该日期（不是窗口开始日，Q05） | `doctorSpecifiedDateBecomesPlanDateWithWindowAroundIt`（自建滚动计划） | `cAcc16DoctorNextDateBecomesPlanDateWithPresetRoutineWindowAroundIt`（V86 映射入组预置计划；doctor_next_date 登记、last_visit_date 快照） |
| 2 | 窗口按**对应节点的窗口宽度**围绕展开（规格例：2027-01-15 ±30 → 2026-12-16 至 2027-02-14；窗口宽度口径 CL-04） | 同上（±30 滚动节点） | 同上（预置「常规随访」节点 ±30：window_start = 医生日 −30、window_end = +30、开启日 = 窗口开始日） |
| 3 | **窗口开始日创建任务**，而不是按计划默认日期 | 同上（开启日前一天不建、当天建、重放幂等〔uk〕） | 同上（前一日 0 任务、当日恰 1 条 PENDING/NORMAL、再跑一日不重复） |
| 4 | **只影响下一次**：消费后清理登记，滚动从医生日期起继续 | 同上（doctor_next_date 清理，OQ-138③）+ `TaskCreationPlannerTest`（E21-T08，数学面） | 同上（清理断言 + next 缓存 = 医生日 + 12 个月、开启 −30） |
| 5 | 未填写时按计划默认（Q05） | 同上；OQR-06 增绑定状态，存在指定日期时缓存可显示该真实日期，无指定时仍用计划默认 | 同上（医生窗口开启前缓存 = 已绑定的医生日期；另核对实际任务计划日不等于默认日期） |
| 6 | **早于今天或本次随访日期不能保存**（前端闸门） | 前端 `next-visit.spec.ts` / `record logic.spec.ts`（E21-T08：提交与保存修改前校验）；后端语义 = 过去日期不登记（`PlanSwitchService` SW-10 分支） | `cAcc16PastDoctorDateOnBackfillOnlySavedWithoutTask`（不登记 → 无从生成任务） |
| 7 | **补录历史记录**中的下次随访时间只保存、不生成任务（已过去） | `PlanSwitchServiceTest`（过去日期忽略分支） | `cAcc16PastDoctorDateOnBackfillOnlySavedWithoutTask`（f333 落记录 JSON、doctor_next_date NULL、患者无任务） |
| 8 | 优先级 医生指定 → ERI 期循环 → 计划默认（C 规格 §5） | `TaskCreationPlannerTest.doctorDateWinsOverEriLoop` + `PlanSwitchIT`（E21-T06/T07） | `CSwitchAcceptanceIT.sw10DoctorNextDateRegistersAndWinsOverEriLoop`（E21-T25 姊妹套件：ERI 模式下医生日期端到端覆盖循环的下一次） |

## 3. C-ACC-26 / 27 / 25 补录算例执行映射

| 用例 | 要点 | 承载 |
|---|---|---|
| C-ACC-26 七档逐档 | 0–7 术后72小时/出院前、25–45 术后1个月、75–110 术后3个月、150–215 术后6个月、330–400 术后1年、其后每6-12个月、接近 ERI 时 ERI期随访 | 配置链面 `cAcc26SuggestsEveryTierOnPresetChain`（每档代表日 3 / 31 / 90 / 180 / 365 / 500 逐档核对编码与名称）+ `cAcc26EriTierSuggestedViaRealEriMarkingPath`（第七档走真实路径：提交 f323=1 翻 ERI 后优先于天数）；边界日 0 / 7 / 8 / 45 / 46 / 110 / 215 / 216 / 330 / 400 / 401 / 3000 由行为面 `TimepointSuggestionServiceTest.dayRangeBoundariesFollowSpec` + `BackfillTimepointIT.suggestsTimepointByDaysSinceImplant`（E21-T14）锁定 |
| C-ACC-26 规格原文 31 天 | 距植入 31 天 → 自动建议「术后1个月」 | 双面：`BackfillTimepointIT.suggestsTimepointByDaysSinceImplant`（行为面）+ `cAcc26SuggestsEveryTierOnPresetChain`（配置链面，daysSinceImplant=31 断言） |
| C-ACC-26 可改选 | 建议只是种进草稿的缺省，可改选 | `cAcc26SuggestionIsDefaultAndChangeableOnSubmit`（种入选项 2 → 提交改选 5 落库）+ 行为面 `RecordBackfillServiceTest` / `BackfillTimepointIT.backfillCreateSeedsTimepointAndSupportsContinuousEntry`（E21-T14） |
| C-ACC-27 补录链路 | 不需任务、不受窗口限制；来源标「补录（纸质）」（source=BACKFILL）；不早于当时器械植入日期、不晚于今天（40001） | `cAcc27BackfillWithoutTaskContinuousEntryAndBounds`（空档日期直接新建、task_id NULL、创建即解析器械维度、早于植入 / 不早于今天 40001）+ 行为面 `BackfillTimepointIT` / `BackfillTimelineIT`（E15-T07）/ `RecordBackfillServiceTest` |
| C-ACC-27 窗口联动 | 开关开启时 0 / 1 / 多候选、双向关联与同请求幂等 | `BackfillTaskLinkIT`；窗口按实际随访日期，已有记录不覆盖；医院 UAT 待执行 |
| C-ACC-27 连续补录 | 「继续补录下一次」保留患者与器械、清空测量值，不需反复打开表单（Tab 跳项、回车继续，OQ-145①） | `cAcc27BackfillWithoutTaskContinuousEntryAndBounds`（第二条同患者同器械、新草稿只种建议、测量值为空）+ 行为面 `BackfillTimepointIT`（E21-T14）；键盘流前端 `backfill-logic.spec` / `record logic.spec` |
| C-ACC-25 序号重排 | 补录较早日期后序号 +1、变更历史有重排记录、内部记录号不变 | `SequenceNumberIT`（E21-T09，评审 §6 原指派承载） |

## 4. 补录日期落在未完成任务窗口内 → 联动完成（OQR-01，OQ-148）

历史缺口：E15-T07 原来无任务锚点的补录提交不联动完成任务；2026-11-18 用户定论选 B，仅医院 C 开关启用，A / B 保持既有行为。

本地代码现已通过 V143 / backfill_task_link 实现：同患者、当前器械、同表单、窗口内的可完成普通任务，零候选独立提交，唯一候选自动关联，多候选显式选择。提交时范围、状态与已有记录再次校验，双向关联、状态完成、首次提交事实及审计同事务；不能覆盖已有草稿 / 记录，重复提交按请求摘要幂等。暂停、历史器械不改变当前排期，删除记录不重开任务且从统计排除。

机器承载：新增 BackfillTaskLinkIT（双租户、窗口首尾闭区间、多选、开关关闭、历史器械 / 暂停、已有记录、HTTP 权限 / 范围、并发、回滚、幂等与删除统计），原 CNextVisitBackfillAcceptanceIT 6 项继续回归未开启联动时的登记 / 窗口 / 连续补录行为。C-ACC-27 末句的本地自动化验证已通过；未推送 CI、未启用生产开关，医院 UAT / 人工多候选选择与部署验收保持待执行，不宣称医院已验收。

实施细节与最终验证记录见 [OQR-01 实施记录](../reviews/oqr-01-backfill-task-link.md)。

## 5. 执行记录

| 批次 | 环境 | 结果 |
|---|---|---|
| 2026-11-13（本任务交付批） | 本机 docker compose MySQL（CRDP_IT_MYSQL_PORT=13306）：`CNextVisitBackfillAcceptanceIT` 6 例全绿；与 `CSwitchAcceptanceIT` / `DoctorNextDateIT` / `BackfillTimepointIT` / `SequenceNumberIT` 联跑 24 例全绿（预置发布态还原互不干扰）；全量 `mvn -B spotless:check test` 见 PR 描述 | 通过（§4 所列条款除外） |
| CI | 每次 push 门控执行（与 A / B 全量回归同套） | 见 PR checks |

> 用例文件为唯一机器面载体：新增 / 修改行为时以本表 C-ACC 编号回链；C-ACC-16 任一面（行为面 / 配置链面）缺失即视为该行未通过。


### OQR-06 本地复测（2026-11-19）

CNextVisitBackfillAcceptanceIT 的 6 项机器用例已纳入本轮 203 项专项 / 兼容回归并通过。C-ACC-16 的计划日、±30 天窗口、开启日与一次消费算例保持；缓存断言更新为实际绑定医生日期，不再声称缓存必须保持默认日期。新绑定 / 竞争 / 回退与事务回滚用例见 [OQR-06 实施记录](../reviews/oqr-06-current-device-schedule.md)。本节不代表医院 UAT 或生产验收完成。
