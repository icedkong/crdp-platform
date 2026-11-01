import type {
  ModuleCatalogItem,
  ModuleSyncResult,
  ModuleUpdateRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 模块目录（E05-T04 / PLT-MOD-001、003）：编码、名称、说明、版本、依赖、菜单与权限码清单、配置项、版本历史。 */
export function listModules() {
  return requestClient.get<ModuleCatalogItem[]>(apiPath('/modules'));
}

/**
 * 维护模块展示信息与上下架（PLT-MOD-002）：只改名称、说明、上架状态（基础模块不能下架），
 * 后端写 MODULE_UPDATE 审计。
 */
export function updateModule(code: string, data: ModuleUpdateRequest) {
  return requestClient.put<ModuleCatalogItem>(
    apiPath(`/modules/${code}`),
    data,
  );
}

/** 手动触发注册同步（PLT-MOD-002）：与启动时同一套逻辑；返回每个模块 REGISTERED / UPDATED / UNCHANGED。 */
export function syncModules() {
  return requestClient.post<ModuleSyncResult>(apiPath('/modules/sync'));
}
