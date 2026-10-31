/**
 * 后端枚举到中文的映射（components.md §2：StatusTag 的文案来源）。未知值原样返回。
 */

/** 租户状态（PLT-TEN-010 七态 + 开通中初始态）。 */
export const TENANT_STATUS_TEXT: Record<string, string> = {
  PROVISIONING: '开通中',
  TRIAL: '试用',
  ENABLED: '启用',
  GRACE: '宽限期',
  DISABLED: '停用',
  CANCELLING: '注销中',
  CANCELLED: '已注销',
};

/** 列表筛选下拉用（顺序即展示顺序）。 */
export const TENANT_STATUS_OPTIONS: Array<{ label: string; value: string }> =
  Object.entries(TENANT_STATUS_TEXT).map(([value, label]) => ({
    label,
    value,
  }));

export function tenantStatusText(status: string): string {
  return TENANT_STATUS_TEXT[status] ?? status;
}

/** 到期类型：FIXED 有到期日；LONG_TERM 长期有效（页面显示「长期」）。 */
export const EXPIRE_TYPE_TEXT: Record<string, string> = {
  FIXED: '有到期日',
  LONG_TERM: '长期有效',
};
