# 医院 C：植入记录、电极、器械与计划切换（implant）

> 仅医院 C 使用，功能开关 `implant`（ERI 循环另有 `eri_cycle`）。后端：`crdp-followup`；表 `pt_implant`、`pt_lead`、`pt_implant_lead`、`fu_plan_mapping`、`fu_switch_rule`。
> 页面：`docs/design/pages.md` §4（C 基线资料页签内的植入记录区，C“植入记录”已并入“基线资料”）、§8（计划页“器械→计划映射”）。
> 需求：C-PAT-004..008、C-PAT-010、C-FU-003、C-FU-005..007、C-FU-009。规格原文见 `docs/requirements/hospital-c-spec.md` 第 4、5 章。
> 全部路径 **推断，待确认**。

## 1. 植入记录与电极

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/implants` | `followup:patient:view` | 全部植入记录（当前器械置顶）及其电极 | C-PAT-004、005、007 |
| POST | `/api/hospital/followup/patients/{id}/implants` | `followup:implant:edit` | 新建（首次植入 / 历史建档允许过去日期）；保存后触发自动入组（§3） | C-PAT-004、008、C-FU-003 |
| PUT | `/api/hospital/followup/implants/{implantId}` | `followup:implant:edit` | 编辑；`{ version, ... }`，写字段历史；器械类型更正会触发 §3 切换 | C-PAT-011、C-FU-007 |
| POST | `/api/hospital/followup/implants/{implantId}/replace` | `followup:implant:edit` | 更换 / 升级：`{ type: REPLACE|UPGRADE|BATTERY, newImplant{...}, leads: [{ leadId, action: KEEP|ABANDON|EXTRACT }], newLeads[] }`；原器械标“已更换”，沿用电极保留原植入日期 | C-PAT-006 |
| POST | `/api/hospital/followup/implants/{implantId}/leads/revise` | `followup:implant:edit` | 电极修正 | C-PAT-006 |
| POST | `/api/hospital/followup/implants/{implantId}/explant` | `followup:implant:edit` | 取出 `{ date, reason }` | C-FU-007 |
| POST | `/api/hospital/followup/implants/{implantId}/eri` | `followup:record:edit` | 标记 / 取消接近 ERI（一般由程控记录提交时带出，也允许单独设置） | C-FU-006 |

植入记录字段（C-PAT-004，表单 `C_IMPLANT` 驱动）：植入类型、日期、起搏器类型、品牌、型号、序列号、起搏模式（统一字典 C-FLD-003）、植入医院、医生、指征、状态。电极（C-PAT-005）：RA / RV / LV、部位、型号、日期、状态；无导线起搏器不录电极。

当前器械（C-PAT-007）：状态为“在用”的植入记录；随访记录按**随访日期**关联当时在用的器械（补录同样按日期匹配），参数对比只与同一器械 / 同一电极的上一次比较。

条件显示上下文（C-FU-009）：后端在 `GET /records/{id}` 的 `context` 中返回 `device.type`、`device.hasRaLead`、`device.chambers`，供 FormRenderer `visibleWhen` 使用（components.md §10）。

## 2. 器械 → 计划映射

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | 起搏器类型 → 计划（`fu_plan_mapping`） |
| PUT | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | 保存映射 |
| GET / PUT | `/api/hospital/followup/plans/switch-rules` | `followup:plan:edit` | 计划切换触发事件表（`fu_switch_rule`），默认值来自 C 规格第 5 章；ERI 期循环 1–3 个月可设 |

> C 没有 `followup:plan:view` / `publish` / `enroll`，计划相关一律用 `followup:plan:edit`（见 OQ-13）。

## 3. 自动入组与计划切换（后端事件，无独立接口）

- 保存植入记录 → 按映射自动入组，起算日 = 植入日期（C-FU-003）；历史建档只为今天之后开始的窗口建任务，之前节点不算逾期（C-PAT-008）。
- 首次植入、升级、电池更换、器械类型更正、取出，以及 ERI 标记变化 → 按切换规则结束原入组 / 切换循环 / 重新起算（C-FU-006、C-FU-007）。
- 随访记录中“下次随访时间”覆盖下一次任务的**计划日期**，窗口按节点宽度围绕它前后展开（C-FU-005；开关 `next_visit_as_plan`，推断）。
- 切换结果写 `pt_status_history` / 入组历史并在接口响应中返回 `{ planChanged: true, fromPlan, toPlan, affectedTasks }`，前端提示用户。

## 4. 测试要点

- 2026-03 更换器械后：档案两条植入记录、当前器械为新器械；2025 年记录仍关联旧器械（C 规格验收示例）。
- 补录早于当前器械植入日期的记录 → 自动关联旧器械，对比不跨器械。
- 下次随访时间 2027-01-15、窗口宽度 ±30 天 → 窗口 2026-12-16 至 2027-02-14（C 规格示例）。
