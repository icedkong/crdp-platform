/**
 * E06-T07 / PLT-ORG-006、008、A-WARD-002、C-WARD-003：医院端用户管理类型，与后端
 * `com.crdp.server.system.user.UserAccountAdminService` 视图同构（docs/api/system.md §2）。
 */

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
