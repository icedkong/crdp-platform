import type {
  TenantViewActiveSession,
  TenantViewEnterRequest,
  TenantViewEnterResult,
  TenantViewEntryTicket,
  TenantViewExitResult,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户视角会话（E20-T01 / PLT-VIEW-001、004；E20-T04 / PLT-VIEW-002，
 * docs/api/platform.md §6）：进入（选目的 + 填说明）、本人进行中会话（倒计时）、
 * 签发医院端进入凭据（60 秒一次性）与一键退出。
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
