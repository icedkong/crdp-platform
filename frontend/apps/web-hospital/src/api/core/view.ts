import type {
  TenantViewExchangeResult,
  TenantViewExitResult,
  TenantViewShellSession,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 医院端租户视角（E20-T04 / PLT-VIEW-002、003，docs/api/platform.md §6）：
 * 进入凭据（60 秒一次性）换视角令牌、横幅会话信息与一键退出。
 * 视角令牌只在凭据换取响应出现一次，随后放 accessStore（与其他会话令牌同一位）。
 */

/** 凭 ticket 换视角令牌（公开口；凭据取出即作废，失败统一 40001）。 */
export function enterTenantViewByTicket(ticket: string) {
  return requestClient.post<TenantViewExchangeResult>(apiPath('/view/enter'), {
    ticket,
  });
}

/** 视角会话信息（PLT-VIEW-003 横幅与水印口径）。 */
export function getTenantViewSession() {
  return requestClient.get<TenantViewShellSession>(apiPath('/view/session'));
}

/** 一键退出租户视角（只退自己的会话；台账 ENDED / 主动退出）。 */
export function exitTenantViewSession() {
  return requestClient.delete<TenantViewExitResult>(apiPath('/view/session'));
}
