import type {
  PlatformAccountResetResult,
  PlatformAccountSaveRequest,
  PlatformAccountView,
  PlatformResponsibleTenant,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 平台账号（E06-T04 / PLT-ROLE-001）：手机号必填（双因素）、只绑平台角色、
 * 支持停用 / 启用、重置密码（临时密码只返回一次）与解锁。手机号只回脱敏值。
 * 负责租户多选（PLT-VIEW-006，E20-T01）：租户范围为「负责的租户」的角色只能进入这些租户。
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

/** 账号负责租户（PLT-VIEW-006）：账号多选的租户（含编码与名称供回显）。 */
export function listResponsibleTenants(id: number) {
  return requestClient.get<PlatformResponsibleTenant[]>(
    apiPath(`/accounts/${id}/responsible-tenants`),
  );
}

/**
 * 维护账号负责租户（整体替换，PLT-VIEW-006，权限 platform:view:grant:edit）：
 * 负责关系变更即时生效——该账号进行中的视角会话立即结束（结束方式=权限变更）。
 */
export function saveResponsibleTenants(id: number, tenantIds: number[]) {
  return requestClient.put<PlatformResponsibleTenant[]>(
    apiPath(`/accounts/${id}/responsible-tenants`),
    { tenantIds },
  );
}
