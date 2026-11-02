import type { NotifySettingsUpdate } from '@crdp/api';

/**
 * E09-T07 / A-NTF-005、A-NTF-007 · 发送设置页签逻辑（纯函数）。
 * 本地预校验与后端 NotifySettingsService 同口径（后端为准）。
 */

/** HH:mm 口径（与后端一致）。 */
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/** 每人每日短信上限范围（0 会关掉全部短信，A-NTF-005 是频控不是停发）。 */
export const SMS_DAILY_LIMIT_MIN = 1;

export const SMS_DAILY_LIMIT_MAX = 20;

/** 表单模型（TimePicker value-format HH:mm 字符串）。 */
export interface SettingsFormModel {
  dndEnd: string;
  dndStart: string;
  overdueSmsOffAllowed: boolean;
  smsDailyLimit: null | number;
}

/** 校验：HH:mm、起止不同、上限范围；返回错误清单（空 = 可提交）。 */
export function validateSettingsModel(model: SettingsFormModel): string[] {
  const errors: string[] = [];
  if (!TIME_RE.test(model.dndStart) || !TIME_RE.test(model.dndEnd)) {
    errors.push('免打扰时段须为 HH:mm（如 21:00）');
  }
  if (
    TIME_RE.test(model.dndStart) &&
    TIME_RE.test(model.dndEnd) &&
    model.dndStart === model.dndEnd
  ) {
    errors.push('免打扰起止时间不能相同');
  }
  if (
    model.smsDailyLimit === null ||
    !Number.isInteger(model.smsDailyLimit) ||
    model.smsDailyLimit < SMS_DAILY_LIMIT_MIN ||
    model.smsDailyLimit > SMS_DAILY_LIMIT_MAX
  ) {
    errors.push(
      `每人每日短信上限须为 ${SMS_DAILY_LIMIT_MIN}–${SMS_DAILY_LIMIT_MAX} 的整数`,
    );
  }
  return errors;
}

/** 表单 → PUT 请求体（校验通过后调用）。 */
export function toSettingsUpdate(
  model: SettingsFormModel,
): NotifySettingsUpdate {
  return {
    dndEnd: model.dndEnd,
    dndStart: model.dndStart,
    overdueSmsOffAllowed: model.overdueSmsOffAllowed,
    smsDailyLimit: model.smsDailyLimit as number,
  };
}

/** 免打扰时段展示文案（跨零点也直接显示起止）。 */
export function dndRangeText(start: string, end: string): string {
  return `${start} 至次日 ${end}`;
}
