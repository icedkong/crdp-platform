import type {
  CaptchaImage,
  LoginResult,
  Me,
  OnlineSessionView,
} from '@crdp/api';

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
    captchaCode: data.captchaCode,
    captchaId: data.captchaId,
    password: data.password,
    tenantCode: data.tenantCode,
    username: data.username,
  });
}

/** E04-T05 / PLT-AUTH-001、007：取图形验证码（PNG data URL，连续失败 3 次后登录必填）。 */
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

/**
 * E04-T07 / PLT-AUTH-011：忘记密码第一步——把重置验证码发到账号绑定手机号。
 * 无论账号是否存在、是否绑定手机号都算成功（防探测）；60 秒冷却与每日上限超限 42900。
 */
export async function requestPasswordResetSmsApi(params: {
  tenantCode: string;
  username: string;
}) {
  return requestClient.post<null>(apiPath('/auth/password/forgot'), {
    tenantCode: params.tenantCode,
    username: params.username,
  });
}

/**
 * E04-T07 / PLT-AUTH-011：忘记密码第二步——验证码 + 新密码重置。
 * 验证码错误 / 未知账号统一 40102；成功后该账号全部会话失效，需用新密码重新登录。
 */
export async function resetPasswordApi(params: {
  code: string;
  newPassword: string;
  tenantCode: string;
  username: string;
}) {
  return requestClient.post<null>(apiPath('/auth/password/reset'), {
    code: params.code,
    newPassword: params.newPassword,
    tenantCode: params.tenantCode,
    username: params.username,
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

// ---- E06-T11 / PLT-ORG-011：个人中心「在线会话」页签（接口 E04-T06 已落地） ----

/** 本人在线会话（只返回会话号，不返回令牌）。 */
export async function getMySessionsApi() {
  return requestClient.get<OnlineSessionView[]>(apiPath('/auth/sessions'));
}

/** 下线自己的某个会话（被下线令牌下次请求即 40100；写审计）。 */
export async function revokeMySessionApi(sessionId: string) {
  return requestClient.delete<OnlineSessionView>(
    apiPath(`/auth/sessions/${sessionId}`),
  );
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
