/**
 * E04-T07 / PLT-AUTH-011：忘记密码流程的校验与步骤门控（纯逻辑，便于单测）。
 * 规则与后端 HospitalPasswordForgotRequest / HospitalPasswordResetRequest 一致；
 * 密码策略提示复用 @crdp/api 的 passwordPolicyViolations（与后端 PasswordPolicy 同口径）。
 */

import { passwordPolicyViolations } from '@crdp/api';

import {
  SMS_CODE_PATTERN,
  SMS_COOLDOWN_SECONDS,
  TENANT_CODE_PATTERN,
} from './sms-login-form';

export { SMS_CODE_PATTERN, SMS_COOLDOWN_SECONDS, TENANT_CODE_PATTERN };

/** 与后端请求的 username 校验一致：字母开头，2–64 位字母 / 数字 / . _ -。 */
export const USERNAME_PATTERN = /^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/;

export interface ForgotSendState {
  /** 冷却剩余秒数。 */
  countdown: number;
  sending: boolean;
  tenantCode: string;
  username: string;
}

/** 「获取验证码」可点：租户编码与账号合法、不在发送中、不在冷却内。 */
export function canRequestResetCode(state: ForgotSendState): boolean {
  return (
    !state.sending &&
    state.countdown <= 0 &&
    TENANT_CODE_PATTERN.test(state.tenantCode) &&
    USERNAME_PATTERN.test(state.username)
  );
}

export interface ResetSubmitState {
  code: string;
  confirmPassword: string;
  newPassword: string;
}

/**
 * 「重置密码」可点：验证码 6 位、两次输入一致、新密码满足策略（长度 / 复杂度 / 不含账号）。
 * 历史密码（最近 5 次）只能由后端比对哈希，这里不预判。
 */
export function canSubmitReset(
  state: ResetSubmitState,
  username: string,
): boolean {
  if (!SMS_CODE_PATTERN.test(state.code)) {
    return false;
  }
  if (!state.newPassword || state.newPassword !== state.confirmPassword) {
    return false;
  }
  return passwordPolicyViolations(state.newPassword, username).length === 0;
}
