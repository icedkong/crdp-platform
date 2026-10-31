/**
 * 租户详情与信息维护的纯逻辑（E05-T01 / PLT-TEN-009）：详情 → 表单模型、表单 → 提交载荷、展示文案。
 */
import type { TenantDetail, TenantUpdateRequest } from '@crdp/api';

import { tenantStatusText } from '@crdp/api';

/** 编辑表单模型（输入态，空串即未填）。 */
export interface TenantFormModel {
  contactEmail: string;
  contactName: string;
  contactPhone: string;
  contractNo: string;
  creditCode: string;
  name: string;
  orgType: string;
  region: string;
  remark: string;
  shortName: string;
}

export function toFormModel(detail: TenantDetail): TenantFormModel {
  return {
    contactEmail: detail.contactEmail ?? '',
    contactName: detail.contactName ?? '',
    contactPhone: detail.contactPhone ?? '',
    contractNo: detail.contractNo ?? '',
    creditCode: detail.creditCode ?? '',
    name: detail.name,
    orgType: detail.orgType ?? '',
    region: detail.region ?? '',
    remark: detail.remark ?? '',
    shortName: detail.shortName ?? '',
  };
}

/** 表单 → 提交载荷：空白一律传 null（后端空串清空与 null 等价，显式 null 更直白）。 */
export function toUpdateRequest(form: TenantFormModel): TenantUpdateRequest {
  return {
    contactEmail: blankToNull(form.contactEmail),
    contactName: blankToNull(form.contactName),
    contactPhone: blankToNull(form.contactPhone),
    contractNo: blankToNull(form.contractNo),
    creditCode: blankToNull(form.creditCode),
    name: form.name.trim(),
    orgType: blankToNull(form.orgType),
    region: blankToNull(form.region),
    remark: blankToNull(form.remark),
    shortName: blankToNull(form.shortName),
  };
}

/** 前端只挡明显错误，最终校验在后端（40001）。 */
export function validateTenantForm(
  form: TenantFormModel,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.name.trim()) errors.name = '必填';
  if (form.name.trim().length > 128) errors.name = '最多 128 个字符';
  if (
    form.contactEmail &&
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.contactEmail.trim())
  ) {
    errors.contactEmail = '邮箱格式不正确';
  }
  return errors;
}

function blankToNull(value: string): null | string {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/** 状态展示（StatusTag 文案）。 */
export function statusText(status: string): string {
  return tenantStatusText(status);
}

/** 提醒节点展示（PLT-TEN-011，如「到期前 30、7、1 天」）；空为「—」。 */
export function remindDaysText(remindDays: null | number[]): string {
  if (!remindDays || remindDays.length === 0) return '—';
  return `到期前 ${remindDays.join('、')} 天`;
}

/** 到期展示：长期有效显示「长期」（与列表一致）。 */
export function expireText(
  detail: Pick<TenantDetail, 'expireAt' | 'expireType'>,
): string {
  if (detail.expireType !== 'FIXED' || !detail.expireAt) return '长期';
  return detail.expireAt;
}

/** 详情空值统一显示「—」。 */
export function orDash(value: null | string | undefined): string {
  return value === null || value === undefined || value === '' ? '—' : value;
}
