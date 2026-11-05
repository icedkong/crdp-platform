/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录纯逻辑（补录弹窗与患者详情页共用）。
 *
 * - 补录随访日期必须是过去的日期（「为过去年度补录随访记录」；当天录入走任务 /
 *   工作台）——校验双保险：前端禁选今天及以后，提交前再验一次（后端 40001 兜底）；
 * - 默认日期 = 一年前的同日（常见年度补录场景的合理起点，可改）；
 * - 创建成功后统一用 recordId 新标签页打开随访表单（pages.md §5）。
 */
import type { PatientTimelineEntry } from '@crdp/api';

import dayjs from 'dayjs';

/** 补录弹窗默认日期：一年前的同日（A 医院年度随访的常见补录场景）。 */
export function defaultBackfillDate(today = new Date()): string {
  return dayjs(today).subtract(1, 'year').format('YYYY-MM-DD');
}

/** 是否可选：今天及以后的日期不可选（补录只收过去的日期）。 */
export function isBackfillDateDisabled(
  date: dayjs.ConfigType,
  today = new Date(),
): boolean {
  return !dayjs(date).isBefore(dayjs(today).startOf('day'), 'day');
}

/** 提交前校验：空值或非过去日期返回错误文案（与后端 40001 口径一致）。 */
export function backfillDateError(
  visitDate: null | string,
  today = new Date(),
): string {
  if (!visitDate) return '请填写实际随访日期';
  if (isBackfillDateDisabled(visitDate, today)) {
    return '补录随访日期必须是过去的日期';
  }
  return '';
}

/**
 * 时间轴节点打开路由（components.md §9 open 事件）：已关联记录直达表单页；
 * 未开始的任务（PENDING 等）先经 /tasks/{id}/start 换 recordId（detail 页处理，
 * 本函数只给出路由形态）；计划中（canOpen=false）返回 null。
 */
export function timelineOpenRoute(entry: PatientTimelineEntry): null | string {
  if (entry.canOpen !== true) return null;
  if (entry.recordId) return `/followup/record/${entry.recordId}`;
  return null;
}

/** 节点打开是否需要先 start（无 recordId 的任务节点）。 */
export function timelineOpenNeedsStart(entry: PatientTimelineEntry): boolean {
  return entry.canOpen === true && !entry.recordId && !!entry.taskId;
}
