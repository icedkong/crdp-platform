import type { CaptchaImage, LoginResult, Me } from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  export interface LoginParams {
    /** E04-T05 / PLT-AUTH-007：连续失败 3 次后必填（后端 40101 的 data.captchaRequired 提示）。 */
    captchaCode?: string;
    captchaId?: string;
    password?: string;
    tenantCode?: string;
    username?: string;
  }

  export interface LoginResultView {
    accessToken: string;
  }

  /** E04-T04 / PLT-AUTH-003：双因素第二步参数。 */
  export interface TwoFactorVerifyParams {
    challengeId: string;
    code: string;
  }
}

/** 双因素开启时返回 challengeId 而不是 token（分流逻辑见 @crdp/api auth-flow）。 */
export async function loginApi(data: AuthApi.LoginParams) {
  const result = await requestClient.post<LoginResult>(apiPath('/auth/login'), {
    captchaCode: data.captchaCode,
    captchaId: data.captchaId,
    password: data.password,
    username: data.username,
  });
  return result;
}

/** E04-T05 / PLT-AUTH-007：取图形验证码（PNG data URL，连续失败 3 次后登录必填）。 */
export async function getCaptchaApi() {
  return requestClient.get<CaptchaImage>(apiPath('/auth/captcha'));
}

/** E04-T05 / PLT-AUTH-006、010：修改密码（首次登录强制改密走同一接口）。 */
export async function changePasswordApi(params: {
  newPassword: string;
  oldPassword: string;
}) {
  return requestClient.post<null>(apiPath('/auth/password'), {
    newPassword: params.newPassword,
    oldPassword: params.oldPassword,
  });
}

/** 平台账号强制双因素的第二步：验证短信验证码后换会话令牌（E04-T04 / PLT-AUTH-005）。 */
export async function verifyTwoFactorApi(data: AuthApi.TwoFactorVerifyParams) {
  return requestClient.post<LoginResult>(apiPath('/auth/2fa/verify'), {
    challengeId: data.challengeId,
    code: data.code,
  });
}

export async function logoutApi() {
  const accessStore = useAccessStore();
  return baseRequestClient.post(apiPath('/auth/logout'), null, {
    headers: accessStore.accessToken
      ? { Authorization: `Bearer ${accessStore.accessToken}` }
      : {},
  });
}

export async function getAccessCodesApi() {
  return requestClient.get<string[]>(apiPath('/auth/codes'));
}

export type { Me };
