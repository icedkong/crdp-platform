# 业务组件规格（components.md）

> 适用：`frontend/apps/web-hospital`（主要）、`frontend/apps/web-platform`（只用到 StatusTag、SearchBar、MaskedField 只读模式）。
> 依据：前端交互设计线框 V0.1 的“界面规范”“详情页左栏：附件导航”（**这两节优先于线框里的旧示意**），技术方案 V0.2 第 6、10、11 章，平台底座规格 V0.3，医院 A / C 规格。
> 视觉数值一律取自 `tokens.ts`；通用规则见 `design-system.md`。本文件只定义**业务组件的契约**，页面怎么组合见 `pages.md`，接口见 `docs/api/`。
> 标“推断，待确认”的是规格没有写死、这里给出的建议实现，可以改，但改之前先在 `open-questions.md` 记一笔。

## 目录

- [0. 放在哪里、共用约定](#0-放在哪里共用约定)
- [1. 基础组合函数（composables）](#1-基础组合函数composables)
- [2. StatusTag 状态标签](#2-statustag-状态标签)
- [3. MaskedField 脱敏字段](#3-maskedfield-脱敏字段)
- [4. PatientHeader 患者信息条](#4-patientheader-患者信息条)
- [5. AttachmentNav 附件导航（详情页左栏）](#5-attachmentnav-附件导航详情页左栏)
- [6. CategoryBar 分类条 / 分组条](#6-categorybar-分类条--分组条)
- [7. InlineAttachmentStrip 内联缩略图条](#7-inlineattachmentstrip-内联缩略图条)
- [8. LocationPicker 报告单位置选择](#8-locationpicker-报告单位置选择)
- [9. FollowupTimeline 随访时间轴](#9-followuptimeline-随访时间轴)
- [10. FormRenderer 表单渲染器契约](#10-formrenderer-表单渲染器契约)
- [11. SearchBar 与 AdvancedSearch](#11-searchbar-与-advancedsearch)
- [12. WatermarkPreview 带水印查看器](#12-watermarkpreview-带水印查看器)
- [13. 其他小组件](#13-其他小组件)
- [14. 组件测试要求](#14-组件测试要求)

---

## 0. 放在哪里、共用约定

| 组件 / 函数 | 放在 | 理由 |
|---|---|---|
| `useBreakpoint`、`usePermission`（`hasPermission`、`v-access`）、`StatusTag`、`MaskedField`、`WatermarkPreview` | `frontend/apps/web-hospital/src/reference` 与 `src/components/followup`（样板，见 `reference-pages.md`） | 平台端要用时再抽 package。`@crdp/admin-shell` 已删除（ADR 0002） |
| `FormRenderer`、`useAutoSave`、条件求值、派生函数、对比规则 | `frontend/packages/form-engine`（规则与派生函数放 `frontend/packages/rules`，Java 侧有同名实现，共用标准用例） | 技术方案第 6、11 章：TS 与 Java 两份实现 |
| `WatermarkPreview` 内部的 PDF / DOCX 渲染 | `frontend/packages/viewer` | 已有包 |
| `PatientHeader`、`AttachmentNav`、`CategoryBar`、`InlineAttachmentStrip`、`LocationPicker`、`FollowupTimeline` | `frontend/apps/web-hospital/src/components/followup/` | 只有医院端用 |
| 接口调用 | `frontend/packages/api`（按模块分文件：`followup/patient.ts` 等） | 组件不直接 `fetch` |

通用约定：

1. 组件用 `<script setup lang="ts">`，props / emits 用 TypeScript 接口声明（下文给出的就是要写的接口）。
2. **组件不自己判断医院**（不写 `if (tenant === 'A')`）。A / C 的差异只通过三种方式进入组件：表单模板（字段、分组、条件）、功能开关（`useFeature`）、权限码（`hasPermission`）。下文的 “A / C” 只是说明两家的配置结果。
3. 所有可见文字在组件里写中文常量（首期只做 `zh-CN`，见 design-system §16）；照抄的文案用 `「」` 标出，不得改字。
4. 组件不持有明文敏感数据，除了 `MaskedField` 在 60 秒内的内存状态。
5. 触控设备上，所有 `a-tooltip` 改为点击触发（`trigger` 用 `useBreakpoint().isTouch` 判断）。
6. 所有列表 / 详情在加载中用 `a-skeleton`（详情）或表格自带 loading（列表），失败时用 `a-result status="error"` + 「重试」按钮，并把后端 `traceId` 显示成 12px 次要色小字，方便报障。

---

## 1. 基础组合函数（composables）

### 1.1 `useBreakpoint()`

```ts
interface BreakpointState {
  width: Ref<number>;
  device: Ref<'mobile' | 'tablet' | 'desktop'>; // 取自 tokens.ts deviceOf()
  isMobile: ComputedRef<boolean>;  // < 768
  isTablet: ComputedRef<boolean>;  // 768–1279
  isDesktop: ComputedRef<boolean>; // ≥ 1280
  isTouch: ComputedRef<boolean>;   // matchMedia('(pointer: coarse)')
}
```

- 全站只有这一个断点来源（NFR-RESP-001），禁止在组件里写 `window.innerWidth < 800` 之类的魔法数。
- 实现：`window.matchMedia('(max-width: 767px)')` 与 `'(max-width: 1279px)'` 两个监听，SSR 安全（首期没有 SSR，但别在模块顶层访问 `window`）。
- 布局层据此：手机隐藏多标签页、菜单进抽屉；平板菜单收成图标栏。

### 1.2 `usePermission()` / `v-access`

```ts
function hasPermission(code: string | string[], mode?: 'any' | 'all'): boolean;
// 指令：v-access="'followup:patient:edit'"  → 没有权限时移除元素（隐藏）
```

- 权限码来自 `GET /api/hospital/auth/codes`（平台端 `/api/platform/auth/codes`），登录后缓存到 Pinia（不持久化），权限版本变化时（接口返回 40300，或 `me` 中 `permissionVersion` 变化）重新拉取。注意 40302 是“查询受控数据但未声明数据范围”的后端编程错误，不是权限变化。
- 按钮规则（design-system §9）：没有权限 → 隐藏；有权限但状态不允许 → `disabled` + `a-tooltip` 说明原因；**例外**：已提交记录上的「修改」没有权限也置灰，提示「需要“修改已提交记录”权限」。
- 前端判断只是体验，**后端每个接口仍校验权限码**（AGENTS.md 硬性规则）。

### 1.3 `useFeature(code)`

```ts
function useFeature(code: FeatureCode): ComputedRef<boolean>;
type FeatureCode =
  // 仓库种子 tenant/seed/V100__seed_tenant.sql 的 cfg_feature 已有：
  | 'report'             // 报告单与资料
  | 'wide_import'        // 宽表 Excel 导入（A-PAT-018；C 首期不做）
  | 'node_follow_rate'   // 节点随访率（A-DASH-012）
  | 'scan_progress'      // 电子化状态与补录进度（C-SCAN-011、C-DASH-007）
  | 'implant'            // 植入记录（C；控制基线资料里的植入记录区块、首页接近 ERI 卡片）
  | 'eri_cycle'          // 接近 ERI 加密随访 / ERI 标记（C-FU-006）
  | 'compare_highlight'  // 与上次对比标红（A-FU-027、C-FU-010）
  // 线框“预览控制”里有、仓库还没有的（编码为推断，待确认，新增时走 Flyway）：
  | 'param_trend'        // 参数趋势页签（C-FU-012）
  | 'next_visit_as_plan' // 下次随访时间 = 计划日期（C-FU-005）
  | 'baseline_lock'      // 基线锁定（A-PAT-006）
  | 'report_inbox';      // 待归档区（C-SCAN-009，P1）
```

- 功能开关存租户库 `cfg_feature`（`feature_code`, `enabled`），随 `GET /api/hospital/auth/me` 一起下发（若 `me` 尚未返回开关，需要后端补字段，推断，待确认，OQ-03）。样板页先用 fixture 上的 `features`，见 `reference-pages.md`。
- 关闭的功能：页签、按钮、卡片**直接隐藏**，不置灰。

### 1.4 `useAutoSave(options)`（随访表单、基线分节编辑共用）

```ts
interface AutoSaveOptions {
  recordKey: ComputedRef<string>;        // IndexedDB 键：`${tenantId}:${userId}:${kind}:${id}`
  kind: 'followup' | 'baseline';
  getChanges: () => Record<string, unknown>; // 与上次服务器版本相比的变更字段
  saveDraft: (body: { baseVersion: number; changes: Record<string, unknown> }) => Promise<{ version: number; savedAt: string }>;
  enabled: ComputedRef<boolean>;          // 已提交 / 只读 / 冲突中 → false
}
interface AutoSaveState {
  status: Ref<'idle' | 'dirty' | 'saving' | 'saved' | 'offline' | 'conflict'>;
  lastSavedAt: Ref<string | null>;        // 'HH:mm'
  conflict: Ref<null | { updatedBy: string; updatedAt: string; fields: string[]; serverVersion: number }>;
  saveNow(): Promise<void>;               // 「暂存」按钮、Ctrl/⌘+S
  keepMine(): Promise<void>;              // 「保留我的并覆盖」
  loadServer(): Promise<void>;            // 「载入服务器版本」，本地改动另存一份副本
  hasLocalDraft(): Promise<boolean>;      // 打开时提示「发现未提交的草稿，是否恢复」
}
```

规则（NFR-SAVE-001/002/003，不得改动）：

| 项 | 行为 |
|---|---|
| 本地草稿 | 每 `timings.localDraftIntervalMs`（30s）一次 + 每次字段 blur 写 IndexedDB；**不写**明文查看结果；退出登录清空本机全部草稿 |
| 服务器草稿 | 每 `timings.serverDraftIntervalMs`（2min）检查一次，**只有 dirty 时**才 `PUT …/draft`，带 `baseVersion` |
| 409 | 进入 `conflict`，**停止定时器**，表单顶部出冲突条（见 FormRenderer 状态条）；不做字段级合并 |
| 离线 | `navigator.onLine === false` 或请求网络错误 → `offline`，只存本地；`online` 事件后立即同步一次；离线时提交按钮置灰，tooltip「离线时不能提交：改动已存本地草稿，恢复网络后再提交」 |
| 状态文案（照抄） | 「已自动保存 HH:mm」/「有改动待同步（每 2 分钟）」/「离线 · 改动存在本机草稿」/「版本冲突 · 自动保存已暂停」 |
| 审计 | 自动保存不逐次写审计；`keepMine` 的覆盖由后端写审计 |
| 离开拦截 | dirty 时 `beforeunload` + 路由守卫 + 上一条 / 下一条：弹窗标题「有未保存的修改，离开前怎么处理？」，按钮 `暂存并离开` / `放弃修改` / `取消` |

测试：用 fake timers 覆盖 30s / 2min / blur / 离线 / 409 / 恢复网络 6 个分支。

---

## 2. StatusTag 状态标签

一个组件负责全站所有业务状态的颜色，页面里**禁止**直接写 `<a-tag color="red">逾期</a-tag>`。

```ts
interface StatusTagProps {
  status: string;                     // 中文状态名，如 '逾期'；或枚举值 + domain
  domain?: 'task' | 'patient' | 'plan' | 'record' | 'contact' | 'report' | 'tenant' | 'user' | 'import';
  variant?: 'dot' | 'tag';            // 列表用 dot（a-badge），详情 / 时间轴用 tag；默认按所在容器：表格单元格内 dot
  count?: number;                     // 标红数等，显示为 “标红 2”
  tooltip?: string;                   // 如“窗口结束后完成，记逾期完成”
}
```

- 基于：`a-badge status`（dot）、`a-tag color`（tag）；「待随访」是白底主色描边 `a-tag`（自定义 class）。
- 颜色来自 `tokens.ts` 的 `statusTone`；后端枚举到中文的映射放在 `packages/api/src/enums.ts`。未知状态显示灰色并在控制台 `warn`（开发环境）。
- 无事件、无响应式差异（手机上同样显示）。

完整对照（design-system §1.2 的展开版）：

| domain | 后端状态（建议枚举，推断，待确认） | 中文 | tone |
|---|---|---|---|
| task | `PENDING` / `IN_PROGRESS` / `DONE` / `DONE_EARLY` / `DONE_LATE` / `OVERDUE` / `BACKFILL` / `LOST_CANDIDATE` / `LOST` / `CLOSED` / `PLANNED`（计算值，不落库） | 待随访 / 进行中 / 已完成 / 已完成（窗口前完成）/ 窗口外（逾期完成）/ 逾期 / 补录 / 失访候选 / 失访 / 停止 / 计划中 | pending / processing / success / success / warning / error / default / warning / warning / default / default |
| patient | `ACTIVE` / `PAUSED` / `DEAD` / `TERMINATED` / `LOST` / `FINISHED` | 随访中 / 已暂停 / 死亡 / 停止 / 失访 / 随访结束 | processing / error / default / default / warning / default |
| plan | `UNPUBLISHED` / `PUBLISHED` / `RUNNING` / `PAUSED` / `DISABLED` | 未发布 / 已发布 / 随访中 / 已暂停 / 已停用 | warning / success / processing / error / default |
| record | `DRAFT` / `SUBMITTED` | 草稿 / 已提交 | default / success |
| contact | `REACHED` / `NOT_REACHED` / `WRONG_NUMBER` / `REFUSED` / `FAMILY` / `DECEASED` | 已联系 / 未接通（未联系到）/ 号码错误 / 拒访 / 家属代答 / 告知已故 | success / warning / warning / warning / success / default |
| report | `PENDING_ENTRY` / `DIGITIZED` / `NOT_NEEDED` / `UNLOCATED` | 待录入 / 已电子化 / 无需录入 / 未指定位置 | warning / success / default / warning |
| tenant | 试用 / 启用 / 宽限期 / 停用 / 注销中 / 已注销（PLT-TEN-010） | 同左 | processing / success / warning / error / warning / default |

---

## 3. MaskedField 脱敏字段

```ts
interface MaskedFieldProps {
  value: string;                 // 服务端已脱敏的值，如 '张*明'
  field: 'name' | 'mrn' | 'phone' | 'idNo' | string; // 字段键
  patientId: string;
  revealable?: boolean;          // 默认 false；只在 PatientHeader / 患者档案详情为 true
  permission?: string;           // 默认 'followup:patient:plain'
}
// emits: revealed(field), hidden(field)
```

**状态机**：`masked` →（点「显示明文」）→ `confirming`（原地 `a-popconfirm`）→（确认显示）→ `loading` → `revealed`（倒计时 60s）→ `masked`。任何时候点「隐藏（60 秒后自动脱敏）」或组件卸载 → `masked` 并清空内存值。

- 入口条件：`revealable && hasPermission(permission)`，否则只显示脱敏值，没有入口（隐藏，不置灰）。
- 确认文案（照抄）：「确认显示明文？本次查看写入审计日志（查看人、时间、患者、字段），60 秒后自动恢复脱敏。不需要再次输入密码。」按钮 `确认显示` / `取消`。
- 确认后调 `POST /api/hospital/followup/patients/{id}/plaintext`（body `{ fields: [field] }`，推断，待确认；后端每次写审计，A-PAT-010、C-PAT-012、PLT-SEC 相关）；成功提示「已显示明文并写入审计；60 秒后自动恢复脱敏」。
- 明文只放在组件的 `ref` 里：不进 Pinia、不进 IndexedDB、不进 URL、不 `console.log`、不放 `title` 属性（避免被浏览器缓存到无障碍树之外的地方）。定时器 `timings.plaintextRevealMs`。
- 列表里的脱敏列用 `revealable=false`；**列表不提供逐行看明文**。
- 平台租户视角：后端按视角规则返回脱敏 / 设盲值（PLT-VIEW-010/011），前端照样显示，入口隐藏。
- 基于：`a-popconfirm`、`a-typography-text`、`a-button type="link" size="small"`。
- 响应式：手机上 popconfirm 改 `a-modal` 小弹窗（宽 100%-32px），按钮 ≥ 40px 高。
- 测试：60 秒自动恢复（fake timers）；卸载时清空；无权限无入口；明文不出现在 `localStorage` / `sessionStorage` / IndexedDB。

---

## 4. PatientHeader 患者信息条

患者详情页顶部吸顶的信息条（A-PAT-004、C-PAT-010，A-PAT-019 完整度，C 另有器械信息）。

```ts
interface PatientHeaderProps {
  patient: PatientSummaryDTO;   // 见 docs/api/patient.md「患者概览」
  features: { implant: boolean; eriCycle: boolean }; // 来自 useFeature('implant') / useFeature('eri_cycle')
}
interface PatientSummaryDTO {
  id: string;
  nameMasked: string;
  gender: '男' | '女' | '未知';
  age: number | null;
  patientNo: string;            // A：编号；C：随访编号（C-PAT-003）
  legacyNo?: string;            // 原系统编号
  status: string;               // 患者随访状态（StatusTag domain=patient）
  wardName: string;             // A 病区；C 科室病区
  responsibleName: string;      // 负责医生 / 责任随访人
  baselineCompleteness?: number; // 0–100
  followupCompleteness?: number;
  nextVisit?: { label: string; planDate: string } | null; // 如 {label:'第4年', planDate:'2026-11-05'}；已终止为 null
  device?: { typeLabel: string; implantDuration: string; eri: '正常' | '接近ERI' | '已达ERI(EOI)' } | null; // C
}
// emits: action(type: 'unplanned' | 'upload' | 'contact' | 'stop')
```

**布局（桌面）**

```text
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ ← 张*明（示例） [显示明文]  男 · 63 岁 · 编号 DEMO-A-0001 · 原系统编号 OLD-A-000123  (随访中) │
│   病区 示例二病区   负责医生 王医生（示例）                [新建计划外随访] [上传报告单]   │
│   基线资料完整度 92%   随访资料完整度 78%   下次随访 第4年 · 计划 2026-11-05            │
│                                                            [记录沟通] [停止随访 / 标记失访] │
│ C 另加：当前器械 CRT-D · 植入 1 年 5 个月   ERI：正常                                    │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

| 按钮 | 权限码 | 状态限制（置灰 + tooltip） | 行为 |
|---|---|---|---|
| 新建计划外随访 | `followup:record:edit`（A、C 相同） | 患者不在随访中 → 「患者不在随访中，不能新建」 | 新标签页打开随访表单，随访时间点 = “其他”（C-FU-022） |
| 上传报告单 | `followup:scan:upload` | 无 | 打开上传弹窗（见 pages.md §6），位置预选为空 |
| 记录沟通 | `followup:record:edit`（A：录入与修改随访记录、沟通记录） | 「患者不在随访中」 | 打开沟通记录弹窗（520） |
| 停止随访 / 标记失访 | A `followup:plan:enroll`（暂停、恢复、移出计划，A-PAT-011）；C 规格无 `plan:enroll`，暂用 `followup:patient:edit`（推断，待确认，见 OQ-15.md） | 已终止 → 「随访已终止」 | 弹窗：选停止 / 失访，填原因；确认后不再创建后续任务；随访员标记的失访只是“失访候选”，需医生确认（A-FU-024） |

- 完整度、下次随访为大号数字（16/600）；下次随访已终止显示「—（已终止）」。
- 只读角色（科研查看）：不显示任何操作按钮。
- 姓名用 `MaskedField revealable`。
- C：`implant` 关闭时不显示器械行；`eri_cycle` 关闭时不显示 ERI。
- **平板**：信息换行，按钮保留在右侧可换行。
- **手机**：折叠为「姓名 + 状态 + 下次随访」，右侧「详情 ▾ / 收起 ▴」展开其余；4 个操作按钮收进「操作」按钮打开的底部抽屉。
- 吸顶：`position: sticky; top: 0`（在内容滚动容器内），z-index 低于弹窗。
- 基于：`a-card`、`a-descriptions`（只读块）、`a-space`、`a-dropdown`（手机为 `a-drawer placement="bottom"`）。

---

## 5. AttachmentNav 附件导航（详情页左栏）

**定稿（2026-10-31，优先于线框旧示意）**：基线资料、随访记录两个页签的左栏**只放报告单和附件**，不放表单分类。需求：A-PAT-020、C-PAT-017、A-ATT-002、A-ATT-020、C-SCAN-010、C-SCAN-020。

```ts
interface AttachmentNavProps {
  scope: 'baseline' | 'followup';
  patientId: string;
  items: AttachmentNavItem[];     // GET .../patients/{id}/reports?scope=baseline|followup（推断，待确认）
  loading?: boolean;
  canUpload: boolean;             // hasPermission('followup:scan:upload')
  collapsed?: boolean;            // v-model:collapsed，桌面可收起
}
interface AttachmentNavItem {
  reportId: string;
  typeCode: string; typeName: string;  // 报告单类型，如 心脏超声
  name: string;                        // 名称字典里的名称
  reportDate: string;                  // 报告时间
  thumbnailUrl?: string;               // 图片缩略图（短时链接）；PDF 等显示文件图标
  fileCount: number;
  digitizeStatus?: '待录入' | '已电子化' | '无需录入'; // C
  locations: ReportLocation[];         // 全部位置，可为空 → 进“未指定位置”
}
interface ReportLocation {
  linkType: 'baseline' | 'followup_record' | 'implant_record';
  linkRecordId?: string;               // 基线为空
  recordLabel?: string;                // 如 '第3年随访'、'植入记录 #2'
  recordDate?: string;                 // 随访分组排序用
  anchorType?: 'category' | 'section' | null; // null = 整条记录
  anchorKey?: string;                  // 模板稳定 key
  anchorLabel?: string;                // 显示名，如 '病例基本信息'
  adjusted?: boolean;                  // 模板改版后退回过，显示“原位置已调整”
}
// emits:
//   locate(loc: ReportLocation)      — 点位置标签：右侧滚动定位
//   open(reportId)                   — 点缩略图：打开 WatermarkPreview
//   upload(preset?: ReportLocation)  — 空状态 / 组内「上传」
//   edit-location(reportId)          — 附件菜单「修改位置」/ 未指定位置组「指定位置」
//   update:collapsed(boolean)
```

**结构**

```text
附件（12）                            [«]   ← 宽 240，可收起
筛选：类型 ▾ · 日期 ▾（可选）
▾ 心脏超声（2）     ▢▢
▾ 出院小结（1）     ▢
     [病例基本信息] [出院带药]            ← 位置标签（a-tag，可点）
▾ 冠脉造影（3）     ▢▢▢
▾ 未指定位置（1） · 指定位置              ← 组名橙色；“指定位置”有上传权限才显示
```

分组规则：

| | 基线资料（scope=baseline） | 随访记录（scope=followup） |
|---|---|---|
| 一级分组 | 报告单类型 | 随访（年度或节点，**时间倒序**），再按类型二级分组 |
| 出现次数 | **每份附件只出现一次**，下面列出全部基线位置标签 | 同一次随访内只出现一次；关联到几次随访的，在每次随访下各出现一次，标签只列该次的分组 |
| 点附件本身 | 跳到第一个位置 | 跳到该次记录的第一个位置 |
| 未指定位置 | 只放一个位置都没有的附件（含旧数据迁移来的基线关联），组名 `colors.warning` | 同左；旧“关联某年随访”的数据已对应到该年记录整条，算有位置 |
| 空状态（照抄） | 「暂无基线附件 · 上传」 | 「暂无随访附件 · 上传」 |
| 筛选无结果（照抄） | 「没有符合条件的附件 · 清除筛选」 | 同左 |

点击位置标签（`locate`）由**页面**处理（组件只发事件），页面行为：

1. 基线：右侧滚到 `anchorKey` 对应分类（DOM id 由 FormRenderer 输出，见 §10），加 `.crdp-anchor-flash` 2 秒（`timings.anchorHighlightMs`），`CategoryBar` 同步高亮；写 URL hash（`#cat=<anchorKey>`），可分享、可后退。
2. 随访：若在表格视图先切到时间轴；展开该次记录；滚到该分组并高亮；只关联到整条记录的滚到记录顶部；hash `#rec=<recordId>&sec=<anchorKey>`。
3. 抽屉模式（平板 / 手机）：点完先关抽屉再跳转。

- 附件右键 / `⋮` 菜单：`预览`、`修改位置`（`followup:scan:upload`）、`下载`（`followup:scan:download`）、`删除`（`followup:scan:delete`，popconfirm，软删除填原因）。
- 左栏**只用于查看和跳转**，编辑表单仍在右上「编辑」打开新标签页。
- C 的电子化状态在附件名后显示小号 `StatusTag`（待录入橙 / 已电子化绿）。
- **桌面**：固定左栏 240px，可收起成 24px 竖条（展开按钮）。**平板**：左栏不显示，右侧内容顶部一个按钮「附件（n）」打开 `a-drawer placement="left"` 宽 320。**手机**：同一按钮打开 `a-drawer placement="bottom"` 高 70vh。
- 基于：`a-collapse`（ghost）或 `a-directory-tree`（二选一，推荐 `a-collapse` + 自定义行，便于放缩略图和标签）、`a-tag`、`a-image`（缩略图）、`a-empty`、`a-select`（筛选）、`a-drawer`。
- 缩略图懒加载（`loading="lazy"`），短时链接过期时重新取列表。
- 测试：多位置只出现一次；跨两次随访出现两次且标签各自只列本次分组；无位置进入橙色组；空 / 筛选空文案；点击事件参数。

---

## 6. CategoryBar 分类条 / 分组条

右侧内容顶部的**吸顶横向分类条**（A-PAT-021、C-PAT-018），以及随访表单 / 展开记录里的**分组条**，同一个组件。

```ts
interface CategoryBarProps {
  items: CategoryProgress[];   // 来自表单引擎：GET .../forms/{code}/progress 或随记录返回（推断，待确认）
  activeKey?: string;          // v-model:activeKey，滚动时由 scroll-spy 更新
  mode?: 'auto' | 'bar' | 'select'; // auto：桌面/平板 bar，手机 select
  showHighlightCount?: boolean;     // 随访分组条显示标红数
  scrollContainer?: () => HTMLElement; // 用于 scroll-spy
}
interface CategoryProgress {
  key: string;        // 模板稳定 anchorKey
  label: string;      // 如 '病例基本信息'
  filled: number;     // 已填必填（只计实际显示的字段）
  total: number;      // 应填必填
  highlightCount?: number; // 标红数（随访）
  attachmentCount?: number; // 📎 数
  missingRequired?: number; // 提交校验后未填必填数
}
// emits: jump(key), update:activeKey(key)
```

- 每项显示 `label filled/total`；`filled < total` 时数字为 `colors.warning`；有标红时追加红色「▲n」；当前项主色底。示例：`[基本信息 12/12] [病例基本信息 35/37] [实验室检查 15/18] [超声检查 46/49] …`
- 点击 → `jump(key)`，页面滚动到 `#crdp-anchor-<key>` 并闪烁高亮 2 秒。
- scroll-spy：`IntersectionObserver` 观察各分类锚点，顶部最近的一个为 active。
- **桌面 / 平板**：横向 chip 条，溢出横向滚动（隐藏滚动条，左右渐隐），`position: sticky`，位于 PatientHeader 之下。**手机**：`a-select` 下拉（「跳到分组」）+ 在随访表单里另有底部抽屉列表。
- 随访记录页签：只在**展开某次记录**时，在该记录顶部吸顶显示该记录的分组条；记录收起时不显示。
- 随访整页表单：桌面为左侧竖向锚点（宽 `sizes.formAnchorWidth` 180，`a-anchor`），平板为吸顶横向条，手机为顶部下拉 + 底部抽屉。三种形态共用 `items` 数据。
- 基于：`a-anchor`（竖向）、自定义 chip 条（`a-tag` checkable 样式）、`a-select`。
- 测试：进度着色、标红数显示、手机切换为下拉、scroll-spy 更新 active。

---

## 7. InlineAttachmentStrip 内联缩略图条

显示在每个分类 / 分组**末尾**的缩略图条；一份附件关联几个位置，就在几个位置都显示（A-ATT-002、C-SCAN-010）。

```ts
interface InlineAttachmentStripProps {
  anchor: { linkType: ReportLocation['linkType']; linkRecordId?: string; anchorKey?: string };
  items: Array<Pick<AttachmentNavItem, 'reportId' | 'typeName' | 'name' | 'reportDate' | 'thumbnailUrl' | 'fileCount'>>;
  canUpload: boolean;
  editable?: boolean;   // 整页表单内为 true：显示「上传附件」入口（预选当前位置）
}
// emits: open(reportId), upload(preset: ReportLocation)
```

- 分类标题旁显示「📎 n」（n = 该位置附件数，0 不显示）。
- 缩略图 64×64，圆角 4，最多一行，超出显示「+N」（点开 = 在 AttachmentNav 中按该位置筛选）；PDF / DOCX 显示文件类型图标 + 名称截断。
- 悬停显示「类型 · 名称 · 报告时间」。
- 点击 → `open(reportId)` → `WatermarkPreview`。
- `editable && canUpload` 时末尾有虚线框「上传附件」，`upload` 事件带当前位置作为 LocationPicker 预选。
- 手机：缩略图 56×56，横向滚动。
- 基于：`a-image`（只用缩略图，不用它自带的 preview，预览统一走 WatermarkPreview）、`a-badge`。

---

## 8. LocationPicker 报告单位置选择

上传与「修改位置」时选择报告单位置。**必填，可多选，至少 1 个**（A-ATT-002、A-ATT-020、C-SCAN-010、C-SCAN-020）。

```ts
interface LocationPickerProps {
  patientId: string;
  value: ReportLocationInput[];             // v-model
  preset?: ReportLocationInput[];           // 从某分类 / 分组进入时预选，可再加
  options: LocationTreeNode[];              // GET .../patients/{id}/report-locations（推断，待确认）
  disabled?: boolean;
}
interface ReportLocationInput {
  linkType: 'baseline' | 'followup_record' | 'implant_record';
  linkRecordId?: string;
  anchorType?: 'category' | 'section' | null;
  anchorKey?: string;
  anchorTemplateVersion: number;            // 由 options 带出，提交时原样回传，后端按模板版本校验
}
interface LocationTreeNode {
  key: string; title: string;
  value?: ReportLocationInput;              // 叶子 / 可选节点才有
  selectable: boolean;
  children?: LocationTreeNode[];
}
// emits: update:value
```

**树形结构**（`a-tree-select` `tree-checkable` `show-checked-strategy="SHOW_ALL"`）：

```text
基线资料
 ├─ 基本信息                 (baseline / category)
 ├─ 病例基本信息
 └─ …（A 9 个分类；C：基本信息、病史与植入指征、术前检查、当前器械摘要）
随访记录
 ├─ 第3年随访 2025-11-20     (followup_record / 整条记录)
 │   ├─ 随访概况             (followup_record / section)
 │   └─ 超声复查 …
 └─ 计划外随访 2026-06-10 …
植入记录（C，功能开关 `implant`）
 ├─ 植入记录 #2 2025-…       (implant_record / 整条)
 │   ├─ 器械信息
 │   └─ 电极
```

- 只勾记录（不勾分组）= 关联整条记录；勾分组时**不自动**勾父节点（`tree-check-strictly`）。
- **不支持字段级位置**。
- 校验：至少 1 个，错误文案「请至少选择 1 个位置」（推断，待确认）。
- 选中值在框内以标签显示：「基线 · 病例基本信息」「第3年随访 · 超声复查」，超过 2 个折叠「+N」。
- 「修改位置」保存调 `PUT /api/hospital/followup/reports/{id}/locations`（推断，待确认）；后端写操作日志。
- 手机：`a-tree-select` 下拉改为底部抽屉内的 `a-tree`（全屏高 80vh）。
- 测试：预选、至少 1 个、整条 vs 分组、模板版本回传。

---

## 9. FollowupTimeline 随访时间轴

患者详情「随访记录」页签的右侧内容（A-FU-023、A-PAT-013、C-FU-023）。默认时间轴，可切换表格，**记住用户上次选择**（`localStorage` 键 `crdp:fu-view`，只存 'timeline' | 'table'，不含任何患者信息）。

```ts
interface FollowupTimelineProps {
  patientId: string;
  nodes: TimelineNode[];         // GET .../patients/{id}/followup-timeline（推断，待确认）
  events?: DeviceEvent[];        // C：器械事件（功能开关 implant）
  view?: 'timeline' | 'table';   // v-model:view
  expandedRecordId?: string;     // v-model，展开显示该次记录只读内容
}
interface TimelineNode {
  taskId?: string; recordId?: string;
  label: string;                 // '第1年随访' / '术后1个月' / '计划外随访' / '常规随访'
  status: string;                // task domain 状态
  planDate?: string;             // 计划外无
  windowStart?: string; windowEnd?: string;
  doneDate?: string;
  highlightCount?: number;
  note?: string;                 // 如 '补录（纸质），2025-01-15 录入'、'窗口结束后完成，记逾期完成'、'症状就诊；不作对比对象'、'已暂存 1 次'、'计划中，任务开启日才创建'
  canOpen: boolean;              // 计划中（任务未创建）为 false
}
interface DeviceEvent {
  date: string;
  kind: 'first' | 'upgrade' | 'replace' | 'lead_fix' | 'other';
  title: string;                 // '首次植入 · 双腔(DDD)' / '升级 · CRT-D'
  detail?: string;               // '入组“普通起搏器术后随访”' / '转入“ICD/CRT-D 术后随访”，从升级日重新起算'
}
// emits: open(node) — 新标签页打开随访表单；quick-view(recordId) — 右侧抽屉只读查看；update:view; update:expandedRecordId
```

**时间轴形态**

```text
[时间轴 | 表格]                                    跳到节点 ▾      状态：●待随访 ●进行中 ●已完成 ●逾期 ●补录 ●窗口外
◆ 2024-05-…  首次植入 · 双腔(DDD)   入组“普通起搏器术后随访”                     ← C 器械事件：菱形
● 第1年随访  (已完成)   计划 2023-11-05 · 窗口 2023-10-29 ~ 2023-11-12 · 完成 2023-11-02
● 第2年随访  (补录) 标红 1   计划 … · 完成 …        补录（纸质），2025-01-15 录入
● 第3年随访  (窗口外) 标红 2  计划 … · 完成 …       窗口结束后完成，记逾期完成
● 计划外随访 (已完成)  无计划日期 · 完成 2026-06-10   症状就诊；不作对比对象
● 第4年随访  (进行中)  计划 2026-11-05 · 窗口 …     已暂存 1 次
○ 第5年随访  (计划中)  计划 2027-11-05 · 窗口 …     计划中，任务开启日才创建
```

- 节点圆点颜色 = `StatusTag` tone；卡片内状态用 `StatusTag variant="tag"`，标红数为红色「标红 n」。
- 点节点卡片：`canOpen` → `open`（新标签页 `/followup/records/:recordId/edit` 或 `/followup/tasks/:taskId/form`，见 pages.md §5）；`canOpen=false` → 置灰 tooltip「计划中的节点尚未创建任务，任务开启日才可录入」。
- 已提交记录卡片右侧「快速查看」→ `quick-view` → 右侧 `a-drawer` 宽 640 只读显示（抽屉**只**用于这个场景）。
- 「展开」→ 卡片内展开该次记录只读内容（FormRenderer `mode="readonly"`），顶部吸顶该记录的 CategoryBar（带标红数），每个关联分组末尾 InlineAttachmentStrip。
- C：器械事件（升级 / 更换）以菱形 ◆ 插在时间线上，之后的节点重新起算（C-FU-007）。
- **表格形态**：列 `节点 / 状态 / 计划日期 / 窗口 / 完成日期 / 标红 / 备注 / 操作`，`size="middle"`，不分页（一名患者节点数有限）。
- 「跳到节点 ▾」：`a-select`，选中后滚动并展开。
- **平板**：同桌面。**手机**：时间轴卡片撑满宽，窗口与完成日期换行；表格视图改卡片列表。
- 基于：`a-timeline`（`a-timeline-item` 自定义 dot：菱形用 CSS rotate 的 8px 方块）、`a-card size="small"`、`a-segmented`（视图切换）、`a-table`、`a-drawer`。
- 测试：状态颜色、计划中不可打开、菱形事件排序、视图记忆、hash 定位展开。

---

## 10. FormRenderer 表单渲染器契约

**最重要的组件**。随访表单、基线分节编辑与只读视图、报告单类型字段集、植入记录都由它渲染。**字段、分组、选项、条件显示、条件必填、校验、派生字段全部来自表单模板（字段字典）**，前端不得写死任何业务字段（AGENTS.md 硬性规则；A-FLD-001..007、C-FLD-001..010）。

### 10.1 输入：表单模板 schema（后端 `GET /api/hospital/form/forms/{code}?version=` 返回，推断，待确认）

```ts
interface FormSchema {
  code: string;              // 如 'A_BASELINE'、'A_ANNUAL_FU'、'C_PROGRAM_FU'、'C_IMPLANT'
  version: number;
  categories: FormCategory[]; // 基线“分类”；随访“分组”。统一叫 category，anchorType 由使用方决定
  rules: CrossFieldRule[];    // fe_rule 跨字段校验
  compareRules?: CompareRule[]; // 对比标红规则（租户配置，A Q41 / C Q02 默认）
}
interface FormCategory {
  key: string;               // 稳定 anchorKey，模板改版不变
  label: string;
  order: number;
  sections?: Array<{ key: string; label: string; fieldKeys: string[] }>; // 分组内小标题（可选）
  fields: FormField[];
}
interface FormField {
  key: string;               // 稳定 ASCII 键，如 'f019'
  code: string;              // 字段编码（导入导出名）
  label: string;
  type: 'number' | 'text' | 'textarea' | 'date' | 'datetime' | 'select' | 'radio' | 'multiselect'
      | 'boolean' | 'pair_number' /* 数值×2：长、宽 */ | 'derived';
  unit?: string;
  decimals?: number;
  hardRange?: { min?: number; max?: number };   // 取值校验范围：超出不能保存
  softRange?: { min?: number; max?: number };   // 提示范围：超出提示，也用于标红“超出提示范围”
  optionSet?: Array<{ code: number; label: string; ordinal?: number; exclusive?: boolean; needsDetail?: boolean; disabled?: boolean }>;
  required?: boolean;
  visibleWhen?: ConditionTree;   // { all: [...] } / { any: [...] }
  requiredWhen?: ConditionTree;
  derived?: { fn: 'DAYS_INCLUSIVE' | 'MINUTES_BETWEEN' | 'HOURS_BETWEEN_1DP' | 'BMI' | 'AGE_YEARS' | 'SUM' | 'YEARS_SINCE' | 'DURATION_YM' | 'CHANGE_PCT'; args: string[] };
  sensitive?: boolean;           // 敏感字段：服务端脱敏，表单内显示 MaskedField 只读
  carryOver?: boolean;           // 带入上次值适用字段（A-FU-015 / C-FU-016，E11-T07）：设置项 true、测量值缺省；「带入上次值」按钮一键带入 previous 的值再修改
  storage: 'json' | 'column' | 'encrypted';
  help?: string;
  width?: 'number' | 'date' | 'select' | 'short' | 'long'; // 缺省按 type 推导（tokens.sizes.fieldWidth）
}
type ConditionTree = { all?: Condition[]; any?: Condition[] };
type ConditionOp = 'eq' | 'ne' | 'in' | 'nin' | 'gt' | 'gte' | 'lt' | 'lte' | 'empty' | 'notEmpty';
type Condition =
  | { field: string; op: ConditionOp; value?: unknown }  // 本表单字段
  | { ctx: string; op: ConditionOp; value?: unknown }    // 上下文变量，如 'device.type'、'device.hasRaLead'、'device.chambers'
  | ConditionTree;                                       // 嵌套
interface CrossFieldRule {   // fe_rule（E11-T04 已落地；实时下发，不进版本快照，docs/api/form.md §2.2）
  id: string;                // 规则稳定编码（命中时 fieldErrors[].rule）
  severity: 'HARD' | 'SOFT'; // HARD 违反拦截（42200）/ SOFT 黄色提示可保存（C-FLD-007）
  categoryKey: string;       // 命中提示挂靠分类锚点键
  key: string;               // 命中提示挂靠字段稳定键
  params: { leftField: string; op: 'lt' | 'lte'; rightField: string };  // 「leftField op rightField 须成立」
  message: string;           // 违反提示文案（前后端同源）
}
```

### 10.2 Props / Emits

```ts
interface FormRendererProps {
  schema: FormSchema;
  modelValue: Record<string, unknown>;        // data（按字段 key）
  meta?: Record<string, { manual?: boolean; note?: string; fromReport?: string }>; // data_meta
  mode: 'edit' | 'readonly';
  layout?: 'auto' | 'horizontal' | 'vertical'; // auto：桌面 140 右对齐、平板 120、手机标签在上；只读 120
  context?: Record<string, unknown>;          // 条件上下文：C 的 device.type / device.hasRaLead / device.chambers
  previous?: { recordId: string; date: string; values: Record<string, unknown> } | null; // “上一次”（规则参数决定取法，后端给）
  history?: Array<{ recordId: string; date: string; values: Record<string, unknown> }>;  // 最近 3 次（已提交，不含计划外与草稿）
  highlightEnabled?: boolean;                 // useFeature('compare_highlight')
  locatedAttachments?: Record<string, AttachmentNavItem[]>; // anchorKey → 附件，用于每组末尾 InlineAttachmentStrip
  showErrors?: boolean;                       // 提交校验后为 true
}
// emits:
//   update:modelValue(data)
//   update:meta(meta)               — 「其他」说明等写入 data_meta
//   fieldBlur(key)                  — useAutoSave 本地草稿（E11-T07 落地 camelCase；模板监听 @field-blur 等价）
//   carryOver(keys)                 — 「带入上次值」按钮带入的字段键（E11-T07）
//   clearedHidden(keys)             — 条件变为不显示时被清空的字段（同上 camelCase；后端提交时也会清空并写字段历史）
//   progress(items: CategoryProgress[]) — 给 CategoryBar
//   validate(result: { ok: boolean; missingRequired: Array<{ key: string; categoryKey: string }>; hardErrors: [...] })
//   highlight(result: Array<{ key: string; categoryKey: string; ruleId: string; message: string }>)
// expose: validate(): Promise<ValidateResult>; scrollTo(categoryKey): void; focusFirstError(): void
```

### 10.3 行为

| 主题 | 规则 |
|---|---|
| 锚点 | 每个分类 / 分组输出 `id="crdp-anchor-<key>"`；分类标题 14/600，旁边「📎 n」 |
| 布局 | 编辑：标签右对齐 140px + 冒号（平板 120，手机标签在上）；只读：右对齐 120px；字段按类型定宽（`tokens.sizes.fieldWidth`），**不随屏幕拉伸**；长文本占满一行 |
| 数值 | `a-input-number`（`controls=false`），单位放框外；`inputmode="decimal"`；超出 hardRange 阻止保存，超出 softRange 显示橙色提示文字 |
| 选项 | 单选：选项 ≤ 4 用 `a-radio-group`（按钮样式），否则 `a-select`（120–180px）；多选：`a-checkbox-group` 或 `a-select mode="multiple"`；互斥选项（“无”）选中时清除其他；“其他”需补充说明时出短文本 |
| 条件显示 | 实时求值 `visibleWhen`；隐藏字段**不进 Tab 顺序**、不计完整度、值清空并 `cleared-hidden`；`requiredWhen` 动态加必填 * |
| 派生字段 | 灰色文字 + 「自动计算 · 只读」chip，永不可编辑；来源缺失时允许手工录入并 `meta.manual = true`；依赖拓扑顺序实时重算（`packages/rules`） |
| 上次值 | 字段下方 12px 次要色「上次 xx」；「历史」`a-popover`（手机底部抽屉）标题「最近 3 次（已提交记录，不含计划外与草稿）」，列「前 2 次 / 前 1 次 / 上次 / 本次」；没有对比规则的字段显示「仅显示上次值，无对比规则」；「带入上次值（日期）」按钮一键带入 `carryOver` 字段的 previous 值（E11-T07 / A-FU-015、C-FU-016），带入后浅色标出、改动即恢复 |
| 对比标红 | `highlightEnabled` 时按 `compareRules` 实时求值；命中字段：红底 + 「▲」+ 规则说明（tooltip，如「射血分数下降：比上次下降 ≥ 10 个百分点；或超出提示范围 EF ＜ 40%（A-FU-027 / Q41，阈值可由租户管理员调整）」）；分组标题、锚点、CategoryBar 显示该组标红数；**不弹窗、不发通知、不拦提交**；C 脉宽不同追加「；脉宽不同，仅供参考」 |
| 必填错误 | 红边框 + 「必填」；与标红靠形状区分 |
| 报告单带入 | `meta.fromReport` 的字段：主色浅底 + 「报告单带入」chip |
| 敏感字段 | 只读显示 `MaskedField`（不可在表单中看明文），chip「敏感 · 默认脱敏」 |
| 只读模式 | 空值显示「未填写」（次要色）；不渲染输入控件 |
| 键盘 | Tab 分组从上到下、组内从左到右；**Enter 不提交**；`Ctrl/⌘+S`、`Ctrl/⌘+Enter` 由**页面**监听（FormRenderer 只阻止 Enter 提交） |
| 手机 | 单列；输入框 100% 宽、高 36、字号 16；底部操作栏由页面提供 |

### 10.4 两家医院的配置结果（由模板产生，不写在组件里）

- A 随访分组：随访概况、随访结局（本次结局）、临床事件、再住院、心功能与症状、超声复查、用药。再住院 = 是 → 才显示再住院类型、再住院日期；结局 = 死亡 → 死亡日期、死亡原因；结局 = 失访 → 失访原因（多次未接通 / 号码停机 / 拒绝随访 / 其他）、最后成功联系日期。
- C 随访分组：随访概况、症状与检查、电池、起搏阈值与脉宽、感知、阻抗、起搏比例与程控参数、ICD 项目（仅 ICD / CRT-D）。心房参数只在有 RA 电极时显示（`ctx device.hasRaLead`）；AV 间期只在双腔及以上；ICD 项目只在 ICD / CRT-D（`ctx device.type in [ICD, CRT-D]`）。随访序号「第 n 次（按随访日期自动编号）」、植入时长「x 年 y 个月（当前器械）」为派生只读。
- A 基线分类：基本信息、病例基本信息、诊断信息、实验室检查、超声检查、术前造影、手术信息、围手术期、出院带药。
- C 基线（基线资料页签）：基本信息、病史与植入指征、术前检查、当前器械摘要；另有植入记录与电极区块（见 pages.md §4.4）。

### 10.5 共享用例

条件求值、派生函数、对比规则在 `packages/rules` 与后端 Java 各一份，**同一份用例文件**（JSON，放 `backend/crdp-form/src/test/resources/shared-cases/`，前端测试按相对路径读取——已定稿），取自需求验收标准（例如：出院 − 手术 + 1；160 小时 54 分 → 160.9；EF 56 → 45 下降 11 → 标红）。范围与跨字段校验用例已随 E11-T04 落地：`shared-cases/form-validation-cases.json` 由后端 `FormRuleEngineTest` 与前端 `packages/rules/src/form-validation.test.ts` 各执行一遍，errors / warnings 逐条（key、categoryKey、rule、message）一致；条件求值已随 E11-T03 落地：`shared-cases/form-condition-cases.json` 由后端 `FormConditionEngineTest` 与前端 `packages/rules/src/form-condition.test.ts` 各执行一遍，visible / missingRequired 逐条一致（求值语义见 docs/api/form.md §2.4）；派生已随 E11-T05 落地：`shared-cases/form-derived-cases.json` 由后端 `FormDerivedEngineTest` 与前端 `packages/rules/src/form-derived.test.ts` 各执行一遍，每个派生字段的 value / status / message 逐条一致（函数库与写入闸门见 docs/api/form.md §2.6）；**三引擎组合的标准用例已随 E11-T10 落地**：`shared-cases/form-standard-cases.json` 由后端 `FormStandardCaseTest` 与前端 `packages/rules/src/form-standard.test.ts` 各执行一遍，按提交流程的标准管线（① 条件求值 visible → ② 隐藏残留值清空 clearedHidden → ③ 派生拓扑重算（清空后的数据）→ ④ 校验在「清空 + ok 计算值回写」的数据上执行、结果无效的手工派生值追加 errors rule=derived 在最后）五个结果块逐条一致——组合顺序本身是被测语义（清空先于派生、派生先于校验、掩蔽先于跨字段），见 docs/api/form.md §2.5；对比（E17）沿用同一目录。

---

## 11. SearchBar 与 AdvancedSearch

### 11.1 SearchBar（行内筛选）

```ts
interface SearchBarProps {
  fields: Array<{ key: string; label: string; type: 'input' | 'select' | 'date-range' | 'ward' | 'user'; options?: any[]; width?: number }>;
  modelValue: Record<string, unknown>;
  collapsedRows?: number;        // 默认 2，超出折叠「展开 ▾ / 收起 ▴」
  advanced?: boolean;            // 是否显示「高级检索」
  savedFilters?: boolean;        // 是否显示「我的筛选 ▾」（A-PAT-015）
}
// emits: update:modelValue, search, reset, open-advanced
```

- 「标签：输入框」，框宽 180–220；按钮顺序 `查询`（主色 + 放大镜）/ `重置`（刷新图标）/ `高级检索`（ghost + 漏斗）。
- 筛选条件写入 URL query（**不得包含姓名、病案号、电话**原文；这些检索词只放组件状态，不进 URL，防止进浏览器历史，推断，待确认）。
- 平板：筛选收进右侧抽屉；手机：底部抽屉。
- 基于：`a-form layout="inline"`、`a-input`、`a-select`、`a-range-picker`。

### 11.2 AdvancedSearch（高级检索弹窗）

```ts
interface AdvancedSearchProps {
  open: boolean;
  fieldCatalog: Array<{ key: string; label: string; type: FormField['type']; optionSet?: FormField['optionSet']; category: string }>; // 只能选字段字典中的字段
  value?: ConditionGroup;
  mobileCommonOnly?: boolean;    // 手机只留常用条件
}
type ConditionGroup = { logic: 'and' | 'or'; items: Array<ConditionRow | ConditionGroup> };
type ConditionRow = { field: string; op: string; value?: unknown };
// emits: update:open, apply(group), save-as(name, group)
```

- 弹窗宽 900；逐行：指标 / 运算符 / 条件值 / 逻辑运算符（并且 / 或者）/ `+` / `−`；可拖动排序；可加条件组。
- 底部只读「逻辑表达式预览」，示例：（病区 = 示例一病区 并且 EF 射血分数 < 40）或者 再住院类型 不为空。
- 「保存为个人筛选」（A-PAT-015）。
- 输出**结构化 JSON**，后端只接收结构化条件，**不允许原始 SQL**（A-PAT-014、A-NFR-009），后端自动叠加数据范围。
- 条件行组件全站共用（以后预警规则 A-ALR 也用它）。
- 基于：`a-modal`、`a-select`、`a-input-number`、`a-date-picker`、拖动用 `vuedraggable` 或原生 HTML5 DnD（推断，待确认，避免引新依赖可用原生）。

---

## 12. WatermarkPreview 带水印查看器

```ts
interface WatermarkPreviewProps {
  open: boolean;
  reportId: string;
  files: Array<{ fileId: string; mime: string; name: string; pages?: number }>;
  index?: number;                         // 当前文件
  canDownload: boolean;                   // hasPermission('followup:scan:download')
  viewer: { displayName: string; account: string; tenantName: string }; // 来自 me
}
// emits: update:open, update:index, download(fileId)
```

- 全屏深色遮罩，顶部工具栏：`下载`（有权限才显示）/ `旋转` / `翻转` / `缩小` / `放大` / `关闭`，图片用 `a-image` 的 `preview` + `toolbarRender`；PDF 用 pdf.js（`packages/viewer`）；DOCX 用 docx-preview；XLSX 只能下载（无权限时显示「无法在线预览，需下载权限」）；音频用 `<audio controls controlsList="nodownload">`（AMR 只能下载）。
- 内容走预览代理的**短时有效链接**（`GET …/files/{fileId}/preview-url`，推断，待确认），不缓存；后端每次写审计。
- **水印**（不可关闭）：平铺文本「姓名（账号）· 医院名称 · 预览时间（精确到分钟）」，例如「王医生（示例）（demo_doctor）· 医院 A（示例租户）· 2026-10-31 14:05」；canvas 生成背景图覆盖在预览层之上，`pointer-events: none`，旋转 -20°，透明度约 0.15（推断，待确认）。
- 防篡改：`MutationObserver` 观察水印节点及其父节点的 `childList`、`attributes`（style / class），被删或改时 **立即重绘**；同时每 2 秒自检一次（推断，待确认）。
- 下载：点「下载」→ `POST …/files/{fileId}/download-url` 拿 5 分钟签名链接 → `window.location` 打开；后端写审计。
- 手机 / 平板：全屏，双指缩放（图片），工具栏图标 ≥ 40px。
- 测试：删除水印节点后被重绘；修改水印 style 后恢复；无下载权限不显示下载；XLSX 无预览。

---

## 13. 其他小组件

| 组件 | 用途 | 要点 |
|---|---|---|
| `MobileBlocker` | 非响应式页面在手机（< 768）打开时整页替换 | `a-result status="info"` 标题「请在电脑或平板上操作」，按钮「返回首页」；路由 meta `responsive: false`（默认）时布局层渲染它 |
| `FormStatusBar` | 随访表单顶部状态：自动保存状态 + 横幅 | 见 pages.md §5；横幅类型：补录 / 逾期 / 窗口外 / 计划外 / 修改中 v2 / 基线已锁定 |
| `ConflictBanner` | 409 冲突处理 | 显示更新人、更新时间、不同的字段；按钮 `保留我的并覆盖` / `载入服务器版本` |
| `ContactLogModal` | 记录沟通 | 520 宽；字段：方式 / 结果 / 约定下次联系时间 / 备注 / MP3 录音（可选）；结果 = 未接通时提示「系统次日自动生成跟进」 |
| `AudioPlayer` | 录音播放 | 原生 `audio`，`controlsList="nodownload"`，播放器里**不放下载按钮**（下载走 `followup:scan:download`） |
| `ReasonModal` | 需要填原因的操作（删除、解锁基线、修改已提交、停止随访、转病区） | 520；原因必填，placeholder「必填，如：抄录错误」 |
| `StatCard` | 首页统计卡 | `a-statistic` 24–28/600 + 右上线性图标 + 「较昨日 +n / −n」12px |
| `EmptyBlock` | 带下一步的空状态 | 文案「暂无随访任务 · 去新建计划」，按钮按权限显示 |
| `TenantViewBanner` | 平台进入租户视角时的顶部常驻提示条 + 一键退出（PLT-VIEW-003） | 橙色 `a-alert banner`，显示剩余时长；不可关闭 |
| `NoticeBell` | 顶栏站内信铃铛（A-NTF-002 / C-NTF-002，E09-T02；**推断，待确认**——线框未画顶栏通知位） | `a-popover`（360 宽，最近 20 条）+ `a-badge` 未读角标（60s 轮询 + 页面可见时刷新，`tokens.timings.notifyPollMs`）；条目：标题（未读加粗）/ 摘要两行截断 / 脱敏姓名 `a-tag` / 时间（当天 HH:mm，跨天 MM-DD HH:mm）；点击 = 未读先标已读再按 target 跳转（TASK→`/followup/task?taskId=`、PATIENT→`/followup/patient?patientId=`、RECORD→`/followup/record/{id}`，路由未开放提示「该消息对应的页面尚未开放」）；「全部已读」写回并提示条数；空态「暂无站内信」 |

---

## 14. 组件测试要求

- 每个组件一个 `*.spec.ts`（Vitest + @vue/test-utils），放在组件旁边或 `__tests__/`；断点相关用 `matchMedia` mock，计时用 fake timers。
- 测试数据只能用虚构示例（`张*明（示例）`、`DEMO-A-0001` 等，见 design-system §17），**禁止**使用任何真实姓名、电话、证件号、病案号。
- 每个测试文件头注释写覆盖的需求编号，如 `// A-PAT-010, C-PAT-012`。
