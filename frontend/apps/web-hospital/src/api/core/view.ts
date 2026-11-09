import type {
  TenantViewExchangeResult,
  TenantViewExitResult,
  TenantViewProxyResetResult,
  TenantViewShellSession,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 医院端租户视角（E20-T04 / PLT-VIEW-002、003；E20-T06 / PLT-VIEW-017，docs/api/platform.md §6）：
 * 进入凭据（60 秒一次性）换视角令牌、横幅会话信息与一键退出；
 * 代重置医院用户密码走视角专用代理口（新密码只经短信发给用户本人，响应不含新密码）。
 * 视角令牌只在凭据换取响应出现一次，随后放 accessStore（与其他会话令牌同一位）。
 */

/** 凭 ticket 换视角令牌（公开口；凭据取出即作废，失败统一 40001）。 */
export function enterTenantViewByTicket(ticket: string) {
  return requestClient.post<TenantViewExchangeResult>(apiPath('/view/enter'), {
    ticket,
  });
}

/** 视角会话信息（PLT-VIEW-003 横幅与水印口径；E20-T06 起带代为操作能力）。 */
export function getTenantViewSession() {
  return requestClient.get<TenantViewShellSession>(apiPath('/view/session'));
}

/** 一键退出租户视角（只退自己的会话；台账 ENDED / 主动退出）。 */
export function exitTenantViewSession() {
  return requestClient.delete<TenantViewExitResult>(apiPath('/view/session'));
}

/** 代重置医院用户密码（PLT-VIEW-017）：只回账号与脱敏手机号（短信去向），绝不回新密码。 */
export function proxyResetUserPassword(userId: number, reason: string) {
  return requestClient.post<TenantViewProxyResetResult>(
    apiPath(`/view/users/${userId}/reset-password`),
    { reason },
  );
}
