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
  EXPIRE_TYPE_TEXT,
  TENANT_STATUS_OPTIONS,
  TENANT_STATUS_TEXT,
  tenantStatusText,
} from './enums';
export {
  errorBody,
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from './login-rejection';

export type { LoginRejectionInfo } from './login-rejection';
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

export { menuNodesToRoutes, menuNodeToRoute, toIconifyIcon } from './vben-menu';
export type { VbenMenuRoute } from './vben-menu';

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
