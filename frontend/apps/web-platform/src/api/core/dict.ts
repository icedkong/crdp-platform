import type {
  PlatformDictItemSaveRequest,
  PlatformDictItemView,
  PlatformDictSaveRequest,
  PlatformDictView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T10 / PLT-CFG-001：平台字典管理接口。
 * 契约见 docs/api/platform.md §7；查看与维护都用 platform:dict:edit（菜单按它下发）。
 * 编码创建后不可改；字典项停用不删除；保存即时下发全部租户（缓存失效）。
 */

/** 字典类型列表（含停用行与生效项数）。 */
export function listDicts() {
  return requestClient.get<PlatformDictView[]>(apiPath('/dicts'));
}

/** 字典项列表（含停用行；停用项不进租户生效集）。 */
export function listDictItems(code: string) {
  return requestClient.get<PlatformDictItemView[]>(
    apiPath(`/dicts/${code}/items`),
  );
}

/** 新建字典类型（编码全局唯一，创建后不可改）。 */
export function createDict(data: PlatformDictSaveRequest) {
  return requestClient.post<PlatformDictView>(apiPath('/dicts'), data);
}

/** 维护字典类型：名称 / 说明 / 停用启用。 */
export function updateDict(code: string, data: PlatformDictSaveRequest) {
  return requestClient.put<PlatformDictView>(apiPath(`/dicts/${code}`), data);
}

/** 新增字典项（项编码字典内唯一，创建后不可改）。 */
export function createDictItem(
  code: string,
  data: PlatformDictItemSaveRequest,
) {
  return requestClient.post<PlatformDictItemView[]>(
    apiPath(`/dicts/${code}/items`),
    data,
  );
}

/** 维护字典项：文字 / 排序 / 停用启用。 */
export function updateDictItem(
  code: string,
  itemCode: string,
  data: PlatformDictItemSaveRequest,
) {
  return requestClient.put<PlatformDictItemView[]>(
    apiPath(`/dicts/${code}/items/${itemCode}`),
    data,
  );
}
