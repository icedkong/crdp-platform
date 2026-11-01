import type {
  TenantDictItemSaveRequest,
  TenantDictItemView,
  TenantDictSaveRequest,
  TenantDictTypeView,
  TenantParamLogView,
  TenantParamOverrideRequest,
  TenantParamView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T10 / PLT-CFG-002、004：机构设置 · 租户字典与租户参数接口。
 * 契约见 docs/api/system.md §4；字典用 tenant:dict:edit、参数用 tenant:param:edit。
 * 字典继承平台定义（停用平台项 / 新增本院项）；参数在平台允许范围内覆盖，覆盖与恢复都留变更记录。
 */

/** 字典类型列表（平台继承 + 本院自有）。 */
export function listDicts() {
  return requestClient.get<TenantDictTypeView[]>(apiPath('/system/dicts'));
}

/** 字典项列表（生效集 = 平台项 − 本院停用 + 本院自有项）。 */
export function listDictItems(code: string) {
  return requestClient.get<TenantDictItemView[]>(
    apiPath(`/system/dicts/${code}/items`),
  );
}

/** 新建本院字典。 */
export function createDict(data: TenantDictSaveRequest) {
  return requestClient.post<TenantDictTypeView>(apiPath('/system/dicts'), data);
}

/** 维护本院字典（平台字典不可改）。 */
export function updateDict(code: string, data: TenantDictSaveRequest) {
  return requestClient.put<TenantDictTypeView>(
    apiPath(`/system/dicts/${code}`),
    data,
  );
}

/** 新增本院自有项（加进平台字典或本院字典）。 */
export function createDictItem(code: string, data: TenantDictItemSaveRequest) {
  return requestClient.post<TenantDictItemView[]>(
    apiPath(`/system/dicts/${code}/items`),
    data,
  );
}

/** 维护字典项：本院项可改文字 / 排序；平台项只认 status（停用 / 恢复）。 */
export function updateDictItem(
  code: string,
  itemCode: string,
  data: TenantDictItemSaveRequest,
) {
  return requestClient.put<TenantDictItemView[]>(
    apiPath(`/system/dicts/${code}/items/${itemCode}`),
    data,
  );
}

/** 参数列表（平台定义 + 本院覆盖状态；stale= 覆盖被平台锁定失效）。 */
export function listParams() {
  return requestClient.get<TenantParamView[]>(apiPath('/system/params'));
}

/** 覆盖平台默认值（范围内）。 */
export function overrideParam(
  paramKey: string,
  data: TenantParamOverrideRequest,
) {
  return requestClient.put<TenantParamView>(
    apiPath(`/system/params/${paramKey}`),
    data,
  );
}

/** 恢复平台默认值（幂等）。 */
export function resetParam(paramKey: string) {
  return requestClient.delete<TenantParamView>(
    apiPath(`/system/params/${paramKey}`),
  );
}

/** 覆盖变更记录（倒序，最近 100 条）。 */
export function listParamLogs() {
  return requestClient.get<TenantParamLogView[]>(
    apiPath('/system/params/logs'),
  );
}
