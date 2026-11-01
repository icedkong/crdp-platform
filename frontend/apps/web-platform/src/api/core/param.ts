import type { PlatformParamSaveRequest, PlatformParamView } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T10 / PLT-CFG-003：平台系统参数接口。
 * 契约见 docs/api/platform.md §7；查看与维护都用 platform:param:edit（菜单按它下发）。
 * 参数键创建后不可改；可覆盖参数必须配置取值范围（BOOLEAN / JSON 除外）；
 * 改为锁定后租户已覆盖的值即时失效并提示（PLT-CFG-011）。
 */

/** 系统参数列表（参数键 / 值类型 / 平台值 / 层级 / 取值范围）。 */
export function listParams() {
  return requestClient.get<PlatformParamView[]>(apiPath('/params'));
}

/** 新建系统参数（参数键全局唯一，创建后不可改）。 */
export function createParam(data: PlatformParamSaveRequest) {
  return requestClient.post<PlatformParamView>(apiPath('/params'), data);
}

/** 维护系统参数（参数键不可改）。 */
export function updateParam(paramKey: string, data: PlatformParamSaveRequest) {
  return requestClient.put<PlatformParamView>(
    apiPath(`/params/${paramKey}`),
    data,
  );
}
