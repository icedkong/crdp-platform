/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录纯逻辑（补录弹窗与患者详情页共用）；E21-T14 /
 * C-FU-014 建议随访时间点提示。
 *
 * - 补录随访日期必须是过去的日期（「为过去年度补录随访记录」；当天录入走任务 /
 *   工作台）——校验双保险：前端禁选今天及以后，提交前再验一次（后端 40001 兜底）；
 * - 有植入记录时随访日期不得早于最早植入日期（C 规格 §5「不早于植入日期、不晚于今天」；
 *   下界来自建议接口的 earliestImplantDate，后端 40001 兜底）；
 * - 默认日期 = 一年前的同日（常见年度补录场景的合理起点，可改）；
 * - 建议随访时间点提示行（C-FU-014）：按随访日期距植入天数建议（如 25–45 天「术后1个月」）、
 *   器械接近 ERI 优先「ERI期随访」，可改选；空档区间提示自选；无器械（A / B）整行省略；
 * - 创建成功后统一用 recordId 新标签页打开随访表单（pages.md §5）。
 */
import type {
  PatientTimelineEntry,
  RecordTimepointSuggestionView,
} from '@crdp/api';

import dayjs from 'dayjs';

/** 补录弹窗默认日期：一年前的同日（A 医院年度随访的常见补录场景）。 */
export function defaultBackfillDate(today = new Date()): string {
  return dayjs(today).subtract(1, 'year').format('YYYY-MM-DD');
}

/** 是否可选：今天及以后的日期不可选（补录只收过去的日期）；早于最早植入日期不可选（C-FU-014 下界）。 */
export function isBackfillDateDisabled(
  date: dayjs.ConfigType,
  today = new Date(),
  earliestImplantDate?: null | string,
): boolean {
  const picked = dayjs(date);
  if (!picked.isBefore(dayjs(today).startOf('day'), 'day')) {
    return true;
  }
  if (
    earliestImplantDate &&
    picked.isBefore(dayjs(earliestImplantDate), 'day')
  ) {
    return true;
  }
  return false;
}

/** 提交前校验：空值 / 非过去日期 / 早于最早植入日期返回错误文案（与后端 40001 口径一致）。 */
export function backfillDateError(
  visitDate: null | string,
  today = new Date(),
  earliestImplantDate?: null | string,
): string {
  if (!visitDate) return '请填写实际随访日期';
  if (isBackfillDateDisabled(visitDate, today)) {
    return '补录随访日期必须是过去的日期';
  }
  if (
    earliestImplantDate &&
    dayjs(visitDate).isBefore(dayjs(earliestImplantDate), 'day')
  ) {
    return `补录随访日期不能早于植入日期（${earliestImplantDate}）`;
  }
  return '';
}

// ------------------------------------------------------- E21-T14 / C-FU-014 建议时间点提示

/** 建议时间点行：有建议「建议随访时间点：术后1个月（可改选）」；ERI 优先；空档提示自选；无器械省略（''）。 */
export function timepointSuggestionText(
  view: null | RecordTimepointSuggestionView,
): string {
  if (!view?.device) return '';
  if (view.suggestedLabel) {
    return view.eriFollowup
      ? `建议随访时间点：${view.suggestedLabel}（器械接近 ERI，可改选）`
      : `建议随访时间点：${view.suggestedLabel}（可改选）`;
  }
  return '该日期在建议区间空档，请自选随访时间点';
}

/** 当时器械行：「当时器械 双腔起搏器(DDD)（2024-06-01 植入）」；接近 ERI 追加「· 接近 ERI」；无器械省略（''）。 */
export function deviceContextText(
  view: null | RecordTimepointSuggestionView,
): string {
  const device = view?.device;
  if (!device) return '';
  const label = device.typeLabel ?? device.type;
  const parts = [`当时器械 ${label}（${device.implantDate} 植入）`];
  if (view?.eriFollowup) parts.push('接近 ERI');
  return parts.join(' · ');
}

/**
 * 转录预填日期（E21-T17 / C-SCAN-012「没有时新建补录记录」）：preset（报告单的报告时间）
 * 是合法的过去日期时用它，否则（空 / 当天及以后 / 解析失败）回落默认补录日期。
 */
export function resolvePresetVisitDate(
  preset: null | string,
  today = new Date(),
): string {
  const sliced = preset ? preset.slice(0, 10) : '';
  const parsed = dayjs(sliced);
  if (sliced && parsed.isValid() && !backfillDateError(sliced, today)) {
    return sliced;
  }
  return defaultBackfillDate(today);
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
