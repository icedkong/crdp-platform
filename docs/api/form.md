# 表单引擎接口（form）

> 后端：`crdp-form`（字段字典、表单模板、规则）；前端：`frontend/packages/form-engine`（`FormRenderer`）与 `frontend/packages/rules`（前后端共用的校验 / 派生规则，见技术方案 §15 前端结构）。
> 契约基准：`docs/design/components.md` §10.1 的 `FormSchema` 类型 —— **本文件与之保持一致，有变化两处同改**。
> 原则：**字段由表单模板驱动**，页面不得硬编码业务字段；新增 / 改字段 = 改模板 + 新 Flyway 迁移（种子）或字典维护页，不改页面代码。
> 全部路径 **推断，待确认**。

## 1. 读取表单模板

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/form/forms` | 登录 | 当前租户可用表单列表 `{ code, name, currentVersion, usage: BASELINE|FOLLOWUP|IMPLANT|REPORT }[]` |
| GET | `/api/hospital/form/forms/{code}?version=` | 登录 | 返回 `FormSchema`（components.md §10.1）；不传 `version` 取当前发布版；**已有记录按记录保存时的版本渲染** |

示例 `code`：`A_BASELINE`、`A_ANNUAL_FU`、`C_PROGRAM_FU`、`C_IMPLANT`（命名推断，待确认）。

响应要求：

- `categories[].key`、`fields[].key` 为稳定键，模板改版不变（报告单位置 `anchorKey` 依赖它，A-ATT-021、C-SCAN-021）。
- 当前用户字段策略为 `HIDDEN` 的字段**不下发**；`MASKED` 的字段带 `sensitive: true`，值由后端脱敏。
- 响应可按 `ETag`（`code + version + 字段策略版本`）缓存。

## 2. 规则执行（前后端一致）

- 校验类型：类型与范围（`hardRange` 不能保存、`softRange` 提示，A-FLD-003、C-FLD-007）、条件显示与条件必填（A-FLD-004、C-FLD-005）、派生字段（A-FLD-005、C-FLD-006）、跨字段校验（A-FLD-006）。
- 前端用 `packages/rules` 即时执行；后端在**提交 / 保存修改**时用同一份规则定义再执行一次（Java 端实现同语义，用共享测试用例 JSON 保证一致，见 components.md §10 共用测试用例）。
- 草稿保存只做类型检查，不做必填与跨字段校验（技术方案“草稿语义”）。
- 条件变为不显示时，后端提交时清空该字段并写字段历史。
- 失败返回 42200：
```json
{ "code": 42200, "message": "表单校验未通过",
  "data": { "fieldErrors": [ { "key": "f019", "categoryKey": "echo", "rule": "hardRange", "message": "取值应在 20–90 之间" } ] } }
```

## 3. 字段字典维护（A-FLD-001..009/012/013、C-FLD-001..010）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/form/fields?formCode=&categoryKey=` | `followup:field:edit` | 按分类列字段 |
| PUT | `/api/hospital/form/fields/{key}` | `followup:field:edit` | 可改：显示名、帮助文字、单位显示、`softRange`、必填、选项（新增 / 停用 / 排序，不能删除已用选项、不能改选项 `code`）；**不能改** `key`、`code`、类型、存储方式（A-FLD-002） |
| GET | `/api/hospital/form/fields/{key}/logs` | `followup:field:edit` | 字典变更记录（A-FLD-009、C-FLD-009） |
| POST | `/api/hospital/form/forms/{code}/publish` | `followup:field:edit` | 字段修改生成新模板版本并发布（推断，待确认） |

不做：医院自助新增字段（A-FLD-010）、通用表单设计器（A-FLD-011）——延后（见 `docs/tasks/tasks.md`）。C-FLD-008 “新增字段”指由实施通过迁移新增，不提供页面。

## 4. 业务字典（A-FLD-012、C-FLD-002/003）

表单选项可引用租户字典（`optionSource: { dict: 'pacing_mode' }`，推断，待确认），由 `system.md` §4 维护；后端在下发 schema 时展开为 `optionSet`。C 的统一起搏模式字典（C-FLD-003）为预置字典。

## 5. 租户配置模板（A-FLD-014）

表单模板、字典、报告单类型等配置可通过平台“配置模板”导出 / 应用（见 `platform.md` §7）。
