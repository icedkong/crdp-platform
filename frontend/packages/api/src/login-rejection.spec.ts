import { describe, expect, it } from 'vitest';

import {
  errorBody,
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from './login-rejection';

describe('e04-T05 / PLT-AUTH-007 登录失败提示解析', () => {
  it('40101 带验证码要求与剩余次数（data 在顶层，requestClient 的错误形态）', () => {
    expect(
      loginRejectionInfo({
        data: {
          code: 40_101,
          message: '租户、账号或密码不正确',
          data: { captchaRequired: true, remainingAttempts: 2 },
        },
      }),
    ).toEqual({ captchaRequired: true, remainingAttempts: 2 });
  });

  it('40101 未到验证码阈值时 captchaRequired 为 false', () => {
    expect(
      loginRejectionInfo({
        data: {
          code: 40_101,
          data: { captchaRequired: false, remainingAttempts: 4 },
        },
      }),
    ).toEqual({ captchaRequired: false, remainingAttempts: 4 });
  });

  it('兼容 axios 形态的错误（响应体在 response.data）', () => {
    expect(
      loginRejectionInfo({
        response: {
          data: {
            code: 40_101,
            data: { captchaRequired: false, remainingAttempts: 3 },
          },
        },
      }),
    ).toEqual({ captchaRequired: false, remainingAttempts: 3 });
  });

  it('42900 锁定带剩余秒数', () => {
    expect(
      loginRejectionInfo({
        data: { code: 42_900, data: { lockedSeconds: 1740 } },
      }),
    ).toEqual({
      captchaRequired: true,
      remainingAttempts: 0,
      lockedSeconds: 1740,
    });
  });

  it('其他错误码与空错误返回 null', () => {
    expect(loginRejectionInfo({ data: { code: 40_102 } })).toBeNull();
    expect(loginRejectionInfo(null)).toBeNull();
    expect(loginRejectionInfo(undefined)).toBeNull();
  });

  it('锁定文案按分钟向上取整', () => {
    expect(lockedMessage(1740)).toBe(
      '账号已锁定，请 29 分钟后再试或联系管理员解锁',
    );
    expect(lockedMessage(30)).toBe(
      '账号已锁定，请 1 分钟后再试或联系管理员解锁',
    );
    expect(lockedMessage(undefined)).toBeNull();
    expect(lockedMessage(0)).toBeNull();
  });

  it('剩余次数提示在 1–4 次时显示', () => {
    expect(remainingAttemptsMessage(4)).toBe('还可尝试 4 次');
    expect(remainingAttemptsMessage(1)).toBe('还可尝试 1 次');
    expect(remainingAttemptsMessage(5)).toBeNull();
    expect(remainingAttemptsMessage(0)).toBeNull();
    expect(remainingAttemptsMessage(undefined)).toBeNull();
  });

  it('errorBody 取出统一响应体', () => {
    expect(
      errorBody({ data: { code: 40_101, message: 'x', data: null } }),
    ).toEqual({ code: 40_101, message: 'x', data: null });
    expect(errorBody({})).toEqual({});
  });
});
