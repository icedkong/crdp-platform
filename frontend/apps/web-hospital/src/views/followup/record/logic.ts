/**
 * E15-T02 / A-FU-005：随访工作台（pages.md §5）的纯逻辑。
 *
 * - 横幅文案照抄 pages.md §5.1：逾期（窗口已结束仍提交记「逾期完成」）、窗口外
 *   （随访日期不在窗口内）；
 * - 暂存差异：changes 只带与上次服务器版本相比有变化的键（E25-T01 契约）；
 * - 提交成功文案：常规「已提交；按计划生成下次任务」、死亡「已提交；随访计划终止，不再创建后续任务」；
 * - 「是否死亡」按模板字段编码 death 定位稳定键（与后端 RecordEntryService 同口径，不硬编码键名）；
 * - 下次随访只读行「下次随访 · 计划 …（按计划自动生成）」（fu_enrollment.next_plan_date 缓存）。
 */
import type {
  FormSchema,
  RecordDeviceContext,
  RecordTaskContext,
} from '@crdp/api';

import { addDateUnit } from '@crdp/rules';

/** 顶部横幅（pages.md §5.1，文案照抄）。 */
export interface WorkbenchBanner {
  key:
    | 'backfill'
    | 'outside'
    | 'overdue'
    | 'readonly'
    | 'remote'
    | 'submitted'
    | 'unplanned';
  text: string;
  tone: 'error' | 'info' | 'warning';
}

/** 今天（可注入，测试用）：比较窗口与随访日期只取日期部分（北京时间）。 */
type DateLike = () => string;

/** 横幅集合：逾期（未完成且窗口已过）、窗口外（随访日期落在窗口外）、计划外（E15-T06 / C-FU-022）、远程监测（E21-T12 / C-FU-025）。 */
export function bannersOf(
  context: null | RecordTaskContext,
  visitDate: null | string,
  today: DateLike = () => new Date().toISOString().slice(0, 10),
  source: null | string = null,
): WorkbenchBanner[] {
  const banners: WorkbenchBanner[] = [];
  if (!context && source === 'UNPLANNED') {
    banners.push({
      key: 'unplanned',
      text: '计划外随访：随访时间点为“其他”，不影响后续节点。',
      tone: 'info',
    });
    return banners;
  }
  if (!context && source === 'REMOTE') {
    banners.push({
      key: 'remote',
      text: '远程监测记录：随访方式为“远程监测”，同一表单人工录入（首期不对接厂商远程监测平台）。',
      tone: 'info',
    });
    return banners;
  }
  if (!context) return banners;
  const open = ['PENDING', 'IN_PROGRESS', 'OVERDUE', 'SKIPPED_SUSPENDED'];
  if (open.includes(context.taskStatus) && context.windowEnd < today()) {
    banners.push({
      key: 'overdue',
      text: `窗口已于 ${context.windowEnd} 结束：现在提交记为“逾期完成”。`,
      tone: 'error',
    });
  }
  if (
    visitDate &&
    (visitDate < context.windowStart || visitDate > context.windowEnd)
  ) {
    banners.push({
      key: 'outside',
      text: `随访日期不在窗口 ${context.windowStart} ~ ${context.windowEnd} 内：提交后标记为窗口外（逾期完成）。`,
      tone: 'warning',
    });
  }
  if (context.createType === 'BACKFILL') {
    banners.push({
      key: 'backfill',
      text: '补录任务：入组前窗口已过，不受逾期限制；提交后记为「已完成（补录）」。',
      tone: 'info',
    });
  }
  return banners;
}

/** 只读 / 已提交横幅（无编辑权「只读角色：不能录入」；已提交整体只读）。 */
export function readonlyBanner(status: 'DRAFT' | 'SUBMITTED'): WorkbenchBanner {
  return status === 'SUBMITTED'
    ? {
        key: 'submitted',
        text: '该记录已提交，整体只读；修改需走已提交记录修改流程。',
        tone: 'info',
      }
    : { key: 'readonly', text: '只读角色：不能录入', tone: 'info' };
}

/** 暂存差异（E25-T01：changes 只带变更字段，null 值 = 清空该键）。 */
export function diffChanges(
  base: Record<string, unknown>,
  current: Record<string, unknown>,
): Record<string, unknown> {
  const changes: Record<string, unknown> = {};
  for (const key of new Set([...Object.keys(base), ...Object.keys(current)])) {
    const before = base[key];
    const after = current[key];
    if (!Object.is(before, after)) {
      changes[key] = after === undefined ? null : after;
    }
  }
  return changes;
}

/** 是否有未保存修改（上一条 / 下一条与离开拦截用）。 */
export function hasChanges(
  base: Record<string, unknown>,
  current: Record<string, unknown>,
): boolean {
  return Object.keys(diffChanges(base, current)).length > 0;
}

/** 「是否死亡」字段的稳定键（模板字段编码 death 定位，与后端同口径）。 */
export function deathFieldKey(schema: FormSchema | null): null | string {
  if (!schema) return null;
  for (const category of schema.categories) {
    for (const field of category.fields) {
      if (field.code === 'death') {
        return field.key;
      }
    }
  }
  return null;
}

/** 无任务记录的来源名称（补录 / 计划外随访 / 远程监测，E21-T12 起）。 */
function tasklessSourceLabel(source: null | string): string {
  if (source === 'UNPLANNED') return '计划外随访';
  if (source === 'REMOTE') return '远程监测';
  return '补录';
}

/** 无任务记录的来源短名（顶栏摘要用）。 */
function tasklessSourceShortLabel(source: null | string): string {
  if (source === 'UNPLANNED') return '计划外';
  if (source === 'REMOTE') return '远程监测';
  return '补录';
}

/** 提交成功文案（pages.md §5.1 提交确认：常规 / 死亡・失访两态；补录 / 计划外记录无任务联动）。 */
export function submitSuccessMessage(
  deathSelected: boolean,
  hasTask = true,
  source: null | string = null,
): string {
  if (!hasTask) {
    const kind = tasklessSourceLabel(source);
    return deathSelected
      ? `已提交${kind}记录；随访计划终止，不再创建后续任务`
      : `已提交${kind}记录`;
  }
  return deathSelected
    ? '已提交；随访计划终止，不再创建后续任务'
    : '已提交；按计划生成下次任务';
}

/**
 * 转录态提交成功追加文案（E21-T17 / C-SCAN-012「保存后变为已电子化」）：
 * 报告单已自动置「已电子化」；非转录态（transcribedReportId 为空）原样返回。
 */
export function transcribeAppendedText(
  transcribedReportId: null | string | undefined,
): string {
  return transcribedReportId ? '；报告单已标记为「已电子化」' : '';
}

/** 提交确认弹窗文案（补录 / 计划外记录不联动任务完成）。 */
export function submitConfirmText(
  hasTask: boolean,
  source: null | string = null,
): string {
  if (hasTask) return '提交后任务完成，按计划生成下次随访任务。';
  if (source === 'UNPLANNED') {
    return '提交后计划外随访记录生效（不影响后续节点，C-FU-022）。';
  }
  if (source === 'REMOTE') {
    return '提交后远程监测记录生效（同一表单人工录入，C-FU-025）。';
  }
  return '提交后补录记录生效（不占用年度任务，A-FU-014）。';
}

/** 必填缺失提示（照抄：「还有 n 个必填项未填写，已在左侧导航标出」）。 */
export function missingRequiredText(count: number): string {
  return `还有 ${count} 个必填项未填写，已在左侧导航标出`;
}

/** 下次随访只读行（fu_enrollment.next_plan_date；A：只读，按计划自动生成）。 */
export function nextVisitText(context: null | RecordTaskContext): string {
  if (!context?.nextPlanDate) return '';
  return `下次随访 · 计划 ${context.nextPlanDate}（按计划自动生成）`;
}

// ------------------------------------------------------- E21-T08 / C-FU-005 医生指定下次随访时间

/** 「下次随访时间」稳定键（模板字段编码 next_visit_date 定位；A / B 模板无该字段 → null）。 */
export function nextVisitFieldKey(schema: FormSchema | null): null | string {
  if (!schema) return null;
  for (const category of schema.categories) {
    for (const field of category.fields) {
      if (field.code === 'next_visit_date') {
        return field.key;
      }
    }
  }
  return null;
}

/**
 * 提交校验（pages.md §5.1 照抄）：「下次随访时间不能早于或等于本次随访日期」「不能早于今天」；
 * 返回文案（空 = 通过）。后端登记侧对早于今天的值忽略（OQ-138③ 暂行），前端照设计文档拦截；
 * 补录的历史记录照填（提示「只保存」，见 nextVisitNote）。
 */
export function nextVisitIssueText(
  value: unknown,
  visitDate: null | string,
  today: () => string = () => new Date().toISOString().slice(0, 10),
): string {
  if (typeof value !== 'string' || !value) return '';
  if (visitDate && value <= visitDate) {
    return '下次随访时间不能早于或等于本次随访日期';
  }
  if (value < today()) return '下次随访时间不能早于今天';
  return '';
}

/** 两个 ISO 日期的天数差（b − a；解析失败 null）。 */
function daysBetween(a: string, b: string): null | number {
  const from = new Date(`${a}T00:00:00Z`).getTime();
  const to = new Date(`${b}T00:00:00Z`).getTime();
  if (Number.isNaN(from) || Number.isNaN(to)) return null;
  return Math.round((to - from) / 86_400_000);
}

/**
 * 窗口预览（pages.md §5.1「= 下次计划日期；窗口 …」）：按<b>当前任务节点</b>的窗口宽度
 * （plan_date 与 window_start / window_end 的前后天数）围绕医生指定日期展开——滚动循环节点
 * （C 常规 / ERI）的下一次同节点同宽度；跨到不同节点的次数宽度随实际排期（后端调度为准）。
 * 无任务上下文（补录 / 计划外）或未填返回 null。
 */
export function nextVisitWindowPreviewOf(
  value: unknown,
  context: null | RecordTaskContext,
): null | { end: string; start: string } {
  if (typeof value !== 'string' || !value || !context) return null;
  const before = daysBetween(context.windowStart, context.planDate);
  const after = daysBetween(context.planDate, context.windowEnd);
  if (before === null || after === null) return null;
  return {
    start: addDateUnit(value, -before, 'DAY'),
    end: addDateUnit(value, after, 'DAY'),
  };
}

/** 字段旁补充提示（pages.md §5.1）：补录「（补录：只保存）」；常规 / 计划外为空。 */
export function nextVisitNote(source: null | string): null | string {
  return source === 'BACKFILL' ? '补录：只保存' : null;
}

/**
 * 渲染器条件上下文（E21-T03 / C-FU-009）：记录详情下发的器械上下文包成
 * `{ device }`（条件 DSL 按 ctx 点路径 device.type / device.hasRaLead / device.chambers
 * 取值，components.md §10.2）；A / B 租户（implant 开关关闭）与无匹配器械为 null——
 * ctx 断链 → 条件不成立 → 字段隐藏，与后端提交清空同一上下文。
 */
export function rendererContextOf(
  device: null | RecordDeviceContext,
): null | Record<string, unknown> {
  return device ? { device } : null;
}

/** 顶栏标题：随访录入 · 患者（脱敏）；补录 / 计划外记录（无任务上下文）显示来源。 */
export function headerTitle(
  context: null | RecordTaskContext,
  patient?: null | { patientName: string },
  source: null | string = null,
): string {
  if (!context && patient && source === 'UNPLANNED') {
    return `计划外随访 · ${patient.patientName}`;
  }
  if (!context && patient && source === 'REMOTE') {
    return `远程监测 · ${patient.patientName}`;
  }
  if (!context && patient) return `历史补录 · ${patient.patientName}`;
  if (!context) return '随访录入';
  return `随访录入 · ${context.patientName}`;
}

/** 顶栏摘要：节点 · 计划日期 · 窗口；补录 / 计划外记录显示实际随访日期。 */
export function headerSummary(
  context: null | RecordTaskContext,
  visitDate?: null | string,
  source: null | string = null,
): string {
  if (!context) {
    if (!visitDate) return '';
    return `${tasklessSourceShortLabel(source)} · 实际随访日期 ${visitDate}`;
  }
  return `${context.nodeName ?? ''} · 计划 ${context.planDate} · 窗口 ${context.windowStart} ~ ${context.windowEnd}`;
}

/**
 * 本地草稿键（E25-T02 / NFR-SAVE-002，design-system §13）：
 * `${tenantId}:${userId}:${kind}:${id}` —— 键含租户、用户、记录 ID，跨租户 / 跨用户
 * 互不可见；租户标识取会话 tenantCode（医院端登录会话即租户内用户）。
 */
export function draftRecordKey(
  tenantCode: null | string | undefined,
  userId: null | number | string | undefined,
  recordId: string,
): string {
  return `${tenantCode ?? ''}:${userId ?? ''}:followup:${recordId}`;
}

// ------------------------------------------------------- E21-T11 / C-FU-008、C-FU-024、C-FU-026

/**
 * 修改已提交记录的按钮权限（pages.md §4.5 / §5、C-FU-026 / Q17）：开关
 * `record_edit_submitted` 开启（医院 C）→ 必须 `followup:record:edit-submitted`
 * （未分配也置灰显示，tooltip「需要“修改已提交记录”权限」）；关闭（A / B）→
 * `followup:record:edit` 即可。与后端 RecordEntryService 的闸门同口径。
 */
export function canEditSubmittedRecord(input: {
  editSubmittedFeatureOn: boolean;
  hasEdit: boolean;
  hasEditSubmitted: boolean;
}): boolean {
  return input.editSubmittedFeatureOn ? input.hasEditSubmitted : input.hasEdit;
}

/** 重复记录提示文案（C-FU-024，pages.md §5.1「推断，待确认」照抄）。 */
export function duplicateRecordConfirmText(visitDate: string): string {
  return `该患者在 ${visitDate} 已有一条随访记录，确定继续？`;
}

/** 植入时长文案（C 头幅「植入时长 1 年 5 个月」；不足 1 月只显示天数）。 */
export function implantDurationText(
  implantDate: null | string | undefined,
  today: () => string = () => new Date().toISOString().slice(0, 10),
): string {
  if (!implantDate) return '';
  const from = new Date(`${implantDate}T00:00:00Z`);
  const to = new Date(`${today()}T00:00:00Z`);
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) return '';
  let months =
    (to.getUTCFullYear() - from.getUTCFullYear()) * 12 +
    (to.getUTCMonth() - from.getUTCMonth());
  if (to.getUTCDate() < from.getUTCDate()) months -= 1;
  if (months < 0) return '';
  const years = Math.floor(months / 12);
  const rest = months % 12;
  if (years <= 0 && rest <= 0) return '不足 1 个月';
  const parts: string[] = [];
  if (years > 0) parts.push(`${years} 年`);
  if (rest > 0) parts.push(`${rest} 个月`);
  return parts.join(' ');
}

/**
 * C 录入头幅自动带入行（C-FU-008）：「随访编号 105-2026-001 · 第 3 次 · 当前器械
 * 双腔(DDD) · 植入时长 1 年 5 个月」——各段缺省（A / B 租户、未生成编号）整段省略，
 * 全空返回 ''（不渲染该行）。
 */
export function entrySummaryText(input: {
  device?: null | RecordDeviceContext;
  sequenceNo?: null | number;
  today?: () => string;
  visitNo?: null | string;
}): string {
  const parts: string[] = [];
  if (input.visitNo) parts.push(`随访编号 ${input.visitNo}`);
  if (input.sequenceNo && input.sequenceNo > 0) {
    parts.push(`第 ${input.sequenceNo} 次`);
  }
  if (input.device?.typeLabel) {
    const duration = implantDurationText(input.device.implantDate, input.today);
    parts.push(
      duration
        ? `当前器械 ${input.device.typeLabel} · 植入时长 ${duration}`
        : `当前器械 ${input.device.typeLabel}`,
    );
  }
  return parts.join(' · ');
}

/**
 * 录入缺省写入（C-FU-008「随访医生默认当前用户」；E21-T12 起随访方式缺省）：
 * 模板含编码 `visit_doctor` / `visit_method` 的字段（C 随访 #5 / #6）且当前无值时
 * 写入缺省值——随访医生 = 当前用户；随访方式 = REMOTE 记录「远程监测」、其余取患者
 * 默认随访方式（后端 entryDefaults.visitMethod 下发，A / B 模板无此字段即无操作）；
 * 仅在为空时写入，录入中可改选。返回写入后的 data（不修改原对象）。
 */
export function applyEntryDefaults(
  schema: FormSchema | null,
  data: Record<string, unknown>,
  defaults?: null | {
    doctorId: string;
    doctorName: string;
    visitMethod?: null | number;
  },
): Record<string, unknown> {
  const visitMethod = defaults?.visitMethod ?? null;
  if (!schema || (!defaults?.doctorId && visitMethod === null)) {
    return data;
  }
  const next = { ...data };
  for (const category of schema.categories) {
    for (const field of category.fields) {
      const empty =
        next[field.key] === undefined ||
        next[field.key] === null ||
        next[field.key] === '';
      if (field.code === 'visit_doctor' && empty && defaults?.doctorId) {
        next[field.key] = defaults.doctorId;
      }
      if (field.code === 'visit_method' && empty && visitMethod !== null) {
        next[field.key] = visitMethod;
      }
    }
  }
  return next;
}

// ------------------------------------------------------- E21-T14 / C-FU-015 连续补录

/**
 * 提交成功面板是否提供「继续补录下一次」（pages.md §5.1 提交确认行「补录（C-FU-015）
 * 提供继续补录下一次」）：补录记录（source=BACKFILL）＋有录入权限＋能定位患者
 * （taskless 记录的患者摘要）＋implant 开关开启（医院 C；A / B 的补录流无器械与
 * 时间点建议，不提供连续补录，保持 E15-T07 原貌）。
 */
export function canContinueBackfill(input: {
  canEdit: boolean;
  implantEnabled: boolean;
  patientId?: null | string;
  source?: null | string;
}): boolean {
  return (
    input.source === 'BACKFILL' &&
    input.canEdit &&
    Boolean(input.patientId) &&
    input.implantEnabled
  );
}
