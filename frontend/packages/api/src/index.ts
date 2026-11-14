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
  /**
   * 会话类型：医院端 / 平台端登录，或 TENANT_VIEW——平台账号进入租户视角的查看会话
   * （E20-T04 / PLT-VIEW-002，医院端外壳按视角模式渲染横幅与水印）。
   */
  sessionType: 'HOSPITAL' | 'PLATFORM' | 'TENANT_VIEW';
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

// E13-T06 高级检索条件构造器与保存的个人筛选（A-PAT-014、A-PAT-015、A-NFR-009、C-PAT-013）
export {
  conditionPreview,
  conditionRowCount,
  conditionRowText,
  emptyConditionGroup,
  emptyConditionRow,
  isConditionGroup,
  isConditionGroupComplete,
  isRowComplete,
  OPERATOR_LABELS,
  pruneConditionGroup,
  withGroupAdded,
  withItemMoved,
  withItemRemoved,
  withItemReplaced,
  withRowAdded,
} from './advanced-search';

export type {
  AdvancedOptionValue,
  AdvancedSearchField,
  ConditionGroup,
  ConditionRow,
  SavedFilter,
  SavedFilterPayload,
} from './advanced-search';

// E05-T11 系统公告（PLT-CFG-009）
export {
  announcementStatusText,
  recentAnnouncementCount,
} from './announcement';
export type {
  AnnouncementScope,
  AnnouncementStatus,
  AnnouncementTenantRef,
  HospitalAnnouncement,
  PlatformAnnouncementPage,
  PlatformAnnouncementPublishRequest,
  PlatformAnnouncementView,
} from './announcement';
// E08-T04 审计中心（PLT-AUD-005~007）
export * from './audit';

export {
  cooldownRemaining,
  isTwoFactorChallenge,
  maskPhone,
  pickTwoFactorChallenge,
} from './auth-flow';

export type { TwoFactorChallenge } from './auth-flow';

// E13-T02 基线分节、基线锁定与解锁（A-PAT-003、A-PAT-005、A-PAT-006）
export {
  BASELINE_LOCKED_TOOLTIP,
  baselineDraftRecordKey,
  baselineLockBanner,
  baselineProgressText,
  baselineReasonError,
  baselineSectionStatusText,
  canEditBaseline,
  sectionDisplaySchema,
  showBaselineLock,
  showBaselineUnlock,
  sliceSchemaCategory,
} from './baseline';
export type {
  BaselineCategoryProgress,
  BaselineDraftPayload,
  BaselineDraftSavedView,
  BaselineFieldHistoryRow,
  BaselineLockInfo,
  BaselineLockPayload,
  BaselineSection,
  BaselineSectionSavePayload,
  BaselineSectionSaveResult,
  BaselineSectionStatus,
  PatientBaseline,
} from './baseline';

export {
  BATCH_SKIP_REASON_TEXT,
  batchOutcomeText,
  countSkipByReason,
  doctorOptionLabel,
  filterDoctorsByWards,
} from './batch-operation';

export type {
  BatchOutcomeItem,
  BatchResult,
  DoctorOption,
  DoctorOptions,
} from './batch-operation';

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

// E17-T01 对比标红规则配置（A-FU-027 / C-FU-011，docs/api/followup.md §4）
export {
  COMPARE_DIRECTION_LABELS,
  COMPARE_OP_LABELS,
  COMPARE_RULE_TYPE_LABELS,
  compareTargetText,
  conditionText,
  supportsThreshold,
  thresholdBounds,
  thresholdKeyOf,
  toCompareRuleFormModel,
  toCompareRuleUpdate,
  validateCompareRuleModel,
} from './compare-rule';
export type {
  CompareRule,
  CompareRuleParams,
  CompareRuleType,
  CompareRuleUpdate,
} from './compare-rule';
// E24-T07 真机兼容：structuredClone / Object.hasOwn 的全内核替身（微信 XWeb 等
// 旧内核没有这两个 API，调用即 TypeError）
export { deepCloneJson, hasOwnProp } from './compat';

export {
  DICT_LOG_ACTION_TEXT,
  DICT_SOURCE_TEXT,
  DICT_STATUS_TEXT,
  dictLogActionText,
  dictLogTargetText,
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
  TenantDictLogView,
  TenantDictSaveRequest,
  TenantDictTypeView,
  TenantParamLogView,
  TenantParamOverrideRequest,
  TenantParamView,
} from './config';
// E12-T04 配置模板管理页与导入预览（A-FLD-014，接口随 E12-T01/T02/T03 已交付）
export * from './config-template';
// E15-T04 沟通记录与录音（A-FU-006、A-FU-025、C-FU-019；pages.md §4.8）
export {
  CONTACT_METHOD_OPTIONS,
  CONTACT_METHOD_TEXT,
  CONTACT_RESULT_OPTIONS,
  CONTACT_RESULT_TEXT,
  contactFollowupHint,
  contactMethodText,
  contactResultText,
} from './contact';
export type {
  ContactAudioFile,
  ContactLogCreateRequest,
  ContactLogCreateResult,
  ContactLogPage,
  ContactLogRow,
  ContactLogUpdateRequest,
  ContactMethod,
  ContactResult,
} from './contact';
// E19-T02 首页指标卡与趋势图（A-DASH-001、A-DASH-002、C-DASH-001）
export * from './dashboard';
export {
  DEPT_IMPORT_COLUMNS,
  deptImportActionText,
  deptImportSummary,
  failedRows,
  isXlsxFile,
} from './dept-import';
export type {
  DeptImportAction,
  DeptImportReport,
  DeptImportRowResult,
} from './dept-import';
export {
  EXPIRE_TYPE_TEXT,
  MODULE_LOG_ACTION_TEXT,
  MODULE_SHELF_OPTIONS,
  MODULE_SHELF_TEXT,
  MODULE_SYNC_ACTION_TEXT,
  moduleLogActionText,
  moduleShelfText,
  moduleSyncActionText,
  PATIENT_STATUS_TEXT,
  PLAN_STATUS_TEXT,
  planStatusText,
  SURVIVAL_STATUS_TEXT,
  survivalStatusText,
  TENANT_MODULE_NOT_OPENED_TEXT,
  TENANT_MODULE_STATUS_TEXT,
  TENANT_STATUS_OPTIONS,
  TENANT_STATUS_TEXT,
  tenantModuleStatusText,
  tenantStatusText,
} from './enums';
export type { FileDownloadGrant, FilePreviewGrant } from './file';
// E16-T03 上传策略（本院生效的格式 / 大小 / 每份报告单文件数上限）
export type { FileFormatPolicy, FileUploadPolicy } from './file';
export { previewGrantExpired } from './file';
// E11-T01 字段字典（A-FLD-001、A-FLD-002、C-FLD-001：显示名与编码分离，字段可启停）
export {
  FIELD_DICT_STATUS_TEXT,
  FIELD_STORAGE_TEXT,
  fieldDictStatusText,
  fieldRangeText,
  fieldStorageText,
  FORM_FIELD_TYPE_TEXT,
  FORM_USAGE_TEXT,
  formFieldTypeText,
  formUsageText,
  isOptionFieldType,
  isValidFieldRange,
  matchFieldKeyword,
} from './form';
export type {
  FieldDerivedDef,
  FieldDictLogView,
  FieldDictStatus,
  FieldOption,
  FieldOptionUpdate,
  FieldRange,
  FieldStorage,
  FormFieldDef,
  FormFieldType,
  FormFieldUpdateRequest,
  FormSchema,
  FormSchemaCategory,
  FormSchemaField,
  FormSummary,
  FormUsage,
} from './form';
// E21-T01 植入记录与电极（C-PAT-004、C-PAT-005）+ E21-T02 更换 / 升级 / 电极修正 / 取出（C-PAT-006）
export {
  activeLeadsOf,
  buildExplantPayload,
  buildImplantPayload,
  buildImplantUpdatePayload,
  buildReplacePayload,
  emptyExplantForm,
  emptyImplantForm,
  emptyReplaceForm,
  generatorUnchanged,
  IMPLANT_DEVICE_LEADLESS,
  IMPLANT_INDICATION_OTHER,
  IMPLANT_LEAD_ACTION_ABANDON,
  IMPLANT_LEAD_ACTION_EXTRACT,
  IMPLANT_LEAD_ACTION_KEEP,
  IMPLANT_LEAD_STATUS_ACTIVE,
  IMPLANT_REPLACE_TYPES,
  IMPLANT_TYPE_FIRST,
  IMPLANT_TYPE_LEAD_REVISION,
  IMPLANT_TYPE_OTHER,
  IMPLANT_TYPE_REPLACE,
  IMPLANT_TYPE_UPGRADE,
  implantCardLines,
  implantFormOf,
  leadPositionOptions,
  leadsAllowedForDevice,
  leadSummary,
  validateCRegistration,
  validateExplantForm,
  validateImplantForm,
  validateReplaceForm,
} from './implant';
export type {
  ImplantExplantForm,
  ImplantExplantPayload,
  ImplantForm,
  ImplantFormErrors,
  ImplantLeadActionForm,
  ImplantLeadForm,
  ImplantLeadPositionGroup,
  ImplantLeadView,
  ImplantListResult,
  ImplantNewLeadForm,
  ImplantOptionItem,
  ImplantOptions,
  ImplantPayload,
  ImplantRecordView,
  ImplantReplaceForm,
  ImplantReplacePayload,
  ImplantUpdatePayload,
} from './implant';
export {
  errorBody,
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from './login-rejection';
export type { LoginRejectionInfo } from './login-rejection';
// E09-T02 站内信收件箱（A-NTF-002、C-NTF-002）
export { noticeTargetRoute } from './notify';
export type {
  NoticeMarkedCount,
  NoticeMessage,
  NoticeMessagePage,
  NoticeRouteLocation,
  NoticeTarget,
  NoticeTargetType,
  NoticeUnreadCount,
} from './notify';
// E09-T04 通知设置（A-NTF-001、C-NTF-001）
export {
  NOTIFY_CHANNEL_LABELS,
  NOTIFY_RECIPIENT_LABELS,
  toNotifyRuleFormModel,
  toNotifyRuleUpdate,
} from './notify';
export type {
  NotifyChannel,
  NotifyRecipientOption,
  NotifyRule,
  NotifyRuleFormModel,
  NotifyRuleUpdate,
  NotifySmsTemplate,
} from './notify';
// E09-T07 发送记录与个人通知偏好（A-NTF-006、A-NTF-007）
export { NOTIFY_SEND_STATUS_LABELS } from './notify';

export type {
  NotifyPrefOption,
  NotifyPrefUpdate,
  NotifyPrefView,
  NotifySendLog,
  NotifySendLogPage,
  NotifySendLogSearch,
  NotifySendStatus,
  NotifySettings,
  NotifySettingsUpdate,
} from './notify';
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
  buildPlaintextRevealRequest,
  normalizePatientPlainField,
  normalizePatientPlainScene,
  PATIENT_PLAIN_FIELDS,
} from './patient';
export type {
  PatientPlainField,
  PatientPlaintextRequest,
  PatientPlaintextResult,
  PatientPlaintextScene,
} from './patient';
export {
  buildPatientExportPayload,
  PATIENT_DELETE_REASON_MAX,
  PATIENT_EXPORT_REASON_MAX,
  PATIENT_EXPORT_VALUE_MODE_OPTIONS,
  validatePatientDeleteReason,
  validatePatientExportForm,
} from './patient-export';
export type {
  PatientExportCondition,
  PatientExportJob,
  PatientExportJobPage,
  PatientExportRequest,
  PatientExportScope,
  PatientExportSubmitted,
  PatientExportValueMode,
} from './patient-export';
// E18-T06 患者历史数据导入向导（A-PAT-008、A-PAT-018）
export {
  canConfirmImport,
  IMPORT_POLL_INTERVAL_MS,
  importCommitPercent,
  importFileSizeText,
  importResultSummary,
  importStatusColor,
  importStatusText,
  importStepStatus,
  importWizardStep,
  shouldPollImport,
  validateImportConfirmForm,
  validateImportUploadFile,
} from './patient-import';
export type {
  ImportBatch,
  ImportCommitStats,
  ImportConfirmRequest,
  ImportDuplicateStrategy,
  ImportIssueItem,
  ImportIssuePage,
  ImportJobStatus,
  ImportJobView,
  ImportLayout,
  ImportStepStatus,
  ImportWizardStep,
} from './patient-import';
// E13-T05 档案概览与患者时间轴（A-PAT-004、A-PAT-013、C-PAT-010）
// E21-T22 器械卡片（deviceText / deviceEriText）与待录入扫描件数（pendingScanText）
export {
  completenessText,
  contactSlotText,
  contactSourceText,
  deviceEriText,
  deviceText,
  genderText,
  latestFollowupText,
  nextVisitText,
  pendingScanText,
  statusSourceText,
  TIMELINE_PLANNED_TEXT,
  timelineEntryLabel,
  timelineProgressText,
} from './patient-overview';
export type {
  PatientContactInfo,
  PatientContactVersion,
  PatientDeviceCard,
  PatientLatestFollowup,
  PatientLostView,
  PatientNextVisit,
  PatientOverview,
  PatientStatusHistoryRow,
  PatientTimeline,
  PatientTimelineEntry,
  PatientTimelineKind,
} from './patient-overview';
export {
  applyRegistrationDefaults,
  buildDuplicateCheckPayload,
  buildRegistrationPayload,
  canCheckDuplicate,
  DISCHARGE_STATUS_TEXT,
  dischargeStatusText,
  duplicateNoticeOf,
  validateRegistration,
  visitNoError,
  visitNoHelpText,
} from './patient-registration';
export type {
  DuplicateNotice,
  DuplicatePatientSummary,
  PatientCreatedResult,
  PatientDuplicateCheckResult,
  PatientRegistrationForm,
  PatientRegistrationOptions,
  PatientRegistrationPayload,
  RegistrationDefaults,
  RegistrationErrors,
  RegistrationOptionItem,
  RegistrationWardOption,
  VisitNoRule,
} from './patient-registration';
export {
  buildPatientSearchPayload,
  completenessDrillOfQuery,
  emptyPatientFilters,
  hasActiveFilters,
  isKeywordValid,
  KEYWORD_MAX_LENGTH,
  patientEmptyText,
  patientStatusOfQuery,
  patientStatusOptions,
} from './patient-search';

export type {
  CompletenessDrill,
  PatientFilterOptions,
  PatientFilterState,
  PatientSearchPage,
  PatientSearchPayload,
  PatientSearchRow,
  PatientSortSpec,
  PatientWardOption,
} from './patient-search';
export {
  TREND_BATTERY_PARAMS,
  TREND_CHAMBER_OPTIONS,
  TREND_KIND_OPTIONS,
  TREND_PARAM_CATALOG,
  trendKindParamKeys,
  trendSeriesFor,
  trendTableRows,
  trendVisitDates,
} from './patient-trend';

export type {
  ParamTrendChamber,
  ParamTrendKind,
  ParamTrendPoint,
  ParamTrendRow,
  ParamTrendSeries,
  ParamTrendView,
  SelectedTrendSeries,
} from './patient-trend';

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
  RESOURCE_CHANGE_ACTION_TEXT,
  RESOURCE_CHANGE_CHANNEL_TEXT,
  RESOURCE_CHANGE_TYPE_TEXT,
  resourceChangeActionText,
  resourceChangeChannelText,
  resourceChangeTypeText,
} from './perm-resources';

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
  /**
  /** 初始存储上限（字节；E05-T09 / PLT-TEN-014）：省略 = 不限；达上限禁止上传。 */
  storageLimitBytes?: null | number;
  /**
   * 初始化所选配置模板（E12-T05 / A-FLD-014「新建租户时选择模板初始化」，向导第 2 步）：
   * 省略 = 不用模板（开通 config-template 步骤记 SKIPPED）；未知模板 40400。
   */
  templateId?: number;
  /**
   * 初始用户数上限（E05-T09 / PLT-TEN-014，向导第 2 步）：省略 = 不限；
   * 上限按「启用中用户数」拦截新建与启用（PLT-ORG-010）。
   */
  userLimit?: null | number;
}

/**
 * 配额视图（E05-T09 / PLT-TEN-014）：上限（null=不限）与实时用量；用量为 null 表示租户库
 * 暂不可达（不阻塞展示）。用量达 80% 预警、达到上限即满额（*LimitReached）。
 */
export interface TenantQuotaView {
  enabledUserCount: null | number;
  storageLimitBytes: null | number;
  storageLimitReached: boolean;
  storageUsedBytes: null | number;
  storageWarning: boolean;
  userLimit: null | number;
  userLimitReached: boolean;
  userWarning: boolean;
}

/** 配额维护提交（PLT-TEN-014）：null / 省略 = 不限（清除上限）；上限不强制高于当前用量。 */
export interface TenantQuotaUpdateRequest {
  storageLimitBytes?: null | number;
  userLimit?: null | number;
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

/** 租户列表行（PLT-TEN-001）：长期有效时 expireAt 为空；用户数取不到时为空。 viewEnterable：当前平台账号能否进入该租户视角（PLT-VIEW-001，E20-T01）。 */
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
  viewEnterable: boolean;
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
  viewEnterable: boolean;
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

// ---------------------------------------------------------------- 租户注销与数据处理（E05-T08 / PLT-TEN-018..021）

/** 注销流程阶段（plt_tenant_cancel.stage）。 */
export type TenantCancelStage =
  | 'ACKED'
  | 'COOLING'
  | 'DESTROYED'
  | 'EXPORTED'
  | 'PENDING_APPROVAL'
  | 'REJECTED'
  | 'REVOKED';

/** 注销流程附件（医院书面确认 / 迁出包签收单）。 */
export interface TenantCancelFileView {
  fileName: string;
  id: number;
  kind: 'ACK' | 'CONFIRMATION';
  sizeBytes: number;
  uploadedAt: string;
}

/** 注销流程主体（阶段、申请、审批、冷静期、撤销）。 */
export interface TenantCancelFlowView {
  appliedAt: null | string;
  appliedByName: null | string;
  approvedAt: null | string;
  approvedByName: null | string;
  approveNote: null | string;
  confirmationFile: null | TenantCancelFileView;
  coolingUntil: null | string;
  reason: null | string;
  revokedAt: null | string;
  revokedByName: null | string;
  revokeReason: null | string;
  stage: null | TenantCancelStage;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
  tenantStatus: TenantStatus;
}

/** 法定保留数据登记 / 封存条目（PLT-TEN-021）。 */
export interface TenantRetentionView {
  certificateId: null | number;
  destroyedAt: null | string;
  id: number;
  legalBasis: string;
  retainUntil: string;
  scope: string;
  sealedAt: null | string;
  sealedSha256: null | string;
  status: 'DESTROYED' | 'REGISTERED' | 'SEALED';
  tables: string[];
}

/** 标准迁出包行（PLT-TEN-019）。 */
export interface TenantExportView {
  ackedAt: null | string;
  ackFileName: null | string;
  ackNote: null | string;
  downloadCount: number;
  expiresAt: string;
  fileCount: number;
  generatedAt: string;
  id: number;
  maxDownloads: number;
  packageNo: string;
  sha256: string;
  sizeBytes: number;
  tableCount: number;
  totalRows: number;
}

/** 数据销毁证明行（PLT-TEN-020；TENANT 整体销毁 / RETENTION 保留期满销毁）。 */
export interface TenantCertificateView {
  certNo: string;
  executedAt: string;
  executedByName: string;
  id: number;
  kind: 'RETENTION' | 'TENANT';
  reviewedByName: string;
  tenantCode: string;
  tenantName: string;
}

/** 注销与数据处理页总览。 */
export interface TenantCancelOverview {
  certificates: TenantCertificateView[];
  exports: TenantExportView[];
  flow: TenantCancelFlowView;
  retentionItems: TenantRetentionView[];
}

/** 发起注销申请（PLT-TEN-018）：原因 + 医院书面确认 + 法定保留清单。 */
export interface TenantCancelApplyRequest {
  confirmationFileId: number;
  reason: string;
  retentionItems: TenantRetentionItemInput[];
}

/** 法定保留清单条目（登记输入）。 */
export interface TenantRetentionItemInput {
  legalBasis: string;
  retainUntil: string;
  scope: string;
}

/** 审批注销：APPROVE 通过（注销中 + 冷静期）/ REJECT 驳回。 */
export interface TenantCancelApproveRequest {
  decision: 'APPROVE' | 'REJECT';
  note?: string;
}

/** 保存法定保留清单（整体替换未封存项）。 */
export interface TenantRetentionSaveRequest {
  items: TenantRetentionSaveItem[];
}

/** 保留项保存条目（可带封存时导出的租户库表，逗号分隔）。 */
export interface TenantRetentionSaveItem {
  legalBasis: string;
  retainUntil: string;
  scope: string;
  tablesCsv?: string;
}

/** 执行销毁 / 保留期满销毁：复核人（另一名平台管理员）+ 原因。 */
export interface TenantDestroyRequest {
  reason: string;
  reviewerId: number;
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
  ResourceChangeAction,
  ResourceChangeChannel,
  ResourceChangeLogItem,
  ResourceChangeLogPage,
  ResourceChangeLogQuery,
  ResourceChangeType,
} from './perm-resources';
// E14-T02 随访计划配置（docs/api/followup.md §1；页面 pages.md §8）
export {
  PLAN_ENROLL_MODE_TEXT,
  PLAN_ENROLLMENT_STATUS_TEXT,
  PLAN_LOG_ACTION_TEXT,
  PLAN_NODE_BASIS_TEXT,
  PLAN_OFFSET_UNIT_TEXT,
  PLAN_OWNER_TYPE_TEXT,
  PLAN_PASSED_WINDOW_TEXT,
  PLAN_REMIND_UNIT_TEXT,
  PLAN_REPEAT_MODE_TEXT,
  PLAN_START_DATE_FIELD_OPTIONS,
  PLAN_START_DATE_FIELD_TEXT,
  PLAN_TASK_TYPE_TEXT,
  planCycleText,
  planEnrollmentStatusText,
  planLogActionText,
  planStartDateFieldText,
} from './plan';

export type {
  PlanCreateRequest,
  PlanCycleType,
  PlanDetailView,
  PlanEnrollmentPage,
  PlanEnrollmentPatientView,
  PlanEnrollmentStatus,
  PlanEnrollMode,
  PlanLogAction,
  PlanLogsView,
  PlanLogView,
  PlanNodeBasis,
  PlanNodeInput,
  PlanNodeView,
  PlanOffsetUnit,
  PlanOwnerType,
  PlanPassedWindowAction,
  PlanRemindUnit,
  PlanRepeatMode,
  PlanSaveNodesRequest,
  PlanStartDateField,
  PlanStatus,
  PlanTaskType,
  PlanUpdateRequest,
  PlanView,
} from './plan';
export {
  platformAccountStatusText,
  platformRoleStatusText,
  platformRoleViewSummary,
  platformTenantScopeText,
  platformVisibleScopeText,
  validatePlatformAccount,
  validatePlatformRole,
} from './platform-role';

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
// E04-T08 / PLT-AUTH-012：敏感操作二次认证（明文查看、导出、下载、重置密码）
export {
  isReauthCancelled,
  isReauthRequired,
  REAUTH_HEADER,
  REAUTH_REQUIRED_CODE,
  ReauthCancelledError,
  reauthHeaders,
  ReauthTokenKeeper,
  runWithReauth,
} from './reauth';

export type { ReauthFlowDeps, ReauthResult } from './reauth';
// E16-T02 报告单运行时：添加报告单对话框、位置选择（必填多选树）与患者内清单；
// E16-T05 全院报告单列表与检索（A-ATT-016 / C-SCAN-014）；
// E21-T15 电子化状态手工修改（C-SCAN-011）；E21-T16 待归档区（C-SCAN-009）
export {
  DIGITIZE_CHANGE_OPTIONS,
  digitizeChangeError,
  digitizeChangeRequiresReason,
  foldReportLocations,
  reportInboxArchiveError,
  reportInboxSizeText,
  reportIndicatorFlag,
  reportLocationText,
  reportRefRangeFor,
  reportRowDigitizeStatusText,
} from './report';

export type {
  MobileUploadIssue,
  MobileUploadStatus,
  PatientReportsView,
  ReportAnchorType,
  ReportBatchCreatedView,
  ReportBatchCreateRequest,
  ReportBatchItemOverride,
  ReportBatchMode,
  ReportCreatedView,
  ReportCreateRequest,
  ReportDeletedView,
  ReportDigitizeChangedView,
  ReportDigitizeChangeRequest,
  ReportFirstFile,
  ReportInboxArchiveRequest,
  ReportInboxIntakeRequest,
  ReportInboxIntakeView,
  ReportInboxPage,
  ReportInboxRow,
  ReportInboxSearchRequest,
  ReportIndicatorInput,
  ReportLinkType,
  ReportLocationInput,
  ReportLocationNode,
  ReportLocationRemoveInput,
  ReportLocationsChangedView,
  ReportLocationsChangeRequest,
  ReportLocationScope,
  ReportLocationTag,
  ReportRow,
  ReportRowDigitizeStatus,
  ReportSearchFirstFile,
  ReportSearchPage,
  ReportSearchRequest,
  ReportSearchRow,
} from './report';
// E16-T01 报告单设置：资料类型、名称字典与指标模板（A-ATT-003、011、012、018；C-SCAN-002、003、004）
export {
  REPORT_CODE_PATTERN,
  REPORT_CONFIG_STATUS_TEXT,
  REPORT_DIGITIZE_STATUS_TEXT,
  reportConfigStatusText,
  reportDigitizeStatusText,
  reportNameRequiredText,
  reportRefRangeText,
  validateReportTypeCode,
} from './report-config';

export type {
  ReportCatalogField,
  ReportCatalogName,
  ReportCatalogType,
  ReportConfigStatus,
  ReportDigitizeStatus,
  ReportIndicatorItemRequest,
  ReportIndicatorItemView,
  ReportIndicatorTplView,
  ReportNameCreateRequest,
  ReportNameUpdateRequest,
  ReportNameView,
  ReportTplCreateRequest,
  ReportTplUpdateRequest,
  ReportTypeCreateRequest,
  ReportTypeUpdateRequest,
  ReportTypeView,
} from './report-config';
export type { OnlineSessionView } from './session';
// E15-T02 / A-FU-004、A-FU-005、C-FU-020：我的任务、任务列表与随访工作台；
// E15-T06 / A-FU-013、A-FU-026：临时添加任务与站内提醒（无需填表）
export {
  isTaskAcknowledgable,
  isTaskClaimable,
  isTaskEnterable,
  isTaskLostCandidate,
  isTaskReassignable,
  TASK_OPEN_STATUSES,
  TASK_STATUS_OPTIONS,
  TASK_STATUS_TEXT,
  TASK_TABS,
  TASK_TYPE_TEXT,
  taskAssignItemLabel,
  taskAssignOutcomeText,
  taskAssignSummary,
  taskDisplayStatus,
  taskStatusTooltip,
  taskWindowText,
  TEMP_TASK_DEFAULT_WINDOW_DAYS,
  TEMP_TASK_MAX_WINDOW_DAYS,
} from './task';
export type {
  BackfillCreateRequest,
  BackfillCreateView,
  RecordDetailView,
  RecordDeviceContext,
  RecordHighlightHit,
  RecordPatientBrief,
  RecordPreviousView,
  RecordSubmitResult,
  RecordTaskContext,
  RecordTimepointSuggestionView,
  TaskAcknowledgeView,
  TaskAssignItem,
  TaskAssignRequest,
  TaskAssignResult,
  TaskClaimView,
  TaskCompleteKind,
  TaskLostConfirmView,
  TaskPage,
  TaskQueueView,
  TaskRow,
  TaskScope,
  TaskSearchRequest,
  TaskStartView,
  TaskStatus,
  TaskTabCounts,
  TaskType,
  TaskWardOption,
  TempTaskCreateRequest,
  TempTaskView,
} from './task';
export {
  TENANT_ROLE_SCOPE_OPTIONS,
  tenantFieldPolicyText,
  tenantRoleScopeTypeText,
  tenantRoleTypeHint,
  tenantRoleTypeText,
} from './tenant-role';

export type {
  FieldPolicy,
  RoleScopeType,
  RoleStatus,
  RoleType,
  TenantRoleCopyRequest,
  TenantRoleFieldPoliciesSaveRequest,
  TenantRoleFieldPoliciesView,
  TenantRoleFieldPolicyEntry,
  TenantRoleFieldPolicySaveItem,
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
  FieldPolicyPreviewView,
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
export * from './tenant-view';
// E22-T16 / 等保 D-01 前端面：上传失败分类与行级文案（病毒检出 40001 / 扫描不可用 50300）
export type { UploadFailureInfo, UploadFailureKind } from './upload-rejection';
export { uploadFailureInfo, uploadFailureText } from './upload-rejection';
export {
  failedUserRows,
  USER_IMPORT_COLUMNS,
  userImportActionText,
  userImportSummary,
} from './user-import';
export type {
  UserImportAction,
  UserImportReport,
  UserImportRowResult,
} from './user-import';
export { menuNodesToRoutes, menuNodeToRoute, toIconifyIcon } from './vben-menu';
export type { VbenMenuRoute } from './vben-menu';
export * from './view-rule';

export {
  buildTransferPayload,
  buildWardPayload,
  emptyTransferForm,
  emptyWardForm,
  parseAliasInput,
  transferDeptTargetOptions,
  transferSuccessText,
  transferTargetOptions,
  validateTransferForm,
  validateWardForm,
  WARD_STATUS_TEXT,
  WARD_TYPE_TEXT,
  wardDisableBlockedText,
  wardFormOf,
  wardTypeText,
} from './ward';
export type {
  PatientDeptHistory,
  PatientDeptHistoryItem,
  PatientTransferPayload,
  PatientTransferredResult,
  PatientWardHistory,
  PatientWardHistoryItem,
  TransferWardFormState,
  WardCreateRequest,
  WardFormErrors,
  WardFormState,
  WardMembersView,
  WardMemberView,
  WardStatusRequest,
  WardType,
  WardUpdateRequest,
  WardView,
} from './ward';

// E09-T06 租户 Webhook（A-NTF-008、C-NTF-008、PLT-CFG-007）
export {
  isWebhookUrlLikelyValid,
  WEBHOOK_EVENT_OPTIONS,
  webhookDeliveryStatusText,
  webhookEventLabel,
} from './webhook';
export type {
  WebhookDeliveryPage,
  WebhookDeliveryStatus,
  WebhookDeliveryView,
  WebhookEventOption,
  WebhookRedeliverResult,
  WebhookSaveRequest,
  WebhookSaveResult,
  WebhookTestResult,
  WebhookView,
} from './webhook';
