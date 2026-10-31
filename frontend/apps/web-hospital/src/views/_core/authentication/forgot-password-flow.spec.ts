/**
 * E04-T07 / PLT-AUTH-011：忘记密码流程门控逻辑的单测。
 * 口径与后端一致：租户编码 / 账号格式（HospitalPasswordForgotRequest）、
 * 验证码 6 位 + 新密码策略（HospitalPasswordResetRequest + PasswordPolicy）。
 */

import { describe, expect, it } from 'vitest';

import {
  canRequestResetCode,
  canSubmitReset,
  USERNAME_PATTERN,
} from './forgot-password-flow';

const SEND_OK = {
  countdown: 0,
  sending: false,
  tenantCode: 'demoa',
  username: 'doctor.demo',
};

describe('canRequestResetCode（第一步：获取验证码）', () => {
  it('租户编码与账号都合法时可点', () => {
    expect(canRequestResetCode(SEND_OK)).toBe(true);
  });

  it('发送中或冷却中不可点', () => {
    expect(canRequestResetCode({ ...SEND_OK, sending: true })).toBe(false);
    expect(canRequestResetCode({ ...SEND_OK, countdown: 30 })).toBe(false);
  });

  it('租户编码不合法不可点（大写 / 空 / 带中文）', () => {
    expect(canRequestResetCode({ ...SEND_OK, tenantCode: 'DemoA' })).toBe(
      false,
    );
    expect(canRequestResetCode({ ...SEND_OK, tenantCode: '' })).toBe(false);
    expect(canRequestResetCode({ ...SEND_OK, tenantCode: '医院' })).toBe(false);
  });

  it('账号不合法不可点（数字开头 / 过短 / 非法字符）', () => {
    expect(canRequestResetCode({ ...SEND_OK, username: '1doctor' })).toBe(
      false,
    );
    expect(canRequestResetCode({ ...SEND_OK, username: 'd' })).toBe(false);
    expect(canRequestResetCode({ ...SEND_OK, username: 'doctor demo' })).toBe(
      false,
    );
  });
});

describe('canSubmitReset（第二步：验证码 + 新密码）', () => {
  const SUBMIT_OK = {
    code: '123456',
    confirmPassword: 'New@2027x',
    newPassword: 'New@2027x',
  };

  it('验证码、两次密码与策略全部满足时可提交', () => {
    expect(canSubmitReset(SUBMIT_OK, 'doctor.demo')).toBe(true);
  });

  it('验证码必须 6 位数字', () => {
    expect(canSubmitReset({ ...SUBMIT_OK, code: '12345' }, 'doctor.demo')).toBe(
      false,
    );
    expect(
      canSubmitReset({ ...SUBMIT_OK, code: '12345a' }, 'doctor.demo'),
    ).toBe(false);
    expect(canSubmitReset({ ...SUBMIT_OK, code: '' }, 'doctor.demo')).toBe(
      false,
    );
  });

  it('两次输入不一致不可提交', () => {
    expect(
      canSubmitReset(
        { ...SUBMIT_OK, confirmPassword: 'New@2027y' },
        'doctor.demo',
      ),
    ).toBe(false);
  });

  it('新密码不满足策略不可提交（过短 / 类别不足 / 包含账号）', () => {
    expect(
      canSubmitReset(
        { ...SUBMIT_OK, confirmPassword: 'Ab@123', newPassword: 'Ab@123' },
        'doctor.demo',
      ),
    ).toBe(false);
    // 三类字符但包含账号
    expect(
      canSubmitReset(
        {
          ...SUBMIT_OK,
          confirmPassword: 'Doctor.Demo26',
          newPassword: 'Doctor.Demo26',
        },
        'doctor.demo',
      ),
    ).toBe(false);
    // 只有两类字符（大写 + 数字），长度达标
    expect(
      canSubmitReset(
        { ...SUBMIT_OK, confirmPassword: 'ABCD1234', newPassword: 'ABCD1234' },
        'doctor.demo',
      ),
    ).toBe(false);
  });
});

describe('uSERNAME_PATTERN（账号格式与后端一致）', () => {
  it('字母开头的字母/数字/._- 组合合法', () => {
    expect(USERNAME_PATTERN.test('doctor.demo')).toBe(true);
    expect(USERNAME_PATTERN.test('A-1_2.3')).toBe(true);
  });

  it('数字开头 / 含空格 / 单字符不合法', () => {
    expect(USERNAME_PATTERN.test('1abc')).toBe(false);
    expect(USERNAME_PATTERN.test('a b')).toBe(false);
    expect(USERNAME_PATTERN.test('a')).toBe(false);
  });
});
