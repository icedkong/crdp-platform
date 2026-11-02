# 表单引擎接口（form）

> 后端：`crdp-form`（字段字典、表单模板、规则）；前端：`frontend/packages/form-engine`（`FormRenderer`）与 `frontend/packages/rules`（前后端共用的校验 / 派生规则，见技术方案 §15 前端结构）。
> 契约基准：`docs/design/components.md` §10.1 的 `FormSchema` 类型 —— **本文件与之保持一致，有变化两处同改**。
> 原则：**字段由表单模板驱动**，页面不得硬编码业务字段；新增 / 改字段 = 改模板 + 新 Flyway 迁移（种子）或字典维护页，不改页面代码。
> §1 模板按版本读取与发布、§3 字段字典维护已随 E11-T01 / E11-T02 落地（实现即定义）；其余路径 **推断，待确认**。

## 1. 读取表单模板（E11-T02 已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/form/forms` | 登录 | 当前租户可用表单列表 `{ code, name, currentVersion, usage: BASELINE|FOLLOWUP|IMPLANT|REPORT, status }[]`（E11-T01 已实现，`@PublicApi` + `@RequireModule("followup")`） |
| GET | `/api/hospital/form/forms/{code}?version=` | 登录 | 返回 `FormSchema`（components.md §10.1 + `name` / `usage`）；不传 `version` 取当前发布版；**已有记录按记录保存时的版本取**（E11-T02 已实现）；版本不存在 40400 |
| POST | `/api/hospital/form/forms/{code}/publish` | `followup:field:edit` | 发布新模板版本（E11-T02 已实现，见 §3 末行） |

示例 `code`：`A_BASELINE`、`A_ANNUAL_FU`、`C_PROGRAM_FU`、`C_IMPLANT`（命名推断，待确认）。

版本与快照口径（E11-T02，验收「表单按版本读写，旧版本可读」）：

- 模板版本快照存租户库 `fe_form_version` / `fe_form_version_field`（followup V29）：publish 把当时的分类与**启用字段**固化成快照并前移 `fe_form.current_version`；快照不可变，旧版本永久可读。
- 停用字段不进新版本；旧版本快照里仍在——旧记录（`data` JSON 键 = 稳定键）按其保存时版本渲染与校验，模板改版不影响。
- 选项（`fe_option`）不进快照：选项编码创建后不可改、停用不删除（V28），显示名与停用标记读取时现取；停用选项在 `optionSet[]` 带 `disabled: true`（不可新选，历史值仍可显示）。
- `rules` 为 `fe_rule` 跨字段校验，随 E11-T04 落地（当前恒为 `[]`）；`compareRules` 随 E17。
- 记录侧绑定见 §1.1：记录存 `templateVersion`，读取时前端用 `GET /forms/{code}?version={templateVersion}` 取渲染口径。

响应要求：

- `categories[].key`、`fields[].key` 为稳定键，模板改版不变（报告单位置 `anchorKey` 依赖它，A-ATT-021、C-SCAN-021）。
- 敏感字段带 `sensitive: true`（`fe_field.sensitive_flag`），值不经表单 JSON 下发（混合存储走实体加密列）；按字段策略 `HIDDEN` 过滤 schema 需要「字段编码 → 敏感字段目录」映射，随 E11-T07 渲染器接入字段策略时落地（见 OQ-63 ④）。
- 响应可按 `ETag`（`code + version + 字段策略版本`）缓存（未实现，随渲染器落地）。

## 1.1 表单数据 JSON 记录（FormService，引擎层）

E11-T02 落地（A-NFR-002 / C-NFR-002「JSON 文档存储」）。数据记录存租户库 `fe_record`（followup V29）：
`data`（键 = 稳定键 f0xx，仅 `storage=json` 的字段）、`data_meta`（`manual` / `note` / `fromReport`）、
`template_version`（**创建时固化** = 当时 `current_version`，发布新版不升级）、`version`（乐观锁）、
`status`（DRAFT / SUBMITTED，提交流转随 E15）。

- **写入只经 FormService**（`crdp-form` 的 `com.crdp.form.record.app.FormService`，技术方案 §6「禁止绕过服务直接写 JSON」）：键必须在记录模板版本内且为 json 存储、类型按值编码口径检查（单选 / 下拉存选项编码整数、多选存编码数组、数值存数字、日期 `YYYY-MM-DD`、日期时间 `YYYY-MM-DDTHH:mm`、是否存 true/false、数值 × 2 存 `{length,width}`、派生字段不查类型随 E11-T05 重算）；范围 / 必填 / 条件 / 跨字段随 E11-T03 / E11-T04。
- 保存带 `baseVersion`：缺失 40001；与当前不一致 **40900**，`data` 附 `{ serverVersion, changedFields[], updatedBy, updatedAt }`（README §错误码 40900、技术方案「版本冲突」；E25 草稿接口复用本口径）。已提交记录 40901（修改走 E15 编辑流程）。
- 记录读写是**引擎服务**（无独立 HTTP 端点），业务接口（基线 E13、随访记录 E15、报告单映射 E16-T07、草稿 E25）复用同一服务与校验；患者域业务表（`pt_baseline_section` / `fu_record` 等）的 `data` JSON 列同样经 FormService 写入，见 OQ-63 ①。
- 草稿不逐次写审计（技术方案「服务器自动保存」）；提交、修改已提交与覆盖他人版本的审计随业务接口（E15 / E25）。

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

## 3. 字段字典维护（A-FLD-001..009/012/013、C-FLD-001..010；E11-T01 已实现前两行与 publish）

字段元数据存租户库 `fe_form` / `fe_field` / `fe_option_set` / `fe_option`（tech-plan §6「元数据模型」）。
稳定键 `field_key` 与字段编码 `field_code`（导入导出名）**表单内唯一**、创建后不可改；显示名与编码分离
（A-FLD-002：页面显示「吸烟史」与选项「否 / 是」，数据库存 0 / 1）。字段可启停（`status`
ENABLED/DISABLED，停用不删除；**模板下发与校验只取启用字段**——发布进快照时过滤，E11-T02）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/form/fields?formCode=&categoryKey=` | `followup:field:edit` | 按分类列字段；行含 `key/code/label/categoryKey/categoryLabel/type/unit/decimals/hardRange/softRange/options/required/visibleWhen/requiredWhen/derivedDef/sensitive/storage/help/sortNo/status/updatedAt`（`categoryKey` 可省略 = 全部分类） |
| PUT | `/api/hospital/form/fields/{key}?formCode=` | `followup:field:edit` | 全量覆盖可维护列：显示名、帮助文字、单位显示、`softRange`、必填、**状态（启停）**、选项（`options[]`：`code` 已存在为改显示名 / 排序 / 停用，不存在为新增；不能删除已用选项、不能改选项 `code`）；**不能改** `key`、`code`、类型、存储方式、敏感标记、`hardRange`（请求体不携带这些列，A-FLD-002/008）。写审计 `FIELD_DICT_UPDATE`（前后值，启停在描述点明）。校验失败 40001（显示名必填 ≤128、软范围 min≤max、状态只认 ENABLED/DISABLED、选项编码必填且不重复）；字段 / 表单不存在 40400 |
| GET | `/api/hospital/form/fields/{key}/logs` | `followup:field:edit` | 字典变更记录（A-FLD-009、C-FLD-009；随 E11-T08） |
| POST | `/api/hospital/form/forms/{code}/publish` | `followup:field:edit` | **E11-T02 已实现**：无请求体；把当前分类与启用字段固化成新版本快照并前移 `currentVersion`，响应 `{ code, name, version, fieldCount, publishedAt }`。没有字典修改也允许发布（版本是显式动作）；并发发布后到者 40900。写审计 `FORM_PUBLISH`（前后版本与字段数）。停用表单发布 40001；表单不存在 40400 |

不做：医院自助新增字段（A-FLD-010）、通用表单设计器（A-FLD-011）——延后（见 `docs/tasks/tasks.md`）。C-FLD-008 “新增字段”指由实施通过迁移新增，不提供页面。页面 `/system/field`（菜单 `FollowupField`，平台库迁移 V35）。

## 4. 业务字典（A-FLD-012、C-FLD-002/003）

表单选项可引用租户字典（`optionSource: { dict: 'pacing_mode' }`，推断，待确认），由 `system.md` §4 维护；后端在下发 schema 时展开为 `optionSet`。C 的统一起搏模式字典（C-FLD-003）为预置字典。

## 5. 租户配置模板（A-FLD-014）

表单模板、字典、报告单类型等配置可通过平台“配置模板”导出 / 应用（见 `platform.md` §7）。
