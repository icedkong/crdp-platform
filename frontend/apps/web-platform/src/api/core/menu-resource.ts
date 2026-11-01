import type {
  MenuCreateRequest,
  MenuResourceNode,
  MenuSyncApplyResult,
  MenuSyncDiffResult,
  MenuUpdateRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 菜单资源（E05-T06 / PLT-PERM-001、002、003、011）：平台端、医院端两棵全量树（含按钮与停用行），
 * 目录 / 菜单 / 按钮维护（软删），菜单清单同步（差异 + 确认应用）。
 */
export function listMenuTree(side: 'HOSPITAL' | 'PLATFORM') {
  return requestClient.get<MenuResourceNode[]>(
    apiPath(`/menus/tree?side=${side}`),
  );
}

export function createMenu(data: MenuCreateRequest) {
  return requestClient.post<MenuResourceNode>(apiPath('/menus'), data);
}

export function updateMenu(id: number, data: MenuUpdateRequest) {
  return requestClient.put<MenuResourceNode>(apiPath(`/menus/${id}`), data);
}

/** 软删除：移除平台角色授予并逐租户清理 sys_role_menu，受影响用户权限版本递增。 */
export function deleteMenu(id: number) {
  return requestClient.delete<null>(apiPath(`/menus/${id}`));
}

/** 清单差异（只读）：新增 / 变更（结构字段）/ 下线。 */
export function menuSyncDiff() {
  return requestClient.get<MenuSyncDiffResult>(apiPath('/menus/sync/diff'));
}

/** 平台确认后应用差异。 */
export function applyMenuSync() {
  return requestClient.post<MenuSyncApplyResult>(apiPath('/menus/sync'));
}
