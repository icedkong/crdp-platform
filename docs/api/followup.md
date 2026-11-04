# 随访计划、任务、记录、沟通（followup）

> 后端：`crdp-followup`；表 `fu_plan`、`fu_plan_node`、`fu_enrollment`、`fu_task`、`fu_record`、`fu_contact_log`、`fu_saved_filter`。
> 页面：`docs/design/pages.md` §4（随访页签、沟通）、§5（整页随访表单）、§7（任务）、§8（计划）。组件：FollowupTimeline、FormRenderer、ContactLogModal、AudioPlayer、ConflictBanner、FormStatusBar。
> **规格已定**：`PUT /api/hospital/followup/records/{id}/draft`（`baseVersion`、409）——**E25-T01 已实现**（载体 fe_record，OQ-77①）。其余 **推断，待确认**。
> 权限差异：A 用 `followup:plan:view/edit/publish/enroll`；C 只有 `followup:plan:edit`。修改已提交记录：A 用 `followup:record:edit`，C 用 `followup:record:edit-submitted`；删除记录两院都是 `followup:record:delete`。

## 1. 计划（A-FU-002/016..022、C-FU-001/002）

| 方法 | 路径 | 权限（A / C） | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/plans` | `plan:view` / `plan:edit` | 列表：名称、版本、状态（`UNPUBLISHED/PUBLISHED/RUNNING/PAUSED/DISABLED`）、入组人数 |
| POST | `/api/hospital/followup/plans` | `plan:edit` | 新建（未发布）；body 可带 `autoEnroll`（自动入组开关，Q35，缺省 false） |
| GET | `/api/hospital/followup/plans/{id}` | `plan:view` / `plan:edit` | 详情：计划 → 子计划 → 节点（偏移、提醒、窗口、周期、事件类型 `FORM|REMINDER`、表单 code） |
| PUT | `/api/hospital/followup/plans/{id}` | `plan:edit` | 仅未发布可改，否则 40901；body 可带 `autoEnroll`（Q35） |
| PUT | `/api/hospital/followup/plans/{id}/nodes` | `plan:edit` | 整体保存节点 `{ version, nodes[] }` |
| POST | `/api/hospital/followup/plans/{id}/publish` | `plan:publish` / `plan:edit` | 发布后不可改（A-FU-019） |
| POST | `/api/hospital/followup/plans/{id}/copy` | `plan:edit` | 复制出新版本（A-FU-019） |
| POST | `/api/hospital/followup/plans/{id}/pause` · `/resume` · `/disable` | `plan:publish` / `plan:edit` | 计划状态（A-FU-018），写计划变更审计（A-FU-021） |
| GET | `/api/hospital/followup/plans/{id}/enrollments` | `plan:view` / `plan:edit` | **E14-T08 已实现**。计划患者列表（A-FU-022）：`?pageNo=1&pageSize=20`（上限 100）→ `{ items, total, pageNo, pageSize }`；行 `{ id, patientId, name, mrn(脱敏), legacyNo, gender, wardName, mode, status(ACTIVE\|PAUSED\|REMOVED), pauseReason, removeReason, enrolledAt, completedCount }`，按加入时间倒序；姓名 / 病案号只含脱敏值，按当前用户数据范围出行（`@DataScope`，范围为空返回空列表）；`completedCount` = fu_task COMPLETED 聚合（E15-T01 流转后累计）；计划不存在 40400 |
| POST | `/api/hospital/followup/plans/{id}/enrollments/batch-preview` | `plan:enroll` / `plan:edit` | **E13-T07 已实现**（A-PAT-016「确认前显示将加入人数与将跳过人数」，OQ-80②）：body 与执行一致 `{ patientIds[], doctorId? }`；逐条 `ENROLL / SKIP + reason`（`ALREADY_ENROLLED` 已在组 / `REMOVED` 已从该计划移除〔优先于患者级已终止，OQ-80①〕/ `DEAD` / `TERMINATED` / `NOT_FOUND` 越范围〔不带摘要〕/ `DOCTOR_NOT_IN_WARD`），不写数据；→ `{ items, succeeded(将加入), skipped(将跳过) }`；写 `BATCH_QUERY` 数据访问审计 |
| POST | `/api/hospital/followup/plans/{id}/enrollments/batch` | `plan:enroll` / `plan:edit` | **E13-T07 已实现**（A-PAT-016）：`{ patientIds[], doctorId? }`（单次 ≤ 500、去重保序；`startDateField` 不收——计划自带配置、起算快照由任务创建侧回填，OQ-80⑤）；逐条先指定责任医生（可选，A-PAT-017 同口径）再经 `enroll`（入组即建补录 / 当期任务，E14-T05），并发状态变化按 `CONFLICT` 逐条跳过不中断整批；→ `{ items: [{patientId, name(脱敏), patientNo(脱敏), wardName, outcome: SUCCESS\|SKIP, reason?}], succeeded, skipped }`（批量操作结果逐条可查，E13-T07 验收）；写一条 `PLAN_ENROLL_BATCH` 汇总审计（单条成功另有 `PLAN_ENROLL`，OQ-80④） |
| POST | `/api/hospital/followup/plans/{id}/enrollments/{enrollmentId}/pause` | `plan:enroll` / `plan:edit` | **E14-T06 已实现**。暂停计划内患者（A-FU-008 / A-PAT-011）：body `{ reason }` 须填（拒绝随访 / 外地随访 / 其他，≤255）；ACTIVE → PAUSED，暂停期间不创建任务、不发通知；入组不在该计划 / 患者越数据范围 40400；非 ACTIVE 40901；返回 `{ id, patientId, planId, status: 'PAUSED', pausedSkipped: 0, normal: 0, terminated: 0 }` |
| POST | `/api/hospital/followup/plans/{id}/enrollments/{enrollmentId}/resume` | `plan:enroll` / `plan:edit` | **E14-T06 已实现**。恢复计划内患者（A-FU-008「恢复后从下一个窗口起继续按需创建任务」）：PAUSED → ACTIVE，同一事务补建暂停区间内任务——窗口已结束的次数建为 `create_type = PAUSED_SKIPPED`（暂停期间未执行，A 的 Q32）、状态 `SKIPPED_SUSPENDED`、不发 `TaskOpened`（由医生选择补做或关闭），窗口未结束的当期任务正常创建并照常发 `TaskOpened`；患者已死亡 / 已终止 40901；返回 `{ ..., status: 'ACTIVE', pausedSkipped, normal, terminated: 0 }` |
| POST | `/api/hospital/followup/plans/{id}/enrollments/{enrollmentId}/remove` | `plan:enroll` / `plan:edit` | **E14-T06 已实现**。移出计划内患者（A-FU-008 / A-PAT-011 须填原因）：在组（含已暂停）→ REMOVED；该入组全部未完成任务（待随访 / 进行中 / 逾期 / 期间未执行）自动终止，`terminate_reason` = 移除原因（fu_task 终止三列，followup V51），之后不再创建任务；返回 `{ ..., status: 'REMOVED', terminated }` |
| GET | `/api/hospital/followup/plans/{id}/logs` | `plan:view` / `plan:edit` | **E14-T08 已实现**。计划变更审计（A-FU-021）：倒序最近 100 条 `{ items: [{ id, planId, planCode, action, description, beforeJson, afterJson, operatorId, operatorName(联查 sys_user), createdAt }] }`；action ∈ `PLAN_CREATE/PLAN_UPDATE/PLAN_NODE_SAVE/PLAN_PUBLISH/PLAN_COPY/PLAN_PAUSE/PLAN_RESUME/PLAN_DISABLE`；计划不存在 40400 |

E14-T01 已实现（OQ-76 暂行口径）：每个版本一行 `fu_plan`（`code` 跨版本不变、`version` 递增）；「随访中 / RUNNING」为派生态（PUBLISHED 且有在组入组，不入库）；发布新版本时同族旧已发布 / 已暂停版本自动转 DISABLED（不再允许入组 → 新入组必然用新版本；在组患者沿用原版本，A-FU-020 迁移延后）；停用为终态（恢复 40901）；基本信息与节点共用乐观锁 `lockVersion`（保存带 `baseVersion`，冲突 40900）；节点两层共表 `fu_plan_node`，`PUT .../nodes` body `{ baseVersion, nodes[] }`（节点行带 `key` / `parentKey` 请求内引用，整体替换，仅未发布）；发布前置至少一个子计划与一个节点。入组领域入口 `PlanEnrollmentService.enroll(patientId, planId, mode)`（批量入组 E13-T07、自动入组 E14-T05 复用；入组只允许 PUBLISHED，同一计划族至多一条在组入组；批量接口随 E13-T07、暂停恢复移出随 E14-T06）。权限「任一码命中即放行」由 `@RequiresPermission` 多值实现（OQ-13）。

E14-T05 已实现（Q44 建档补录 + Q35 自动入组）：`PlanEnrollmentService.enroll` 入组当天同一事务内即时建任务——窗口在入组日前已结束的次数按节点 `passed_window_action` 处理：`BACKFILL` 照常建任务并标 `create_type = BACKFILL`（补录：PENDING 可直接录入、**不写** `TaskOpened` 事件 → 不进即将逾期 / 已逾期 / 失访候选通知，E15-T01 状态流转同样按 create_type 豁免逾期；提交后为「已完成（补录）」，统计按 Q38 照常计入）；`SKIP` 或未配置不建（医院 C 口径 Q14）；窗口已开启未结束的次数当天正常创建（NORMAL，照常发 `TaskOpened`）；未来次数不预生成只刷新缓存；起算日期快照为空时入组侧解析回填（与每日调度同口径）；任务建不出来整个入组回滚（每日调度不补入组前已过窗口，OQ-78③）。自动入组（Q35）：`PatientRegistrationService.create`（出院登记，E13-T01 接口）登记成功后调 `PlanEnrollmentService.autoEnrollOnRegistration`——加入全部 `auto_enroll = 1` 且 PUBLISHED 的计划（`fu_plan.auto_enroll`，followup V50；复制新版本沿用开关、仅未发布可改），出院状态为死亡（dischargeStatus = 3）不加入；单个计划的业务性失败只跳过不中断登记，登记侧重故障记 `PLAN_ENROLL` FAIL 审计（登记本体不回滚）。历史导入（E18）不走 enroll 入口，按自身规则生成历史记录。

E14-T08 已实现（A-FU-021「计划改动全记审计」、A-FU-022 计划患者列表）：每次成功改动（新建 / 编辑 / 保存节点 / 发布 / 复制 / 暂停 / 恢复 / 停用）双写 aud_log 审计与 `fu_plan_log` 变更记录（followup V49，只追加）——审计只追加且缺 `(object_type, object_id)` 索引，`GET .../logs` 从 fu_plan_log 出（口径同 `fe_field_dict_log`，OQ-69③）；前后内容：基本信息为摘要对象（含备注）、节点保存为替换前后节点数组、复制含 `copiedFromVersion`；发布新版本时同族旧版本的自动停用在旧版本自己的记录里落 `PLAN_DISABLE`（逐版本留痕）；被拒操作只写审计 DENIED、不落变更记录。患者列表（`GET .../enrollments`）行含加入时间、随访完成次数、状态三态（已启动 / 已暂停 / 已移除，原因随 E14-T06 的操作写入）；单个患者的暂停 / 恢复 / 移出操作与批量入组入口随 E14-T06 / E13-T07。

E14-T06 已实现（A-PAT-011、A-FU-007、A-FU-008、C-FU-021；OQ-79 暂行口径）：单个患者在计划中的暂停 / 恢复 / 移出（见上三行接口）——暂停 / 移出须填原因并写 `ENROLLMENT_PAUSE` / `ENROLLMENT_RESUME` / `ENROLLMENT_REMOVE` 审计（A-NFR-004；被拒只写 DENIED）；患者级随访状态（pt_patient.followup_status）只在「该患者无其他在组入组」时同步流转（暂停 → PAUSED、恢复 → ACTIVE、移出 → TERMINATED），多计划并存时以在组口径为准，状态时间线 pt_status_history 只追加（source=PLAN）；移出与死亡把未完成任务置 TERMINATED 并落终止三列（terminate_reason / terminated_at / terminated_by，followup V51 同时把 status 列加宽到 VARCHAR(24) 以容纳 SKIPPED_SUSPENDED）。死亡自动终止（A-FU-007，验收「告知已故后未开始任务全部终止」）落领域入口 `FollowupTerminationService.terminateOnDeath(patientId, reason, operatorId)`：患者 → DEAD（时间线 source=SYSTEM）+ 全部未开始 / 未完成任务终止（原因「死亡」）+ 幂等 + 一条 `PATIENT_DEATH_TERMINATE` 审计；HTTP 触发随 E15-T04（沟通结果「告知已故」，A-FU-006）与随访记录提交流程接入（各自先做任务 / 患者范围校验）。停用补偿（PLT-TEN-017）见 §2。

不做：迁移到计划新版本（A-FU-020，延后）。预置计划（A-FU-017、C-FU-002）通过 Flyway 种子写入，**种子只含配置，不含任何真实患者数据**。

## 2. 任务（A-FU-001/003/004/009..013/024/026、C-FU-004/020）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/tasks/search` | `followup:task:view` | `{ mine: boolean, status[], wardIds, doctorId, dueFrom, dueTo, nodeName, keyword, pageNo, pageSize }`；行含患者脱敏信息、节点、窗口、计划日期、状态（components.md §2 task 枚举）、责任人；C 另含器械类型、随访时间点、上次标红项数 |
| GET | `/api/hospital/followup/tasks/board?wardId=&from=&to=` | `followup:task:view` | 看板分组计数（与首页同一查询，A-DASH-003） |
| POST | `/api/hospital/followup/tasks/{id}/claim` | `followup:task:view` | 认领 |
| POST | `/api/hospital/followup/tasks/batch-assign` | `followup:task:assign` | 改派 `{ taskIds[], assigneeId, reason }`（A-FU-009） |
| POST | `/api/hospital/followup/tasks` | `followup:record:edit` | 临时添加任务（A-FU-013） |
| POST | `/api/hospital/followup/tasks/{id}/start` | `followup:record:edit` | 开始录入：无记录则创建 `DRAFT` 记录并返回 `recordId`，任务变“进行中” |
| POST | `/api/hospital/followup/tasks/{id}/confirm-lost` | `followup:record:edit`（推断） | 失访候选 → 失访（医生确认，A-FU-012） |
| GET | `/api/hospital/followup/tasks/queue?cursor=&filter=` | `followup:task:view` | 表单页“上一条 / 下一条”，按当前任务列表筛选的顺序 |

后端规则（无独立接口）：

- 按需生成：任务在“任务开启日”才创建（A-FU-001、C-FU-004）；未来节点在时间轴中以计算值“计划中”展示，不落库。日期计算（E14-T03，A-FU-002、A-NFR-006）由领域库 `PlanDateCalculator` 实现：计划日期 = 基准 + 偏移 +（N − 1）× 周期（月 / 年按总量一次相加，遇月末取当月最后一天）、提醒开始日 = 计划日期 − 提醒提前量、开启日 = min（提醒开始日，窗口开始日），全部按北京时间 `LocalDate`；与前端 `packages/rules/plan-date`（E14-T02 计算预览）共享 A 年度、B 3 个月、B 1 年、B 术后 1/3/5 天验算用例；E14-T10 起跨年、闰年（含世纪年 2100 非闰 / 2000 闰、闰日基准）与窗口（不对称、零宽、跨年、按月钳制）共享用例 `shared-cases/plan-date-cases.json`（components.md §10.5）由后端 `PlanDateSharedCasesTest` 与前端 `packages/rules/src/plan-date-cases.test.ts` 各执行一遍，循环第 N 次（occurrenceCases）仅后端调度端执行（前端预览只算第 1 次）。
- 每日调度（**E14-T04 已实现**，A-NFR-006 / C-NFR-007 建议 00:10，北京时间）：`FollowupTaskScheduler`（cron `0 10 0 * * *` + 启动补跑）按租户扇出（E02-T06 `JobScheduler`，jobCode `fu-task-create`，job_tenant_run 记执行、补跑区间 = 上次成功日 + 1 至当天）；followup 模块未开通的租户跳过（OQ-46②）。作业体 `FollowupTaskCreationService` 逐逻辑日遍历「已启动」入组（计划 PAUSED 不建、被顶替 DISABLED 版本在组沿用照建 OQ-76①；患者死亡 / 终止不建），用 `TaskCreationPlanner`（纯函数）排期后创建 `fu_task` 行：初始 PENDING 待随访、创建方式 NORMAL（**入组即建的补录 / 当期任务由 E14-T05 在 enroll 时同步创建；患者暂停恢复的「暂停期间未执行」由 E14-T06 在 resume 时同步补建（§1）；跟进 / 临时随 E15**），幂等键 uk（入组 + 节点 + 第 N 次），负责人按 A-FU-009 回落链解析（责任医生 → 病区默认负责人 OQ-60 → NULL 待认领）；入组前已过窗口的次数不属每日调度（enroll 时已按 passed_window_action 处理，OQ-78③）、LAST_VISIT 基准只排第 1 次、FIXED 未存周期回落偏移（OQ-78）；刷新入组 `next_plan_date` / `next_open_date` 缓存，新建任务写 `TaskOpened` 事件到租户库 outbox（消费方随 E15，OQ-60③）、每租户每次运行汇总一条 `TASK_CREATE` 审计；**E15-T01 起创建循环后同一次运行内做逾期刷新**（`FollowupTaskStatusService.refreshOverdue`，见下条）。**停用补偿（E14-T06 / PLT-TEN-017 已实现）**：补跑（租户停用恢复、停机）时，本应在开启日创建而未创建、且窗口已在本次运行目标逻辑日前结束的次数，补建为 `create_type = DISABLED_SKIPPED`（停用期间未执行）、状态 `SKIPPED_SUSPENDED`、不发 `TaskOpened`，由人工选择补做或关闭（恢复后不自动补发）；窗口结束日落在此前患者暂停区间内的归 `PAUSED_SKIPPED`（暂停期间未执行）——两种「未执行」共用 SKIPPED_SUSPENDED 状态，以 create_type 区分来源（followup V51）。
- **状态流转、逾期与逾期完成（A-FU-003、A-FU-010，E15-T01 已实现；OQ-82 暂行口径）**：状态机 `TaskStatusFlow`（纯函数，合法方向按 A 规格「关键字段 · 任务状态」表）+ 写入口 `FollowupTaskStatusService`（服务层领域入口，HTTP 随 E15-T02 提交流程 / E15-T04 沟通记录接入，调用方先做任务对应患者的数据范围校验 40400，与 E14-T06 / E14-T07 同口径）：
  - **待随访 → 进行中**：`markInProgress(taskId, operatorId, trigger)`，第一次联系（E15-T04）或暂存草稿（E25-T01 的 `RecordDraftService.saveDraft` 成功后，OQ-77② 接入点）触发；只从 PENDING 进，**逾期不退回进行中**（逾期是窗口事实，联系 / 录入不改写，OQ-82①）；
  - **逾期（每日 00:10 调度内刷新，tech-plan §8 步骤 W）**：`refreshOverdue(asOf)` 在任务创建循环后按本次运行目标逻辑日执行——窗口结束日次日（window_end < asOf）仍未完成（待随访 / 进行中）且**非补录**（create_type = BACKFILL 豁免，建任务时窗口已过、语义是补录历史，E14-T05 口径；跟进 / 临时任务随 E15-T05 / T06 落地后按各自窗口同口径参与）的任务置 OVERDUE，同事务写 `TASK_OVERDUE` 事件（事件类型 = `NotifyEventType.TASK_OVERDUE`，负载契约 `NotifyEventPayload`：患者姓名经 CryptoService 解密只进站内信密文列——E09-T04 规则引擎消费：站内信即时、短信并入每日汇总、可抄送病区负责人）与一条 `TASK_OVERDUE_MARK` 汇总审计；补跑 / 重放幂等（状态守卫，已 OVERDUE 落空不再发事件）；
  - **完成（提交联动）**：`complete(taskId, operatorId)` → COMPLETED 并按完成业务日与窗口的相对关系落 `complete_kind`（followup V53，验收「逾期完成单独标记」）：BEFORE_WINDOW 窗口前完成（开启日早于窗口开始日时窗口开启前提交）/ ON_TIME 窗口内 / OVERDUE 逾期完成（含「期间未执行」补做后完成）；**直接录入期**（A-FU-010 / Q09）：窗口结束后到本节点第 N+1 次的**任务开启日**（A 医院 = 下一次窗口开始日）之前可完成，到点后 40901 只能历史补录（E15-T07）；无下一次（FIXED 排完 / YEARS 周期到界 / 配置缺失）不设界（OQ-82③）；已完成 / 失访 / 已终止 / 期间未执行 40901（期间未执行先补做），并发冲突 40900；写 `TASK_COMPLETE` 审计；
  - **「停用 / 暂停期间未执行」补做与关闭（PLT-TEN-017、A 的 Q32）**：`redoSkipped(taskId, operatorId)`——窗口已结束重回 OVERDUE（OQ-82②，随后照常联系与提交，完成自然落逾期完成）、未结束重回 PENDING；`closeSkipped(taskId, operatorId, reason)`——原因必填（≤255），置 TERMINATED 并落终止三列（人工停止该次随访，复用 E14-T06 终止列）；各写 `TASK_REDO` / `TASK_CLOSE` 审计；非「期间未执行」状态 40901。
  - 失访判定：窗口内连续 3 次“未联系到 / 号码错误”且窗口结束 → 失访候选，“拒访”不计（A-FU-012，随 E15-T05）。
- **起算日期变更重算（A-FU-011，E14-T07 已实现；OQ-81 暂行口径）**：领域入口 `StartDateRecalculationService.recalculateOnBaseDateChange(patientId, operatorId)`——出院日期（以手术日期起算的计划为手术日期，如医院 B）修改后调用；HTTP 触发随患者基础信息编辑（`PUT /patients/{id}`，patient.md §2，随 E13-T02）接入，调用方先做患者数据范围校验（40400）。同一事务内：①逐在组入组（ACTIVE / PAUSED）按计划起算字段重新解析并把起算快照（fu_enrollment.start_date）整体更新，`next_plan_date` / `next_open_date` 缓存**实时**按新日期刷新（未来年度未预先创建、无需清理）；②该入组「已创建但尚无联系记录」的任务（status = PENDING 待随访，含补录 BACKFILL；fu_contact_log 随 E15 落地前的暂行判定，OQ-81②）五个日期列整体按新日期重排（计划日期 / 提醒开始日 / 窗口与开启日，TaskCreationPlanner 同一套次数推进口径），状态、负责人、创建方式与创建事实不变；③新开启日在当天之后的撤回为「计划中」：删任务行（A-FU-003 该显示态不对应实际任务），每日调度到新开启日照常按新日期重建（uk 幂等不破坏），新计划日期超出管理周期（YEARS）边界的同撤回（防御）；④进行中（已有联系 / 录入口径）、已完成、逾期、失访、已终止与「期间未执行」补偿任务**不变**（历史事实，A-FU-011「已完成任务不变」）。不创建新任务：日期改早后新开启的次数由每日调度下一执行日按常规律创建（含停用 / 暂停补偿改判，OQ-81③）；起算字段不可解析或解析值未变的入组幂等跳过；写一条 `PLAN_START_DATE_RECALC` 审计（前后快照与重排 / 撤回计数）。
- 未联系到自动跟进：次日生成跟进任务，每窗口最多 5 次（A-FU-024，P1）；站内提醒类任务（A-FU-026，P1）。
- 定时作业受功能开关与租户状态控制（E14-T04 已按 openedModules 过滤扇出），多实例用调度锁。

## 3. 随访记录（A-FU-005/014/015/027、C-FU-005/008..010/013..018/022/024..026）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/records/{id}` | `followup:task:view` | `{ id, patientId, taskId?, formCode, formVersion, status: DRAFT|SUBMITTED, version, data, meta, context, previous, history[≤3], source: TASK|BACKFILL|UNPLANNED|REMOTE, visitDate, sequenceNo(C) }` |
| PUT | `/api/hospital/followup/records/{id}/draft` | `followup:record:edit` | **E25-T01 已实现**（按钮 plt_menu 222，平台库 V43）。`{ baseVersion, changes: { [fieldKey]: value }, overwrite? }` → `{ version, savedAt }`；changes 只带变更字段（null 值 = 清空该键），只过写入闸门（键 / 类型 / 派生重算 / 硬范围），不做必填 / 跨字段 / 软范围校验、不写字段历史、不逐次写审计、不更新 data_meta（OQ-77）；冲突 409（40900，同 `patient.md` §4.1：currentVersion / updatedBy 显示名 / updatedAt / diffFields 只列 changes 两边不同的键）；「保留我的并覆盖」= currentVersion 作 baseVersion 重发并带 `overwrite: true`，后端写 RECORD_DRAFT_OVERWRITE 审计（只记字段键）；已提交 40901；载体 fe_record（fu_record 随 E15 落地后迁业务表，HTTP 契约不变，OQ-77①）；「任务变进行中」的联动接入点在保存成功后，随 E14-T04 / E15-T01（OQ-77②） |
| POST | `/api/hospital/followup/records/{id}/submit` | `followup:record:edit` | `{ baseVersion, data, meta }`；完整校验（42200）；任务完成；生成下次任务；写字段历史与审计；C 处理“下次随访时间”与 ERI 标记 |
| POST | `/api/hospital/followup/records/{id}/edit-session` | A `record:edit`；C `record:edit-submitted` | 开始修改已提交记录（返回当前 version） |
| PUT | `/api/hospital/followup/records/{id}` | 同上 | 保存修改 `{ baseVersion, data, meta, reason }`；C 原因必填，A 是否必填见 OQ-14；写字段级历史 |
| DELETE | `/api/hospital/followup/records/{id}` | `followup:record:delete` | 软删除，`{ reason }` 必填，审计（C-FU-026） |
| POST | `/api/hospital/followup/patients/{id}/records` | `followup:record:edit` | 计划外随访 / 历史补录 / 远程监测 `{ source, visitDate, formCode }`（A-FU-014、C-FU-013、C-FU-022、C-FU-025） |
| GET | `/api/hospital/followup/patients/{id}/records/check-duplicate?visitDate=` | `followup:record:edit` | 同一患者同一随访日期已有记录 → 提示后可继续（C-FU-024） |
| GET | `/api/hospital/followup/patients/{id}/records/suggest-timepoint?visitDate=` | `followup:record:edit` | C 补录建议随访时间点（距植入日期天数，C-FU-014） |
| GET | `/api/hospital/followup/records/{id}/history?fieldKey=` | `followup:task:view` | 字段级历史 |

- `previous`：A 与上一次已提交的年度记录对比（第 1 年与基线出院前超声对比，A-FU-027）；C 与同一器械 / 电极的上一次对比（C-FU-010）。由后端按规则参数选取，前端不自己找。
- 带入上次值（A-FU-015、C-FU-016）：`GET /records/{id}?withCarry=true` 返回 `carry` 建议值，用户确认后才写入 `data`。
- C 随访序号按随访日期自动重排，内部记录号不变（C-FU-018）。
- 连续补录（C-FU-015）：提交接口返回 `{ nextBackfill: { patientId, implantId } }`，前端清空测量值继续。
- 报告单带入：`report.md` §5。

## 4. 对比标红 / 异常阈值规则（A-FU-027、C-FU-011；开关 `compare_highlight`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/compare-rules?formCode=` | `followup:abnormal:config`（C）；A 无对应码（见 OQ-16） | 规则列表：字段、比较对象（上一次 / 基线 / 固定阈值）、条件、阈值、开关 |
| PUT | `/api/hospital/followup/compare-rules/{id}` | 同上 | 开关、调阈值；写变更记录 |

规则只在录入表单内标红提示（A 规格：不属于预警）。C 的异常标红记录进入首页 / 统计（C-DASH-006）。

## 5. 预警（A-ALR-001..007）

**首期不做**（A 规格 Q30，附录 B）。不要建表、不要建接口。

## 6. 沟通记录（A-FU-006/012/024/025、A-ATT-017、C-FU-019）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/contacts` | `followup:patient:view` | 沟通记录列表（`fu_contact_log`）：时间、方式、结果（components.md §2 contact 枚举）、关联任务、备注、录音 |
| POST | `/api/hospital/followup/patients/{id}/contacts` | `followup:record:edit` | `{ taskId?, method, result, note, audioFileIds[] }`；结果计入失访判定；录音自动生成一份“通话记录”报告单（A-ATT-017） |
| PUT / DELETE | `/api/hospital/followup/contacts/{id}` | `followup:record:edit` | 修改 / 删除（审计） |

录音：MP3 / M4A / WAV / AMR，单个 ≤ 50 MB；MP3、M4A、WAV 浏览器原生播放不转码；AMR 处理见 A 规格 A-FU-025（AudioPlayer，components.md §13）。

## 7. 测试要点

- 已发布计划 PUT → 40901；复制后新版本可改；患者沿用入组时版本。
- 草稿 → 提交 → 修改：版本号递增、字段历史只在提交 / 修改时写。
- C 用户无 `record:edit-submitted` 时 `edit-session` 返回 40300；A 用户有 `record:edit` 即可。
- 失访：3 次未联系到 + 窗口结束 → `LOST_CANDIDATE`；含“拒访”时不计入。
- 越范围患者的任务不出现在搜索结果，直接访问 40400。
