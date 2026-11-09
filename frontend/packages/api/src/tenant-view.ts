/**
 * E20-T01 / PLT-VIEW-001、004、006 + E20-T04 / PLT-VIEW-002、003 + E20-T06 / PLT-VIEW-013、017、018：
 * 租户视角会话、账号负责租户与代为操作的前端类型与纯逻辑，与后端
 * `com.crdp.server.platform.TenantViewService` / `TenantViewProxyService` 视图同构
 * （docs/api/platform.md §6）。
 *
 * 会话时长（PLT-VIEW-004）：单次最长 2 小时（expiresAt = 开始 + 2 小时）、空闲 15 分钟
 * 自动退出（服务端 Redis TTL 执行，前端按 remainingSeconds 倒计时展示）。
 *
 * 医院端视角外壳（E20-T04）：进入凭据一次性（60 秒）换视角令牌、横幅（租户名称 / 会话号 /
 * 剩余时长 / 一键退出）与全页水印（平台账号 + 会话号）的纯逻辑也在这里，平台端与医院端共用。
 *
 * 代为操作（E20-T06，验收「默认关闭，开启需授权并记审计」）：会话携带 capabilities
 * （账号启用角色并集）；视角模式下写按钮按 `tenantViewWritePermission` 合成显隐、写请求带
 * `X-View-Reason`（encodeURIComponent 百分号编码，后端解码）。
 */

/** 查看目的（PLT-VIEW-001，进入弹窗单选）。 */
export type TenantViewPurpose =
  | 'CONFIG_CHECK'
  | 'OTHER'
  | 'TRAINING'
  | 'TROUBLESHOOT';

/** 目的选项（弹窗与文案同口径，后端审计用枚举值）。 */
export const TENANT_VIEW_PURPOSE_OPTIONS: {
  label: string;
  value: TenantViewPurpose;
}[] = [
  { label: '排障', value: 'TROUBLESHOOT' },
  { label: '配置核对', value: 'CONFIG_CHECK' },
  { label: '培训支持', value: 'TRAINING' },
  { label: '其他', value: 'OTHER' },
];

/** 目的文案。 */
export function tenantViewPurposeText(purpose: string): string {
  return (
    TENANT_VIEW_PURPOSE_OPTIONS.find((option) => option.value === purpose)
      ?.label ?? purpose
  );
}

/** 结束方式（platform-spec §8 视角会话「结束方式」）。 */
export type TenantViewEndMethod = 'IDLE' | 'LOGOUT' | 'PERM_CHANGE' | 'TIMEOUT';

/** 结束方式文案。 */
export function tenantViewEndMethodText(method: string): string {
  switch (method) {
    case 'IDLE': {
      return '空闲超时';
    }
    case 'LOGOUT': {
      return '主动退出';
    }
    case 'PERM_CHANGE': {
      return '权限变更';
    }
    case 'TIMEOUT': {
      return '到时';
    }
    default: {
      return method;
    }
  }
}

/** 进入请求（PLT-VIEW-001）：租户 + 目的 + 说明（说明必填 ≤500）。 */
export interface TenantViewEnterRequest {
  note: string;
  purpose: TenantViewPurpose;
  tenantId: number;
}

/** 进入结果：视角令牌只出现一次；expiresAt = 开始 + 2 小时（PLT-VIEW-004）。 */
export interface TenantViewEnterResult {
  expiresAt: string;
  expiresInSeconds: number;
  hospitalUrl: string;
  purpose: TenantViewPurpose;
  sessionId: number;
  sessionNo: string;
  startedAt: string;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 进行中的视角会话（倒计时与一键退出用；无进行中会话时 data 为 null）。 */
export interface TenantViewActiveSession {
  expiresAt: string;
  note: string;
  purpose: TenantViewPurpose;
  remainingSeconds: number;
  roleCodes: string[];
  sessionId: number;
  sessionNo: string;
  startedAt: string;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 退出结果（结束方式回显）。 */
export interface TenantViewExitResult {
  endMethod: TenantViewEndMethod;
  sessionId: number;
  sessionNo: string;
}

// ---------------------------------------------------------------- E20-T05 / PLT-VIEW-016：会话台账（仅平台侧）

/** 视角会话台账行（审计中心「视角会话」：平台账号、租户、目的、起止时间、结束方式与计数摘要）。 */
export interface TenantViewSessionRecord {
  accountId: number;
  accountName: string;
  deniedCount: number;
  displayName: string;
  endAt: null | string;
  endMethod: null | TenantViewEndMethod;
  endsAt: string;
  id: number;
  note: string;
  pageCount: number;
  proxyCount: number;
  purpose: TenantViewPurpose;
  queryCount: number;
  roleCodes: string;
  ruleVersion: string;
  sessionNo: string;
  startedAt: string;
  status: 'ACTIVE' | 'ENDED';
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 会话台账分页。 */
export interface TenantViewSessionPage {
  items: TenantViewSessionRecord[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 会话状态文案。 */
export function tenantViewSessionStatusText(status: null | string): string {
  switch (status) {
    case 'ACTIVE': {
      return '进行中';
    }
    case 'ENDED': {
      return '已结束';
    }
    default: {
      return status ?? '—';
    }
  }
}

/** 账号负责租户（PLT-VIEW-006）。 */
export interface PlatformResponsibleTenant {
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 按租户反查负责人（platform-spec §8「平台账号管理 · 负责租户」）。 */
export interface PlatformResponsibleOwner {
  accountId: number;
  displayName: string;
  status: string;
  username: string;
}

// ---------------------------------------------------------------- E20-T04 / PLT-VIEW-002、003：医院端视角外壳

/** 医院端视角入口路由（平台端以 hospitalUrl + 本路径 + #ticket=… 打开）。 */
export const TENANT_VIEW_ENTRY_PATH = '/view/entry';

/** 医院端视角退出页路由（一键退出 / 到时 / 失效后落地）。 */
export const TENANT_VIEW_EXIT_PATH = '/view/exit';

/** 进入凭据（60 秒一次性，平台端为本人的进行中会话签发）。 */
export interface TenantViewEntryTicket {
  expiresInSeconds: number;
  hospitalUrl: string;
  ticket: string;
}

/**
 * 医院端视角会话信息（PLT-VIEW-003 横幅与水印口径）：
 * 会话号、租户、剩余时长与平台账号，GET /api/hospital/view/session 与凭据换取响应同构。
 */
export interface TenantViewShellSession {
  accountDisplayName: string;
  accountName: string;
  expiresAt: string;
  purpose: TenantViewPurpose;
  remainingSeconds: number;
  sessionId: number;
  sessionNo: string;
  startedAt: string;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 凭据换取结果（视角令牌只在本次响应出现一次）。 */
export interface TenantViewExchangeResult {
  expiresInSeconds: number;
  session: TenantViewShellSession;
  token: string;
  tokenType: string;
}

/** 视角退出原因（退出页提示与台账结束方式对应）。 */
export type TenantViewExitReason =
  | 'EXPIRED'
  | 'IDLE'
  | 'LOGOUT'
  | 'PERM_CHANGE'
  | 'TIMEOUT';

/** 退出原因文案（与后端 endMethodText 同口径 + 令牌失效兜底）。 */
export function tenantViewExitReasonText(reason: null | string): string {
  switch (reason) {
    case 'EXPIRED': {
      return '视角会话已失效，请从平台端重新进入';
    }
    case 'IDLE': {
      return '视角会话空闲超时（15 分钟无操作），已自动退出';
    }
    case 'LOGOUT': {
      return '你已退出租户视角';
    }
    case 'PERM_CHANGE': {
      return '视角权限已变更，会话立即结束';
    }
    case 'TIMEOUT': {
      return '视角会话已到时（单次最长 2 小时），到时可重新进入';
    }
    default: {
      return '视角会话已结束，请从平台端重新进入';
    }
  }
}

/**
 * 从 URL 片段解析进入凭据（platform 端打开 /view/entry#ticket=…）。
 * 只认片段（#），不读查询串——凭据不进服务端访问日志；无凭据返回 null。
 */
export function parseTenantViewEntryTicket(hash: string): null | string {
  const value = hash.startsWith('#') ? hash.slice(1) : hash;
  const match = /^ticket=([A-Za-z0-9_-]+)$/.exec(value);
  return match?.[1] ?? null;
}

/** 拼医院端视角入口地址（片段携带一次性凭据）。 */
export function tenantViewEntryUrl(
  hospitalUrl: string,
  ticket: string,
): string {
  const base = hospitalUrl.replace(/\/+$/u, '');
  return `${base}${TENANT_VIEW_ENTRY_PATH}#ticket=${encodeURIComponent(ticket)}`;
}

/** 横幅标题（PLT-VIEW-003）：「租户视角：租户名称」。 */
export function tenantViewBannerTitle(session: TenantViewShellSession): string {
  return `租户视角：${session.tenantName}`;
}

/**
 * 全页水印文案（PLT-VIEW-003「页面水印含平台账号与会话号」）：
 * `平台账号显示名（账号）· 视角会话 会话号`，与报告单水印「姓名（账号）· …」同款式。
 */
export function tenantViewWatermarkText(
  session: TenantViewShellSession,
): string {
  const account = session.accountDisplayName || session.accountName;
  return `${account}（${session.accountName}）· 视角会话 ${session.sessionNo}`;
}

/** 说明长度上限（与后端同口径）。 */
const NOTE_MAX = 500;

/**
 * 进入表单校验（前端先行，与后端 requirePurpose / requireNote 同口径）：
 * 目的必选、说明必填 ≤500。返回错误信息（空数组 = 通过）。
 */
export function validateTenantViewEnter(model: {
  note: string;
  purpose: null | string;
}): string[] {
  const errors: string[] = [];
  if (!model.purpose) {
    errors.push('请选择查看目的');
  } else if (
    !TENANT_VIEW_PURPOSE_OPTIONS.some(
      (option) => option.value === model.purpose,
    )
  ) {
    errors.push('查看目的只允许 排障 / 配置核对 / 培训支持 / 其他');
  }
  if (!model.note.trim()) {
    errors.push('请填写查看说明（必填）');
  } else if (model.note.trim().length > NOTE_MAX) {
    errors.push(`查看说明过长（最多 ${NOTE_MAX} 个字符）`);
  }
  return errors;
}

/**
 * 剩余时长文案（倒计时展示）：≥1 分钟按「X 分 Y 秒」，不足 1 分钟按秒；
 * 非正数返回「已到时」（到时可重新进入）。
 */
export function tenantViewRemainingText(remainingSeconds: number): string {
  if (remainingSeconds <= 0) return '已到时';
  const minutes = Math.floor(remainingSeconds / 60);
  const seconds = remainingSeconds % 60;
  if (minutes <= 0) return `${seconds} 秒`;
  return `${minutes} 分 ${seconds} 秒`;
}

// ---------------------------------------------------------------- E20-T06 / PLT-VIEW-013、017、018：代为操作

/** 代为操作原因请求头（HTTP 头不承载非 ASCII，取值按 encodeURIComponent 百分号编码）。 */
export const TENANT_VIEW_REASON_HEADER = 'X-View-Reason';

/** 原因长度口径（与后端 requireReason 同口径）：≥2 字、≤500 字。 */
export const TENANT_VIEW_REASON_MIN = 2;

export const TENANT_VIEW_REASON_MAX = 500;

/** 头值编码（中文原因无损传输；后端 URLDecoder 解码）。 */
export function encodeTenantViewReason(reason: string): string {
  return encodeURIComponent(reason);
}

/**
 * 代为操作原因校验（前端先行，与后端 requireReason 同口径）：二次确认弹窗必填 2–500 字。
 * 返回错误信息（空数组 = 通过）。
 */
export function validateTenantViewProxyReason(reason: string): string[] {
  const value = reason.trim();
  if (value.length < TENANT_VIEW_REASON_MIN) {
    return [
      `请填写操作原因（${TENANT_VIEW_REASON_MIN}–${TENANT_VIEW_REASON_MAX} 字）`,
    ];
  }
  if (value.length > TENANT_VIEW_REASON_MAX) {
    return [`操作原因过长（最多 ${TENANT_VIEW_REASON_MAX} 个字符）`];
  }
  return [];
}

/** 对视角永不放行的动作段（与后端 TenantViewProxyService.NEVER_ACTIONS + 导出类动作同口径）。 */
const NEVER_ACTIONS = new Set([
  'approve',
  'download',
  'export',
  'plain',
  'reset',
  'view',
]);

/** 需医院端二次认证的写接口（@RequireReauth 40304 收口，视角模式不显示按钮）。 */
const REAUTH_CODES = new Set(['followup:patient:delete']);

/** 权限码动作段（{模块}:{资源}:{动作} 的最后一段）。 */
function actionOf(code: string): string {
  const at = code.lastIndexOf(':');
  return at === -1 ? code : code.slice(at + 1);
}

/** 权限码资源段（第二段；目录外资源返回 null）。 */
function resourceOf(code: string): null | string {
  const first = code.indexOf(':');
  const last = code.lastIndexOf(':');
  if (first <= 0 || last <= first) return null;
  return code.slice(first + 1, last);
}

/** 权限码模块段（第一段）。 */
function moduleOf(code: string): null | string {
  const at = code.indexOf(':');
  return at <= 0 ? null : code.slice(0, at);
}

/**
 * 视角模式下写接口的分类（与后端 `TenantViewProxyService.classify` 同口径）：
 * 'CONFIG' 代为配置（PLT-VIEW-013）/ 'BUSINESS' 编辑业务数据（PLT-VIEW-018）/ null 不放行
 * （明文、导出、医院侧重置口、审批等）。纯函数。
 */
export function tenantViewWriteClass(
  code: string,
): 'BUSINESS' | 'CONFIG' | null {
  if (!code) return null;
  const action = actionOf(code);
  if (NEVER_ACTIONS.has(action)) return null;
  const resource = resourceOf(code);
  const module = moduleOf(code);
  if (module === 'followup') {
    // followup 内的配置页：action=config（异常规则 / 通知 / 卡片 / 报告单配置）与字段字典
    return action === 'config' || resource === 'field' ? 'CONFIG' : 'BUSINESS';
  }
  // 后端接口登记里 tenant:*（系统配置类）落 base 模块——配置类；后续模块（如 rct）按业务数据
  // （platform-spec §8「后续模块数据按同一规则」，默认只读规则 fail closed）
  return module === 'base' || module === 'tenant' ? 'CONFIG' : 'BUSINESS';
}

/**
 * 视角模式下权限码判定（E20-T06）：读动作不合成（视角菜单驱动，E20-T04 口径）；写动作按
 * 「配置类 = 可代为配置 ∧ 资源在范围」「业务数据 = 可编辑业务数据 ∧ 可编辑规则命中」合成显隐
 * （PLT-VIEW-018 任一不满足即只读）。前端显隐只是体验，后端执行点逐请求校验。
 */
export function tenantViewWritePermission(
  capabilities: null | TenantViewProxyCapabilities | undefined,
  code: string,
): boolean {
  if (!capabilities || REAUTH_CODES.has(code)) return false;
  const writeClass = tenantViewWriteClass(code);
  if (writeClass === 'CONFIG') {
    return (
      capabilities.canConfigure &&
      (capabilities.configResources === null ||
        capabilities.configResources.includes(resourceOf(code) ?? ''))
    );
  }
  if (writeClass === 'BUSINESS') {
    return capabilities.canEditBusiness && capabilities.businessEditable;
  }
  return false;
}

/** 代为重置结果（PLT-VIEW-017）：只回账号与脱敏手机号（证明短信去向），绝不回新密码。 */
export interface TenantViewProxyResetResult {
  displayName: string;
  sentTo: string;
  userId: number;
  username: string;
}
