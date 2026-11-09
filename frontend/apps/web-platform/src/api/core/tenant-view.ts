import type {
  TenantViewActiveSession,
  TenantViewEnterRequest,
  TenantViewEnterResult,
  TenantViewEntryTicket,
  TenantViewExitResult,
  TenantViewSessionPage,
  TenantViewSessionRecord,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户视角会话（E20-T01 / PLT-VIEW-001、004；E20-T04 / PLT-VIEW-002；E20-T05 / PLT-VIEW-016，
 * docs/api/platform.md §6）：进入（选目的 + 填说明）、本人进行中会话（倒计时）、
 * 签发医院端进入凭据（60 秒一次性）与一键退出；会话台账（审计中心「视角会话」，仅平台侧）。
 * 单次最长 2 小时、空闲 15 分钟自动退出（服务端执行）。
 */
export function enterTenantView(data: TenantViewEnterRequest) {
  return requestClient.post<TenantViewEnterResult>(
    apiPath('/tenant-view/sessions'),
    data,
  );
}

/** 本人进行中的视角会话（无进行中会话时 data 为 null）。 */
export function currentTenantView() {
  return requestClient.get<null | TenantViewActiveSession>(
    apiPath('/tenant-view/sessions/current'),
  );
}

/**
 * 签发医院端进入凭据（E20-T04 / PLT-VIEW-002）：60 秒一次性 ticket，
 * 前端拼 hospitalUrl + /view/entry#ticket=… 新标签页打开医院端视角页。
 */
export function createTenantViewEntryTicket() {
  return requestClient.post<TenantViewEntryTicket>(
    apiPath('/tenant-view/sessions/current/entry-ticket'),
  );
}

/** 一键退出（只允许退自己的进行中会话；他人 / 已结束 40400）。 */
export function exitTenantView(sessionId: number) {
  return requestClient.delete<TenantViewExitResult>(
    apiPath(`/tenant-view/sessions/${sessionId}`),
  );
}

/**
 * 视角会话台账列表（E20-T05 / PLT-VIEW-016 审计中心「视角会话」，权限
 * platform:view:session:view）：平台账号、租户、目的、起止时间、结束方式与计数摘要。
 */
export function listTenantViewSessions(params?: {
  accountName?: string;
  pageNo?: number;
  pageSize?: number;
  purpose?: string;
  status?: string;
  tenantId?: number;
}) {
  const query: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== '') {
      query[key] = value;
    }
  }
  return requestClient.get<TenantViewSessionPage>(
    apiPath('/tenant-view/sessions'),
    { params: query },
  );
}

/** 会话台账详情（含生效规则版本与计数摘要；未知 40400）。 */
export function getTenantViewSession(sessionId: number) {
  return requestClient.get<TenantViewSessionRecord>(
    apiPath(`/tenant-view/sessions/${sessionId}`),
  );
}
