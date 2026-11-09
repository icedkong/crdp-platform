import type {
  PlatformGrantMenuNode,
  PlatformRoleHolder,
  PlatformRoleSaveRequest,
  PlatformRoleView,
  PlatformViewConfigResource,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 平台角色（E06-T04 / PLT-ROLE-002、003）：预置 + 自定义同表维护。
 * 预置角色不可删除，权限（菜单授权、租户视角字段、状态）可由平台管理员调整。
 */
export function listRoles() {
  return requestClient.get<PlatformRoleView[]>(apiPath('/roles'));
}

export function getRole(id: number) {
  return requestClient.get<PlatformRoleView>(apiPath(`/roles/${id}`));
}

export function createRole(data: PlatformRoleSaveRequest) {
  return requestClient.post<PlatformRoleView>(apiPath('/roles'), data);
}

export function updateRole(id: number, data: PlatformRoleSaveRequest) {
  return requestClient.put<PlatformRoleView>(apiPath(`/roles/${id}`), data);
}

/** 持有人列表（查看持有人）。 */
export function listRoleHolders(id: number) {
  return requestClient.get<PlatformRoleHolder[]>(
    apiPath(`/roles/${id}/holders`),
  );
}

/** 可授权的平台端启用菜单 / 按钮（授权树数据源，避免依赖 platform:menu:view）。 */
export function listGrantableMenus() {
  return requestClient.get<PlatformGrantMenuNode[]>(apiPath('/roles/menus'));
}

/** 代为配置资源目录（E20-T06 / PLT-VIEW-013）：角色「代为配置资源范围」多选项；空清单 = 全部（已确认）。 */
export function listViewConfigResources() {
  return requestClient.get<PlatformViewConfigResource[]>(
    apiPath('/roles/view-config-resources'),
  );
}
