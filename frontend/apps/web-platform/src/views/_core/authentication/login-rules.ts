/**
 * E04-T03 / PLT-AUTH-005：平台端登录表单的校验规则（纯逻辑，便于单测）。
 * 与后端 PlatformLoginRequest 的校验一致；E04-T05 增加图形验证码字段（连续失败 3 次后必填）。
 */

/** 与后端 PlatformLoginRequest.username 一致：字母开头，2–64 位。 */
export const USERNAME_PATTERN = /^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/;

/** 图形验证码固定 4 位（后端 CaptchaStore 渲染 4 字符）。 */
export const CAPTCHA_LENGTH = 4;

export const loginRules = {
  password: [{ message: '密码至少 8 位', min: 8, trigger: 'blur' }],
  username: [
    { message: '账号格式不正确', pattern: USERNAME_PATTERN, trigger: 'blur' },
  ],
};

/** 图形验证码输入的合法性（连续失败 3 次后才出现，出现后必填）。 */
export function validCaptchaInput(
  captchaRequired: boolean,
  captchaCode: string,
): boolean {
  if (!captchaRequired) {
    return true;
  }
  return captchaCode.trim().length === CAPTCHA_LENGTH;
}
