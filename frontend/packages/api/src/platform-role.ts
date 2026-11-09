/**
 * E06-T04 / PLT-ROLE-001..003、E20-T01 / PLT-VIEW-005、006：平台角色与平台账号类型，与后端
 * `com.crdp.server.platform.PlatformRoleService` / `PlatformAccountService` 视图同构
 * （docs/api/platform.md §5）。
 *
 * 账号类型模型（PLT-ROLE-001）：平台账号存平台库、只能绑平台角色；租户账号存各自租户库。
 * 视角六字段（PLT-VIEW-005）随平台角色维护；租户范围「指定租户」须带非空清单（E20-T01 起可选，OQ-48 收敛）；
 * 账号负责租户（PLT-VIEW-006）见 `responsible-tenants` 接口类型（本文件 AccountView 之外）。
 */

/** 可见范围（PLT-VIEW-005）：仅配置类 / 配置类 + 业务数据。 */
export type PlatformVisibleScope = 'CONFIG_AND_BUSINESS' | 'CONFIG_ONLY';

/** 租户范围（PLT-VIEW-005）：全部 / 负责的租户 / 指定租户（清单必填非空）。 */
export type PlatformTenantScope = 'ALL' | 'RESPONSIBLE' | 'SPECIFIED';

/**
 * 角色上的租户视角权限（平台规格 §8「关键字段 · 平台角色视角权限」）。 configResources=null
 * 表示全部配置类资源（E20-T06 / PLT-VIEW-013，已确认）。
 */
export interface PlatformRoleViewFields {
  canConfigure: boolean;
  canEditBusiness: boolean;
  canEnter: boolean;
  canResetPassword: boolean;
  configResources: null | string[];
  tenantScope: PlatformTenantScope;
  visibleScope: PlatformVisibleScope;
}

/** 平台角色（预置 + 自定义）。menuIds 与 specifiedTenantIds 仅详情返回；持有人数为绑定账号数（含停用账号）。 */
export interface PlatformRoleView {
  builtIn: boolean;
  code: string;
  createdAt: null | string;
  description: null | string;
  holderCount?: number;
  id: number;
  menuIds?: number[];
  name: string;
  specifiedTenantIds?: number[];
  status: 'DISABLED' | 'ENABLED';
  updatedAt: null | string;
  view: PlatformRoleViewFields;
}

/** 新建 / 维护平台角色。预置角色权限（菜单、视角字段、状态）可调整；不提供删除。 viewSpecifiedTenantIds：租户范围为 SPECIFIED 时必填非空（全部存在的租户）。 */
export interface PlatformRoleSaveRequest {
  description?: null | string;
  menuIds: number[];
  name: string;
  status?: 'DISABLED' | 'ENABLED';
  viewCanConfigure?: boolean;
  viewCanEditBusiness?: boolean;
  viewCanEnter?: boolean;
  viewCanResetPassword?: boolean;
  /** 代为配置资源范围（E20-T06 / PLT-VIEW-013）：空 / 缺省 = 全部配置类资源（已确认）。 */
  viewConfigResources?: null | string[];
  viewSpecifiedTenantIds?: number[];
  viewTenantScope?: PlatformTenantScope;
  viewVisibleScope?: PlatformVisibleScope;
}

/** 角色持有人（platform-spec §1 主要页面「查看持有人」）。 */
export interface PlatformRoleHolder {
  accountId: number;
  accountStatus: string;
  displayName: string;
  username: string;
}

/** 代为配置资源目录项（GET /api/platform/roles/view-config-resources；权限码资源段 + 文案）。 */
export interface PlatformViewConfigResource {
  code: string;
  label: string;
}

/** 可授权的平台端启用菜单 / 按钮（平铺，前端组树）。 */
export interface PlatformGrantMenuNode {
  id: number;
  parentId: number;
  permCode: null | string;
  sortNo: number;
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 平台账号视图：手机号只回脱敏值（不回明文）。 */
export interface PlatformAccountView {
  createdAt: null | string;
  displayName: string;
  id: number;
  phoneMasked: null | string;
  roleIds: number[];
  roleNames: string[];
  status: 'DISABLED' | 'ENABLED';
  username: string;
}

/** 新建 / 维护平台账号。新建必填 username/phone/password/roleIds；维护不含密码（走重置）。 */
export interface PlatformAccountSaveRequest {
  displayName: string;
  password?: string;
  phone?: string;
  roleIds: number[];
  status?: 'DISABLED' | 'ENABLED';
  username?: string;
}

/** 重置密码结果：临时密码只在本次响应返回一次。 */
export interface PlatformAccountResetResult {
  id: number;
  temporaryPassword: string;
  username: string;
}

/** 可见范围文案。 */
export function platformVisibleScopeText(scope: string): string {
  if (scope === 'CONFIG_AND_BUSINESS') return '配置类 + 业务数据';
  if (scope === 'CONFIG_ONLY') return '仅配置类';
  return scope;
}

/** 租户范围文案。 */
export function platformTenantScopeText(scope: string): string {
  if (scope === 'ALL') return '全部租户';
  if (scope === 'RESPONSIBLE') return '负责的租户';
  if (scope === 'SPECIFIED') return '指定租户';
  return scope;
}

/** 角色状态文案（StatusTag 用，与模块上架状态同风格）。 */
export function platformRoleStatusText(status: string): string {
  if (status === 'ENABLED') return '已启用';
  if (status === 'DISABLED') return '已停用';
  return status;
}

/** 账号状态文案。 */
export function platformAccountStatusText(status: string): string {
  if (status === 'ENABLED') return '已启用';
  if (status === 'DISABLED') return '已停用';
  return status;
}

/**
 * 租户视角权限摘要（角色列表 / 详情页签顶部一行展示）。
 * 例：「租户视角：可进入 · 配置类 + 业务数据 · 全部租户 · 可代为配置 · 可代重置密码」；不可进入显示「不可进入租户视角」。
 */
export function platformRoleViewSummary(view: PlatformRoleViewFields): string {
  if (!view.canEnter) return '不可进入租户视角';
  const parts = [
    '可进入',
    platformVisibleScopeText(view.visibleScope),
    platformTenantScopeText(view.tenantScope),
  ];
  if (view.canConfigure) {
    parts.push(
      view.configResources === null
        ? '可代为配置（全部资源）'
        : `可代为配置（${view.configResources.length} 类资源）`,
    );
  }
  if (view.canResetPassword) parts.push('可代重置密码');
  if (view.canEditBusiness) parts.push('可编辑业务数据');
  return parts.join(' · ');
}

/**
 * 平台角色保存校验（前端先行，与后端 validateCommon / validateViewFields 同口径）：
 * 名称必填 ≤64、说明 ≤255、视角字段枚举、租户范围 SPECIFIED 须带非空指定租户清单（E20-T01，OQ-48 收敛）。
 * 返回错误信息（空数组 = 通过）。
 */
export function validatePlatformRole(model: {
  description?: null | string;
  name: string;
  viewSpecifiedTenantIds?: number[];
  viewTenantScope?: PlatformTenantScope;
  viewVisibleScope?: PlatformVisibleScope;
}): string[] {
  const errors: string[] = [];
  if (!model.name.trim()) {
    errors.push('角色名称必填');
  } else if (model.name.trim().length > 64) {
    errors.push('角色名称过长（最多 64 个字符）');
  }
  if ((model.description ?? '').trim().length > 255) {
    errors.push('角色说明过长（最多 255 个字符）');
  }
  if (
    model.viewVisibleScope !== undefined &&
    model.viewVisibleScope !== 'CONFIG_ONLY' &&
    model.viewVisibleScope !== 'CONFIG_AND_BUSINESS'
  ) {
    errors.push('可见范围只允许 仅配置类 / 配置类 + 业务数据');
  }
  if (
    model.viewTenantScope !== undefined &&
    model.viewTenantScope !== 'ALL' &&
    model.viewTenantScope !== 'RESPONSIBLE' &&
    model.viewTenantScope !== 'SPECIFIED'
  ) {
    errors.push('租户范围只允许 全部 / 负责的租户 / 指定租户');
  }
  if (model.viewTenantScope === 'SPECIFIED') {
    const count = model.viewSpecifiedTenantIds?.length ?? 0;
    if (count === 0) {
      errors.push('租户范围「指定租户」须选择至少一个租户');
    }
  }
  return errors;
}

/**
 * 平台账号保存校验（与后端同口径）：账号名小写字母开头 2–64；手机号 11 位国内手机号；
 * 姓名必填 ≤64；新建时密码与角色必填（密码策略详见 password-policy）。
 */
export function validatePlatformAccount(
  model: {
    displayName: string;
    password?: string;
    phone?: string;
    roleIds: number[];
    username?: string;
  },
  mode: 'create' | 'update',
): string[] {
  const errors: string[] = [];
  if (mode === 'create') {
    const username = (model.username ?? '').trim();
    if (!username) {
      errors.push('账号名必填');
    } else if (!/^[a-z][a-z0-9._-]{1,63}$/.test(username)) {
      errors.push(
        '账号名须以小写字母开头，只含小写字母、数字、点、下划线、连字符，长度 2–64',
      );
    }
    if (!model.password) {
      errors.push('初始密码必填');
    }
  }
  const displayName = model.displayName.trim();
  if (!displayName) {
    errors.push('姓名必填');
  } else if (displayName.length > 64) {
    errors.push('姓名过长（最多 64 个字符）');
  }
  const phone = (model.phone ?? '').trim();
  if (mode === 'create' && !phone) {
    errors.push('手机号必填（平台账号强制绑定，用于双因素认证）');
  } else if (phone && !/^1[3-9]\d{9}$/.test(phone)) {
    errors.push('手机号格式不正确（11 位国内手机号）');
  }
  if (model.roleIds.length === 0) {
    errors.push('至少选择一个平台角色');
  }
  return errors;
}
