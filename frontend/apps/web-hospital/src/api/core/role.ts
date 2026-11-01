import type {
  TenantRoleCopyRequest,
  TenantRoleFromTemplateRequest,
  TenantRoleMembersView,
  TenantRoleMenusView,
  TenantRoleSaveRequest,
  TenantRoleScopeSaveRequest,
  TenantRoleScopeView,
  TenantRoleStatusRequest,
  TenantRoleView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T05 / PLT-ORG-004、005、PLT-ROLE-004、005：医院端角色管理接口。
 * 契约见 docs/api/system.md §3；查看 tenant:role:view，维护 tenant:role:edit。
 */

/** 角色列表：内置 / 模板 / 自定义，附成员数与菜单数（内置角色不可删、不可改权限）。 */
export function listRoles() {
  return requestClient.get<TenantRoleView[]>(apiPath('/system/roles'));
}

/** 新建自定义角色：编码租户内唯一、创建后不可改（重复 40902）。 */
export function createRole(data: TenantRoleSaveRequest) {
  return requestClient.post<TenantRoleView>(apiPath('/system/roles'), data);
}

/** 编辑角色（仅自定义）：名称与说明可改、编码不可改（40001）。 */
export function updateRole(roleId: number, data: TenantRoleSaveRequest) {
  return requestClient.put<TenantRoleView>(
    apiPath(`/system/roles/${roleId}`),
    data,
  );
}

/** 删除角色：仅无成员的自定义角色（内置与模板 40901）。 */
export function deleteRole(roleId: number) {
  return requestClient.delete<null>(apiPath(`/system/roles/${roleId}`));
}

/** 复制自定义角色：菜单、数据范围与自定义科室一并复制。 */
export function copyRole(roleId: number, data: TenantRoleCopyRequest) {
  return requestClient.post<TenantRoleView>(
    apiPath(`/system/roles/${roleId}/copy`),
    data,
  );
}

/** 应用业务角色模板（PLT-ORG-005）：把模块下发的模板复制成自定义角色。 */
export function roleFromTemplate(data: TenantRoleFromTemplateRequest) {
  return requestClient.post<TenantRoleView>(
    apiPath('/system/roles/from-template'),
    data,
  );
}

/** 停用 / 启用：停用后成员权限立即失效（会话按新权限重算）。 */
export function changeRoleStatus(
  roleId: number,
  data: TenantRoleStatusRequest,
) {
  return requestClient.post<TenantRoleView>(
    apiPath(`/system/roles/${roleId}/status`),
    data,
  );
}

/** 菜单按钮页签：可分配范围树（assignable 标记）+ 当前授权。 */
export function roleMenus(roleId: number) {
  return requestClient.get<TenantRoleMenusView>(
    apiPath(`/system/roles/${roleId}/menus`),
  );
}

/** 保存菜单与按钮（全量覆盖）：只能选平台可分配范围内的项（超出 40001）。 */
export function updateRoleMenus(roleId: number, menuIds: number[]) {
  return requestClient.put<TenantRoleView>(
    apiPath(`/system/roles/${roleId}/menus`),
    {
      menuIds,
    },
  );
}

/** 数据范围页签：范围类型与自定义科室清单（生效口径 E07-T02）。 */
export function roleScope(roleId: number) {
  return requestClient.get<TenantRoleScopeView>(
    apiPath(`/system/roles/${roleId}/scope`),
  );
}

/** 保存数据范围（全量覆盖）：CUSTOM_DEPT 必须勾选科室。 */
export function updateRoleScope(
  roleId: number,
  data: TenantRoleScopeSaveRequest,
) {
  return requestClient.put<TenantRoleView>(
    apiPath(`/system/roles/${roleId}/scope`),
    data,
  );
}

/** 成员页签：当前成员清单。 */
export function roleMembers(roleId: number) {
  return requestClient.get<TenantRoleMembersView>(
    apiPath(`/system/roles/${roleId}/members`),
  );
}

/** 保存成员（全量覆盖）：模板角色不可直接分配（40901，先复制）。 */
export function updateRoleMembers(roleId: number, userIds: number[]) {
  return requestClient.put<TenantRoleMembersView>(
    apiPath(`/system/roles/${roleId}/members`),
    { userIds },
  );
}
