# 患者、基线、草稿、明文查看（patient）

> 后端：`crdp-followup`（患者子域）；表 `pt_patient`、`pt_patient_summary`、`pt_baseline_section`、`pt_baseline_lock`、`pt_contact`（联系方式版本）、`pt_status_history`、`pt_ward_history`、`fu_saved_filter`。
> 页面：`docs/design/pages.md` §3（患者列表）、§4（患者详情、基线编辑）。组件：`components.md` §3 MaskedField、§4 PatientHeader、§10 FormRenderer、§11 AdvancedSearch。
> 前缀 `/api/hospital/followup/`，`@RequireModule("followup")`。
> **规格已定**：基线草稿接口 `PUT /api/hospital/followup/baseline/{patientId}/sections/{code}/draft` 及其 `baseVersion` / 409 语义。其余 **推断，待确认**。
> 所有接口经过数据范围；不在范围内的患者一律 40400。

## 1. 列表与检索（A-PAT-001/002/014/015/019、C-PAT-009/013）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/patients/search` | `followup:patient:view` | **E13-T04 已实现**（简单检索与筛选）；**E13-T06 已并入高级检索条件树**（body 增加 `condition`，结构与 components.md §11.2 一致；字段必须出现在 search-fields 目录，后端编译为参数化查询——取值全部预编译参数位、列名只来自白名单绑定，条件树不含病区条件，由数据范围拦截器自动追加，A-NFR-009；嵌套 ≤ 3 层、条件 ≤ 30 行）。body 见 README §2.4；`keyword` 按姓名 / 病案号**盲索引精确匹配**（一个检索词 OR 命中两个字段，不做模糊，A-PAT-002「病案号、姓名精确查找」）；`filters`：`wardIds[], deptIds[], status[], doctorId, dischargeStatus, patientType, surgeryFrom/To, dischargeFrom/To, nextVisitFrom/To`（术者 / 手术方式随 E13-T02 表单数据、是否有逾期任务随 E15、C 器械类筛选随 E21）；`sort` 白名单 `nextVisitDate / dischargeDate`，默认下次随访日期升序；越范围（病区 / 科室 / 本人范围外）行不可见（@DataScope patient，验收「按病区隔离」）；行内 `name` / `patientNo` 只返回脱敏值；每次检索写 `BATCH_QUERY` 数据访问审计（queryDigest 只记筛选面，不含检索词明文） |
| GET | `/api/hospital/followup/patients/filter-options` | `followup:patient:view` | **E13-T04 已实现**。筛选候选：`{ wards: [{id,name}] }`（启用病区；随访状态等枚举文案由前端 enums 维护） |
| GET | `/api/hospital/followup/saved-filters` | `followup:patient:view` | **E13-T06 已实现**。本人筛选清单（新在前）：`{ items: [{ id, name, payload, updatedAt }] }`，仅本人可见（fu_saved_filter，V42；每用户 ≤ 20 条） |
| POST | `/api/hospital/followup/saved-filters` | `followup:patient:view` | **E13-T06 已实现**。`{ name, payload }`；payload = `{ filters?, condition? }`（**不含检索词 keyword**——检索词可能是患者姓名，与「检索词不写 URL / 审计不存明文」同口径不落库）；condition 保存时即按字段目录编译校验，未知键 / 目录外字段 40001；写 `SAVED_FILTER_CREATE` OPERATION 审计 |
| PUT | `/api/hospital/followup/saved-filters/{id}` | `followup:patient:view` | **E13-T06 已实现**。修改（A-PAT-015「可命名、修改」）：`{ name?, payload? }` 至少一项（重命名 / 覆盖条件，校验同 POST）；写 `SAVED_FILTER_UPDATE` 审计 |
| DELETE | `/api/hospital/followup/saved-filters/{id}` | `followup:patient:view` | **E13-T06 已实现**。删除本人筛选；不存在 / 他人的一律 40400；写 `SAVED_FILTER_DELETE` 审计 |
| GET | `/api/hospital/followup/patients/search-fields` | `followup:patient:view` | **E13-T06 已实现**。高级检索字段目录 `{ fields: [{ field, label, type, categoryKey, categoryLabel, unit, ops, options, common }] }`：由字段字典（fe_field，BASELINE 表单、ENABLED、非敏感、storage=column）∩ 代码内列绑定白名单生成——**只有字典字段且存储已落地（pt_patient 实体列）的字段出现**（JSON 分节字段随 E13-T02、植入字段随 E21 扩绑定）；性别按存储口径 M=男 / F=女，病区选项 = 启用病区（值为 ward.id），出院状态 = 选项集启用项（1–3）；敏感加密字段（姓名 / 病案号 / 电话）不进目录，检索走 keyword 盲索引 |

列表行（A；C 器械列随 E21 植入数据另加）：

```ts
interface PatientRow {
  id: string; name: string /* 已脱敏（张*明） */; patientNo: string /* 本系统编号 = 病案号脱敏（前 2 后 2） */;
  legacyNo?: string; gender: 'M'|'F'; age?: number /* 手术年龄：手术日期 − 出生日期（周岁） */;
  wardName?: string; doctorName?: string; surgeryDate?: string; dischargeDate?: string;
  nextVisitDate?: string /* pt_patient_summary.next_plan_date，E14 写入 */;
  status: PatientStatus; // components.md §2 patient 域（ACTIVE/PAUSED/DEAD/TERMINATED/LOST/FINISHED）
  baselineCompleteness?: number; followupCompleteness?: number | null /* 无已完成记录为 null，前端显示— */;
}
```

- 列表排序默认：下次随访日期升序（E13-T04 实现口径：`next_plan_date` 空值靠后，`p.id` 稳定次序）。
- 完整度只统计已填写的资料（A-PAT-019、C-DASH-009），来源 `pt_patient_summary`，异步刷新；列在 E13-T02 / E15 写入前为 null，前端显示「—」。

## 2. 新建、查重、编辑、删除

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| POST | `/api/hospital/followup/patients/check-duplicate` | `followup:patient:create` | **E13-T01 已实现**。body `{ medicalRecordNo, name?, birthDate? }` → `{ exists, patient?: { id, nameMasked, wardName, dischargeDate }, possibleMatches?: [...] }`；`exists` 按全院（A-PAT-007 本院唯一），范围外的同号患者只返回 `exists: true` 不带摘要；`patient` 只在已有患者位于当前用户数据范围内时下发（前端跳转已有患者）；`possibleMatches` 为姓名 + 出生日期相同的范围内患者（C-PAT-002 可能重复提示，不阻止） | A-PAT-007、C-PAT-002 |
| POST | `/api/hospital/followup/patients` | `followup:patient:create` | **E13-T01 已实现**（出院登记，A-PAT-003 最小必填集：病案号、姓名、性别、病区、手术 / 入院 / 出院日期、出院状态，实体列落 `pt_patient`；C 的差异口径见 OQ-71）。重复返回 40902，`data` 为上面的查重结果结构（范围内带已有患者脱敏摘要）；写 `PATIENT_CREATE` 操作审计（SUCCESS / DENIED，敏感值只存摘要）。基线分节 pt_baseline_section 惰性建行（首次暂存 / 保存时，E13-T02 §4），登记事务不建行；**立即（自动）入组 E14-T05 已实现（Q35）**：登记成功后同事务加入全部 `auto_enroll = 1` 且已发布的计划并按节点策略即时创建补录 / 当期任务（见 followup.md §1「E14-T05 已实现」），出院状态为死亡（dischargeStatus = 3）不加入；自动入组故障不回滚登记（记 `PLAN_ENROLL` FAIL 审计）；C 随访编号与首条植入记录随 E21 | A-PAT-003、C-PAT-001 |
| GET | `/api/hospital/followup/patients/registration-options` | `followup:patient:create` | **E13-T01 已实现**。登记候选：`{ wards: [{id,name}], depts: [{id,name}], genders: [{code,label}], dischargeStatuses: [{code,label}] }`；出院状态编码 1=存活 / 2=病重自动出院 / 3=死亡（A 规格字段 191） | A-PAT-003、C-PAT-001 |
| GET | `/api/hospital/followup/patients/{id}/summary` | `followup:patient:view` | **E13-T05 已实现**。PatientHeader 数据（OQ-21：规格「概览」页 = 顶部 PatientHeader）：脱敏基本信息（姓名 / 病案号 = 编号解密后脱敏）、状态（`terminated` = 死亡 / 停止 / 失访，pages.md §4.10）、病区（`wardId` E13-T08 起随概览下发〔转病区候选排除用〕、`deptName` C 科室病区）、责任医生、基线块（登记实体列 + `baselineCompleteness`，E13-T02 起随分节保存 / 暂存重算写入：已填必填 ÷ 应填必填，A-PAT-019，未保存过分节时 null）、计划块（`nextVisit{planDate,windowStart,windowEnd}`，E14 写入前 null）、最近随访块（`latestFollowup{date,survivalStatus,keyIndicators,endpointEvents}`，E15 写入前 null）、C `device`（E21 前恒 null）；**附带 `actions` 可用操作列表**（`unplanned`/`contact` 需 ACTIVE + `followup:record:edit`；`upload` 需 `followup:scan:upload`；`stop` 需未终止 + `followup:plan:enroll` 或 `followup:patient:edit`〔OQ-15 A/C 差异，其一即可〕；`transfer` 需 `followup:patient:transfer`、不受随访状态限制——转病区是管理动作，已终止患者的病区归属仍可调整〔E13-T08〕；其余操作入口随 E14/E15/E16 开放，前端仍做按钮级权限）；越范围 / 不存在 40400 | A-PAT-004、C-PAT-010 |
| PUT | `/api/hospital/followup/patients/{id}` | `followup:patient:edit` | 基础信息（非表单字段）；写字段级历史。**出院 / 手术日期修改后须同事务调计划域重算入口 `StartDateRecalculationService.recalculateOnBaseDateChange`（E14-T07 已实现，A-FU-011：未开始任务重排窗口 / 新窗口未开启撤回、下次计划日期实时重算；OQ-81①）**；接口本体随本任务实现 | A-PAT-005、C-PAT-011 |
| DELETE | `/api/hospital/followup/patients/{id}` | `followup:patient:delete`（C 规格权限表原有码；A 按 OQ-17 同名新增，按钮 plt_menu 217，平台库 V41，敏感权限已登记 plt_sensitive_perm）；敏感操作需二次认证（`X-Reauth-Token`） | **E13-T09 已实现**。软删除：`{ reason }` 必填（≤255），置 `deleted=1`、`delete_token=id`（释放病案号，可复用）、记录原因，**数据长期保留（A-NFR-008 只软删）**，子表经 patient_id 随患者不可见；越范围 / 已删除 40400（越范围写 DENIED 审计）；并发重复删除 40901；写 `PATIENT_DELETE` 审计 | A-PAT-012、C-PAT-015 |
| POST | `/api/hospital/followup/patients/{id}/restore` | `followup:patient:delete`（A-PAT-012「租户管理员恢复」，与删除同一权限）；敏感操作需二次认证 | **E13-T09 已实现**。恢复已软删患者：清删除标记 / token / 原因；**病案号已被新在册患者占用时 40902**（V37 预留的恢复冲突流程）；没有可恢复的已删除患者 40400；重复恢复 40901；写 `PATIENT_RESTORE` 审计 | A-PAT-012、A-NFR-008 |
| POST | `/api/hospital/followup/patients/batch-assign` | `followup:task:assign`（OQ-18；按钮 plt_menu 224，平台库 V44） | **E13-T07 已实现**（A-PAT-017、C-PAT-016）：`{ patientIds[], doctorId }`，单次 ≤ 500、去重保序；医生须启用（40001）且**负责患者当前病区**（sys_user_ward，逐条校验）——跨病区患者逐条跳过（`DOCTOR_NOT_IN_WARD`），越范围 / 不存在患者逐条 `NOT_FOUND` 且不带摘要（不暴露存在），并发转病区 `WARD_CHANGED`（ward_id 守卫）；只改 `responsible_user_id` 并递增乐观锁版本；→ `{ items: [{patientId, name(脱敏), patientNo(脱敏), wardName, outcome: SUCCESS\|SKIP, reason?}], succeeded, skipped }`（逐条可查，E13-T07 验收）；写一条 `PATIENT_BATCH_ASSIGN` 汇总审计（afterJson 逐条前 20 条 + 原因计数，不落姓名明文） | A-PAT-017、C-PAT-016 |
| GET | `/api/hospital/followup/patients/doctor-options?wardIds=` | `followup:task:assign` | **E13-T07 已实现**。责任医生候选（批量指定与批量入组页右侧下拉）：启用病区的启用用户（sys_user_ward），`{ doctors: [{id, username, displayName, wardId, wardName}] }`，可选按病区过滤；组织信息不按数据范围拦截（与病区成员同口径，OQ-80⑤），逐条「医生属于患者病区」强校验在批量接口服务层 | A-PAT-017 |
| POST | `/api/hospital/followup/patients/{id}/transfer` | `followup:patient:transfer` | **E13-T08 已实现**。转病区 `{ wardId, reason }`（`reason` 必填 ≤255；目标病区须存在且启用、与当前病区相同 40001；C 的 `deptId` 转科室随 C 域任务接入，未在本任务实现）；只改 `pt_patient.ward_id` 并递增乐观锁版本 + 追加 `pt_ward_history`（同一事务）——**原病区医生立即失去访问权（数据范围按患者行改写，任务 / 附件经 patient_id 随患者迁移，子表不搬）**；→ `{ patientId, wardId, wardName }`；写 `PATIENT_TRANSFER` 审计（越范围 40400 不暴露存在并写 DENIED）；按钮 plt_menu 216（平台库 V40） | A-WARD-005、C-WARD-006 |
| GET | `/api/hospital/followup/patients/{id}/ward-history` | `followup:patient:view` | **E13-T08 已实现**。转病区历史（新在前）：`{ patientId, items: [{ id, fromWardName, toWardName, reason, operatorName, transferredAt }] }`；越范围 / 不存在 40400（`pt_ward_history` 在 ControlledTables，按 patient 锚点过数据范围） | A-WARD-005 |

## 3. 状态：暂停、移除、恢复、停止、失访、死亡

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| POST | `/api/hospital/followup/patients/{id}/status` | A：`followup:plan:enroll`；C：`followup:patient:edit`（推断，见 OQ-15） | **随 E14-T06**。`{ action: PAUSE|RESUME|REMOVE|TERMINATE|LOST|DEAD, reason, effectiveDate, deathDate? }`；写 `pt_status_history`（经 `PatientStatusHistoryService.record`，E13-T03 已落地）；死亡 / 停止关闭未完成任务（A-FU-007、C-FU-021）；返回受影响任务数 | A-PAT-011、A-FU-008、C-FU-021 |
| GET | `/api/hospital/followup/patients/{id}/status-history` | `followup:patient:view` | **E13-T03 已实现**。状态变更历史（生效日期新在前）：`items: [{ id, status, effectiveDate, reason, source, createdBy, createdAt }]`；登记即写初始行 `ACTIVE / REGISTRATION`（OQ-71②）；表只追加 | — |

## 4. 基线（A-PAT-004/005/006/020/021、C-PAT-010/011/017/018；E13-T02 已实现分节、草稿、保存、字段历史与锁定 / 解锁）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/baseline` | `followup:patient:view` | **E13-T02 已实现**。`{ formCode（当前 A_BASELINE，C 随 E21）, formVersion, lock: { locked, lockReason, lockedBy, lockedAt, unlockReason, unlockedBy, unlockedAt }, sections: [{ code, id, version, status: EMPTY\|PARTIAL\|DONE, data, meta, updatedBy, updatedAt, hasDraft }], progress: [{ key, label, filled, total }] }`。按当前发布版模板分类列全部分节（无行分类 version 0 / EMPTY）；status 口径 = 已填必填 ÷ 应填必填（A-PAT-019）；分节行 pt_baseline_section 惰性建行（首次暂存 / 保存），登记事务不建行；越范围 / 不存在 40400 |
| PUT | `/api/hospital/followup/baseline/{patientId}/sections/{code}/draft` | `followup:patient:edit` | **E13-T02 已实现（规格已定）**。body `{ baseVersion, changes: { [fieldKey]: value }, overwrite? }`；只在有新改动时调用；只过写入闸门（键 / 类型 / 派生 / 硬范围），不做必填 / 跨字段校验，不写字段历史；hasDraft 置 1；成功返回 `{ version, savedAt }`；baseVersion 过期 40900（附 §4.1 冲突详情）；首次暂存建行（baseVersion 须为 0）；锁定后 40901；保存后重算基线完整度（pt_patient_summary.baseline_completeness） |
| PUT | `/api/hospital/followup/baseline/{patientId}/sections/{code}` | `followup:patient:edit` | **E13-T02 已实现**。正式保存 `{ baseVersion, data, meta? }`；执行完整校验（42200，未填完必填先「暂存」保留，A-PAT-003 可分次补录）；写字段级历史（record_type = PT_BASELINE_SECTION，fe_field_history）；清除草稿（hasDraft=0）；写 BASELINE_SECTION_SAVE 审计并重算基线完整度；首次保存建行（baseVersion 0）；锁定后 40901 |
| GET | `/api/hospital/followup/baseline/{patientId}/sections/{code}/history?fieldKey=` | `followup:patient:view` | **E13-T02 已实现**。字段级历史（谁、何时、旧值 → 新值；敏感字段不经表单 JSON、防御性跳过）；分节未保存过 40400；前端历史查看入口随操作日志页签开放 |
| POST | `/api/hospital/followup/patients/{id}/lock` | `followup:patient:lock`（A 规格权限表「锁定与解锁基线」，按钮 plt_menu 226；开关 `baseline_lock` 未落地——cfg_feature 下发机制见 OQ-03，定论后接入） | **E13-T02 已实现**。锁定 / 解锁 `{ locked, reason }`；**锁定后保存与暂存一律 40901（锁定后只读，A-PAT-006）**；解锁原因必填（空白 40001、≤255 字）；重复锁定 / 未锁定时解锁 40001；锁定 / 解锁各写操作审计（BASELINE_LOCK / BASELINE_UNLOCK，留痕只追加）并保留最近一次流转的人 / 时间 / 原因（pt_baseline_lock）；「出院后 30 天内锁定」为流程约定不硬拦（Q25）；越范围 40400 |

### 4.1 409 冲突响应（规格已定语义，字段名推断）

```json
{ "code": 40900, "message": "该记录已被他人更新",
  "data": { "currentVersion": 7, "updatedBy": "张医生（示例）", "updatedAt": "2026-10-31T10:12:00+08:00",
            "diffFields": [ { "key": "f019", "label": "左室射血分数", "mine": 55, "server": 52 } ] } }
```

前端处理（ConflictBanner，components.md §13）：暂停自动保存 → 「保留我的并覆盖」= 以 `currentVersion` 作为 `baseVersion` 重发并带 `overwrite: true`（后端写审计）；「载入服务器版本」= 本地改动另存本机副本。**不做字段级自动合并**。已提交或只读的记录不自动保存（自动保存请求返回 40901）。

E25-T01 已按此形态落地（随访记录草稿接口，followup.md §3）：`updatedBy` 为更新人显示名（`sys_user.display_name`，用户已删除等取不到时为 null）；`diffFields` 只列本次 `changes` 里两边取值不同的键（`mine` = 请求值、`server` = 服务器当前值，`label` 取记录模板版本快照的显示名；值相同的键与请求未涉及的字段不列，后者由「载入服务器版本」兜底，见 OQ-77③）；`overwrite: true` 的覆盖写 `RECORD_DRAFT_OVERWRITE` 审计（只记字段键，不落值）。

## 5. 明文查看（A-PAT-010、C-PAT-012；技术方案“明文查看（已确认 2026-10-31）”；E07-T05 已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/patients/{id}/plaintext` | `followup:patient:plain`（A/C 规格权限表，按钮 211；敏感权限，平台需先对该院放开可分配范围 PLT-SCOPE-004），且该字段对当前角色策略为 `MASKED` | body `{ fields: ['name','phone','idCard'], scene: 'detail'|'form' }`（scene 缺省 `detail`；字段去重）→ `{ values: { phone: '...' }, expiresInSeconds: 60 }` |

- 每次调用写租户数据访问审计（`DATA_ACCESS / VIEW_PLAIN`，对象 `PATIENT:{id}`，描述含字段中文名与场景、traceId，**不记录明文值**，`before/after`/摘要均不落）；随访员看电话也每次记。越范围 / 不存在的患者 40400 并写 `DENIED` 审计。
- 字段策略口径：`MASKED` 才走本接口；`VISIBLE`（明文已随普通接口返回）与 `HIDDEN`（禁止任何输出）请求一律 **40001**；目录外字段与非法场景 40001。
- 响应头 `Cache-Control: no-store`；`expiresInSeconds = 60`（design tokens `timings.plaintextRevealMs` 同口径）。
- 前端点击后原地确认即显示，不再输入密码（OQ-57 定论：popconfirm 二次确认，不加 `@RequireReauth`）；60 秒后恢复脱敏；明文**不进**本地草稿与任何缓存。
- 列表页不提供逐行明文（design-system 表格规则）。
- 频控：同一用户每分钟 ≤ 30 次（文档建议值，暂按此实现，产品定论后只改 `PlaintextRateLimiter` 常量），超出 **42900** 并写 `DENIED(reason=RATE_LIMITED)` 审计，触发安全告警 `PLAINTEXT_ABUSE`（PLT-AUD-012，接收人为启用的平台管理员）。
- 默认脱敏（序列化）：DTO 敏感字符串字段标 framework `@MaskedByPolicy(resource, field)`（`FieldPolicyJacksonModule`）——`MASKED` 掩码、`HIDDEN` 整字段不出现、`VISIBLE` 明文；动态结构（导出行、站内信参数）用 `SensitiveFieldSanitizer.apply`，同一套口径。掩码规则（`SensitiveValueMasker`）：姓名「张*明」（两字「张*」）、手机号前 3 后 4「138****5678」、证件号前 6 后 4、病案号前 2 后 2、住址前 6 字 + 固定 4 星，其余通用（首尾保留、中间按位数补星）。
- 实现备注：患者表已随 E13-T01 建表（`pt_patient`，followup V37），明文取数经端口 `PatientPlaintextSource`（真实实现 `PatientPlaintextSourceImpl`：`@DataScope` 查询 + `CryptoService` 解密；电话已随 E13-T03 并入——`phone` 取 `pt_contact` 当前电话1版本明文，患者无联系方式版本时不返回该字段）。

## 6. 联系方式（pt_contact，按版本保存）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/contact-info` | `followup:patient:view` | **E13-T03 已实现**。当前与历史版本（脱敏）：`items: [{ id, slot, phoneMasked, wechatAdded, effectiveDate, source, sourceRefId, version, remark, current }]`，电话1（PHONE_1）在前、版本新在前 |
| POST | `/api/hospital/followup/patients/{id}/contact-info` | `followup:patient:edit` | **E13-T03 已实现**。新增版本（不覆盖旧值）：body `{ slot: PHONE_1\|PHONE_2, phone, effectiveDate?, source?, sourceRefId?, wechatAdded?, remark? }`（effectiveDate 缺省今天、不得晚于今天也不得早于当前版本生效日期；source 缺省 MANUAL，取值 REGISTRATION / FOLLOWUP / IMPORT / MANUAL）；与当前版本号码与微信标志都相同 40001；并发同槽位 40900；写 `PATIENT_CONTACT_ADD` 审计（脱敏摘要） |

- 版本模型（tech-plan §7「每个号码一个版本」）：`slot` 为号码槽位，槽位内 `version` 递增、旧行永久保留，「当前版本」= 槽位内最大 version；`pt_contact` 行 id 供沟通记录引用（E15 `fu_contact_log`），换号不改历史。
- 电话加密列 + 盲索引（purpose `pt-contact-phone`，重加密作业已登记）；归一化口径：去空格 / 连字符 / 括号后须为 5–20 位数字（可带 `+`），归一化值同时用于「同号判断」。
- 明文：默认输出脱敏（`SensitiveValueMasker` phone 规则）；明文走 §5 专用接口，`phone` 字段 = 当前电话1（PHONE_1 最大 version）的明文，无版本时不返回该字段。
- `wechat_added`（A 规格字段 213「是否已加微信」）作为版本快照随行保存；仅该标志变化（号码不变）同样开新版本。

> 注意区分：`/contact-info` 是联系方式；`/contacts` 是**沟通记录**（见 `followup.md` §6）。

## 7. 患者详情其他页签

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/followup-timeline` | `followup:patient:view` | **E13-T05 已实现；E15-T07 起随访节点在同一入口并入**（A-FU-023 / C-FU-023 验收「时间轴显示子计划进度与下次计划日期」）。患者时间轴按时间顺序（旧 → 新，components.md §9 线框）：`items: [{kind, date, label, status, note, recordId, taskId, taskType, completeKind, createType, planDate, windowStart, windowEnd, doneDate, progressDone, progressTotal, canOpen, highlightCount}]`；`kind=DISCHARGE` 出院（`label`「出院」，C 无出院日期不出该节点）、`kind=STATUS` 状态变更含死亡（`status` 为患者状态码，文案由前端 `PATIENT_STATUS_TEXT` 映射，`note` 为原因；登记初始行 source=REGISTRATION 不重复展示）、`kind=FOLLOWUP` 每年随访（任务节点，E15-T07：主任务 create_type≠FOLLOWUP 按（入组×节点×第 N 次）聚合一节点，`progressDone/progressTotal` = 子计划完成进度 x/y、`doneDate`/`completeKind` 完成日期与方式、`canOpen` = FORM 任务未完成或已关联记录）、`kind=BACKFILL` 历史补录（无任务的补录记录，`label`「历史补录」、`note` 补录标识与录入日期，DRAFT 按进行中展示）、`kind=PLANNED` 下次计划日期（fu_enrollment.next_plan_date 实时缓存，A-FU-001「计划中 / 待开始」不落库，`canOpen=false`、该次数已有任务时不重复出节点）；`kind=UNPLANNED` 计划外随访（**E15-T06 已实现** / C-FU-022：无任务记录按 source=UNPLANNED 分流，`label`「计划外随访」、`note`「不影响后续节点；不作对比对象」、DRAFT 按进行中展示、canOpen=true）；REMINDER 站内提醒任务同为 FOLLOWUP 节点（E15-T06 起 `note`「站内提醒：无需填表，到点提醒责任人（A-FU-026）」、canOpen=false 无表单）；`EVENT` 终点事件随 E17、C 器械事件随 E21；`highlightCount` 恒 null（对比标红随 E17）；越范围 / 不存在 40400 | A-PAT-013、A-FU-023、C-FU-023 |
| GET | `/api/hospital/followup/patients/{id}/reports?scope=baseline|followup|all&anchorKey=` | `followup:patient:view`（报告单内容另需 `followup:report:view` 或 `followup:scan:upload`，见 OQ-12） | 报告单导航数据（AttachmentNav，components.md §5） | A-PAT-020、C-PAT-017 |
| GET | `/api/hospital/followup/patients/{id}/logs` | `followup:patient:view`（只读用户是否可见见 OQ-19） | 本患者操作日志 | PLT-AUD-001 |
| GET | `/api/hospital/followup/patients/{id}/param-trend?params=&from=&to=` | `followup:patient:view` + 开关 `param_trend`（推断） | C 程控参数趋势 `{ series: [{ param, unit, points: [{ date, value, recordId, abnormal }] }] }` | C-FU-012 |

## 8. 测试要点

- 草稿：`baseVersion` 一致 → 200 且 version+1；不一致 → 409 且 `diffFields` 正确；已提交 / 锁定 → 40901。
- 明文：每次调用审计 +1；响应头 `Cache-Control: no-store`；越范围患者 40400。
- 查重：范围外同号只返回 `exists: true`，不泄露摘要。
- 联系方式（E13-T03）：换号后旧版本行保留、当前版本 = 最大 version；与当前版本完全相同 40001；明文取当前电话1；审计只存脱敏摘要；越范围 40400、无 edit 40300、模块关闭 40301。
- 列表检索（E13-T04）：病区医生看不到其他病区患者（数据范围改写）；`keyword` 姓名精确只命中同名患者（部分姓名不命中）、病案号同样精确；行内姓名 / 病案号脱敏；非法状态 / 排序白名单外 / 日期区间颠倒 40001；无 view 40300、模块关闭 40301；双租户互不可见；批量查询写 `BATCH_QUERY` 审计（aud_data_access_log）。
- 概览与时间轴（E13-T05）：概览姓名 / 病案号只含脱敏值；派生列（完整度 / nextVisit / latestFollowup）在写入方（E13-T02 / E14 / E15）落地前为 null，前端显示占位；已终止患者 `terminated=true` 且 actions 无 `unplanned` / `contact` / `stop`；时间轴出院节点与状态事件按时间排序、登记初始行不重复展示；越范围 / 不存在 40400（summary 与 timeline 同口径）；无 view 40300、模块关闭 40301；双租户互不可见；纯概览读取不写审计（与查重 / 状态历史同口径，批量查询与明文查看才有 DATA_ACCESS 审计）。
- 高级检索与保存的筛选（E13-T06）：search-fields 目录只含字典 + 列绑定双命中的字段（敏感 / JSON 存储字段不出现）；条件树检索命中预期患者且**注入样例作为取值只做参数比较**（不生效，A-NFR-009 参数化）；目录外字段 / 白名单外运算符 / 选项外取值 / 超层超行 40001；病区隔离对条件树同样生效（另一病区患者不可见，即使条件放行）；保存的筛选仅本人可见（第二用户清单为空、删他人 40400）、检索词 keyword 被拒、增改删写 OPERATION 审计；无 view 40300、模块关闭 40301（目录与个人筛选同口径）。
- 列表与导出、首页数字使用同一范围（A-WARD-004）。
- 删除与恢复（E13-T09）：只软删（行保留、病案号释放可复用、原因留痕）；已删患者列表 / 详情 / 查重均不可见；越范围删除 40400 + DENIED 审计且不改数据；恢复被占用病案号 40902、恢复成功清标记；缺二次认证 40304、原因必填 40001；无 delete 40300、模块关闭 40301。
