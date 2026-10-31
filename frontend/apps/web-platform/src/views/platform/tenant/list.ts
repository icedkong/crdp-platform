/**
 * 租户列表页的纯逻辑（E05-T01 / PLT-TEN-001）：筛选条件 → 查询参数、到期展示、行操作可用性。
 */
import type { TenantListItem, TenantListQuery, TenantStatus } from '@crdp/api';

import { tenantStatusText } from '@crdp/api';

/** 筛选表单模型（页面状态）。 */
export interface TenantFilterModel {
  /** 名称/编码关键词（检索词只放组件状态，不进 URL）。 */
  keyword: string;
  /** 到期日不晚于（yyyy-MM-dd；空为不限）。 */
  expireBefore: string;
  status: string;
}

export const EMPTY_FILTER: TenantFilterModel = {
  expireBefore: '',
  keyword: '',
  status: '',
};

/** 表单模型 → 接口查询参数（补默认分页；空条件只有分页参数）。 */
export function toTenantListQuery(
  model: TenantFilterModel,
  pageNo = 1,
  pageSize = 20,
): TenantListQuery {
  const query: TenantListQuery = { pageNo, pageSize };
  if (model.keyword.trim()) query.keyword = model.keyword.trim();
  if (model.status) query.status = model.status as TenantStatus;
  if (model.expireBefore) query.expireBefore = model.expireBefore;
  return query;
}

/** 到期日列（PLT-TEN-001）：长期有效显示「长期」。 */
export function expireText(
  item: Pick<TenantListItem, 'expireAt' | 'expireType'>,
): string {
  if (item.expireType !== 'FIXED' || !item.expireAt) return '长期';
  return item.expireAt;
}

/** 用户数列：租户库不可达时后端返回空，显示「—」。 */
export function userCountText(item: Pick<TenantListItem, 'userCount'>): string {
  return item.userCount === null || item.userCount === undefined
    ? '—'
    : String(item.userCount);
}

/** 开通中的租户可看开通进度 / 重试 / 回收（E02-T08 页面）。 */
export function canOpenProvision(status: string): boolean {
  return status === 'PROVISIONING';
}

/** 状态列文案（StatusTag 的中文输入）。 */
export function statusText(status: string): string {
  return tenantStatusText(status);
}

/** 序号列：当前页内从 1 连续编号。 */
export function rowNumber(
  index: number,
  pageNo: number,
  pageSize: number,
): number {
  return (pageNo - 1) * pageSize + index + 1;
}
