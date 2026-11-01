/**
 * E06-T05 / PLT-ORG-004、005、PLT-ROLE-004、005：医院端角色管理类型，与后端
 * `com.crdp.server.system.role.RoleAdminService` 视图同构（docs/api/system.md §3）。
 */

/** 角色类型（平台规格 §6 关键字段·角色）：内置 / 模板复制 / 自定义。 */
export type RoleType = 'BUILT_IN' | 'CUSTOM' | 'TEMPLATE';

export type RoleStatus = 'DISABLED' | 'ENABLED';

/** 数据范围类型（七种，平台口径；生效计算在 E07-T02）。 */
export type RoleScopeType =
  | 'ALL'
  | 'CUSTOM_DEPT'
  | 'DEPT'
  | 'RESPONSIBLE_DEPT'
  | 'RESPONSIBLE_PATIENT'
  | 'SELF'
  | 'WARD';

/** 角色行（列表与页签头部共用）。memberCount / menuCount 供列表与影响提示。 */
export interface TenantRoleView {
  code: string;
  description: null | string;
  id: number;
  memberCount: number;
  menuCount: number;
  /** 模板角色的来源模块（如 followup）；其余为 null。 */
  moduleCode: null | string;
  name: string;
  roleType: RoleType;
  status: RoleStatus;
}

/** 新建 / 编辑：编码创建后不可改（更新时原样回传做一致性校验）。 */
export interface TenantRoleSaveRequest {
  code: string;
  description?: null | string;
  name: string;
}

/** 复制与模板应用的目标角色命名；description 省略时继承来源说明。 */
export interface TenantRoleCopyRequest {
  code: string;
  description?: null | string;
  name: string;
}

/** 模板应用（PLT-ORG-005）：要复制的模板角色 + 目标编码 / 名称。 */
export interface TenantRoleFromTemplateRequest extends TenantRoleCopyRequest {
  templateId: number;
}

/** action=ENABLE|DISABLE；reason 可选（≤200，写审计）。 */
export interface TenantRoleStatusRequest {
  action: 'DISABLE' | 'ENABLE';
  reason?: string;
}

/** 菜单树节点（含按钮）：assignable=false 表示不在平台可分配范围，禁止勾选。 */
export interface TenantRoleMenuNode {
  assignable: boolean;
  children: TenantRoleMenuNode[];
  id: number;
  moduleCode: string;
  parentId: number;
  permCode: null | string;
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 菜单按钮页签：可分配范围树 + 当前授权（全量覆盖保存）。 */
export interface TenantRoleMenusView {
  menuIds: number[];
  menus: TenantRoleMenuNode[];
  role: TenantRoleView;
}

/** 数据范围页签：类型并集 + 自定义科室清单（CUSTOM_DEPT 时使用）。 */
export interface TenantRoleScopeView {
  customDeptIds: number[];
  role: TenantRoleView;
  scopeTypes: RoleScopeType[];
}

export interface TenantRoleScopeSaveRequest {
  customDeptIds?: number[];
  scopeTypes: RoleScopeType[];
}

/** 成员页签：当前成员清单（成员用全量覆盖保存）。 */
export interface TenantRoleMemberView {
  displayName: string;
  status: string;
  userId: number;
  username: string;
}

export interface TenantRoleMembersView {
  members: TenantRoleMemberView[];
  role: TenantRoleView;
}

/** 角色类型文案（列表 Tag 与表单提示）。 */
export function tenantRoleTypeText(type: RoleType): string {
  if (type === 'BUILT_IN') return '内置';
  if (type === 'TEMPLATE') return '模板';
  return '自定义';
}

/** 角色类型在列表里的辅助说明（操作列按钮的可用性依据）。 */
export function tenantRoleTypeHint(type: RoleType): string {
  if (type === 'BUILT_IN') return '内置角色不可删除、不可修改权限';
  if (type === 'TEMPLATE') return '模板由模块开通下发，复制后调整';
  return '自定义角色可编辑、复制、停用';
}

/** 数据范围类型文案（平台口径七种；PLT-PERM-007）。 */
export function tenantRoleScopeTypeText(type: RoleScopeType): string {
  const texts: Record<RoleScopeType, string> = {
    ALL: '全院（全部病区）',
    CUSTOM_DEPT: '自定义科室',
    DEPT: '本科室',
    RESPONSIBLE_DEPT: '本人负责科室',
    RESPONSIBLE_PATIENT: '仅本人负责的患者',
    SELF: '仅本人',
    WARD: '本人负责病区',
  };
  return texts[type];
}

/** 数据范围选项（勾选态用；CUSTOM_DEPT 需要再勾科室）。 */
export const TENANT_ROLE_SCOPE_OPTIONS: Array<{
  requiresDeptIds: boolean;
  type: RoleScopeType;
}> = [
  { type: 'ALL', requiresDeptIds: false },
  { type: 'WARD', requiresDeptIds: false },
  { type: 'RESPONSIBLE_DEPT', requiresDeptIds: false },
  { type: 'RESPONSIBLE_PATIENT', requiresDeptIds: false },
  { type: 'DEPT', requiresDeptIds: false },
  { type: 'SELF', requiresDeptIds: false },
  { type: 'CUSTOM_DEPT', requiresDeptIds: true },
];
