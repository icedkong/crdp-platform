/**
 * 租户生命周期纯逻辑（E05-T02 / PLT-TEN-010、011、013）：状态操作的可用性与文案、
 * 到期设置表单 ⇄ 提交载荷、影响面展示。页面只做展示与编排。
 */
import type {
  CreateTenantRequest,
  TenantDetail,
  TenantExpireRequest,
  TenantStatus,
  TenantStatusAction,
  TenantStatusImpact,
} from '@crdp/api';

import { tenantStatusText } from '@crdp/api';

/** 到期设置表单模型（输入态，空串即未填；与详情页签、续期对话框、向导第 2 步共用）。 */
export interface ExpiryFormModel {
  /** 到期日 yyyy-MM-dd；FIXED 必填。 */
  expireAt: string;
  expireType: 'FIXED' | 'LONG_TERM';
  /** 宽限期天数（输入态，空串表示未填，用默认）。 */
  graceDays: string;
  graceReadOnly: boolean;
  /** 提醒节点输入态，逗号或空格分隔（如「30, 7, 1」）。 */
  remindDays: string;
}

export const EMPTY_EXPIRY_FORM: ExpiryFormModel = {
  expireAt: '',
  expireType: 'LONG_TERM',
  graceDays: '',
  graceReadOnly: false,
  remindDays: '',
};

/** 详情（或向导默认）→ 到期设置表单模型。 */
export function toExpiryFormModel(
  detail: Pick<
    TenantDetail,
    'expireAt' | 'expireType' | 'graceDays' | 'graceReadOnly' | 'remindDays'
  >,
): ExpiryFormModel {
  return {
    expireAt: detail.expireAt ?? '',
    expireType: detail.expireType,
    graceDays:
      detail.graceDays === null || detail.graceDays === undefined
        ? ''
        : String(detail.graceDays),
    graceReadOnly: detail.graceReadOnly ?? false,
    remindDays: (detail.remindDays ?? []).join(','),
  };
}

/** 提醒节点输入 → 列表（去重、降序）；空白返回空列表由校验兜底。 */
export function parseRemindInput(value: string): number[] {
  return [
    ...new Set(
      value
        .split(/[,，\s]+/)
        .map((part) => part.trim())
        .filter((part) => part !== '')
        .map(Number),
    ),
  ].toSorted((left, right) => right - left);
}

/** 前端只挡明显错误，最终校验在后端（40001）。 */
export function validateExpiryForm(
  form: ExpiryFormModel,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (form.expireType === 'FIXED') {
    if (!form.expireAt) {
      errors.expireAt = '有到期日时必填';
    } else if (!/^\d{4}-\d{2}-\d{2}$/.test(form.expireAt)) {
      errors.expireAt = '日期格式应为 YYYY-MM-DD';
    } else if (new Date(form.expireAt) <= new Date(todayText())) {
      errors.expireAt = '必须晚于今天';
    }
    const remind = parseRemindInput(form.remindDays);
    if (form.remindDays.trim() !== '' && remind.length === 0) {
      errors.remindDays = '须为 1 至 365 的天数';
    }
    if (remind.some((day) => !Number.isInteger(day) || day < 1 || day > 365)) {
      errors.remindDays = '须为 1 至 365 的天数';
    }
    if (form.graceDays.trim() !== '') {
      const grace = Number(form.graceDays);
      if (!Number.isInteger(grace) || grace < 1 || grace > 365) {
        errors.graceDays = '须为 1 至 365 的整数';
      }
    }
  }
  return errors;
}

function todayText(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** 表单 → 到期设置 / 续期提交（PLT-TEN-011、013）：未填项不传，由后端沿用现值或取默认。 */
export function toExpireRequest(
  form: ExpiryFormModel,
  reason: string,
): TenantExpireRequest {
  const request: TenantExpireRequest = {
    expireType: form.expireType,
    reason: reason.trim(),
  };
  if (form.expireType === 'FIXED') {
    request.expireAt = form.expireAt || null;
    if (form.remindDays.trim() !== '') {
      request.remindDays = parseRemindInput(form.remindDays);
    }
    if (form.graceDays.trim() !== '') {
      request.graceDays = Number(form.graceDays);
    }
    request.graceReadOnly = form.graceReadOnly;
  }
  return request;
}

/** 表单 → 向导提交的到期设置段（向导第 2 步）：长期有效只传类型；FIXED 未填项由后端取默认值。 */
export function toCreateExpiry(
  form: ExpiryFormModel,
): Pick<
  CreateTenantRequest,
  'expireAt' | 'expireType' | 'graceDays' | 'graceReadOnly' | 'remindDays'
> {
  if (form.expireType === 'LONG_TERM') {
    return { expireType: 'LONG_TERM' };
  }
  const payload: Pick<
    CreateTenantRequest,
    'expireAt' | 'expireType' | 'graceDays' | 'graceReadOnly' | 'remindDays'
  > = {
    expireAt: form.expireAt || null,
    expireType: 'FIXED',
  };
  if (form.remindDays.trim() !== '') {
    payload.remindDays = parseRemindInput(form.remindDays);
  }
  if (form.graceDays.trim() !== '') {
    payload.graceDays = Number(form.graceDays);
  }
  payload.graceReadOnly = form.graceReadOnly;
  return payload;
}

// ---------------------------------------------------------------- 状态操作（PLT-TEN-010、013）

/** 手动停用：仅启用态（状态图没有 试用/宽限期→停用 的人工边）。 */
export function canDisable(status: TenantStatus): boolean {
  return status === 'ENABLED';
}

/** 启用（转正 / 恢复）：试用或停用态。 */
export function canEnable(status: TenantStatus): boolean {
  return status === 'DISABLED' || status === 'TRIAL';
}

/** 续期：试用、启用、宽限期、停用都可改到期；宽限期 / 停用中续期立即恢复启用。 */
export function canRenew(status: TenantStatus): boolean {
  return (
    status === 'DISABLED' ||
    status === 'ENABLED' ||
    status === 'GRACE' ||
    status === 'TRIAL'
  );
}

/** 动词（对话框标题与确认按钮）：ENABLE 从试用是「转正」，从停用是「恢复启用」。 */
export function actionVerb(
  action: TenantStatusAction,
  status: TenantStatus,
): string {
  if (action === 'DISABLE') return '停用';
  return status === 'TRIAL' ? '转正' : '恢复启用';
}

/** 二次确认说明（展示操作后果，照状态图口径）。 */
export function actionConfirmText(
  action: TenantStatusAction,
  status: TenantStatus,
): string {
  if (action === 'DISABLE') {
    return '停用后该租户不可登录，数据保留；本应执行的随访任务将标记为「停用期间未执行」，恢复后由人工选择补做或关闭。';
  }
  return status === 'TRIAL'
    ? '转正后租户进入正式服务；到期设置保持不变，可在「到期设置」页签调整。'
    : '恢复启用后租户可正常登录；停用期间未执行的任务不自动补发，由人工处理。';
}

/** 续期确认说明（PLT-TEN-013：宽限期 / 停用中续期即恢复）。 */
export function renewalConfirmText(status: TenantStatus): string {
  if (status === 'GRACE' || status === 'DISABLED') {
    return '续期后租户立即恢复启用，可正常登录；未填的提醒节点与宽限期沿用当前配置。';
  }
  return '保存后生效；未填的提醒节点与宽限期沿用当前配置。';
}

// ---------------------------------------------------------------- 影响面（状态变更对话框）

export interface ImpactRows {
  online: string;
  pending: string;
}

/** 在线用户数、进行中任务数（fu_task 未上线时后端返回空，显示「—」）。 */
export function impactRows(impact: null | TenantStatusImpact): ImpactRows {
  return {
    online: impact ? `${impact.onlineUserCount} 人` : '—',
    pending:
      impact &&
      impact.pendingTaskCount !== null &&
      impact.pendingTaskCount !== undefined
        ? `${impact.pendingTaskCount} 条`
        : '—',
  };
}

/** 到期设置一行摘要（向导确认步展示用）：未填项用默认值，不在此展开。 */
export function expiryFormText(
  form: Pick<
    ExpiryFormModel,
    'expireAt' | 'expireType' | 'graceDays' | 'graceReadOnly' | 'remindDays'
  >,
): string {
  if (form.expireType === 'LONG_TERM') {
    return '长期有效';
  }
  const parts = [`有到期日 · ${form.expireAt || '未填'}`];
  if (form.remindDays.trim() !== '') {
    parts.push(`提醒 ${parseRemindInput(form.remindDays).join('、')} 天前`);
  }
  if (form.graceDays.trim() !== '') {
    parts.push(`宽限 ${form.graceDays} 天`);
  }
  if (form.graceReadOnly) {
    parts.push('宽限期只读');
  }
  return parts.join(' / ');
}

/** 变更原因必填（后端 40001，前端先挡）。 */
export function validateReason(reason: string): string | undefined {
  if (!reason.trim()) return '必填';
  if (reason.trim().length > 200) return '最多 200 个字符';
  return undefined;
}

/** 状态列文案（StatusTag 的中文输入）。 */
export function statusLabel(status: TenantStatus): string {
  return tenantStatusText(status);
}
