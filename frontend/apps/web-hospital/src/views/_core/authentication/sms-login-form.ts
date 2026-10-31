/**
 * E04-T04 / PLT-AUTH-002：医院端短信登录表单的校验规则与发送按钮状态（纯逻辑，便于单测）。
 * 规则与后端 HospitalSmsRequest / HospitalSmsLoginRequest 的校验一致。
 */

/** 与后端 HospitalLoginRequest.tenantCode 一致。 */
export const TENANT_CODE_PATTERN = /^[a-z][a-z0-9]{1,31}$/;

/** 与后端 HospitalSmsRequest.phone 一致：11 位手机号。 */
export const PHONE_PATTERN = /^1\d{10}$/;

/** 与后端 HospitalSmsLoginRequest.code 一致：6 位数字。 */
export const SMS_CODE_PATTERN = /^\d{6}$/;

/** 与后端 crdp.security.sms-resend-cooldown-seconds 一致：60 秒。 */
export const SMS_COOLDOWN_SECONDS = 60;

export interface SmsSendState {
  /** 冷却剩余秒数。 */
  countdown: number;
  phone: string;
  sending: boolean;
  tenantCode: string;
}

/** 「获取验证码」可点：表单两项合法、不在发送中、不在冷却内。 */
export function canRequestSmsCode(state: SmsSendState): boolean {
  return (
    !state.sending &&
    state.countdown <= 0 &&
    TENANT_CODE_PATTERN.test(state.tenantCode) &&
    PHONE_PATTERN.test(state.phone)
  );
}
