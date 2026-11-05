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
import type { FormSchema, RecordTaskContext } from '@crdp/api';

/** 顶部横幅（pages.md §5.1，文案照抄）。 */
export interface WorkbenchBanner {
  key: 'backfill' | 'outside' | 'overdue' | 'readonly' | 'submitted';
  text: string;
  tone: 'error' | 'info' | 'warning';
}

/** 今天（可注入，测试用）：比较窗口与随访日期只取日期部分（北京时间）。 */
type DateLike = () => string;

/** 横幅集合：逾期（未完成且窗口已过）、窗口外（随访日期落在窗口外）。 */
export function bannersOf(
  context: null | RecordTaskContext,
  visitDate: null | string,
  today: DateLike = () => new Date().toISOString().slice(0, 10),
): WorkbenchBanner[] {
  const banners: WorkbenchBanner[] = [];
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

/** 提交成功文案（pages.md §5.1 提交确认：常规 / 死亡・失访两态；补录记录无任务联动）。 */
export function submitSuccessMessage(
  deathSelected: boolean,
  hasTask = true,
): string {
  if (!hasTask) {
    return deathSelected
      ? '已提交补录记录；随访计划终止，不再创建后续任务'
      : '已提交补录记录';
  }
  return deathSelected
    ? '已提交；随访计划终止，不再创建后续任务'
    : '已提交；按计划生成下次任务';
}

/** 提交确认弹窗文案（补录记录不联动任务完成）。 */
export function submitConfirmText(hasTask: boolean): string {
  return hasTask
    ? '提交后任务完成，按计划生成下次随访任务。'
    : '提交后补录记录生效（不占用年度任务，A-FU-014）。';
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

/** 顶栏标题：随访录入 · 患者（脱敏）；补录记录（无任务上下文）显示「历史补录」。 */
export function headerTitle(
  context: null | RecordTaskContext,
  patient?: null | { patientName: string },
): string {
  if (!context && patient) return `历史补录 · ${patient.patientName}`;
  if (!context) return '随访录入';
  return `随访录入 · ${context.patientName}`;
}

/** 顶栏摘要：节点 · 计划日期 · 窗口；补录记录显示实际随访日期。 */
export function headerSummary(
  context: null | RecordTaskContext,
  visitDate?: null | string,
): string {
  if (!context) return visitDate ? `补录 · 实际随访日期 ${visitDate}` : '';
  return `${context.nodeName ?? ''} · 计划 ${context.planDate} · 窗口 ${context.windowStart} ~ ${context.windowEnd}`;
}
