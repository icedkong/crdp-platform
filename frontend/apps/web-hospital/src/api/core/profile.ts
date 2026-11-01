import type { LoginLogView, ProfileView } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T11 / PLT-ORG-011：个人中心接口（登录即可，契约见 docs/api/system.md §7）。
 * 修改密码（/auth/password）与在线会话（/auth/sessions）沿用 auth 接口，见 core/auth.ts。
 */

/** 本人基本信息（手机号只有脱敏值，不提供明文口）。 */
export function getProfile() {
  return requestClient.get<ProfileView>(apiPath('/profile'));
}

/** 修改本人姓名（账号不可改）。 */
export function updateDisplayName(displayName: string) {
  return requestClient.put<ProfileView>(apiPath('/profile'), { displayName });
}

/** 换绑第一步：验证码发到新手机号（60 秒冷却与每日上限超限 42900）。 */
export function requestPhoneBindSms(phone: string) {
  return requestClient.post<null>(apiPath('/profile/phone/sms'), { phone });
}

/** 换绑第二步：验证码通过后落新密文与盲索引（院内冲突 40902）。 */
export function rebindPhone(code: string, phone: string) {
  return requestClient.put<ProfileView>(apiPath('/profile/phone'), {
    code,
    phone,
  });
}

/** 本人最近登录记录（最近 20 条，不含 IP）。 */
export function getLoginLogs() {
  return requestClient.get<LoginLogView[]>(apiPath('/profile/login-logs'));
}
