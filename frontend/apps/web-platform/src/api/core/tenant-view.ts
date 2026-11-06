import type {
  TenantViewActiveSession,
  TenantViewEnterRequest,
  TenantViewEnterResult,
  TenantViewExitResult,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户视角会话（E20-T01 / PLT-VIEW-001、004，docs/api/platform.md §6）：
 * 进入（选目的 + 填说明 → 视角令牌只出现一次）、本人进行中会话（倒计时）、一键退出。
 * 单次最长 2 小时、空闲 15 分钟自动退出（服务端执行；菜单与视角界面随 E20-T04）。
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

/** 一键退出（只允许退自己的进行中会话；他人 / 已结束 40400）。 */
export function exitTenantView(sessionId: number) {
  return requestClient.delete<TenantViewExitResult>(
    apiPath(`/tenant-view/sessions/${sessionId}`),
  );
}
