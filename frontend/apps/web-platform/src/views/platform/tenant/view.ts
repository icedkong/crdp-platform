/**
 * 租户视角进入弹窗（E20-T01 / PLT-VIEW-001、004 · pages.md §11「进入租户视角」）的纯逻辑：
 * 表单模型、校验（目的 + 说明，与后端同口径）、剩余时长倒计时。
 * 校验与文案复用 @crdp/api 的 tenant-view（前后端同口径）。
 */
import {
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewPurposeText,
  tenantViewRemainingText,
  validateTenantViewEnter,
} from '@crdp/api';

export {
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewPurposeText,
  tenantViewRemainingText,
  validateTenantViewEnter,
};

/** 进入表单模型（无进行中会话时展示）。 */
export interface EnterViewModel {
  note: string;
  purpose: null | string;
}

export function toEnterViewModel(): EnterViewModel {
  return { note: '', purpose: null };
}

/** 保存校验（前端先行；返回错误信息，空数组 = 通过）。 */
export function validateEnterModel(model: EnterViewModel): string[] {
  return validateTenantViewEnter(model);
}

/** 倒计时每秒递减（到 0 停住，由调用方刷新实际状态）。 */
export function tickRemaining(remainingSeconds: number): number {
  return Math.max(0, remainingSeconds - 1);
}

/** 时间列展示：ISO 精确到分钟。 */
export function viewTimeText(value: null | string): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}
