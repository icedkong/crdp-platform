import type { TenantQuotaView } from '@crdp/api';

/**
 * 配额页签的纯逻辑（E05-T09 / PLT-TEN-014）：表单模型 ↔ 请求转换、校验、用量与预警文案。
 * 页面（detail.vue 配额页签）与新建向导第 2 步共用；展示口径：用户数为整数、存储按 GB（一位小数）。
 */

/** 配额表单：不限时 limit 为 undefined（Switch 关）；存储以 GB 录入、提交转字节。 */
export interface QuotaFormModel {
  userLimited: boolean;
  userLimit?: number;
  storageLimited: boolean;
  storageLimitGb?: number;
}

export const EMPTY_QUOTA_FORM: QuotaFormModel = {
  storageLimitGb: undefined,
  storageLimited: false,
  userLimit: undefined,
  userLimited: false,
};

/** GB → 字节（四舍五入到整数，避免浮点尾差）。 */
export const GB_BYTES = 1024 ** 3;

export function gbToBytes(gb: number): number {
  return Math.round(gb * GB_BYTES);
}

/** 视图 → 表单回显。 */
export function toQuotaFormModel(quota: TenantQuotaView): QuotaFormModel {
  return {
    storageLimitGb:
      quota.storageLimitBytes === null
        ? undefined
        : Number((quota.storageLimitBytes / GB_BYTES).toFixed(1)),
    storageLimited: quota.storageLimitBytes !== null,
    userLimit: quota.userLimit ?? undefined,
    userLimited: quota.userLimit !== null,
  };
}

/** 校验：设置上限时必填且为正（用户数须为整数、存储 GB ≥ 0.1）；返回错误映射，空对象为通过。 */
export function validateQuotaForm(form: QuotaFormModel): {
  storageLimitGb?: string;
  userLimit?: string;
} {
  const errors: { storageLimitGb?: string; userLimit?: string } = {};
  if (form.userLimited) {
    if (form.userLimit === undefined || form.userLimit === null) {
      errors.userLimit = '设置上限后必须填写用户数上限';
    } else if (!Number.isInteger(form.userLimit) || form.userLimit < 1) {
      errors.userLimit = '用户数上限须为不小于 1 的整数';
    }
  }
  if (form.storageLimited) {
    if (form.storageLimitGb === undefined || form.storageLimitGb === null) {
      errors.storageLimitGb = '设置上限后必须填写存储上限';
    } else if (form.storageLimitGb < 0.1) {
      errors.storageLimitGb = '存储上限须不小于 0.1 GB';
    }
  }
  return errors;
}

/** 表单 → 提交请求；不限的字段传 null（后端清除上限）。 */
export function toQuotaRequest(form: QuotaFormModel): {
  storageLimitBytes: null | number;
  userLimit: null | number;
} {
  return {
    storageLimitBytes: form.storageLimited
      ? gbToBytes(form.storageLimitGb as number)
      : null,
    userLimit: form.userLimited ? (form.userLimit as number) : null,
  };
}

/** 表单 → 新建租户请求的配额片段；不限时整体省略（POST /tenants 里省略 = 不限）。 */
export function toCreateQuota(form: QuotaFormModel): {
  storageLimitBytes?: number;
  userLimit?: number;
} {
  const request: { storageLimitBytes?: number; userLimit?: number } = {};
  if (form.userLimited) {
    request.userLimit = form.userLimit as number;
  }
  if (form.storageLimited) {
    request.storageLimitBytes = gbToBytes(form.storageLimitGb as number);
  }
  return request;
}

/** 确认步骤展示文案：两项都不限显示「不限」。 */
export function quotaFormText(form: QuotaFormModel): string {
  const users = form.userLimited ? `用户 ${form.userLimit}` : '';
  const storage = form.storageLimited ? `存储 ${form.storageLimitGb} GB` : '';
  const parts = [users, storage].filter((part) => part.length > 0);
  return parts.length === 0 ? '不限' : parts.join('，');
}

/** 用户数展示：不限显示「不限」；用量未知显示上限加「—」。 */
export function userUsageText(quota: TenantQuotaView): string {
  const limit = quota.userLimit === null ? '不限' : `${quota.userLimit}`;
  const used =
    quota.enabledUserCount === null ? '—' : `${quota.enabledUserCount}`;
  return `${used} / ${limit}`;
}

/** 存储展示（GB 一位小数）：不限显示「不限」；用量未知显示上限加「—」。 */
export function storageUsageText(quota: TenantQuotaView): string {
  const limit =
    quota.storageLimitBytes === null
      ? '不限'
      : `${(quota.storageLimitBytes / GB_BYTES).toFixed(1)} GB`;
  const used =
    quota.storageUsedBytes === null
      ? '—'
      : `${(quota.storageUsedBytes / GB_BYTES).toFixed(1)} GB`;
  return `${used} / ${limit}`;
}

/** 预警横幅（design-system §16 口径：预警不阻塞操作）：满额提示红色、80% 预警提示橙色；无限则为空。 */
export function quotaAlerts(quota: TenantQuotaView): Array<{
  kind: 'storage' | 'user';
  level: 'error' | 'warning';
  text: string;
}> {
  const alerts: Array<{
    kind: 'storage' | 'user';
    level: 'error' | 'warning';
    text: string;
  }> = [];
  if (quota.userLimitReached) {
    alerts.push({
      kind: 'user',
      level: 'error',
      text: '已达用户数上限：新建与启用用户将被拒绝，请上调配额或停用冗余账号',
    });
  } else if (quota.userWarning) {
    alerts.push({
      kind: 'user',
      level: 'warning',
      text: '用户数已达上限的 80%：即将无法新建用户，请关注配额余量',
    });
  }
  if (quota.storageLimitReached) {
    alerts.push({
      kind: 'storage',
      level: 'error',
      text: '存储已达上限：文件上传将被拒绝，请上调配额或清理已删除文件',
    });
  } else if (quota.storageWarning) {
    alerts.push({
      kind: 'storage',
      level: 'warning',
      text: '存储用量已达上限的 80%：即将无法上传文件，请关注配额余量',
    });
  }
  return alerts;
}
