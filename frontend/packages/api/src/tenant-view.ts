/**
 * E20-T01 / PLT-VIEW-001、004、006：租户视角会话与账号负责租户的前端类型与纯逻辑，
 * 与后端 `com.crdp.server.platform.TenantViewService` / `PlatformAccountService` 视图同构
 * （docs/api/platform.md §6）。
 *
 * 会话时长（PLT-VIEW-004）：单次最长 2 小时（expiresAt = 开始 + 2 小时）、空闲 15 分钟
 * 自动退出（服务端 Redis TTL 执行，前端按 remainingSeconds 倒计时展示）。
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
