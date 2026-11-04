# 随访计划、任务、记录、沟通（followup）

> 后端：`crdp-followup`；表 `fu_plan`、`fu_plan_node`、`fu_enrollment`、`fu_task`、`fu_record`、`fu_contact_log`、`fu_saved_filter`。
> 页面：`docs/design/pages.md` §4（随访页签、沟通）、§5（整页随访表单）、§7（任务）、§8（计划）。组件：FollowupTimeline、FormRenderer、ContactLogModal、AudioPlayer、ConflictBanner、FormStatusBar。
> **规格已定**：`PUT /api/hospital/followup/records/{id}/draft`（`baseVersion`、409）——**E25-T01 已实现**（载体 fe_record，OQ-77①）。其余 **推断，待确认**。
> 权限差异：A 用 `followup:plan:view/edit/publish/enroll`；C 只有 `followup:plan:edit`。修改已提交记录：A 用 `followup:record:edit`，C 用 `followup:record:edit-submitted`；删除记录两院都是 `followup:record:delete`。

## 1. 计划（A-FU-002/016..022、C-FU-001/002）

| 方法 | 路径 | 权限（A / C） | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/plans` | `plan:view` / `plan:edit` | 列表：名称、版本、状态（`UNPUBLISHED/PUBLISHED/RUNNING/PAUSED/DISABLED`）、入组人数 |
| POST | `/api/hospital/followup/plans` | `plan:edit` | 新建（未发布） |
| GET | `/api/hospital/followup/plans/{id}` | `plan:view` / `plan:edit` | 详情：计划 → 子计划 → 节点（偏移、提醒、窗口、周期、事件类型 `FORM|REMINDER`、表单 code） |
| PUT | `/api/hospital/followup/plans/{id}` | `plan:edit` | 仅未发布可改，否则 40901 |
| PUT | `/api/hospital/followup/plans/{id}/nodes` | `plan:edit` | 整体保存节点 `{ version, nodes[] }` |
| POST | `/api/hospital/followup/plans/{id}/publish` | `plan:publish` / `plan:edit` | 发布后不可改（A-FU-019） |
| POST | `/api/hospital/followup/plans/{id}/copy` | `plan:edit` | 复制出新版本（A-FU-019） |
| POST | `/api/hospital/followup/plans/{id}/pause` · `/resume` · `/disable` | `plan:publish` / `plan:edit` | 计划状态（A-FU-018），写计划变更审计（A-FU-021） |
| GET | `/api/hospital/followup/plans/{id}/enrollments` | `plan:view` / `plan:edit` | 计划患者列表（A-FU-022） |
| POST | `/api/hospital/followup/plans/{id}/enrollments/batch` | `plan:enroll` / `plan:edit` | 批量入组 `{ patientIds[], startDateField: 'dischargeDate'|..., doctorId? }`（A-PAT-016）；返回成功 / 跳过明细 |
| GET | `/api/hospital/followup/plans/{id}/logs` | `plan:view` / `plan:edit` | 计划变更审计 |

E14-T01 已实现（OQ-76 暂行口径）：每个版本一行 `fu_plan`（`code` 跨版本不变、`version` 递增）；「随访中 / RUNNING」为派生态（PUBLISHED 且有在组入组，不入库）；发布新版本时同族旧已发布 / 已暂停版本自动转 DISABLED（不再允许入组 → 新入组必然用新版本；在组患者沿用原版本，A-FU-020 迁移延后）；停用为终态（恢复 40901）；基本信息与节点共用乐观锁 `lockVersion`（保存带 `baseVersion`，冲突 40900）；节点两层共表 `fu_plan_node`，`PUT .../nodes` body `{ baseVersion, nodes[] }`（节点行带 `key` / `parentKey` 请求内引用，整体替换，仅未发布）；发布前置至少一个子计划与一个节点。入组领域入口 `PlanEnrollmentService.enroll(patientId, planId, mode)`（批量入组 E13-T07、自动入组 E14-T05 复用；入组只允许 PUBLISHED，同一计划族至多一条在组入组；患者列表 / 变更记录 / 批量接口随 E14-T08、暂停恢复移出随 E14-T06）。权限「任一码命中即放行」由 `@RequiresPermission` 多值实现（OQ-13）。

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

- 按需生成：任务在“任务开启日”才创建（A-FU-001、C-FU-004）；未来节点在时间轴中以计算值“计划中”展示，不落库。
- 逾期与逾期完成（A-FU-010）；起算日期变更重算（A-FU-011）；失访判定：窗口内连续 3 次“未联系到 / 号码错误”且窗口结束 → 失访候选，“拒访”不计（A-FU-012）。
- 未联系到自动跟进：次日生成跟进任务，每窗口最多 5 次（A-FU-024，P1）；站内提醒类任务（A-FU-026，P1）。
- 定时作业受功能开关与租户状态控制，多实例用调度锁。

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
