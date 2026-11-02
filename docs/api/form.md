# 表单引擎接口（form）

> 后端：`crdp-form`（字段字典、表单模板、规则）；前端：`frontend/packages/form-engine`（`FormRenderer`）与 `frontend/packages/rules`（前后端共用的校验 / 派生规则，见技术方案 §15 前端结构）。
> 契约基准：`docs/design/components.md` §10.1 的 `FormSchema` 类型 —— **本文件与之保持一致，有变化两处同改**。
> 原则：**字段由表单模板驱动**，页面不得硬编码业务字段；新增 / 改字段 = 改模板 + 新 Flyway 迁移（种子）或字典维护页，不改页面代码。
> §1 模板按版本读取与发布、§3 字段字典维护已随 E11-T01 / E11-T02 落地、§2 规则执行已随 E11-T04 落地、§2.4 条件显示与条件必填已随 E11-T03 落地、§1.2 字段级变更历史已随 E11-T06 落地、§2.6 派生函数库已随 E11-T05 落地、§3 变更记录已随 E11-T08 落地、§3 Excel 批量导入已随 E11-T09 落地（实现即定义）；其余路径 **推断，待确认**。

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
- `rules` 为 `fe_rule` 跨字段校验（E11-T04 已落地）：实时读取启用规则、**不进版本快照**，形状见 §2.2；`compareRules` 随 E17。
- 字段的 `visibleWhen` / `requiredWhen` 条件树随版本快照下发（`fe_form_version_field`，E11-T03）：旧版本按其快照口径求值；求值语义见 §2.4。
- `carryOver`（带入上次值适用字段，A-FU-015 / C-FU-016）随 E11-T07 定为 FormSchemaField 可选列：模板标记的设置项（A 用药、C 程控起搏模式 / 上下限频率 / AV 间期）可带入、测量值不标记即不带入；`fe_field` 加列 + 快照下发未落地（前端契约先行，渲染器按缺省 false 处理，见 OQ-67 ①）。
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

- **写入只经 FormService**（`crdp-form` 的 `com.crdp.form.record.app.FormService`，技术方案 §6「禁止绕过服务直接写 JSON」）：键必须在记录模板版本内且为 json 存储、类型按值编码口径检查（单选 / 下拉存选项编码整数、多选存编码数组、数值存数字、日期 `YYYY-MM-DD`、日期时间 `YYYY-MM-DDTHH:mm`、是否存 true/false、数值 × 2 存 `{length,width}`、派生字段不查类型）；范围 / 必填 / 条件 / 跨字段已随 E11-T04 / E11-T03 落地（§2）；**派生字段在类型检查后重算**（E11-T05 已落地，见 §2.6——来源齐全以计算值为准、来源缺失保留手工值、结果无效的手工值 42200）。
- 保存带 `baseVersion`：缺失 40001；与当前不一致 **40900**，`data` 附 `{ serverVersion, changedFields[], updatedBy, updatedAt }`（README §错误码 40900、技术方案「版本冲突」；E25 草稿接口复用本口径）。已提交记录 40901（修改走 E15 编辑流程）。
- 记录读写是**引擎服务**（无独立 HTTP 端点），业务接口（基线 E13、随访记录 E15、报告单映射 E16-T07、草稿 E25）复用同一服务与校验；患者域业务表（`pt_baseline_section` / `fu_record` 等）的 `data` JSON 列同样经 FormService 写入，见 OQ-63 ①。
- 草稿不逐次写审计（技术方案「服务器自动保存」）；提交、修改已提交与覆盖他人版本的审计随业务接口（E15 / E25）。

## 1.2 字段级变更历史（FieldHistoryService，E11-T06 已实现；A-PAT-005、C-PAT-011）

「每个字段的修改都记录旧值、新值、修改人、时间、原因」。历史存租户库 `fe_field_history`（followup V32），
**只追加**（无修改 / 删除接口）；与 FormService 记录读写同口径：**引擎服务、无独立 HTTP 端点**（OQ-63 ①），
业务流程（提交 / 保存修改 E15 与 E25、导入更新 E18、报告单映射 E16-T07、派生重算 E11-T05）在自己的事务里调用
（`@Transactional` REQUIRED 自动加入调用方事务，tech-plan §7「同一事务写 fe_field_history」）；
「变更记录 / 操作日志」页（pages.md §4.1）的 HTTP 接口随患者域 E13 暴露。
**草稿不写字段历史**（tech-plan §6「草稿语义」）——FormService 的草稿口（createRecord / saveRecord）不调用本服务。

| 方法（`com.crdp.form.history.app.FieldHistoryService`） | 用途 |
|---|---|
| `logChanges(recordType, recordId, formCode, templateVersion, before, after, source, reason)` | 逐字段比较旧值与新值，一个字段一行（未变不写；行序 = 模板快照 sortNo），返回实际写下的改动 |
| `logChange(recordType, recordId, formCode, templateVersion, fieldKey, oldValue, newValue, source, reason, triggerFields)` | 单字段留痕（系统重算带依赖来源字段、报告单映射、平台支持修正） |
| `logClearedHidden(recordType, recordId, formCode, templateVersion, clearedValues)` | 隐藏字段清空留痕（§2.4）：旧值保留、新值为空、触发字段取 visibleWhen 引用的稳定键 |
| `history(recordType, recordId[, fieldKey])` | 查询：按记录（可按字段过滤），新到旧，默认 200 条；`HistoryView` 含旧值、新值、来源（含中文标签）、触发字段、原因、操作人（id + 显示名快照）、时间（北京时间 ISO-8601） |

- `recordType` + `recordId`（tech-plan §6 列定义）：`FE_RECORD`（引擎表单记录，常量 `FieldHistoryService.FORM_RECORD`）；
  业务记录（`PT_BASELINE_SECTION` / `FU_RECORD` 等）随 E13 / E15 接入同一表。
- `source`（tech-plan §6「来源」）：`USER` 用户 / `SYSTEM_RECALC` 系统重算 / `IMPORT` 导入 / `REPORT` 报告单 / `PLATFORM_SUPPORT` 平台支持。
  USER / IMPORT / REPORT 须有医院会话（否则 40100，A-PAT-005「修改人」必填口径）；SYSTEM_RECALC 允许无会话
  （定时作业无 SecurityContext，操作人记「系统重算」）；PLATFORM_SUPPORT 显示名追加「（平台支持）」，会话号关联随 E20 租户视角落地。
- 值口径（与 fe_record.data 同一值编码）：旧值 / 新值存 JSON（新填旧值为 NULL、清空新值为 NULL）；比较**严格同型**
  （1 与 true、`"1"` 与 1 算变化，与条件 DSL eq 同口径 OQ-65 ②），数值按值比较（170 与 170.0 不算变化），空值 = null / 空串 / 空数组（OQ-65 ①）。
- 敏感字段不经表单 JSON（storage=encrypted 走实体加密列）；快照里标记敏感的键**防御性跳过**（不落明文历史）；
  实体加密列字段的历史值加密随 E13 患者域接入在业务侧处理（tech-plan §7「敏感字段的历史值同样加密存储」）。
- 入参校验 40001：`recordType` 必填 ≤32、`fieldKey` 必填 ≤32、`reason` ≤500、`triggerFields` 拼接 ≤255；`formCode + templateVersion` 版本快照不存在 40400。
- 留痕行不另写平台审计——审计由业务操作（提交、导入等）记一条操作摘要（tech-plan §14「审计中另记一条操作摘要」）。

## 2. 规则执行（前后端一致，E11-T04 / E11-T03 已落地）

- 校验类型：类型与范围（`hardRange` 不能保存、`softRange` 提示，A-FLD-003、C-FLD-007，已落地）、条件显示与条件必填（A-FLD-004、C-FLD-005，已落地，见 §2.4）、派生字段（A-FLD-005、C-FLD-006，已落地，见 §2.6）、跨字段校验（A-FLD-006，已落地）。
- 前端用 `packages/rules` 即时执行；后端在**提交 / 保存修改**时用同一份规则定义再执行一次（Java 端实现同语义，用共享测试用例 JSON 保证一致，见 §2.5 与 components.md §10.5）。
- 草稿保存只做类型检查与**硬范围拦截**（tech-plan §6 写入闸门含范围），不做必填、条件与跨字段校验（技术方案“草稿语义”；草稿闸门不做条件求值——隐藏字段残留值照常查硬范围，提交时按 §2.4 掩蔽与清空）。
- 条件变为不显示时，后端提交时清空该字段的值（`FormService.stripHiddenFields`，E11-T03 已落地，提交流程 E15 / E25 调用）；清空动作经 `FieldHistoryService.logClearedHidden` 写 `fe_field_history`（E11-T06 已落地，见 §1.2）。
- 失败返回 42200：
```json
{ "code": 42200, "message": "表单校验未通过",
  "data": { "fieldErrors": [ { "key": "f019", "categoryKey": "echo", "rule": "hardRange", "message": "取值应在 20–90 之间" } ] } }
```

### 2.1 范围（fe_field.hard_range / soft_range，随版本快照）

- `number` 与 `pair_number`（length / width 各查同一范围）参与；闭区间；空值（null / 缺键 / 分量缺失）与派生字段跳过；硬范围命中时不再叠加软提示。
- 文案（前后端逐字一致，共享用例锁定）：两端都有 → `取值应在 min–max 之间`；只有下限 → `取值不能小于 min`；只有上限 → `取值不能大于 max`（整数不带小数点，如 `50`）。
- 单位（unit）不换算：范围值与录入值同单位（A-FLD-003「数值有单位与合理范围」），单位由渲染器展示（E11-T07）。
- 超软范围保存时 tech-plan §6 口径把确认人记 `data_meta`（「超出范围的确认人」），随提交确认流（E15 / E25）落地。

### 2.2 跨字段规则（fe_rule，followup V30；FormSchema `rules[]` 实时读取）

- 通用比较规则 `COMPARE`：`params = { leftField, op: lt|lte, rightField }`（字段稳定键），语义「leftField op rightField 须成立」。覆盖 A-FLD-006 全部形态（入院 ≤ 手术 ≤ 出院、手术开始 < 结束、进 ICU < 出 ICU、死亡日期 ≥ 出院日期以「出院 ≤ 死亡」表达、随访事件日期 ≥ 出院日期）与 C-FLD-007（上限频率 > 下限频率）。
- 值按 tech-plan §6 值编码口径比较：数值比大小；日期 `YYYY-MM-DD` / 日期时间 `YYYY-MM-DDTHH:mm` 比先后（日期按当日 00:00 归一，分钟与带秒写法等价）；一侧为空或不可比（数值 × 日期、普通文本）不触发。
- `severity`：`HARD` 违反拦截（提交 / 保存修改 42200）；`SOFT` 黄色提示并要求确认、不阻止保存（A-FLD-003 软范围、C-FLD-007 程控参数建议范围同一语义）。
- 命中挂靠：`target_field` / `category_key`（`rules[].key / categoryKey` 与 `fieldErrors[].key / categoryKey` 同源），`rule` = `rule_id`（范围命中用固定码 `hardRange` / `softRange`）。
- 维护：规则由实施经迁移配置（A-FLD-006 完整规则链随 E23-T01 字段录入、C 程控规则随 C 字段字典落地；V30 带演示规则）；`status=DISABLED` 停用即不生效、不删除。

### 2.3 执行时机

| 时机 | 执行内容 | 结果 |
|---|---|---|
| 草稿创建 / 保存（FormService.createRecord / saveRecord） | 类型 + 选项（40001）+ 派生重算（E11-T05，§2.6：计算值覆盖 / 缺失保留手工值）+ 硬范围（不做条件求值） | 硬范围违反与结果无效的手工派生值 42200（fieldErrors），不落库；计算值合并落库 |
| 提交 / 保存修改（业务接口 E15 / E25 调 `FormService.validateForSubmit(formCode, templateVersion, data[, context])`） | 全量：类型 + 硬范围 + 软范围 + 条件必填 + 跨字段（按 severity）；隐藏字段值掩蔽（§2.4） | 硬违反与必填缺失 42200；软命中**不拦截**，作为警告列表返回（调用方做确认流） |
| 提交落库前（提交流程调 `FormService.stripHiddenFields(formCode, templateVersion, data, context)`） | 按当前答案求值 visibleWhen，移除隐藏字段残留值 | 返回清洗后 data 与被清空键（cleared-hidden 口径；清洗前值交 `FieldHistoryService.logClearedHidden` 写 `fe_field_history`，E11-T06 已落地） |

### 2.4 条件显示与条件必填（fe_field.visible_when / required_when，E11-T03 / A-FLD-004、C-FLD-005）

- 条件树与高级检索同构（tech-plan §6）：节点 `{all: [...]}`（全部成立，空数组恒真）/ `{any: [...]}`（任一成立，空数组恒假）可嵌套；叶子 `{field | ctx, op, value}`——`field` 取本表单字段稳定键的值，`ctx` 取上下文变量（如医院 C 的 `device.type` / `device.hasRaLead`，按点路径 `a.b` 在嵌套对象取值；上下文由业务侧随 `validateForSubmit` / `stripHiddenFields` 的 `context` 参数与页面 `FormRenderer` 的 `context` prop 传入，器械匹配随 E21-T03）。操作符 `eq | ne | in | nin | gt | gte | lt | lte | empty | notEmpty`（components.md §10.1 ConditionTree）。
- 求值语义（实现即定义，暂行口径见 OQ-65）：eq / in 按值编码口径**严格同型**比较（数值与数值、布尔与布尔、字符串与字符串；`1` 与 `"1"` 不等——条件值必须按值编码写：选项编码整数、日期字符串）；gt / gte / lt / lte 只比数值与日期 / 日期时间（日期归一当日 00:00，与 §2.2 COMPARE 同口径），普通文本不可比 → 不成立；**操作数缺失（未答 / null）除 `empty` / `notEmpty` 外一律不成立**（未答 ≠ 否：「是否死亡」未答 → 死亡日期不显示、不加必填）；空值口径 = null / 空串 / 空数组 / 分量全缺的 pair_number 对象（`0` 与 `false` 是已答）；条件为空或解析失败 / 残缺（无 op、field 与 ctx 并存或全无、op 不认识）**恒真**——坏配置不隐藏字段、不加必填、不炸保存。
- **条件必填**：有效必填 = 字段显示 &&（`required` || `requiredWhen` 成立）；缺失 → 42200 `fieldErrors` 带 `rule=required`、`message="必填"`（与渲染器 §10.3 同词，前后端逐字一致）。只查 `storage=json` 且非派生的字段（引擎只校验记录 JSON；实体 / 加密列必填随业务接口 E13，派生随 E11-T05）；`requiredWhen` 为空不追加必填（无条件必填就是 `required` 列）。
- **隐藏字段**：visibleWhen 不成立的字段不参与必填、其值不参与范围与跨字段校验（掩蔽，按「提交时已清空」口径）；提交落库前由 `stripHiddenFields` 移除残留值并返回被清空键（渲染器同语义 `hiddenFieldKeys` / `stripHiddenFieldValues`，`cleared-hidden` 事件）。完整度只把显示中的必填字段计入分母（A-PAT-019，渲染器随 E11-T07 / E11-T11）。
- 实现位置：后端 `FormConditionEngine`（求值）+ `FormRuleEngine.validate(data, fields, rules, context)`（必填与掩蔽进提交校验）；前端 `@crdp/rules` 的 `evaluateCondition` / `isFieldVisible` / `missingRequiredFields` / `stripHiddenFieldValues` 与 `validateFormData` 的第 4 参 `context`。演示种子：followup V31（吸烟史=是 → 每日吸烟支数显示并必填）；真实条件配置随 E23-T01 / C 字段字典录入。
- 条件列维护：`visible_when` / `required_when` 由实施经迁移配置（字典维护页 PUT 不改条件，form.md §3）。

### 2.5 共享用例（前后端一致的事实来源）

`backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json`：后端 `FormRuleEngineTest` 与前端 `packages/rules/src/form-validation.test.ts` 各执行一遍，errors / warnings 逐条（`key`、`categoryKey`、`rule`、`message`）完全一致（E11-T03 起用例可带条件字段与 `context`）。条件求值专用 `shared-cases/form-condition-cases.json`：后端 `FormConditionEngineTest` 与前端 `packages/rules/src/form-condition.test.ts` 各执行一遍，visible / missingRequired 逐条一致。派生专用 `shared-cases/form-derived-cases.json`（E11-T05 已落地）：后端 `FormDerivedEngineTest` 与前端 `packages/rules/src/form-derived.test.ts` 各执行一遍，每个派生字段的 value / status / message 逐条一致（见 §2.6）。对比规则（E17）沿用同一目录。

### 2.6 派生函数库（fe_field.derived_def，E11-T05 已实现；A-FLD-005、C-FLD-006）

- 内置 9 个函数（tech-plan §6「派生字段」；首期只用内置函数加参数，不开放任意脚本），`derived_def = {fn, args}`（args 为字段稳定键，随版本快照下发）：

| fn | 语义 | 使用方 |
|---|---|---|
| `DAYS_INCLUSIVE(起, 止)` | 止 − 起 + 1（含当天；止 ≥ 起才计算） | A 术后住院天数 |
| `MINUTES_BETWEEN(起, 止)` | 分钟差向下取整（秒截断） | A 手术时长 min |
| `HOURS_BETWEEN_1DP(起, 止)` | 小时差 1 位小数（160 小时 54 分 → 160.9） | A ICU 时间 h |
| `BMI(体重 kg, 身高 cm)` | kg ÷ (cm ÷ 100)²，1 位小数（175 / 70 → 22.9） | A BMI |
| `AGE_YEARS(出生, 参考日?)` | 周岁（生日未到减一）；参考日缺省按今天 | A 手术年龄；C 年龄、植入时年龄 |
| `SUM(...)` | 数值求和（任一来源缺失即留空） | A 桥血管数量（动脉桥 + 静脉桥） |
| `YEARS_SINCE(起, 止?)` | 整周年数（差一天不满即少一年，不设上限）；止缺省或为空按今天 | A 随访年限（止 = 死亡日期或今天） |
| `DURATION_YM(起, 止?)` | `X 年 Y 个月`（完整年 + 完整月，日未到减月） | C 植入时长（按当前器械） |
| `CHANGE_PCT(本次, 上次)` | （本次 − 上次）÷ 上次 × 100，1 位小数；上次为 0 无效 | C 参数变化率（同一电极 / 器械比较由 E17 对比规则取上次值） |

- **留空与提示**（A-FLD-005「输入缺失或结果无效时留空并提示」）：来源缺失（含空值口径与不可解析，与条件 DSL 空值同口径 OQ-65 ①）→ 留空 + `缺少{显示名}`（多个用「、」连接，按参数顺序）；来源无效（止早于起、身高 ≤ 0、上次为 0）→ 留空 + `{止}早于{起}` 等（显示名取来源字段 label，缺省止日提示「今天」）——文案前后端逐字一致，共享用例锁定。
- **拓扑重算**：派生可依赖派生（依赖图由定义自动生成，按拓扑顺序求值）；被依赖方来源缺失时取其实际生效值（手工值）。前端 `@crdp/rules` 的 `computeDerivedValues` 实时重算（渲染器回写并显示留空原因，来源缺失时放开手工录入并标 `meta.manual`，components.md §10.3）；后端保存时再算一遍并以后端结果为准（tech-plan §6）。
- **写入闸门**（`FormService.createRecord / saveRecord`）：来源齐全 → 计算值覆盖（含旧手工值，「来源齐全后以计算值为准」）；来源缺失 → 保留手工值（`data_meta.manual` 由录入侧标记）；结果无效且带手工值 → **42200** `fieldErrors` 带 `rule=derived`、message 同留空原因（「结果无效不允许手工录入，应先修正来源字段」）。草稿不写字段历史（§1.2）；「系统重算」历史（source=SYSTEM_RECALC、triggerFields=派生定义 args）由提交流程（E15 / E25）与导入（E18）调 `FormService.recalculateDerived(formCode, templateVersion, data)` diff 后经 `FieldHistoryService.logChange` 写入。
- 1 位小数舍入：`Math.round(v × 10) ÷ 10`（Java 与 JS 同为「半值向 +∞」，双精度运算逐位一致；不用十进制 HALF_UP，避免两端分叉）。
- 防御（与条件 / 规则引擎坏配置同口径，OQ-68）：未知函数、参数个数不符、坏 JSON、依赖成环的字段不出结果（不炸保存、不产出误导值）。
- 演示种子：followup V33（体重 f013、出院日期 f021、BMI f031，并把 V28 已建的术后住院天数 f030 来源补齐）；真实派生配置随 E23-T01 / C 字段字典录入。

## 3. 字段字典维护（A-FLD-001..009/012/013、C-FLD-001..010；E11-T01 已实现前两行与 publish，E11-T08 变更记录，E11-T09 Excel 批量导入）

字段元数据存租户库 `fe_form` / `fe_field` / `fe_option_set` / `fe_option`（tech-plan §6「元数据模型」）。
稳定键 `field_key` 与字段编码 `field_code`（导入导出名）**表单内唯一**、创建后不可改；显示名与编码分离
（A-FLD-002：页面显示「吸烟史」与选项「否 / 是」，数据库存 0 / 1）。字段可启停（`status`
ENABLED/DISABLED，停用不删除；**模板下发与校验只取启用字段**——发布进快照时过滤，E11-T02）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/form/fields?formCode=&categoryKey=` | `followup:field:edit` | 按分类列字段；行含 `key/code/label/categoryKey/categoryLabel/type/unit/decimals/hardRange/softRange/options/required/visibleWhen/requiredWhen/derivedDef/sensitive/storage/help/sortNo/status/updatedAt`（`categoryKey` 可省略 = 全部分类） |
| PUT | `/api/hospital/form/fields/{key}?formCode=` | `followup:field:edit` | 全量覆盖可维护列：显示名、帮助文字、单位显示、`softRange`、必填、**状态（启停）**、选项（`options[]`：`code` 已存在为改显示名 / 排序 / 停用，不存在为新增；不能删除已用选项、不能改选项 `code`）；**不能改** `key`、`code`、类型、存储方式、敏感标记、`hardRange`（请求体不携带这些列，A-FLD-002/008）。写审计 `FIELD_DICT_UPDATE`（前后值，启停在描述点明）。校验失败 40001（显示名必填 ≤128、软范围 min≤max、状态只认 ENABLED/DISABLED、选项编码必填且不重复）；字段 / 表单不存在 40400 |
| GET | `/api/hospital/form/fields/{key}/logs` | `followup:field:edit` | **E11-T08 已实现**。字段字典变更记录（A-FLD-009、C-FLD-009）：`[{id,formCode,fieldKey,action(FIELD_UPDATE\|FIELD_CREATE),beforeJson,afterJson,operatorId,operatorName(联查 sys_user),createdAt}]`，倒序最近 100 条；每次「维护字段」（含启停与选项调整）写 `FIELD_UPDATE`，每次导入的新增 / 更新（E11-T09）各写 `FIELD_CREATE` / `FIELD_UPDATE`，同落 `fe_field_dict_log`（租户库 followup V35）并与审计双写；字段 / 表单不存在 40400 |
| GET | `/api/hospital/form/fields/import-template` | `followup:field:edit` | **E11-T09 已实现**。字段字典导入模板（xlsx 单工作表）：27 列表头 `表单编码、稳定键、字段编码、显示名、分类键、类型、单位、小数位、硬范围下限、硬范围上限、提示范围下限、提示范围上限、必填、敏感、存储方式、选项集编码、选项、选项序数、互斥选项编码、需补充说明选项编码、选项别名、派生公式、条件显示、条件必填、帮助说明、排序、状态` + 三行示例（全部虚构数据，覆盖数值范围 / 选项 / 派生） |
| POST | `/api/hospital/form/fields/import` | `followup:field:edit` | **E11-T09 已实现**（验收「产品可用 Excel 批量导入字段定义」，E23-T01 / C 字段字典录入工具；multipart `file`，≤500 行）。同步一次性模型（OQ-52）：逐行校验并落库，**单行失败不影响其他行**，返回 `{ total, created, updated, failed, rows[{ rowNo, formCode, fieldKey, action(CREATED\|UPDATED\|FAILED), message }] }`。语义：按「表单编码 + 稳定键」命中——已有为**更新**（行是字段完整定义，可维护与实施列全量覆盖：显示名、分类、单位、小数位、硬 / 软范围、必填、条件、派生、帮助、排序、状态、选项），没有为**新增**；`字段编码`、类型、存储方式、敏感标记不可改（更新行留空保留原值，填写须与库内一致，否则该行失败，A-FLD-008）；选项集不存在自动建集（名称先取编码），选项 `编码=显示名` 新编码插入（带序数 / 互斥 / 需补充说明 / 别名四列实施标记）、已有编码更新显示名与实施列、不删除；派生公式列写 `AGE_YEARS(f003,f020)`（9 内置函数、arity 配置期校验、参数可前向引用同文件字段），条件列写 `f010=1`（多条件逗号分隔=全部成立，比较符 `= != > >= < <=`，值数字按数字 / 其余按字符串）→ 解析为 §2.4 条件树；表单 / 分类键 / 类型 / 选项集 / 公式与条件引用不合法 → 该行失败。文件级 40001：非 xlsx / 表头不符 / 无数据行 / 超 500 行；无医院会话 40100。写 `FIELD_DICT_IMPORT` 作业级审计 + 每字段一条 `fe_field_dict_log`（A-FLD-009） |
| POST | `/api/hospital/form/forms/{code}/publish` | `followup:field:edit` | **E11-T02 已实现**：无请求体；把当前分类与启用字段固化成新版本快照并前移 `currentVersion`，响应 `{ code, name, version, fieldCount, publishedAt }`。没有字典修改也允许发布（版本是显式动作）；并发发布后到者 40900。写审计 `FORM_PUBLISH`（前后版本与字段数）。停用表单发布 40001；表单不存在 40400 |

不做：医院自助新增字段（A-FLD-010）、通用表单设计器（A-FLD-011）——延后（见 `docs/tasks/tasks.md`）。C-FLD-008 “新增字段”由实施经迁移或 §3 的 Excel 批量导入（E11-T09）录入，不提供页面。页面 `/system/field`（菜单 `FollowupField`，平台库迁移 V35）；导入是实施 / 产品工具（E23-T01 录医院 A 的 266 字段、C 字段字典随 E21 录入），经 Swagger / 接口调用，无专门页面。

A-FLD-013（E11-T09，followup V36）：示例基线补「手术年龄」派生字段 `f032 = AGE_YEARS(f003 出生日期, f020 手术日期)`（手术日期 − 出生日期取周岁）；出生日期 `f003` 由 V28 预置（date、basic_info），不采集身份证号。

## 4. 业务字典（A-FLD-012、C-FLD-002/003）

E11-T08 已落地业务字典的维护能力（取值新增、停用、排序、**别名**与变更记录，接口见 `system.md` §4；「患者类型」「术者」平台预置字典见平台库 V36）。表单选项引用租户字典（`optionSource: { dict: 'pacing_mode' }`，后端下发 schema 时展开为 `optionSet`）仍为**推断，待确认**：fe_field 加 `dict_code` 列与展开逻辑随 E21-T04 / E23-T01 字段录入配套落地（见 OQ-69）；C 的统一起搏模式字典（C-FLD-003）为预置字典，其取值随 E21-T04。

## 5. 租户配置模板（A-FLD-014）

表单模板、字典、报告单类型等配置可通过平台“配置模板”导出 / 应用（见 `platform.md` §7）。
