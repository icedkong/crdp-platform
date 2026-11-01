/**
 * 租户授权范围、可分配范围、模板与敏感权限（E05-T07 / PLT-SCOPE-001..006、008）。
 * 后端下发形状见 backend PlatformTenantScopeController / PlatformScopeTemplateController。
 */

/** 授权范围树里的项的三种状态：未开通（不可选）/ 已开通未放开 / 可分配。 */
export type TenantScopeItemState = 'ASSIGNABLE' | 'CLOSED' | 'NOT_RELEASED';

/** 授权范围树里的一个菜单 / 按钮（PLT-SCOPE-001、002、004）。 */
export interface TenantScopeItem {
  /** 生效值：是否可分配（未开通模块恒为 false）。 */
  assignable: boolean;
  defaultAssignable: boolean;
  /** base 固定能力（组织、用户、角色管理）：不受可分配范围限制，也不允许收回。 */
  fixed: boolean;
  id: number;
  moduleCode: string;
  parentId: number;
  permCode: null | string;
  /** 敏感放开的登记原因（放开时必填，PLT-SCOPE-004）。 */
  releaseReason: null | string;
  sensitive: boolean;
  setAt: null | string;
  state: TenantScopeItemState;
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 按模块分组的授权范围（模块开通状态决定组内项是否可选）。 */
export interface TenantScopeModule {
  baseModule: boolean;
  code: string;
  items: TenantScopeItem[];
  name: string;
  opened: boolean;
}

/** 租户详情 · 授权范围页签视图（PLT-SCOPE-001、002）。 */
export interface TenantScopeView {
  modules: TenantScopeModule[];
  tenantCode: string;
  tenantId: number;
  tenantName: string;
  /** PLT-TEN-010 七态 + PROVISIONING。 */
  tenantStatus: string;
}

/** 可分配范围保存请求（PLT-SCOPE-002、004、008）：提交期望全集。 */
export interface TenantScopeUpdateRequest {
  assignableMenuIds: number[];
  reason: string;
  /** 本次新放开的敏感项原因（menuId → 原因）。 */
  sensitiveReasons: Record<string, string>;
}

/** 变更结果（PLT-SCOPE-006）：移除 / 放开清单与自动收回痕迹。 */
export interface TenantScopeChangeResult {
  addedMenuIds: number[];
  refreshedUsers: number;
  removedMenuIds: number[];
  revokedGrants: number;
  revokedRoles: number;
  tenantCode: string;
  tenantId: number;
}

/** 影响分析（PLT-SCOPE-005）里的一项变化。 */
export interface TenantScopeImpactItem {
  menuId: number;
  permCode: null | string;
  title: string;
}

/** 受影响角色（将失去的菜单数、权限码与成员数）。 */
export interface TenantScopeAffectedRole {
  removedMenuCount: number;
  removedPermCodes: string[];
  roleCode: string;
  roleId: number;
  roleName: string;
  userCount: number;
}

/** 变更影响分析（PLT-SCOPE-005）：保存前展示。 */
export interface TenantScopePreview {
  added: TenantScopeImpactItem[];
  affectedRoles: TenantScopeAffectedRole[];
  affectedUserCount: number;
  removed: TenantScopeImpactItem[];
  sensitiveReleases: TenantScopeImpactItem[];
  tenantCode: string;
  tenantId: number;
}

/** 变更记录行（PLT-SCOPE-008）。 */
export interface TenantScopeLog {
  createdAt: string;
  detail: string;
  id: number;
  operatorId: null | number;
  reason: string;
  tenantId: number;
}

/** 模板视图里的菜单 / 按钮（inTemplate 为勾选态；敏感项不可预置）。 */
export interface ScopeTemplateItem {
  inTemplate: boolean;
  menuId: number;
  parentId: number;
  permCode: null | string;
  sensitive: boolean;
  title: string;
}

export interface ScopeTemplateModule {
  baseModule: boolean;
  code: string;
  items: ScopeTemplateItem[];
  name: string;
}

/** 敏感权限清单行（PLT-SCOPE-004）。 */
export interface SensitivePermItem {
  id: number;
  name: string;
  permCode: string;
}

export interface ScopeTemplateView {
  modules: ScopeTemplateModule[];
  sensitivePerms: SensitivePermItem[];
}

/** 模板保存（PLT-SCOPE-003）：提交该模块默认可分配的全集（替换式）。 */
export interface ScopeTemplateUpdateRequest {
  menuIds: number[];
}

/** 敏感清单保存（PLT-SCOPE-004）：整表替换。 */
export interface SensitivePermUpdateRequest {
  items: { name: string; permCode: string }[];
}

/** 三种状态的文案（docs/design 状态横幅口径）。 */
export const TENANT_SCOPE_STATE_TEXT: Record<TenantScopeItemState, string> = {
  ASSIGNABLE: '可分配',
  CLOSED: '未开通',
  NOT_RELEASED: '已开通未放开',
};
