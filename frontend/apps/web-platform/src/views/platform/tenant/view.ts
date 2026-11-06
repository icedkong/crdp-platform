/**
 * 租户视角进入弹窗（E20-T01 / PLT-VIEW-001、004 · pages.md §11「进入租户视角」）的纯逻辑：
 * 表单模型、校验（目的 + 说明，与后端同口径）、剩余时长倒计时。
 * 校验与文案复用 @crdp/api 的 tenant-view（前后端同口径）。
 */
import {
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewEntryUrl,
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

// ---------------------------------------------------------------- E20-T04 / PLT-VIEW-002：打开医院端

/**
 * 打开医院端视角页：现场签发 60 秒一次性凭据，拼 hospitalUrl + /view/entry#ticket=…
 * 新标签页打开（凭据只放 URL 片段，不进服务端日志；视角令牌只在医院端换取响应出现一次）。
 * open 参数可注入（单测），默认 window.open。
 */
export async function openHospitalView(
  ticket: { hospitalUrl: string; ticket: string },
  open: (url: string) => unknown = (url) => window.open(url, '_blank'),
): Promise<string> {
  const url = tenantViewEntryUrl(ticket.hospitalUrl, ticket.ticket);
  open(url);
  return url;
}
