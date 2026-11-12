# 医院 C：植入记录、电极、器械与计划切换（implant）

> 仅医院 C 使用，功能开关 `implant`（ERI 循环另有 `eri_cycle`）。后端：`crdp-followup`；表 `pt_implant`、`pt_lead`、`pt_implant_lead`、`fu_plan_mapping`、`fu_enrollment`（切换三列 `switch_reason` / `eri_since` / `doctor_next_date`，followup V88）。
> 页面：`docs/design/pages.md` §4（C 基线资料页签内的植入记录区，C“植入记录”已并入“基线资料”）、§8（计划页“器械→计划映射”）。
> 需求：C-PAT-004..008、C-PAT-010、C-FU-003、C-FU-005..007、C-FU-009。规格原文见 `docs/requirements/hospital-c-spec.md` 第 4、5 章。
> §1 的 GET / POST / PUT / options 已随 E21-T01 落地；replace / explant 已随 E21-T02 落地（原推断的 `leads/revise` 并入统一 replace 端点，见 OQ-132）；§2 的 device-mapping GET / PUT 与 §3 的自动入组已随 E21-T05 落地；§3 的计划切换触发事件（升级 / 更换 / 更正 / 取出 / ERI / 医生指定，C-FU-007 切换表十行）已随 E21-T06 落地（`PlanSwitchService`，后端事件、无独立接口）；原推断的 `fu_switch_rule` 配置表不落地——切换行为是 C 规格第 5 章定死的行为规则，不做租户配置（ERI 期 1–3 个月间隔可设的部分随 E21-T07）；eri 单独标记端点随 E21-T07。

## 1. 植入记录与电极

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/implants` | `followup:patient:view` | 全部植入记录（当前器械置顶，其余按植入日期新在前；每条含 `implantNo` 按日期升序编号与电极清单、编码 + 显示名一并下发） | C-PAT-004、005 |
| POST | `/api/hospital/followup/patients/{id}/implants` | `followup:implant:edit` | 新建（首次植入 / 历史建档允许过去日期，不能晚于今天）；新器械接替在用时旧记录自动结束为已更换（结束日期 = 新植入日期），补录早于在用的历史器械直接落已更换；一位患者仅一条在用（库层生成列唯一约束兜底，并发 40900）。在用（新植 / 接替）记录保存后按器械类型映射自动入组（§3，E21-T05 已实现；历史补录不触发） | C-PAT-004、008 |
| PUT | `/api/hospital/followup/implants/{implantId}` | `followup:implant:edit` | 编辑；`{ baseVersion, implant{...} }`（乐观锁 40900）；状态与结束信息系统维护；`implant.leads` 为 null 不动电极、非 null 整组替换（无 id 新植、带 id 更新、缺席解链并删行）；写 IMPLANT_CREATE / IMPLANT_UPDATE 操作审计（字段级历史随 C 字段字典录入 E23-T20 的 C_IMPLANT 模板，OQ-129②） | C-PAT-004、005 |
| GET | `/api/hospital/followup/implants/options` | `followup:patient:view` | 选项清单（植入类型 / 起搏器类型 / 品牌 / 起搏模式（统一字典）/ 植入指征 / 电极部位按 RA / RV / LV 分组 / 电极状态）。业务字典（E21-T04 / C-FLD-002、C-FLD-003）现读生效项：起搏器类型 `pacemaker_type`、品牌 `pacemaker_brand`、起搏模式 `pacing_mode`（Q08 定稿 11 项：SSI、VVI、VVIR、AAI、AAIR、DDD、DDDR、AAI-DDD、AAIR-DDDR、CRT-P、CRT-D）、植入指征 `implant_indication`、电极部位 `lead_position_ra/rv/lv`，平台库 V70 预置、本院可停用平台项 / 新增自有项（停用项不进下拉，历史值照常显示与保存，C-FLD-009）；顺序按字典 sortNo；植入类型与状态为系统枚举 | C-PAT-004、005、C-FLD-002、C-FLD-003 |
| POST | `/api/hospital/followup/implants/{implantId}/replace` | `followup:implant:edit` | 更换 / 升级 / 电极修正 / 其他专用流程（E21-T02，C-PAT-006）。`{ baseVersion, type: REPLACE\|UPGRADE\|LEAD_REVISION\|OTHER, note?, implant{ 除 implantType / leads 外的植入字段 }, leadActions: [{ leadId, action: KEEP\|ABANDON\|EXTRACT }], newLeads[] }`。目标必须在用（40900），baseVersion 乐观锁（40900）；每次变更新增一条植入记录，旧记录自动结束「已更换」（结束日期 = 新植入日期）——电极修正同样落一条 LEAD_REVISION 记录（SW-5 / C-ACC-06，OQ-132）但其起搏器类型 / 品牌 / 型号 / 序列号须与原记录一致（改了 40001，起搏模式为程控设置可变）；OTHER 须填 note（40001）；FIRST 拒绝（走登记 / 新建）。电极处置：目标下每根**在用**电极必须逐根显式选择（缺项 40001）——KEEP 沿用（电极行不动、保留原植入日期，补挂到新记录下）、ABANDON 废弃留置 / EXTRACT 已拔除（行更新状态、保留在旧记录下）；已非在用电极可改标 ABANDON / EXTRACT、不可 KEEP；newLeads 新植（植入日期缺省 = 手术日期）；无导线新器械不可携带电极（40001）。写 IMPLANT_REPLACE 审计。按新器械类型映射自动入组已随 E21-T05 接入（电极修正不切计划）；升级 / 更换的计划切换（结束原入组 + 终止未完成任务 + 按新类型重新入组起算 = 手术日期）已随 E21-T06 接入（§3，SW-2 / SW-3 / SW-4 / SW-5） | C-PAT-006 |
| POST | `/api/hospital/followup/implants/{implantId}/explant` | `followup:implant:edit` | 取出（E21-T02 / C-FU-007 状态面）。`{ baseVersion, date, reason?, leadActions: [{ leadId, action: ABANDON\|EXTRACT }] }`：在用记录置「已取出」，结束日期 = 取出日期，reason 缺省「器械取出」；在用电极逐根显式处置（KEEP 不属于取出，40001）；取出后无在用器械，再植入走登记 / 新建。日期须在植入日期与今天之间（40001）、目标不在用 40900。写 IMPLANT_EXPLANT 审计；计划侧（结束全部在组入组 + 终止未完成任务、不再创建任务）已随 E21-T06 接入（§3，SW-9） | C-FU-007 |
| POST | `/api/hospital/followup/implants/{implantId}/eri` | `followup:record:edit` | 标记 / 取消接近 ERI（一般由程控记录提交时带出，也允许单独设置；随 E21-T07） | C-FU-006 |

植入记录字段（C-PAT-004）：植入类型（FIRST / REPLACE / UPGRADE / LEAD_REVISION / OTHER）、日期、起搏器类型（SSI / DDD / CRT_P / ICD / CRT_D / HBP_LBBP / LEADLESS，字典 `pacemaker_type`）、品牌（字典 `pacemaker_brand`）、型号、序列号、起搏模式（统一字典 `pacing_mode`，C-FLD-003）、植入医院、医生、指征（字典 `implant_indication` + 说明）、植入说明 `note`（类型选 OTHER 必填，C 规格关键字段表；followup V83 列）、状态（ACTIVE / REPLACED / EXPLANTED，系统维护）。电极（C-PAT-005）：RA / RV / LV、部位（按腔室过滤，字典 `lead_position_ra/rv/lv`）、型号、日期（缺省同植入记录、沿用时保留原日期）、状态（ACTIVE / ABANDONED / EXTRACTED）；无导线起搏器（LEADLESS）不录电极（40001）。字典项取值不进下拉即停用（可恢复），字典外取值保存 40001；停用项的历史值保留照常显示（C-FLD-009）。

患者登记（C-PAT-001 / OQ-71①）：`implant` 开关开启时 `POST /patients` 切 C 最小必填（姓名、性别、出生日期、病历号、科室、病区）且请求体须携带 `implant`（首条植入记录，同事务落库）；关闭时 A 出院登记口径不变、携带 `implant` 40001。

当前器械（C-PAT-007，**E21-T03 已实现**，`DeviceContextService`）：状态为“在用”的植入记录即当前器械；随访记录按**随访日期**关联当时在用的器械（补录同样按日期匹配）——区间 `[implant_date, end_date)`（已结束行的结束日期 = 新植入日期或取出日期，区间右开，**更换当日归新器械**）；`fu_record.visit_date` 为空（任务录入，随访日期在表单字段）回落当前器械。**E21-T11 起关联落列**：fu_record.implant_id（followup V87）在创建草稿与提交时按上述区间解析写入（任务录入回落提交时当前器械），存量 visit_date 行已按区间回填；参数对比「上一次」按同一器械过滤（followup.md §3 previous，OQ-130③）——动态判定仍是口径基准（页面 device 与提交校验随用随算）。

条件显示上下文（C-FU-009 / C-FLD-005，**E21-T03 已实现**）：`GET /records/{id}` 附 `device` 字段（按记录随访日期匹配的器械，见上；implant 开关关闭或无匹配器械为 null）：`{ implantId, type, typeLabel, implantDate, hasRaLead, chambers, current }`；页面以 `{ device }` 传入 FormRenderer 的 `context` 供 `visibleWhen` / `requiredWhen` 使用（ctx 点路径 `device.type` / `device.hasRaLead` / `device.chambers`，components.md §10；`chambers` 为在用电极去重腔室数——AV 间期「双腔及以上」用 `gte 2`，形状由共享用例 form-condition-cases.json 锁定）。**提交侧同一上下文**：`POST /records/{id}/submit` 的完整校验（条件必填）与隐藏字段清空按同一匹配结果求值（心房参数仅 `hasRaLead`、ICD 项目仅 `type in [ICD, CRT_D]`），隐藏字段值清空且不必填；无匹配器械（取出后、植入前、开关关闭）ctx 断链 → 条件不成立 → 隐藏（OQ-65①）。

## 2. 器械 → 计划映射

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | **E21-T05 已实现**。起搏器类型 → 计划（`fu_plan_mapping`，followup V86）：全部字典类型（`pacemaker_type`，按字典序）逐行下发，含未映射项——`{ items: [{ deviceType, deviceTypeLabel, planCode, planName, enabled }] }`，未映射行 `planCode / planName` 为 null、`enabled = false`；`planName` 为该计划族最新版本行的名称；implant 开关关闭（含 demob 等 A / B 租户）40301 | C-FU-003 |
| PUT | `/api/hospital/followup/plans/device-mapping` | `followup:plan:edit` | **E21-T05 已实现**。保存映射（整体替换）：body `{ items: [{ deviceType, planCode?, enabled }] }`；`planCode` 留空 / 空白 = 删除该类型映射；字典外类型 / 重复类型 / 不存在的计划族（fu_plan.code 族）40001；写 `PLAN_MAPPING_SAVE` 操作审计（前后快照）；开关关闭 40301 | C-FU-003 |
| — | — | — | 原推断的 `GET/PUT /plans/switch-rules`（`fu_switch_rule` 配置表）**不落地**（E21-T06 按实际定义取消）：切换触发事件表是 C 规格第 5 章定死的行为规则（内置 `PlanSwitchService`，十行逐行用例锁定），不做租户配置；唯一可配部分——ERI 期循环间隔 1–3 个月——随 E21-T07 落地 | C-FU-007 |

> C 没有 `followup:plan:view` / `publish` / `enroll`，计划相关一律用 `followup:plan:edit`（见 OQ-13）。
> V86 随包预置映射（C-FU-002 的类型划分）：SSI / DDD / CRT_P / HBP_LBBP / LEADLESS → `C_PACER_FU_PLAN`（普通起搏器术后随访），ICD / CRT_D → `C_ICD_FU_PLAN`（ICD/CRT-D 术后随访）；两套预置计划本体随 E23-T20 录入（族编码即契约，落地前映射行惰性——解析不到已发布版本即跳过，见 OQ-135③）。

## 3. 自动入组与计划切换（后端事件，无独立接口）

- **E21-T05 已实现**：保存植入记录（登记同建 / 新建接替在用 / 更换、升级、其他专用流程）后按映射自动入组，起算日 = 植入日期（C-FU-003）——`DevicePlanMappingService.autoEnrollByDeviceType` 在 ImplantService 同一事务内调用：映射停用 / 该族无已发布版本 / 同族已在组 / 患者已死亡或终止为业务性跳过（保存不受影响）；**患者在其他计划族在组时同样跳过**（升级 DDD→CRT-D 等不产生双计划并行任务；**E21-T06 起**该场景先由 `PlanSwitchService` 结束原入组并按新类型重新入组，见下条与 OQ-135②）；电极修正与补录早于在用的历史器械（落已更换）不触发。入组即建补录 / 当期任务沿用 E14-T05（历史建档只为今天之后开始的窗口建任务，之前节点不算逾期，C-PAT-008 / Q14）。手工改选计划走既有入组入口（批量入组单条，E13-T07）：起算快照为空时任务创建侧按计划的 `IMPLANT_DATE` 起算字段解析在用器械植入日期回填（`FollowupTaskMapper.resolveStartDate`）。
- **E21-T06 已实现（C-FU-007 切换表十行，`PlanSwitchService`，docs/reviews/hospital-c-acceptance-review.md §3.3.1 SW-1…SW-10 逐行用例）**。换计划各行（升级 / 电池更换 / 器械类型更正 / 取出）统一落法：原入组结束（`fu_enrollment.status` → `SWITCHED` 新终态 + `switch_reason`，V88；不落患者级「已终止」——再植入须能重新入组）→ 未完成任务自动终止并记录原因（终止三列）→ 按新器械类型映射重新入组（起算快照 = 新植入日期；**器械类型更正例外**：编辑在用记录改类型时仍按原植入日期，且未开始任务随重新入组按新计划重算、已完成记录不变）。升级为 ICD / CRT-D 转 ICD/CRT-D 计划；升级为 CRT-P / 普通类型留在普通计划族（CL-02 假设三）；电池更换（同类型）计划类型不变、ERI 期循环结束（新入组 mode = NORMAL）、从更换日期重走术后节点（CL-02 假设二）；电极修正不切换；取出且未再植入只结束入组、不重新入组。新类型未映射 / 映射族无已发布版本时保留原入组（防御，OQ-137⑤）。每次切换写一条 `PLAN_SWITCH` 操作审计（含 from/to 计划族、终止任务数、起算日）。编辑在用记录仅改植入日期时接入 E14-T07 起算重算（A-FU-011 口径）。
- **ERI 标记变化（C-FU-006，同一提交事务）**：随访记录提交 / 修改已提交记录时按 C_PROGRAM_FU 稳定键（OQ-130①）判定——ERI 状态 `f323` ∈ {1 接近ERI, 2 已达ERI(EOI)} 或预计电池寿命 `f324` ≤ 1 年 → 在组入组 `mode` 翻 **ERI**、`eri_since` = 该次随访日期（fu_record.visit_date ?? 表单随访日期 ?? 今天），常规未完成任务终止（循环接管）；后续 `f323` = 0 且电池寿命不再偏低 → `mode` 回 **NORMAL**、`eri_since` 清空（恢复原计划的常规间隔，OQ-137②）。A / B 表单无 f323 / f324 键，天然不触发。ERI 期循环任务的创建（每 1–3 个月、从 eri_since 起算）随 E21-T07。
- **医生指定下次随访时间（C-FU-005 切换表末行）**：提交 / 修改记录带 `f333`（日期，今天或未来）→ 该患者在组入组登记 `doctor_next_date`（每次提交覆盖、只影响下一次任务），**不改变计划类型 / 模式**；早于今天的值忽略（补录的历史记录只保存，C 规格 §5）。窗口按节点宽度围绕该日期展开与任务创建消费随 E21-T08。
- （登记面）下次随访时间的覆盖随 E21-T06 先落「登记 `doctor_next_date`」；「窗口围绕展开 → 建任务」的消费侧随 E21-T08（原推断的开关 `next_visit_as_plan` 取消，无需开关——字段属于 C 程控表单）。
- （原推断的响应扩展不落地）切换结果写 `PLAN_SWITCH` 操作审计与 `fu_enrollment` 行（`switch_reason` / `mode` / `eri_since` / `doctor_next_date`），replace / explant 接口响应形态不变（ImplantView）。

## 4. 测试要点

- 2026-03 更换器械后：档案两条植入记录、当前器械为新器械；2025 年记录仍关联旧器械（C 规格验收示例）。
- 更换时 RV 电极选「沿用」→ 新器械下该电极植入日期显示为原日期（同一 pt_lead 行挂新记录，趋势连续的数据面，C-ACC-06）。
- 电极修正落一条 LEAD_REVISION 记录（旧记录同样结束为已更换）但脉冲发生器不变；「其他」类型须填说明。
- 补录早于当前器械植入日期的记录 → 自动关联旧器械，对比不跨器械。
- 下次随访时间 2027-01-15、窗口宽度 ±30 天 → 窗口 2026-12-16 至 2027-02-14（C 规格示例）。
