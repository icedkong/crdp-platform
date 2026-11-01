export type SessionMode = 'hospital' | 'platform';

export interface ApiResponse<T> {
  code: number;
  data: T;
  message: string;
  traceId: string;
}

/**
 * 登录结果（E04-T04 起双端同构）：密码直接通过时 token/tokenType/expiresInSeconds 有值；
 * 需要双因素时 token 为 null，改发 challengeId 与可用方式（methods，当前只有 "SMS"），
 * 第二步 POST /auth/2fa/verify 通过后才换会话令牌。
 */
/** E04-T05：图形验证码取图结果（image 为 PNG 的 data URL，<img :src> 直用）。 */
export interface CaptchaImage {
  captchaId: string;
  image: string;
}

export interface LoginResult {
  challengeId?: null | string;
  expiresInSeconds?: null | number;
  methods?: null | string[];
  token?: null | string;
  tokenType?: null | string;
}

export interface Me {
  displayName: string;
  /**
   * E04-T05 / PLT-AUTH-010：初始 / 重置 / 到期密码为 true——除改密 / 登出 / me 外
   * 的接口一律 40305，前端跳 /password/change（pages.md §1）。
   */
  mustChangePassword?: boolean;
  /**
   * E04-T05 / PLT-AUTH-006：密码到期剩余天数，仅在到期提醒窗口（默认 7 天）内返回，
   * 可能为负（已过期，此时 mustChangePassword 必为 true）。
   */
  passwordExpiresInDays?: null | number;
  permissionVersion: number;
  roles: string[];
  sessionType: 'HOSPITAL' | 'PLATFORM';
  tenantCode: null | string;
  tenantName: null | string;
  userId: number;
  username: string;
}

export interface MenuMeta {
  hideInMenu: boolean;
  icon?: null | string;
  order: number;
  permission?: null | string;
  title: string;
}

export interface MenuNode {
  children: MenuNode[];
  component: string;
  id: number;
  meta: MenuMeta;
  name: string;
  path: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

export interface Hello {
  database: string;
  message: string;
  tenantCode: string;
  tenantName: string;
}

export function authBase(mode: SessionMode) {
  return mode === 'hospital' ? '/api/hospital' : '/api/platform';
}

export {
  cooldownRemaining,
  isTwoFactorChallenge,
  maskPhone,
  pickTwoFactorChallenge,
} from './auth-flow';

export type { TwoFactorChallenge } from './auth-flow';

export {
  ASSET_URL_PATTERN,
  isValidAssetUrl,
  loginLogResultText,
  THEME_COLOR_OPTIONS,
  themeColorHexOf,
} from './brand';
export type {
  BrandSaveRequest,
  BrandView,
  LoginLogView,
  ProfileView,
  TenantBrandView,
  ThemeColorOption,
} from './brand';

export {
  DICT_SOURCE_TEXT,
  DICT_STATUS_TEXT,
  dictSourceText,
  dictStatusText,
  PARAM_LOG_ACTION_TEXT,
  PARAM_VALUE_TYPE_TEXT,
  paramLogActionText,
  paramRangeText,
  paramValueTypeText,
  validateOverrideValue,
} from './config';

export type {
  DictItemSource,
  DictStatus,
  ParamValueType,
  PlatformDictItemSaveRequest,
  PlatformDictItemView,
  PlatformDictSaveRequest,
  PlatformDictView,
  PlatformParamSaveRequest,
  PlatformParamView,
  TenantDictItemSaveRequest,
  TenantDictItemView,
  TenantDictSaveRequest,
  TenantDictTypeView,
  TenantParamLogView,
  TenantParamOverrideRequest,
  TenantParamView,
} from './config';
export {
  EXPIRE_TYPE_TEXT,
  MODULE_LOG_ACTION_TEXT,
  MODULE_SHELF_OPTIONS,
  MODULE_SHELF_TEXT,
  MODULE_SYNC_ACTION_TEXT,
  moduleLogActionText,
  moduleShelfText,
  moduleSyncActionText,
  TENANT_MODULE_NOT_OPENED_TEXT,
  TENANT_MODULE_STATUS_TEXT,
  TENANT_STATUS_OPTIONS,
  TENANT_STATUS_TEXT,
  tenantModuleStatusText,
  tenantStatusText,
} from './enums';
export {
  errorBody,
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from './login-rejection';
export type { LoginRejectionInfo } from './login-rejection';
export { DEPT_TYPE_OPTIONS, ORG_STATUS_TEXT, orgStatusText } from './org';
export type {
  DeptImpactView,
  DeptNodeView,
  DeptSaveRequest,
  DeptView,
  OrgCampusNode,
  OrgImpactView,
  OrgNodeStatus,
  OrgSaveRequest,
  OrgStatusRequest,
  OrgTreeView,
  OrgView,
} from './org';
export {
  PASSWORD_EXPIRY_REMIND_DAYS,
  PASSWORD_HISTORY_COUNT,
  PASSWORD_MIN_CATEGORIES,
  PASSWORD_MIN_LENGTH,
  PASSWORD_VALID_DAYS,
  passwordCategoryCount,
  passwordPolicyViolations,
  passwordRuleItems,
} from './password-policy';

export type {
  PasswordPolicyOptions,
  PasswordRuleItem,
} from './password-policy';
export {
  API_ACTION_TYPE_TEXT,
  API_AUDIT_LEVEL_TEXT,
  API_SENSITIVE_LEVEL_TEXT,
  API_SOURCE_TEXT,
  apiActionTypeText,
  apiAuditLevelText,
  apiSensitiveLevelText,
  apiSourceText,
  MENU_SOURCE_TEXT,
  MENU_SYNC_ACTION_TEXT,
  MENU_TYPE_TEXT,
  menuSourceText,
  menuSyncActionText,
  menuTypeText,
} from './perm-resources';
export type {
  ApiMarkRequest,
  ApiResourceItem,
  ApiSyncOutcome,
  ApiSyncResult,
  MenuCreateRequest,
  MenuResourceNode,
  MenuSyncApplyResult,
  MenuSyncDiffItem,
  MenuSyncDiffResult,
  MenuUpdateRequest,
} from './perm-resources';
export {
  platformAccountStatusText,
  platformRoleStatusText,
  platformRoleViewSummary,
  platformTenantScopeText,
  platformVisibleScopeText,
  validatePlatformAccount,
  validatePlatformRole,
} from './platform-role';

export interface CreateTenantRequest {
  /** 不填由后端自动生成（页面展示建议值可改）。 */
  adminDisplayName?: string;
  adminInitialPassword?: string;
  adminUsername?: string;
  code?: string;
  /** 到期设置（E05-T02 / PLT-TEN-011，向导第 2 步）：整体省略 = 长期有效。 */
  expireAt?: null | string;
  expireType?: 'FIXED' | 'LONG_TERM';
  graceDays?: null | number;
  graceReadOnly?: boolean | null;
  /** 勾选的业务模块（E05-T05 / PLT-MOD-004，向导第 2 步）：base 必开不传。 */
  modules?: string[];
  name: string;
  remindDays?: null | number[];
}

export interface TenantProvisionStep {
  error: null | string;
  status: 'FAILED' | 'SKIPPED' | 'SUCCESS';
  step: string;
}

export interface TenantOnboardingResult {
  code: string;
  steps: TenantProvisionStep[];
  tenantId: number;
}

export interface TenantProvisionView {
  code: string;
  name: string;
  status: string;
  steps: TenantProvisionStep[];
  tenantId: number;
}

/**
 * 到期设置（E05-T02 / PLT-TEN-011）：FIXED 有到期日并按租户配置提醒节点、宽限期天数与宽限期是否只读；
 * LONG_TERM 长期有效，remindDays / graceDays 为空（不提醒、不进入宽限期）。
 */
export interface TenantExpirySettings {
  expireAt?: null | string;
  expireType: 'FIXED' | 'LONG_TERM';
  graceDays?: null | number;
  graceReadOnly?: boolean;
  remindDays?: null | number[];
}

/** 到期设置 / 续期提交（PLT-TEN-011、013）：FIXED 时 expireAt 必填；省略项沿用现值。 */
export interface TenantExpireRequest {
  expireAt?: null | string;
  expireType: 'FIXED' | 'LONG_TERM';
  graceDays?: null | number;
  graceReadOnly?: boolean | null;
  reason: string;
  remindDays?: null | number[];
}

/** 状态变更（PLT-TEN-010）：ENABLE 转正（试用）/恢复（停用），DISABLE 手动停用（启用）。 */
export type TenantStatusAction = 'DISABLE' | 'ENABLE';

/** 状态变更影响面（状态变更对话框）：随访任务未上线时 pendingTaskCount 为空。 */
export interface TenantStatusImpact {
  code: string;
  name: string;
  onlineUserCount: number;
  pendingTaskCount: null | number;
  status: TenantStatus;
  tenantId: number;
}

export interface TenantStatusResult {
  code: string;
  onlineUserCount: number;
  previousStatus: TenantStatus;
  status: TenantStatus;
  tenantId: number;
}

export interface TenantExpireResult {
  changedFields: string[];
  code: string;
  expiry: TenantExpirySettings;
  previousStatus: TenantStatus;
  status: TenantStatus;
  tenantId: number;
}

/** 租户状态（PLT-TEN-010 七态 + 开通中）。 */
export type TenantStatus =
  | 'CANCELLED'
  | 'CANCELLING'
  | 'DISABLED'
  | 'ENABLED'
  | 'GRACE'
  | 'PROVISIONING'
  | 'TRIAL';

/** 已开通模块（列表行内展示）。 */
export interface TenantModuleRef {
  code: string;
  name: string;
}

/** 租户列表行（PLT-TEN-001）：长期有效时 expireAt 为空；用户数取不到时为空。 */
export interface TenantListItem {
  code: string;
  createdAt: string;
  expireAt: null | string;
  expireType: 'FIXED' | 'LONG_TERM';
  id: number;
  modules: TenantModuleRef[];
  name: string;
  shortName: null | string;
  status: TenantStatus;
  userCount: null | number;
}

/** 租户详情（PLT-TEN-009）：电话仅详情返回（编辑场景），列表不返回；到期设置字段见 PLT-TEN-011。 */
export interface TenantDetail {
  code: string;
  contactEmail: null | string;
  contactName: null | string;
  contactPhone: null | string;
  contractNo: null | string;
  createdAt: string;
  creditCode: null | string;
  expireAt: null | string;
  expireType: 'FIXED' | 'LONG_TERM';
  graceDays: null | number;
  graceReadOnly: boolean;
  id: number;
  name: string;
  orgType: null | string;
  region: null | string;
  remark: null | string;
  remindDays: null | number[];
  shortName: null | string;
  status: TenantStatus;
  updatedAt: null | string;
}

/** 信息维护提交（PLT-TEN-009）：编码、状态、到期不在此列。 */
export interface TenantUpdateRequest {
  contactEmail?: null | string;
  contactName?: null | string;
  contactPhone?: null | string;
  contractNo?: null | string;
  creditCode?: null | string;
  name: string;
  orgType?: null | string;
  region?: null | string;
  remark?: null | string;
  shortName?: null | string;
}

/** 列表检索条件（keyword 命中医名名称或编码）。 */
export interface TenantListQuery {
  expireBefore?: string;
  keyword?: string;
  moduleCode?: string;
  pageNo?: number;
  pageSize?: number;
  sort?: string;
  status?: TenantStatus;
}

/** 分页响应（约定 {items,total,pageNo,pageSize}）。 */
export interface PageView<T> {
  items: T[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 模块上架状态（PLT-MOD-001）：下架后不能再被新开通。 */
export type ModuleShelfStatus = 'OFF' | 'ON';

/** 模块目录行的菜单清单（随发版由迁移登记进 plt_menu）。 */
export interface ModuleMenuRef {
  id: number;
  side: 'HOSPITAL' | 'PLATFORM';
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 模块配置项（PLT-MOD-001 目录展示；租户级取值是 PLT-MOD-008）。 */
export interface ModuleConfigItem {
  code: string;
  defaultValue: string;
  description: string;
  name: string;
}

/** 模块版本记录（PLT-MOD-003）：功能版本、迁移版本与变更说明，倒序（最新在前）。 */
export interface ModuleVersionRecord {
  changeNote: string;
  createdAt: string;
  featureVersion: string;
  migrationVersion: string;
}

/** 模块目录行（PLT-MOD-001）。 */
export interface ModuleCatalogItem {
  baseModule: boolean;
  code: string;
  configItems: ModuleConfigItem[];
  dependsOn: string[];
  description: string;
  featureVersion: string;
  menus: ModuleMenuRef[];
  migrationVersion: string;
  name: string;
  permissions: string[];
  status: ModuleShelfStatus;
  syncedAt: null | string;
  versions: ModuleVersionRecord[];
}

/** 维护模块展示信息与上下架（PLT-MOD-002）：只允许改名称、说明、上架状态。 */
export interface ModuleUpdateRequest {
  description?: null | string;
  name: string;
  status?: ModuleShelfStatus | null;
}

/** 手动同步单个模块的结果（PLT-MOD-002）。 */
export interface ModuleSyncOutcome {
  action: 'REGISTERED' | 'UNCHANGED' | 'UPDATED';
  code: string;
  featureVersion: string;
  migrationVersion: string;
}

export interface ModuleSyncResult {
  outcomes: ModuleSyncOutcome[];
}

/** 租户模块开通状态（PLT-MOD-004、005；null = 未开通）。 */
export type TenantModuleStatus =
  | 'CLOSED'
  | 'MAINTENANCE'
  | 'OPEN'
  | 'OPEN_FAILED'
  | 'OPENING';

/** 租户详情 · 模块页签的行（E05-T05）：目录信息 + 该租户的开通痕迹。 */
export interface TenantModuleItem {
  baseModule: boolean;
  closedAt: null | string;
  code: string;
  currentMigrationVersion: string;
  dependsOn: string[];
  description: string;
  featureVersion: string;
  migrationVersion: string;
  name: string;
  openedAt: null | string;
  /** 关闭 / 开通失败原因（留痕）。 */
  reason: null | string;
  /** 上架状态（ON / OFF；下架后不能再被新开通）。 */
  shelfStatus: ModuleShelfStatus;
  /** 该租户的开通状态；null = 未开通。 */
  status: null | TenantModuleStatus;
}

export interface TenantModulesView {
  code: string;
  modules: TenantModuleItem[];
  name: string;
  tenantId: number;
  tenantStatus: TenantStatus;
}

/** 开通 / 关闭结果（PLT-MOD-004、005）。 */
export interface TenantModuleChangeResult {
  migrationVersion: string;
  moduleCode: string;
  previousStatus: null | string;
  status: TenantModuleStatus;
  tenantCode: string;
  tenantId: number;
}

/** 关闭影响评估（PLT-MOD-005）：二次确认前展示。 */
export interface TenantModuleCloseImpact {
  dependentModules: string[];
  moduleCode: string;
  moduleName: string;
  onlineUserCount: number;
  /** fu_task 未建或租户库不可达时为空。 */
  pendingTaskCount: null | number;
  tenantCode: string;
  tenantId: number;
}

/** 模块级配置（PLT-MOD-008）：默认值取模块定义，租户覆盖值优先。 */
export interface TenantModuleConfigItem {
  code: string;
  defaultValue: string;
  description: string;
  effectiveValue: string;
  name: string;
  /** 租户覆盖值；空 = 用默认。 */
  value: null | string;
}

export interface TenantModuleConfig {
  items: TenantModuleConfigItem[];
  moduleCode: string;
  tenantCode: string;
  tenantId: number;
}

export interface TenantModuleConfigUpdateRequest {
  reason?: null | string;
  values: Record<string, string>;
}

/** 开通记录（PLT-MOD-009）：开通 / 关闭（升级 UPGRADE 随 PLT-MOD-007）。 */
export interface ModuleOpenLog {
  action: 'CLOSE' | 'OPEN' | 'UPGRADE';
  createdAt: string;
  detail: null | string;
  id: number;
  moduleCode: string;
  operatorId: null | number;
  reason: null | string;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

export type {
  PlatformAccountResetResult,
  PlatformAccountSaveRequest,
  PlatformAccountView,
  PlatformGrantMenuNode,
  PlatformRoleHolder,
  PlatformRoleSaveRequest,
  PlatformRoleView,
  PlatformRoleViewFields,
  PlatformTenantScope,
  PlatformVisibleScope,
} from './platform-role';
export type { OnlineSessionView } from './session';

export {
  TENANT_ROLE_SCOPE_OPTIONS,
  tenantRoleScopeTypeText,
  tenantRoleTypeHint,
  tenantRoleTypeText,
} from './tenant-role';
export type {
  RoleScopeType,
  RoleStatus,
  RoleType,
  TenantRoleCopyRequest,
  TenantRoleFromTemplateRequest,
  TenantRoleMembersView,
  TenantRoleMemberView,
  TenantRoleMenuNode,
  TenantRoleMenusView,
  TenantRoleSaveRequest,
  TenantRoleScopeSaveRequest,
  TenantRoleScopeView,
  TenantRoleStatusRequest,
  TenantRoleView,
} from './tenant-role';

export { TENANT_SCOPE_STATE_TEXT } from './tenant-scope';
export type {
  ScopeTemplateItem,
  ScopeTemplateModule,
  ScopeTemplateUpdateRequest,
  ScopeTemplateView,
  SensitivePermItem,
  SensitivePermUpdateRequest,
  TenantScopeAffectedRole,
  TenantScopeChangeResult,
  TenantScopeImpactItem,
  TenantScopeItem,
  TenantScopeItemState,
  TenantScopeLog,
  TenantScopeModule,
  TenantScopePreview,
  TenantScopeUpdateRequest,
  TenantScopeView,
} from './tenant-scope';

export {
  flattenMenuPreview,
  nextStatusActions,
  scopeSummary,
  USER_STATUS_OPTIONS,
  USER_STATUS_TEXT,
  userOptionLabel,
  userStatusText,
} from './tenant-user';
export type {
  MenuPreviewNode,
  MenuPreviewRow,
  ScopeDeptView,
  ScopeTypeView,
  ScopeWardView,
  TenantUserCreated,
  TenantUserCreateRequest,
  TenantUserDetailView,
  TenantUserPage,
  TenantUserResetResult,
  TenantUserStatusRequest,
  TenantUserUpdateRequest,
  TenantUserView,
  UserEffectivePermissionsView,
  UserOptionView,
  UserRoleBrief,
  UserStatus,
  WardOptionView,
} from './tenant-user';

export { menuNodesToRoutes, menuNodeToRoute, toIconifyIcon } from './vben-menu';
export type { VbenMenuRoute } from './vben-menu';
