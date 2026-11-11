/**
 * 资源变更记录（E05-T10 / PLT-PERM-012）：菜单、接口资源变更历史的查询与导出。
 * 权限 platform:menu:view（与菜单资源页一致，docs/api/platform.md §3）。
 */
import type { ResourceChangeLogPage, ResourceChangeLogQuery } from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

export function listResourceChangeLogs(query: ResourceChangeLogQuery) {
  const params = new URLSearchParams();
  if (query.resourceType) params.set('resourceType', query.resourceType);
  if (query.action) params.set('action', query.action);
  if (query.channel) params.set('channel', query.channel);
  if (query.keyword) params.set('keyword', query.keyword);
  params.set('pageNo', String(query.pageNo ?? 1));
  params.set('pageSize', String(query.pageSize ?? 20));
  return requestClient.get<ResourceChangeLogPage>(
    apiPath(`/resource-logs?${params.toString()}`),
  );
}

/** 导出 CSV（响应是文件流，无统一信封）；下载后由浏览器保存。 */
export async function exportResourceChangeLogs(query: ResourceChangeLogQuery) {
  const params = new URLSearchParams();
  if (query.resourceType) params.set('resourceType', query.resourceType);
  if (query.action) params.set('action', query.action);
  if (query.channel) params.set('channel', query.channel);
  if (query.keyword) params.set('keyword', query.keyword);
  const token = useAccessStore().accessToken;
  const response = await fetch(
    apiPath(`/resource-logs/export?${params.toString()}`),
    {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    },
  );
  if (!response.ok) {
    throw new Error(`导出失败（${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = exportFileName(new Date());
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 导出文件名：资源变更记录-yyyyMMdd-HHmmss.csv。 */
export function exportFileName(at: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const stamp = `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-${pad(
    at.getHours(),
  )}${pad(at.getMinutes())}${pad(at.getSeconds())}`;
  return `资源变更记录-${stamp}.csv`;
}
