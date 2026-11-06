import { describe, expect, it } from 'vitest';

import {
  PROVISION_TITLES,
  provisionSkipText,
  subdomainPreview,
  suggestTenantCode,
  templateOptionLabel,
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

describe('e12-T05 向导配置模板初始化展示口径', () => {
  it('开通步骤标题覆盖 config-template（其余步骤不变）', () => {
    expect(PROVISION_TITLES['config-template']).toBe('配置模板初始化');
    expect(PROVISION_TITLES.admin).toBe('账号初始化（首个管理员）');
    expect(PROVISION_TITLES['tenant-row']).toBe('租户登记');
  });

  it('sKIPPED 文案按步骤区分：admin 启用前补设、config-template 未选择模板', () => {
    expect(provisionSkipText('admin')).toBe('已跳过，启用前补设');
    expect(provisionSkipText('config-template')).toBe('未选择配置模板，已跳过');
    expect(provisionSkipText('database')).toBe('已跳过');
  });

  it('模板选项文案含名称、源租户与版本', () => {
    expect(
      templateOptionLabel({
        name: '演练模板（示例）',
        tenantName: '虚构示例医院甲',
        version: 2,
      }),
    ).toBe('演练模板（示例）（源：虚构示例医院甲 v2）');
  });
});
