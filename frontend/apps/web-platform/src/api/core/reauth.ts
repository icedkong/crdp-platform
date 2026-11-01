/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证（平台端，与医院端同构）。 密码再次验证通过后换取 5 分钟有效、绑定当前会话的
 * reauthToken；平台敏感操作（审计导出提交 / 下载、重置账号密码等）通过 ReauthModal 携带 X-Reauth-Token 发起。
 */
import type { ReauthResult } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 密码换 reauthToken（docs/api/auth.md §1）。密码错误 42200 且计入登录失败计数。 */
export function requestReauth(password: string) {
  return requestClient.post<ReauthResult>(apiPath('/auth/reauth'), {
    password,
  });
}
