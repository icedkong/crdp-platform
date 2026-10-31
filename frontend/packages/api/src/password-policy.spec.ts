import { describe, expect, it } from 'vitest';

import {
  passwordCategoryCount,
  passwordPolicyViolations,
  passwordRuleItems,
} from './password-policy';

describe('e04-T05 / PLT-AUTH-006 密码策略实时提示', () => {
  it('合规密码无违规项', () => {
    expect(passwordPolicyViolations('Change@2026', 'doctor.demo')).toEqual([]);
    expect(passwordPolicyViolations('Abc12345', 'doctor.demo')).toEqual([]);
  });

  it('过短密码报长度', () => {
    expect(passwordPolicyViolations('Ab@12', 'doctor.demo')).toEqual([
      '密码长度至少 8 位',
    ]);
  });

  it('字符类别不足报复杂度', () => {
    expect(passwordPolicyViolations('abc12345', 'doctor.demo')).toEqual([
      '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类',
    ]);
  });

  it('包含账号被拒（忽略大小写；局部段不算）', () => {
    expect(
      passwordPolicyViolations('XyDoctor.Demo@26', 'doctor.demo'),
    ).toContain('密码不能包含账号');
    expect(passwordPolicyViolations('Xydoctor@99', 'doctor.demo')).toEqual([]);
  });

  it('多项违规一起报出', () => {
    expect(passwordPolicyViolations('doc', 'doctor.demo')).toEqual([
      '密码长度至少 8 位',
      '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类',
    ]);
  });

  it('租户加严最小长度后按更大值校验', () => {
    expect(
      passwordPolicyViolations('Ab@1234567z', 'doctor.demo', { minLength: 12 }),
    ).toEqual(['密码长度至少 12 位']);
  });

  it('空密码只报前两项', () => {
    expect(passwordPolicyViolations('', 'doctor.demo')).toHaveLength(2);
    expect(passwordPolicyViolations(null, null)).toHaveLength(2);
  });

  it('字符类别计数：非字母数字算特殊字符', () => {
    expect(passwordCategoryCount('abc123')).toBe(2);
    expect(passwordCategoryCount('abc123!@#')).toBe(3);
    expect(passwordCategoryCount('Aa1!')).toBe(4);
    expect(passwordCategoryCount('')).toBe(0);
  });

  it('规则清单逐条打勾', () => {
    expect(
      passwordRuleItems('Change@2026', 'doctor.demo').map((item) => item.ok),
    ).toEqual([true, true, true]);
    const items = passwordRuleItems('abc', 'doctor.demo');
    expect(items.map((item) => item.ok)).toEqual([false, false, true]);
  });
});
