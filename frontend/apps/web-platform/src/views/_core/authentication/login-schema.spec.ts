import { describe, expect, it } from 'vitest';

import {
  PASSWORD_RULE,
  platformLoginPayload,
  platformLoginSchema,
  USERNAME_RULE,
} from './login-schema';

describe('platformLoginSchema', () => {
  it('只有账号与密码字段：平台端独立登录，不出现医院端的租户编码', () => {
    const fields = platformLoginSchema()
      .map((item) => item.fieldName)
      .toSorted();
    expect(fields).toEqual(['password', 'username']);
  });

  it('字段规则与后端 PlatformLoginRequest 校验一致', () => {
    const schema = platformLoginSchema();
    expect(schema.find((item) => item.fieldName === 'username')?.rules).toBe(
      USERNAME_RULE,
    );
    expect(schema.find((item) => item.fieldName === 'password')?.rules).toBe(
      PASSWORD_RULE,
    );
  });
});

describe('账号规则（USERNAME_RULE）', () => {
  it('接受字母开头的账号，拒绝其余输入', () => {
    expect(USERNAME_RULE.safeParse('platform.admin').success).toBe(true);
    expect(USERNAME_RULE.safeParse('D2').success).toBe(true);
    expect(USERNAME_RULE.safeParse('1admin').success).toBe(false);
    expect(USERNAME_RULE.safeParse('a b').success).toBe(false);
    expect(USERNAME_RULE.safeParse('中').success).toBe(false);
  });
});

describe('密码规则（PASSWORD_RULE）', () => {
  it('长度 8–64 位', () => {
    expect(PASSWORD_RULE.safeParse('Demo@2026').success).toBe(true);
    expect(PASSWORD_RULE.safeParse('short').success).toBe(false);
    expect(PASSWORD_RULE.safeParse('x'.repeat(65)).success).toBe(false);
  });
});

describe('platformLoginPayload', () => {
  it('只提交账号与密码：误带的租户字段不进平台登录请求', () => {
    expect(
      platformLoginPayload({
        password: 'Demo@2026',
        tenantCode: 'demoa',
        username: 'platform.admin',
      }),
    ).toEqual({ password: 'Demo@2026', username: 'platform.admin' });
  });

  it('缺失字段按空串提交，交由后端校验拒绝', () => {
    expect(platformLoginPayload({ tenantCode: 'demoa' })).toEqual({
      password: '',
      username: '',
    });
  });
});
