/**
 * E06-T07 / PLT-ORG-006、008、A-WARD-002、C-WARD-003：医院端用户管理类型，与后端
 * `com.crdp.server.system.user.UserAccountAdminService` 视图同构（docs/api/system.md §2）。
 * E06-T09 追加用户选择器（PLT-ORG-012）与权限预览（PLT-ORG-009、A-WARD-009）类型。
 */

import type { RoleScopeType } from './tenant-role';

import { tenantRoleScopeTypeText } from './tenant-role';

/**
 * 用户状态（platform-spec §6 关键字段·用户）。锁定是登录侧（Redis 计数）的瞬时态，
 * 不落在 sys_user.status 上，列表不展示；解锁按钮按 tenant:user:reset 直接可用。
 */
export type UserStatus = 'DISABLED' | 'ENABLED' | 'RESIGNED';

/** 用户行（列表）：手机号只回脱敏值（139****0002），列表不提供明文。 */
export interface TenantUserView {
  createdAt: null | string;
  displayName: string;
  id: number;
  mainDeptName: null | string;
  phoneMasked: null | string;
  roleNames: string[];
  staffNo: null | string;
  status: UserStatus;
  username: string;
}

/** 分页响应（pageNo 从 1 开始，默认 20 条）。 */
export interface TenantUserPage {
  items: TenantUserView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 编辑视图：负责病区（wardIds）/ 负责科室（deptScopeIds）多选引用，全量覆盖保存。 */
export interface TenantUserDetailView extends TenantUserView {
  deptScopeIds: number[];
  mainDeptId: null | number;
  roleIds: number[];
  wardIds: number[];
}

/** 新建：账号与手机号本院唯一（40902）；wardIds / deptScopeIds 可省略（不分配负责范围）。 */
export interface TenantUserCreateRequest {
  deptScopeIds?: number[];
  displayName: string;
  mainDeptId: number;
  phone: string;
  roleIds: number[];
  staffNo?: null | string;
  username: string;
  wardIds?: number[];
}

/**
 * 编辑（全量覆盖）。账号不可改；phone 省略＝保持不变，传值＝换绑（本院唯一校验）。
 */
export interface TenantUserUpdateRequest {
  deptScopeIds?: number[];
  displayName: string;
  mainDeptId: number;
  phone?: null | string;
  roleIds: number[];
  staffNo?: null | string;
  wardIds?: number[];
}

/** 新建结果：初始密码只随本次响应下发一次，首次登录强制改密。 */
export interface TenantUserCreated {
  temporaryPassword: string;
  user: TenantUserDetailView;
}

/** action=ENABLE|DISABLE|RESIGN（离职为终态）；reason 可选（≤200，写审计）。 */
export interface TenantUserStatusRequest {
  action: 'DISABLE' | 'ENABLE' | 'RESIGN';
  reason?: string;
}

/** 重置密码结果：临时密码只在本次响应返回一次。 */
export interface TenantUserResetResult {
  id: number;
  temporaryPassword: string;
  username: string;
}

/** 病区候选（用户编辑页「负责病区」多选；只读启用项）。 */
export interface WardOptionView {
  code: string;
  id: number;
  name: string;
}

// ---- E06-T09 / PLT-ORG-012：用户选择器 ----

/**
 * 用户选择器候选（登录即可调用）：本院启用用户，只返回 id、账号、显示名与主科室名
 * （不含手机号等敏感字段；科室名用于同名人区分）。
 */
export interface UserOptionView {
  deptName: null | string;
  displayName: string;
  id: number;
  username: string;
}

/** 选择器候选项展示文案：`姓名（账号 · 科室）`，无科室时省略科室段。 */
export function userOptionLabel(option: UserOptionView): string {
  return option.deptName
    ? `${option.displayName}（${option.username} · ${option.deptName}）`
    : `${option.displayName}（${option.username}）`;
}

// ---- E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008：用户权限预览 ----

/** 预览里的用户角色行（含停用行，标注状态与类型）。 */
export interface UserRoleBrief {
  code: string;
  id: number;
  name: string;
  roleType: 'BUILT_IN' | 'CUSTOM' | 'TEMPLATE' | string;
  status: 'DISABLED' | 'ENABLED' | string;
}

/** 预览菜单树节点（含按钮）：sourceRoles 为授予该项的启用角色名。 */
export interface MenuPreviewNode {
  children: MenuPreviewNode[];
  id: number;
  moduleCode: null | string;
  parentId: number;
  permCode: null | string;
  sourceRoles: string[];
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU' | string;
}

/** 生效数据范围类型及来源角色（多角色取并集）。 */
export interface ScopeTypeView {
  sourceRoles: string[];
  type: string;
}

/** 可见病区（A-WARD-009）：本人负责病区 × WARD 范围触发，附来源角色。 */
export interface ScopeWardView {
  code: string;
  id: number;
  name: string;
  sourceRoles: string[];
}

/** 负责科室清单：DEPT / CUSTOM_DEPT / RESPONSIBLE_DEPT 触发，附来源角色。 */
export interface ScopeDeptView {
  code: string;
  id: number;
  name: string;
  sourceRoles: string[];
}

/** 权限预览视图（只读）：菜单、权限码与数据范围（含病区）逐项标注来源角色。 */
export interface UserEffectivePermissionsView {
  /** 负责科室清单（无科室类范围或全院时为空数组）。 */
  depts: ScopeDeptView[];
  displayName: string;
  menus: MenuPreviewNode[];
  openedModules: string[];
  permissionCodes: string[];
  roles: UserRoleBrief[];
  /** 任一启用角色为 ALL（租户管理员全院可见，A-WARD-007）。 */
  scopeAll: boolean;
  scopeTypes: ScopeTypeView[];
  status: UserStatus;
  userId: number;
  username: string;
  /** 可见病区清单（WARD 范围触发；全院时为空数组）。 */
  wards: ScopeWardView[];
}

/**
 * 数据范围摘要文案（预览横幅）：全院 → 「全院（全部病区）」；受限 → 范围类型中文名；
 * 完全没有范围类型时提示「暂无数据范围（受控数据将不可见）」。
 */
export function scopeSummary(view: {
  scopeAll: boolean;
  scopeTypes: Array<{ type: string }>;
}): string {
  if (view.scopeAll) return '全院（全部病区）';
  if (view.scopeTypes.length === 0) return '暂无数据范围（受控数据将不可见）';
  return view.scopeTypes
    .map(
      (item) =>
        tenantRoleScopeTypeText(item.type as RoleScopeType) ?? item.type,
    )
    .join('、');
}

/**
 * 把预览菜单树摊平成表格行（缩进层级 + 标题），供权限预览抽屉直接渲染。
 * 按钮（BUTTON）标题加「按钮」前缀标记，便于与菜单区分。
 */
export interface MenuPreviewRow {
  indent: number;
  node: MenuPreviewNode;
  title: string;
}

export function flattenMenuPreview(
  nodes: MenuPreviewNode[],
  indent = 0,
): MenuPreviewRow[] {
  const rows: MenuPreviewRow[] = [];
  for (const node of nodes) {
    rows.push(
      {
        indent,
        node,
        title: node.type === 'BUTTON' ? `${node.title}（按钮）` : node.title,
      },
      ...flattenMenuPreview(node.children, indent + 1),
    );
  }
  return rows;
}

/** 用户状态 → 中文（StatusTag 文案来源；tokens statusTone 已登记启用 / 停用 / 离职）。 */
export const USER_STATUS_TEXT: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
  RESIGNED: '离职',
};

export function userStatusText(status: string): string {
  return USER_STATUS_TEXT[status] ?? status;
}

/** 列表筛选的状态选项（全量）。 */
export const USER_STATUS_OPTIONS: Array<{ label: string; value: UserStatus }> =
  [
    { label: '启用', value: 'ENABLED' },
    { label: '停用', value: 'DISABLED' },
    { label: '离职', value: 'RESIGNED' },
  ];

/**
 * 状态流转（与后端 UserAccountAdminService.ensureTransition 同口径）：
 * 启用 ⇄ 停用、启用 → 离职（终态）；用于操作列决定可用的下一个动作。
 */
export function nextStatusActions(
  status: UserStatus,
): Array<'DISABLE' | 'ENABLE' | 'RESIGN'> {
  if (status === 'ENABLED') {
    return ['DISABLE', 'RESIGN'];
  }
  if (status === 'DISABLED') {
    return ['ENABLE'];
  }
  return [];
}
