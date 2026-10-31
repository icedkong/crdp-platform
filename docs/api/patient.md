# 患者、基线、草稿、明文查看（patient）

> 后端：`crdp-followup`（患者子域）；表 `pt_patient`、`pt_patient_summary`、`pt_baseline_section`、`pt_baseline_lock`、`pt_contact`（联系方式版本）、`pt_status_history`、`pt_ward_history`、`fu_saved_filter`。
> 页面：`docs/design/pages.md` §3（患者列表）、§4（患者详情、基线编辑）。组件：`components.md` §3 MaskedField、§4 PatientHeader、§10 FormRenderer、§11 AdvancedSearch。
> 前缀 `/api/hospital/followup/`，`@RequireModule("followup")`。
> **规格已定**：基线草稿接口 `PUT /api/hospital/followup/baseline/{patientId}/sections/{code}/draft` 及其 `baseVersion` / 409 语义。其余 **推断，待确认**。
> 所有接口经过数据范围；不在范围内的患者一律 40400。

## 1. 列表与检索（A-PAT-001/002/014/015/019、C-PAT-009/013）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/patients/search` | `followup:patient:view` | body 见 README §2.4；`filters`：`wardIds, deptIds, status[], doctorId, dischargeFrom/To, nextVisitFrom/To, deviceType`(C)、`baselineCompleteness`；`condition` 为高级检索条件树 |
| GET | `/api/hospital/followup/saved-filters` | `followup:patient:view` | 个人筛选（只属于本人） |
| POST | `/api/hospital/followup/saved-filters` | `followup:patient:view` | `{ name, payload }`（payload = search body 去掉分页） |
| DELETE | `/api/hospital/followup/saved-filters/{id}` | `followup:patient:view` | — |
| GET | `/api/hospital/followup/patients/search-fields` | `followup:patient:view` | 高级检索可用字段（由表单模板 + 字段策略生成，隐藏字段不出现） |

列表行（A，C 同构另加器械列）：

```ts
interface PatientRow {
  id: string; name: string /* 已脱敏 */; patientNo: string /* 本系统编号 */; legacyNo?: string;
  gender: 'M'|'F'|'U'; age?: number; wardName?: string; doctorName?: string;
  dischargeDate?: string; nextVisitDate?: string; status: PatientStatus; // components.md §2
  baselineCompleteness: number /* 0–100 */; followupCompleteness: number | null /* 无已完成记录为 null，前端显示— */;
  device?: { type: string; model?: string }; // C
}
```

- 列表排序默认：下次随访日期升序（推断，待确认）。
- 完整度只统计已填写的资料（A-PAT-019、C-DASH-009），来源 `pt_patient_summary`，异步刷新。

## 2. 新建、查重、编辑、删除

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| POST | `/api/hospital/followup/patients/check-duplicate` | `followup:patient:create` | `{ medicalRecordNo }` → `{ exists, patient?: { id, nameMasked, wardName, dischargeDate } }`（只返回脱敏摘要；范围外的同号患者只返回 `exists: true`） | A-PAT-007、C-PAT-002 |
| POST | `/api/hospital/followup/patients` | `followup:patient:create` | 出院登记 / 患者登记：基础信息 + 首个基线分节；A 可选择是否立即入组；C 自动生成随访编号（C-PAT-003）且根据植入记录自动入组（`implant.md`）；重复返回 40902 | A-PAT-003、C-PAT-001 |
| GET | `/api/hospital/followup/patients/{id}/summary` | `followup:patient:view` | PatientHeader 数据：脱敏基本信息、状态、病区、责任医生、当前计划、下次随访、完整度、C 当前器械；**附带 `actions` 可用操作列表**（后端按权限 + 状态计算，前端仍做按钮级权限） | A-PAT-004、C-PAT-010 |
| PUT | `/api/hospital/followup/patients/{id}` | `followup:patient:edit` | 基础信息（非表单字段）；写字段级历史 | A-PAT-005、C-PAT-011 |
| DELETE | `/api/hospital/followup/patients/{id}` | C：`followup:patient:delete`；A 权限表无删除码（建议同名新增，见 OQ-17）；敏感操作需二次认证 | 软删除，`{ reason }` 必填，租户管理员可恢复（`POST .../patients/{id}/restore`） | A-PAT-012、C-PAT-015 |
| POST | `/api/hospital/followup/patients/batch-assign` | `followup:task:assign`（推断，待确认） | `{ patientIds[], doctorId }`，单次 ≤ 500；A 只能选该病区的医生 | A-PAT-017、C-PAT-016 |
| POST | `/api/hospital/followup/patients/{id}/transfer` | `followup:patient:transfer` | 转病区 / 科室 `{ wardId | deptId, reason }`，写 `pt_ward_history`；数据范围随当前病区变化 | A-WARD-005、C-WARD-006 |

## 3. 状态：暂停、移除、恢复、停止、失访、死亡

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| POST | `/api/hospital/followup/patients/{id}/status` | A：`followup:plan:enroll`；C：`followup:patient:edit`（推断，见 OQ-15） | `{ action: PAUSE|RESUME|REMOVE|TERMINATE|LOST|DEAD, reason, effectiveDate, deathDate? }`；写 `pt_status_history`；死亡 / 停止关闭未完成任务（A-FU-007、C-FU-021）；返回受影响任务数 | A-PAT-011、A-FU-008、C-FU-021 |
| GET | `/api/hospital/followup/patients/{id}/status-history` | `followup:patient:view` | 状态变更历史 | — |

## 4. 基线（A-PAT-004/005/006/020/021、C-PAT-010/011/017/018）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/baseline` | `followup:patient:view` | `{ formCode, formVersion, locked, sections: [{ code, version, status: EMPTY|PARTIAL|DONE, data, meta, updatedBy, updatedAt, hasDraft }], progress: CategoryProgress[] }` |
| PUT | `/api/hospital/followup/baseline/{patientId}/sections/{code}/draft` | `followup:patient:edit` | **规格已定**。body `{ baseVersion, changes: { [fieldKey]: value } }`；只在有新改动时调用；不做必填 / 跨字段校验，不写字段历史；成功返回 `{ version, savedAt }` |
| PUT | `/api/hospital/followup/baseline/{patientId}/sections/{code}` | `followup:patient:edit` | 正式保存 `{ baseVersion, data, meta }`；执行完整校验（42200）；写字段级历史；清除草稿 |
| GET | `/api/hospital/followup/baseline/{patientId}/sections/{code}/history?fieldKey=` | `followup:patient:view` | 字段级历史（谁、何时、旧值 → 新值；敏感字段值脱敏） |
| POST | `/api/hospital/followup/baseline/{patientId}/lock` | `followup:patient:lock`（A 规格权限表；开关 `baseline_lock` 推断） | 锁定 / 解锁 `{ locked, reason }`；锁定后保存返回 40901 | 

### 4.1 409 冲突响应（规格已定语义，字段名推断）

```json
{ "code": 40900, "message": "该记录已被他人更新",
  "data": { "currentVersion": 7, "updatedBy": "张医生（示例）", "updatedAt": "2026-10-31T10:12:00+08:00",
            "diffFields": [ { "key": "f019", "label": "左室射血分数", "mine": 55, "server": 52 } ] } }
```

前端处理（ConflictBanner，components.md §13）：暂停自动保存 → 「保留我的并覆盖」= 以 `currentVersion` 作为 `baseVersion` 重发并带 `overwrite: true`（后端写审计）；「载入服务器版本」= 本地改动另存本机副本。**不做字段级自动合并**。已提交或只读的记录不自动保存（自动保存请求返回 40901）。

## 5. 明文查看（A-PAT-010、C-PAT-012；技术方案“明文查看（已确认 2026-10-31）”）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/patients/{id}/plaintext` | `followup:patient:plain`（A/C 规格权限表），且该字段对当前角色策略为 `MASKED` | body `{ fields: ['name','phone','idCard'], scene: 'detail'|'form' }` → `{ values: { phone: '...' }, expiresInSeconds: 60 }` |

- 每次调用写租户审计（含字段码、场景、traceId，**不记录明文值**）；随访员看电话也每次记。
- 前端点击后原地确认即显示，不再输入密码；60 秒后恢复脱敏；明文**不进**本地草稿与任何缓存。
- 列表页不提供逐行明文（design-system 表格规则）。
- 频控：同一用户每分钟 ≤ 30 次（推断，待确认），超出 42900 并写安全告警（PLT-AUD-012）。

## 6. 联系方式（pt_contact，按版本保存）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/contact-info` | `followup:patient:view` | 当前与历史版本（脱敏） |
| POST | `/api/hospital/followup/patients/{id}/contact-info` | `followup:patient:edit` | 新增版本（不覆盖旧值） |

> 注意区分：`/contact-info` 是联系方式；`/contacts` 是**沟通记录**（见 `followup.md` §6）。

## 7. 患者详情其他页签

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/hospital/followup/patients/{id}/followup-timeline` | `followup:patient:view` | 随访时间轴：任务（含计算出的“计划中”节点）+ 记录 + 计划外随访，按时间排序 | A-PAT-013、A-FU-023、C-FU-023 |
| GET | `/api/hospital/followup/patients/{id}/reports?scope=baseline|followup|all&anchorKey=` | `followup:patient:view`（报告单内容另需 `followup:report:view` 或 `followup:scan:upload`，见 OQ-12） | 报告单导航数据（AttachmentNav，components.md §5） | A-PAT-020、C-PAT-017 |
| GET | `/api/hospital/followup/patients/{id}/logs` | `followup:patient:view`（只读用户是否可见见 OQ-19） | 本患者操作日志 | PLT-AUD-001 |
| GET | `/api/hospital/followup/patients/{id}/param-trend?params=&from=&to=` | `followup:patient:view` + 开关 `param_trend`（推断） | C 程控参数趋势 `{ series: [{ param, unit, points: [{ date, value, recordId, abnormal }] }] }` | C-FU-012 |

## 8. 测试要点

- 草稿：`baseVersion` 一致 → 200 且 version+1；不一致 → 409 且 `diffFields` 正确；已提交 / 锁定 → 40901。
- 明文：每次调用审计 +1；响应头 `Cache-Control: no-store`；越范围患者 40400。
- 查重：范围外同号只返回 `exists: true`，不泄露摘要。
- 列表与导出、首页数字使用同一范围（A-WARD-004）。
