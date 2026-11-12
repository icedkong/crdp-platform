# 医院 C：植入记录、电极、器械与计划切换（implant）

> 仅医院 C 使用，功能开关 `implant`（ERI 循环另有 `eri_cycle`）。后端：`crdp-followup`；表 `pt_implant`、`pt_lead`、`pt_implant_lead`、`fu_plan_mapping`、`fu_switch_rule`。
> 页面：`docs/design/pages.md` §4（C 基线资料页签内的植入记录区，C“植入记录”已并入“基线资料”）、§8（计划页“器械→计划映射”）。
> 需求：C-PAT-004..008、C-PAT-010、C-FU-003、C-FU-005..007、C-FU-009。规格原文见 `docs/requirements/hospital-c-spec.md` 第 4、5 章。
> §1 的 GET / POST / PUT / options 已随 E21-T01 落地；replace / explant 已随 E21-T02 落地（原推断的 `leads/revise` 并入统一 replace 端点，见 OQ-132）；§2 的 device-mapping GET / PUT 与 §3 的自动入组已随 E21-T05 落地；eri 端点、switch-rules 与 §3 其余条目随后续任务，**推断，待确认**。

## 1. 植入记录与电极

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/implants` | `followup:patient:view` | 全部植入记录（当前器械置顶，其余按植入日期新在前；每条含 `implantNo` 按日期升序编号与电极清单、编码 + 显示名一并下发） | C-PAT-004、005 |
| POST | `/api/hospital/followup/patients/{id}/implants` | `followup:implant:edit` | 新建（首次植入 / 历史建档允许过去日期，不能晚于今天）；新器械接替在用时旧记录自动结束为已更换（结束日期 = 新植入日期），补录早于在用的历史器械直接落已更换；一位患者仅一条在用（库层生成列唯一约束兜底，并发 40900）。在用（新植 / 接替）记录保存后按器械类型映射自动入组（§3，E21-T05 已实现；历史补录不触发） | C-PAT-004、008 |
| PUT | `/api/hospital/followup/implants/{implantId}` | `followup:implant:edit` | 编辑；`{ baseVersion, implant{...} }`（乐观锁 40900）；状态与结束信息系统维护；`implant.leads` 为 null 不动电极、非 null 整组替换（无 id 新植、带 id 更新、缺席解链并删行）；写 IMPLANT_CREATE / IMPLANT_UPDATE 操作审计（字段级历史随 C 字段字典录入 E23-T20 的 C_IMPLANT 模板，OQ-129②） | C-PAT-004、005 |
| GET | `/api/hospital/followup/implants/options` | `followup:patient:view` | 选项清单（植入类型 / 起搏器类型 / 品牌 / 起搏模式（统一字典）/ 植入指征 / 电极部位按 RA / RV / LV 分组 / 电极状态）。业务字典（E21-T04 / C-FLD-002、C-FLD-003）现读生效项：起搏器类型 `pacemaker_type`、品牌 `pacemaker_brand`、起搏模式 `pacing_mode`（Q08 定稿 11 项：SSI、VVI、VVIR、AAI、AAIR、DDD、DDDR、AAI-DDD、AAIR-DDDR、CRT-P、CRT-D）、植入指征 `implant_indication`、电极部位 `lead_position_ra/rv/lv`，平台库 V70 预置、本院可停用平台项 / 新增自有项（停用项不进下拉，历史值照常显示与保存，C-FLD-009）；顺序按字典 sortNo；植入类型与状态为系统枚举 | C-PAT-004、005、C-FLD-002、C-FLD-003 |
| POST | `/api/hospital/followup/implants/{implantId}/replace` | `followup:implant:edit` | 更换 / 升级 / 电极修正 / 其他专用流程（E21-T02，C-PAT-006）。`{ baseVersion, type: REPLACE\|UPGRADE\|LEAD_REVISION\|OTHER, note?, implant{ 除 implantType / leads 外的植入字段 }, leadActions: [{ leadId, action: KEEP\|ABANDON\|EXTRACT }], newLeads[] }`。目标必须在用（40900），baseVersion 乐观锁（40900）；每次变更新增一条植入记录，旧记录自动结束「已更换」（结束日期 = 新植入日期）——电极修正同样落一条 LEAD_REVISION 记录（SW-5 / C-ACC-06，OQ-132）但其起搏器类型 / 品牌 / 型号 / 序列号须与原记录一致（改了 40001，起搏模式为程控设置可变）；OTHER 须填 note（40001）；FIRST 拒绝（走登记 / 新建）。电极处置：目标下每根**在用**电极必须逐根显式选择（缺项 40001）——KEEP 沿用（电极行不动、保留原植入日期，补挂到新记录下）、ABANDON 废弃留置 / EXTRACT 已拔除（行更新状态、保留在旧记录下）；已非在用电极可改标 ABANDON / EXTRACT、不可 KEEP；newLeads 新植（植入日期缺省 = 手术日期）；无导线新器械不可携带电极（40001）。写 IMPLANT_REPLACE 审计。按新器械类型映射自动入组已随 E21-T05 接入（电极修正不切计划）；原入组结束与任务终止等切换规则随 E21-T06 | C-PAT-006 |
| POST | `/api/hospital/followup/implants/{implantId}/explant` | `followup:implant:edit` | 取出（E21-T02 / C-FU-007 状态面）。`{ baseVersion, date, reason?, leadActions: [{ leadId, action: ABANDON\|EXTRACT }] }`：在用记录置「已取出」，结束日期 = 取出日期，reason 缺省「器械取出」；在用电极逐根显式处置（KEEP 不属于取出，40001）；取出后无在用器械，再植入走登记 / 新建。日期须在植入日期与今天之间（40001）、目标不在用 40900。写 IMPLANT_EXPLANT 审计；计划侧（入组结束、任务处理）随 E21-T06 | C-FU-007 |
| POST | `/api/hospital/followup/implants/{implantId}/eri` | `followup:record:edit` | 标记 / 取消接近 ERI（一般由程控记录提交时带出，也允许单独设置；随 E21-T07） | C-FU-006 |

植入记录字段（C-PAT-004）：植入类型（FIRST / REPLACE / UPGRADE / LEAD_REVISION / OTHER）、日期、起搏器类型（SSI / DDD / CRT_P / ICD / CRT_D / HBP_LBBP / LEADLESS，字典 `pacemaker_type`）、品牌（字典 `pacemaker_brand`）、型号、序列号、起搏模式（统一字典 `pacing_mode`，C-FLD-003）、植入医院、医生、指征（字典 `implant_indication` + 说明）、植入说明 `note`（类型选 OTHER 必填，C 规格关键字段表；followup V83 列）、状态（ACTIVE / REPLACED / EXPLANTED，系统维护）。电极（C-PAT-005）：RA / RV / LV、部位（按腔室过滤，字典 `lead_position_ra/rv/lv`）、型号、日期（缺省同植入记录、沿用时保留原日期）、状态（ACTIVE / ABANDONED / EXTRACTED）；无导线起搏器（LEADLESS）不录电极（40001）。字典项取值不进下拉即停用（可恢复），字典外取值保存 40001；停用项的历史值保留照常显示（C-FLD-009）。

患者登记（C-PAT-001 / OQ-71①）：`implant` 开关开启时 `POST /patients` 切 C 最小必填（姓名、性别、出生日期、病历号、科室、病区）且请求体须携带 `implant`（首条植入记录，同事务落库）；关闭时 A 出院登记口径不变、携带 `implant` 40001。

当前器械（C-PAT-007，**E21-T03 已实现**，`DeviceContextService`）：状态为“在用”的植入记录即当前器械；随访记录按**随访日期**关联当时在用的器械（补录同样按日期匹配）——区间 `[implant_date, end_date)`（已结束行的结束日期 = 新植入日期或取出日期，区间右开，**更换当日归新器械**）；`fu_record.visit_date` 为空（任务录入，随访日期在表单字段）回落当前器械。关联随用随算不落列（fu_record 带器械维度与对比过滤随 E21-T11，OQ-130③ / OQ-134③）；参数对比只与同一器械 / 同一电极的上一次比较（同前，随 E21-T11）。

条件显示上下文（C-FU-009 / C-FLD-005，**E21-T03 已实现**）：`GET /records/{id}` 附 `device` 字段（按记录随访日期匹配的器械，见上；implant 开关关闭或无匹配器械为 null）：`{ implantId, type, typeLabel, implantDate, hasRaLead, chambers, current }`；页面以 `{ device }` 传入 FormRenderer 的 `context` 供 `visibleWhen` / `requiredWhen` 使用（ctx 点路径 `device.type` / `device.hasRaLead` / `device.chambers`，components.md §10；`chambers` 为在用电极去重腔室数——AV 间期「双腔及以上」用 `gte 2`，形状由共享用例 form-condition-cases.json 锁定）。**提交侧同一上下文**：`POST /records/{id}/submit` 的完整校验（条件必填）与隐藏字段清空按同一匹配结果求值（心房参数仅 `hasRaLead`、ICD 项目仅 `type in [ICD, CRT_D]`），隐藏字段值清空且不必填；无匹配器械（取出后、植入前、开关关闭）ctx 断链 → 条件不成立 → 隐藏（OQ-65①）。

## 2. 器械 → 计划映射

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | **E21-T05 已实现**。起搏器类型 → 计划（`fu_plan_mapping`，followup V86）：全部字典类型（`pacemaker_type`，按字典序）逐行下发，含未映射项——`{ items: [{ deviceType, deviceTypeLabel, planCode, planName, enabled }] }`，未映射行 `planCode / planName` 为 null、`enabled = false`；`planName` 为该计划族最新版本行的名称；implant 开关关闭（含 demob 等 A / B 租户）40301 | C-FU-003 |
| PUT | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | **E21-T05 已实现**。保存映射（整体替换）：body `{ items: [{ deviceType, planCode?, enabled }] }`；`planCode` 留空 / 空白 = 删除该类型映射；字典外类型 / 重复类型 / 不存在的计划族（fu_plan.code 族）40001；写 `PLAN_MAPPING_SAVE` 操作审计（前后快照）；开关关闭 40301 | C-FU-003 |
| GET / PUT | `/api/hospital/followup/plans/switch-rules` | `followup:plan:edit` | 计划切换触发事件表（`fu_switch_rule`），默认值来自 C 规格第 5 章；ERI 期循环 1–3 个月可设。**推断，待确认**（随 E21-T06） | C-FU-007 |

> C 没有 `followup:plan:view` / `publish` / `enroll`，计划相关一律用 `followup:plan:edit`（见 OQ-13）。
> V86 随包预置映射（C-FU-002 的类型划分）：SSI / DDD / CRT_P / HBP_LBBP / LEADLESS → `C_PACER_FU_PLAN`（普通起搏器术后随访），ICD / CRT_D → `C_ICD_FU_PLAN`（ICD/CRT-D 术后随访）；两套预置计划本体随 E23-T20 录入（族编码即契约，落地前映射行惰性——解析不到已发布版本即跳过，见 OQ-135③）。

## 3. 自动入组与计划切换（后端事件，无独立接口）

- **E21-T05 已实现**：保存植入记录（登记同建 / 新建接替在用 / 更换、升级、其他专用流程）后按映射自动入组，起算日 = 植入日期（C-FU-003）——`DevicePlanMappingService.autoEnrollByDeviceType` 在 ImplantService 同一事务内调用：映射停用 / 该族无已发布版本 / 同族已在组 / 患者已死亡或终止为业务性跳过（保存不受影响）；**患者在其他计划族在组时同样跳过**（升级 DDD→CRT-D 等不产生双计划并行任务，原入组结束与未完成任务终止随 E21-T06，OQ-135②）；电极修正与补录早于在用的历史器械（落已更换）不触发。入组即建补录 / 当期任务沿用 E14-T05（历史建档只为今天之后开始的窗口建任务，之前节点不算逾期，C-PAT-008 / Q14）。手工改选计划走既有入组入口（批量入组单条，E13-T07）：起算快照为空时任务创建侧按计划的 `IMPLANT_DATE` 起算字段解析在用器械植入日期回填（`FollowupTaskMapper.resolveStartDate`）。
- 首次植入、升级、电池更换、器械类型更正、取出，以及 ERI 标记变化 → 按切换规则结束原入组 / 切换循环 / 重新起算（C-FU-006、C-FU-007）。**推断，待确认**（随 E21-T06 / T07）。
- 随访记录中“下次随访时间”覆盖下一次任务的**计划日期**，窗口按节点宽度围绕它前后展开（C-FU-005；开关 `next_visit_as_plan`，推断）。
- 切换结果写 `pt_status_history` / 入组历史并在接口响应中返回 `{ planChanged: true, fromPlan, toPlan, affectedTasks }`，前端提示用户。

## 4. 测试要点

- 2026-03 更换器械后：档案两条植入记录、当前器械为新器械；2025 年记录仍关联旧器械（C 规格验收示例）。
- 更换时 RV 电极选「沿用」→ 新器械下该电极植入日期显示为原日期（同一 pt_lead 行挂新记录，趋势连续的数据面，C-ACC-06）。
- 电极修正落一条 LEAD_REVISION 记录（旧记录同样结束为已更换）但脉冲发生器不变；「其他」类型须填说明。
- 补录早于当前器械植入日期的记录 → 自动关联旧器械，对比不跨器械。
- 下次随访时间 2027-01-15、窗口宽度 ±30 天 → 窗口 2026-12-16 至 2027-02-14（C 规格示例）。
