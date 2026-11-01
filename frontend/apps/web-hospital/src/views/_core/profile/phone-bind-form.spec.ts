import type { PhoneBindState } from './phone-bind-form';

import { describe, expect, it } from 'vitest';

import {
  canRequestBindCode,
  canSubmitBind,
  isValidDisplayName,
} from './phone-bind-form';

/** E06-T11 / PLT-ORG-011：换绑手机号表单状态与姓名校验（纯逻辑）。 */
describe('e06-T11 个人中心表单逻辑', () => {
  const base: PhoneBindState = {
    code: '',
    countdown: 0,
    phone: '13900000099',
    sending: false,
  };

  it('获取验证码按钮状态：新号合法、不在冷却、不在发送中', () => {
    expect(canRequestBindCode(base)).toBe(true);
    expect(canRequestBindCode({ ...base, phone: '12345' })).toBe(false);
    expect(canRequestBindCode({ ...base, countdown: 30 })).toBe(false);
    expect(canRequestBindCode({ ...base, sending: true })).toBe(false);
  });

  it('确认换绑按钮状态：新号与 6 位验证码都合法且不在提交中', () => {
    expect(canSubmitBind({ ...base, code: '123456' }, false)).toBe(true);
    expect(canSubmitBind({ ...base, code: '12345' }, false)).toBe(false);
    expect(canSubmitBind({ ...base, code: '123456' }, true)).toBe(false);
    expect(
      canSubmitBind({ ...base, code: '123456', phone: '123' }, false),
    ).toBe(false);
  });

  it('姓名校验：1–64 字符，去首尾空白', () => {
    expect(isValidDisplayName('示例医生')).toBe(true);
    expect(isValidDisplayName('  示例医生  ')).toBe(true);
    expect(isValidDisplayName('')).toBe(false);
    expect(isValidDisplayName('   ')).toBe(false);
    expect(isValidDisplayName('长'.repeat(64))).toBe(true);
    expect(isValidDisplayName('长'.repeat(65))).toBe(false);
  });
});
