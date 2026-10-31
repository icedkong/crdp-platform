import { describe, expect, it } from 'vitest';

import {
  subdomainPreview,
  suggestTenantCode,
  TENANT_CODE_PATTERN,
  validateInitialPassword,
  validateTenantCode,
} from './wizard';

describe('e02-T07 新建租户向导逻辑', () => {
  it('生成的编码建议值符合租户编码规则且不重复', () => {
    const seen = new Set<string>();
    for (let i = 0; i < 100; i++) {
      const code = suggestTenantCode();
      expect(TENANT_CODE_PATTERN.test(code)).toBe(true);
      seen.add(code);
    }
    expect(seen.size).toBe(100);
  });

  it('编码校验：非法输入给出错误信息，合法输入通过', () => {
    expect(validateTenantCode('demoa')).toBeUndefined();
    expect(validateTenantCode('t1_x')).toBeUndefined();
    expect(validateTenantCode('DemoA')).toBeDefined();
    expect(validateTenantCode('1abc')).toBeDefined();
    expect(validateTenantCode('')).toBeDefined();
  });

  it('初始口令校验：8 至 64 位', () => {
    expect(validateInitialPassword('Init@2026')).toBeUndefined();
    expect(validateInitialPassword('short')).toBeDefined();
    expect(validateInitialPassword('x'.repeat(65))).toBeDefined();
  });

  it('访问地址预览展示租户子域名（PLT-TEN-015）', () => {
    expect(subdomainPreview('demoa')).toBe('demoa.<平台域名>');
  });
});
