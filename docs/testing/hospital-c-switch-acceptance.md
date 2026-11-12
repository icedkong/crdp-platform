# 医院 C 植入、计划切换与 ERI 用例：切换表执行留痕（W41 · E21-T25）

> 任务 E21-T25（测试，4 人天，依赖 **E21-T07** ✅；验收：**切换表全部用例通过**）。关联需求 **C-FU-006**（接近 ERI 加密随访）、**C-FU-007**（植入记录变更触发计划切换）；验收面 = 医院 C 规格 §5「计划切换触发事件」表（Q01 / Q03 已确认）十行逐行用例（`docs/reviews/hospital-c-acceptance-review.md` §3.3.1 SW-1…SW-10，C-ACC-14 执行清单）。
> 承接范围（评审 §6 移交）：C-ACC-05…07、13…15、17…18、25 所列**机器 / 行为面**（生产侧动作不在本任务——C 上线随 E23-T23 / E23-T27）。全部用例为机器可判定，CI 与本机同口径执行；数据全部虚构（`IT-E21T25-` 前缀，自建自清）。

## 1. 验收口径

- 「切换表全部用例通过」= §2 十行**全部通过**，且每行同时过两面：
  - **行为面**（`PlanSwitchIT`，E21-T06 / T07 随实现交付）：自建计划与映射夹具、可控参数下锁定切换规则本身（原入组 SWITCHED + switch_reason、未完成任务终止三列、重新入组起算快照、mode / eri_since / doctor_next_date）；
  - **产品配置链面**（`CSwitchAcceptanceIT`，本任务新增）：按 runbook `docs/ops/hospital-c-plan-preset.md` §3 激活路径——发布 followup V91 两套预置（`C_PACER_FU_PLAN` / `C_ICD_FU_PLAN`，随包 UNPUBLISHED 草稿，OQ-128②）→ 保存植入记录按 followup V86 **预置映射**自动入组（不 PUT 改映射）→ 十行触发后由**预置节点**（Q03 节点表：术后 72 小时〔前 3 后 4〕/ 1 / 3 / 6 个月 / 1 年、常规随访 12 / 6 个月 ±30 / ±14、ERI 期 1 个月 ±7）真实排期，日期与窗口宽度逐项落库核对。
- 门控 `CRDP_IT_MYSQL_PORT`（CI 起 docker compose 注入端口；本机先 `docker compose up -d`）。发布态与映射在收尾还原（预置回 UNPUBLISHED、impl 开关回种子默认），A / B 租户不受影响。
- 任一行不过 = 验收不通过，修复后十行整组复测。

## 2. 切换表十行执行映射（§3.3.1 逐行）

| 行 | 触发动作 | 预期（计划变化 / 是否重起算） | 行为面（PlanSwitchIT） | 配置链面（CSwitchAcceptanceIT） |
|---|---|---|---|---|
| SW-1 | 新建「首次植入」记录（普通起搏器） | 入普通计划，术后节点从植入日期起算 | `sw1FirstImplantEnrollsByDeviceTypeFromImplantDate` | `sw1FirstImplantEnrollsViaPresetMappingFromImplantDate`（V86 映射 + 入组即建「术后6个月」±14、已过窗口 SKIP〔Q14〕，runbook §3.4 试入组验证） |
| SW-2 | 新增「升级」记录为 CRT-D（原双腔） | 原入组结束、转 ICD 计划，从升级日期重起算，常规间隔变 3–6 个月 | `sw2UpgradeToIcdEndsOldEnrollmentAndReenrollsFromUpgradeDate` | `sw2UpgradeToCrtDSwitchesToIcdPresetPlanFromUpgradeDate`（转 `C_ICD_FU_PLAN`，「术后1个月」±7 开窗即建） |
| SW-3 | 新增「升级」记录为 CRT-P | 转入 / 留在普通计划，从升级日期重起算（CL-02 假设三） | `sw3UpgradeToCrtPStaysInPacerFamilyFromUpgradeDate` | `sw3UpgradeToCrtPStaysInPacerPresetPlanFromUpgradeDate` |
| SW-4 | 新增「更换（电池耗竭）」记录（同类型） | 计划类型不变、ERI 循环结束；从更换日期重走术后节点（CL-02 假设二） | `sw4BatteryReplacementEndsEriCycleAndRestartsNodes` | `sw4BatteryReplacementEndsEriCycleAndRestartsPostopNodes`（ERI 期入组随更换结束、不再创建 ERI 循环任务） |
| SW-5 | 新增「电极修正」记录 | 不切换、不重起算；新电极从修正日期起参与参数对比 | `sw5LeadRevisionDoesNotSwitchPlan` | `sw5LeadRevisionKeepsPresetEnrollmentUntouched`（入组行与当期任务原样保留；电极历史面见 §3 C-ACC-06） |
| SW-6 | 编辑植入记录更正起搏器类型（录入纠错） | 按新类型重新入组、未开始任务按新计划重算、已完成记录不变；起算仍按原植入日期 | `sw6DeviceTypeCorrectionReenrollsFromOriginalImplantDate` | `sw6DeviceTypeCorrectionReenrollsFromOriginalImplantDate`（V86 映射按新类型，起算 = 原植入日期，当期任务重算到新计划节点） |
| SW-7 | 随访录入 ERI 状态 = 接近ERI（或预计电池寿命 ≤1 年） | 切换 ERI 期循环（默认 1 个月），从该次随访日期起算 | `sw7Sw8Sw10EriCycleAndDoctorNextVisitDate` · `sw7LowBatteryLifeAlsoTriggersEriCycle`（电池寿命 0.8 年） | `sw7EriMarkingSwitchesToMonthlyLoopOnPresetEriNode`（mode / eri_since / last_visit_date 快照、常规任务终止原因；预置 ERI 节点第 1 次 = 随访 +1 月 ±7、第 2 次按上次随访滚动〔基准 = max（上次随访，已排最新一次计划日），E21-T07〕；记录直接走随包 `C_PROGRAM_FU` 表单〔V90，f323 键契约 OQ-130①〕） |
| SW-8 | 后续随访 ERI 状态更正为「正常」 | 恢复原计划的常规间隔 | 同 SW-7 方法尾段（`sw7Sw8Sw10EriCycleAndDoctorNextVisitDate`） · `eriClearedRoutineLoopRollsFromLastVisit` | `sw8EriClearedRestoresPacerRoutineCadence`（普通计划常规随访 = 解除那次随访 +12 月 ±30，从上次随访滚动；常规模式不排 ERI 节点） |
| SW-9 | 植入记录状态改为「已取出」且未再植入 | 结束入组，不再创建任务 | `sw9ExplantEndsEnrollmentAndTerminatesTasks` | `sw9ExplantEndsEnrollmentWithoutReenrollment`（无新入组、跑作业不再建任务、患者仍在册随访中） |
| SW-10 | 随访记录填写下次随访时间 | 不改变计划类型，只覆盖下一次任务的计划日期（优先级：医生指定 → ERI 期 → 计划默认） | `sw7Sw8Sw10EriCycleAndDoctorNextVisitDate`（doctor_next_date 登记、计划不变）；优先级数学面 `TaskCreationPlannerTest.doctorDateWinsOverEriLoop` | `sw10DoctorNextDateRegistersAndWinsOverEriLoop`（ERI 模式下医生日期**端到端**覆盖 ERI 循环的下一次：计划日 = 医生日、窗口按目标节点宽度围绕） |

三项 Q03 假设（CL-02：保留术后 1 年节点、电池更换重起算、CRT-P 归普通计划）按评审既定口径进用例，列入 §6 医院复核清单，不阻塞本验收。

## 3. 其余机器面映射（评审 §6 承接的 C-ACC 行）

| 用例 | 机器 / 行为面要点 | 承载（均已随实现任务交付，CI 恒跑 / 门控同口径） |
|---|---|---|
| C-ACC-05（C-PAT-004/005） | 多条植入记录 × 多根电极（RA / RV / LV，部位按腔室过滤）；同一时间仅一条在用 | `ImplantRecordIT`（E21-T01） |
| C-ACC-06（C-PAT-006） | 更换 / 升级 / 电极修正保留历史：旧器械「已更换」、结束日期 = 新植入日期、沿用电极原植入日期、旧电极废弃留置 / 已拔除、「其他」须填说明 | `ImplantReplaceIT`（E21-T02） |
| C-ACC-07（C-PAT-007） | 换机后两条植入记录、当前器械为新器械；旧日期随访记录仍关联旧器械；补录早于当前器械植入日期自动关联当时在用旧器械 | `DeviceContextIT`（E21-T03） |
| C-ACC-13（C-FU-002/003） | 七类器械 → 两套计划映射自动入组、起算 = 植入日期；两套计划节点按 Q03 表预置；映射可配置、可手工改选 | `DevicePlanMappingIT`（E21-T05）+ `CPresetPlanSqlTest`（E23-T20，V86/V91 行级核对）+ 本任务 `CSwitchAcceptanceIT`（预置链激活端到端） |
| C-ACC-15（C-FU-006） | ERI 录入 → 下一次任务按 ERI 期创建（1 个月 ±7）；电池寿命 ≤1 年同样标记；更换器械 → 恢复新器械计划、ERI 循环结束 | `PlanSwitchIT.eriLoopSchedulesMonthlyFromVisitDateAndEndsOnReplacement`（E21-T07）+ §2 SW-7 / SW-4 配置链面 |
| C-ACC-17（C-FU-004 / C-NFR-007） | 每日定时在窗口开始日创建任务、未来节点不预生成、补跑 | `FollowupTaskSchedulerIT`（E14-T04 / E21-T05）；C 预置节点参数下的按需创建见 §2 各行（开启日 −窗口前 跑 `FollowupTaskCreationService.runForDate`） |
| C-ACC-18（C-FU-020/023，机器面子集） | 计划切换（升级 / 更换）后患者时间轴连续 | `CSwitchAcceptanceIT.timelineStaysContinuousAcrossPlanSwitch`（切换前补录记录仍在轴上、旧任务保留为历史节点、下次计划日期来自新入组且恰一条）；任务列表 C 列与时间轴 C 展示适配随 E21-T23 交付、机器面届时由 E21-T28 复核 |
| C-ACC-25（C-FU-018） | 补录插入后序号自动加 1、变更历史有重排记录、内部记录号不变 | `SequenceNumberIT`（E21-T09） |

## 4. 执行记录

| 批次 | 环境 | 结果 |
|---|---|---|
| 2026-11-13（本任务交付批） | 本机 docker compose MySQL（CRDP_IT_MYSQL_PORT=13306，重建库）`CSwitchAcceptanceIT` 11 例全绿；全量 `mvn -B spotless:check test` 与前端 format / lint / test / build 见 PR 描述 | 通过 |
| CI | 每次 push 门控执行（与 A / B 全量回归同套） | 见 PR checks |

> 用例文件为唯一机器面载体：新增 / 修改行为时以本表 SW-x 编号回链；十行任一面（行为面 / 配置链面）缺失即视为该行未通过。
