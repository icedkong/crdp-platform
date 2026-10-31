import { describe, expect, it } from 'vitest';

import {
  CAPTCHA_LENGTH,
  USERNAME_PATTERN,
  validCaptchaInput,
} from './login-rules';

describe('e04-T05 平台端登录表单规则', () => {
  it('账号规则与后端一致（字母开头，2–64 位）', () => {
    expect(USERNAME_PATTERN.test('platform.admin')).toBe(true);
    expect(USERNAME_PATTERN.test('a')).toBe(false);
    expect(USERNAME_PATTERN.test('1abc')).toBe(false);
  });

  it('图形验证码出现后必须 4 位', () => {
    expect(CAPTCHA_LENGTH).toBe(4);
    expect(validCaptchaInput(true, 'AB3D')).toBe(true);
    expect(validCaptchaInput(true, 'AB3')).toBe(false);
    expect(validCaptchaInput(true, '')).toBe(false);
    // 未触发验证码时不要求
    expect(validCaptchaInput(false, '')).toBe(true);
  });
});
