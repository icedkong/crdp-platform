import type { TaskType } from '@crdp/api';

/**
 * E15-T06 / A-FU-013、A-FU-026 · 临时添加任务与计划外随访纯逻辑（弹窗与页面共用）。
 *
 * - 临时任务：计划日期必填；窗口宽度 0–365（默认 7，A 医院预置窗口前后 7 天）；
 *   任务类型 FORM 填表随访 / REMINDER 站内提醒（A-FU-026「到点只提醒责任人、无需填表」）；
 * - 计划外随访（C-FU-022）：随访日期必填且不能晚于今天（症状就诊、程控调整是「现在
 *   发生的随访」，当天可录；与补录「必须早于今天」区分）；
 * - 校验双保险：前端先提示，后端 40001 兜底（AGENTS §6）。
 */
import {
  TEMP_TASK_DEFAULT_WINDOW_DAYS,
  TEMP_TASK_MAX_WINDOW_DAYS,
} from '@crdp/api';
import dayjs from 'dayjs';

/** 临时任务表单状态。 */
export interface TempTaskFormState {
  note: string;
  patientId: null | string;
  planDate: null | string;
  taskType: TaskType;
  windowDays: number;
}

/** 临时任务弹窗初始状态（计划日期默认今天：医生加的就是现在要做的加访 / 提醒）。 */
export function defaultTempTaskState(
  patientId: null | string = null,
  today = new Date(),
): TempTaskFormState {
  return {
    patientId,
    planDate: dayjs(today).format('YYYY-MM-DD'),
    taskType: 'FORM',
    windowDays: TEMP_TASK_DEFAULT_WINDOW_DAYS,
    note: '',
  };
}

/** 窗口文案：`计划日期 ± N 天`（宽度 0 时「当天」）。 */
export function tempTaskWindowText(windowDays: number): string {
  return windowDays === 0 ? '计划日当天' : `计划日期 ± ${windowDays} 天`;
}

/** 提交前校验：返回错误文案（空串 = 可提交；与后端 40001 口径一致）。 */
export function tempTaskFormError(state: TempTaskFormState): string {
  if (!state.patientId) return '请选择患者';
  if (!state.planDate) return '请填写计划日期';
  if (
    !Number.isInteger(state.windowDays) ||
    state.windowDays < 0 ||
    state.windowDays > TEMP_TASK_MAX_WINDOW_DAYS
  ) {
    return `窗口宽度须在 0–${TEMP_TASK_MAX_WINDOW_DAYS} 天之间`;
  }
  if (state.note.trim().length > 255) return '备注不能超过 255 字';
  return '';
}

// ---------------------------------------------------------------- 计划外随访（C-FU-022）

/** 计划外随访默认日期：今天（症状就诊、程控调整等直接新建记录）。 */
export function defaultUnplannedDate(today = new Date()): string {
  return dayjs(today).format('YYYY-MM-DD');
}

/** 是否可选：未来日期不可选（未来随访由计划排期；当天可录——与补录区分）。 */
export function isUnplannedDateDisabled(
  date: dayjs.ConfigType,
  today = new Date(),
): boolean {
  return dayjs(date).isAfter(dayjs(today).endOf('day'), 'day');
}

/** 提交前校验：空值或未来日期返回错误文案（与后端 40001 口径一致）。 */
export function unplannedDateError(
  visitDate: null | string,
  today = new Date(),
): string {
  if (!visitDate) return '请填写随访日期';
  if (isUnplannedDateDisabled(visitDate, today)) {
    return '计划外随访日期不能晚于今天';
  }
  return '';
}
