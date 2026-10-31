import { describe, expect, it } from 'vitest';

import {
  canRequestSmsCode,
  PHONE_PATTERN,
  SMS_CODE_PATTERN,
  SMS_COOLDOWN_SECONDS,
  TENANT_CODE_PATTERN,
} from './sms-login-form';

/** E04-T04 / PLT-AUTH-002：短信登录表单规则与发送按钮状态（与后端校验一致）。 */
describe('sms-login-form', () => {
  it('手机号、验证码、租户编码规则与后端一致', () => {
    expect(PHONE_PATTERN.test('13900000001')).toBe(true);
    expect(PHONE_PATTERN.test('1390000000')).toBe(false);
    expect(PHONE_PATTERN.test('23900000001')).toBe(false);

    expect(SMS_CODE_PATTERN.test('123456')).toBe(true);
    expect(SMS_CODE_PATTERN.test('12345')).toBe(false);
    expect(SMS_CODE_PATTERN.test('12345a')).toBe(false);

    expect(TENANT_CODE_PATTERN.test('demoa')).toBe(true);
    expect(TENANT_CODE_PATTERN.test('DemoA')).toBe(false);
  });

  it('冷却时长与后端配置一致（60 秒）', () => {
    expect(SMS_COOLDOWN_SECONDS).toBe(60);
  });

  it('两项都合法且不在发送中、不在冷却内才能获取验证码', () => {
    const ready = {
      countdown: 0,
      phone: '13900000001',
      sending: false,
      tenantCode: 'demoa',
    };
    expect(canRequestSmsCode(ready)).toBe(true);
    expect(canRequestSmsCode({ ...ready, countdown: 59 })).toBe(false);
    expect(canRequestSmsCode({ ...ready, sending: true })).toBe(false);
    expect(canRequestSmsCode({ ...ready, phone: '1390000000' })).toBe(false);
    expect(canRequestSmsCode({ ...ready, tenantCode: '' })).toBe(false);
  });
});
