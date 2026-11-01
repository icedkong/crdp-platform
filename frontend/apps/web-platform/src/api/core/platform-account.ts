import type {
  PlatformAccountResetResult,
  PlatformAccountSaveRequest,
  PlatformAccountView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 平台账号（E06-T04 / PLT-ROLE-001）：手机号必填（双因素）、只绑平台角色、
 * 支持停用 / 启用、重置密码（临时密码只返回一次）与解锁。手机号只回脱敏值。
 */
export function listPlatformAccounts() {
  return requestClient.get<PlatformAccountView[]>(apiPath('/accounts'));
}

export function getPlatformAccount(id: number) {
  return requestClient.get<PlatformAccountView>(apiPath(`/accounts/${id}`));
}

export function createPlatformAccount(data: PlatformAccountSaveRequest) {
  return requestClient.post<PlatformAccountView>(apiPath('/accounts'), data);
}

export function updatePlatformAccount(
  id: number,
  data: PlatformAccountSaveRequest,
) {
  return requestClient.put<PlatformAccountView>(
    apiPath(`/accounts/${id}`),
    data,
  );
}

/** 重置密码：生成一次性临时密码，首次登录强制改密，该账号全部会话失效并解除锁定。敏感操作：需二次认证（X-Reauth-Token，E04-T08）。 */
export function resetPlatformAccountPassword(
  id: number,
  headers?: Record<string, string>,
) {
  return requestClient.post<PlatformAccountResetResult>(
    apiPath(`/accounts/${id}/reset-password`),
    {},
    { headers },
  );
}

/** 解锁登录锁定（连续失败 5 次锁 30 分钟，可提前解锁）。 */
export function unlockPlatformAccount(id: number) {
  return requestClient.post<{ unlocked: boolean; username: string }>(
    apiPath(`/accounts/${id}/unlock`),
    {},
  );
}
