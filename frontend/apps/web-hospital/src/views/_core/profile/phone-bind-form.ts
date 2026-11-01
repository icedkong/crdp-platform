/**
 * E06-T11 / PLT-ORG-011：个人中心换绑手机号表单的校验与发送按钮状态（纯逻辑，便于单测）。
 * 规则与后端 ProfileService 一致：手机号 ^1\d{10}$、验证码 6 位数字、60 秒冷却。
 */
import {
  PHONE_PATTERN,
  SMS_CODE_PATTERN,
  SMS_COOLDOWN_SECONDS,
} from '../authentication/sms-login-form';

export { PHONE_PATTERN, SMS_CODE_PATTERN, SMS_COOLDOWN_SECONDS };

/** 换绑表单状态。 */
export interface PhoneBindState {
  /** 验证码。 */
  code: string;
  /** 冷却剩余秒数。 */
  countdown: number;
  /** 新手机号。 */
  phone: string;
  /** 发送中。 */
  sending: boolean;
}

/** 「获取验证码」可点：新号合法、不在发送中、不在冷却内。 */
export function canRequestBindCode(state: PhoneBindState): boolean {
  return (
    !state.sending && state.countdown <= 0 && PHONE_PATTERN.test(state.phone)
  );
}

/** 「确认换绑」可点：新号与验证码都合法、不在提交中。 */
export function canSubmitBind(
  state: PhoneBindState,
  submitting: boolean,
): boolean {
  return (
    !submitting &&
    PHONE_PATTERN.test(state.phone) &&
    SMS_CODE_PATTERN.test(state.code)
  );
}

/** 姓名修改校验：1–64 字符。 */
export function isValidDisplayName(name: null | string | undefined): boolean {
  const value = (name ?? '').trim();
  return value.length > 0 && value.length <= 64;
}
