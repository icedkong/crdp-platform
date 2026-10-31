import type { LoginResult, Me } from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  export interface LoginParams {
    password?: string;
    tenantCode?: string;
    username?: string;
  }

  /** E04-T04 / PLT-AUTH-002：短信验证码登录参数。 */
  export interface SmsLoginParams {
    code: string;
    phone: string;
    tenantCode: string;
  }

  /** E04-T04 / PLT-AUTH-003：双因素第二步参数。 */
  export interface TwoFactorVerifyParams {
    challengeId: string;
    code: string;
  }

  export interface LoginResultView {
    accessToken: string;
  }
}

/** 双因素开启时返回 challengeId 而不是 token（分流逻辑见 @crdp/api auth-flow）。 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<LoginResult>(apiPath('/auth/login'), {
    password: data.password,
    tenantCode: data.tenantCode,
    username: data.username,
  });
}

/** 发送登录短信验证码；无论手机号是否已绑定都算成功（防探测），冷却与日限超限 42900。 */
export async function sendLoginSmsApi(params: {
  phone: string;
  tenantCode: string;
}) {
  return requestClient.post<null>(apiPath('/auth/sms'), {
    phone: params.phone,
    tenantCode: params.tenantCode,
  });
}

/** 短信验证码登录（验证码 5 分钟内有效，手机号需已绑定且本院唯一）。 */
export async function loginBySmsApi(data: AuthApi.SmsLoginParams) {
  return requestClient.post<LoginResult>(apiPath('/auth/login/sms'), {
    code: data.code,
    phone: data.phone,
    tenantCode: data.tenantCode,
  });
}

/** 双因素第二步：验证短信验证码后换会话令牌。 */
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
