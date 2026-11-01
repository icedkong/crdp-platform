import type { ApiMarkRequest, ApiResourceItem, ApiSyncResult } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 接口资源（E05-T06 / PLT-PERM-004）：启动扫描自动登记的接口清单；按模块与权限码筛选，
 * unbound 只看未绑定权限码的接口；标记公共接口、审计级别、敏感级别、动作类型。
 */
export function listApiResources(filters?: {
  moduleCode?: string;
  permCode?: string;
  unbound?: boolean;
}) {
  const params = new URLSearchParams();
  if (filters?.moduleCode) params.set('moduleCode', filters.moduleCode);
  if (filters?.permCode) params.set('permCode', filters.permCode);
  if (filters?.unbound) params.set('unbound', 'true');
  const suffix = params.size === 0 ? '' : `?${params.toString()}`;
  return requestClient.get<ApiResourceItem[]>(apiPath(`/apis${suffix}`));
}

export function markApiResource(id: number, data: ApiMarkRequest) {
  return requestClient.put<ApiResourceItem>(apiPath(`/apis/${id}`), data);
}

/** 手动触发登记同步（与启动同一套逻辑）。 */
export function syncApiResources() {
  return requestClient.post<ApiSyncResult>(apiPath('/apis/sync'));
}
