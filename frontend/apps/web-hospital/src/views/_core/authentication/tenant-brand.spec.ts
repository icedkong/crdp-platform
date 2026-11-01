import { describe, expect, it } from 'vitest';

import { loginBrandTitle, shouldFetchBrand } from './tenant-brand';

/** E06-T11 / PLT-CFG-008：登录页租户识别取数节奏与标题回落（纯逻辑）。 */
describe('e06-T11 登录页租户品牌', () => {
  it('编码合法才发起识别（公开口按 IP 限流，空 / 非法不发请求）', () => {
    expect(shouldFetchBrand('demoa')).toBe(true);
    expect(shouldFetchBrand(' demoa ')).toBe(true);
    expect(shouldFetchBrand('')).toBe(false);
    expect(shouldFetchBrand(null)).toBe(false);
    expect(shouldFetchBrand('DemoA')).toBe(false);
    expect(shouldFetchBrand('非法编码')).toBe(false);
  });

  it('未识别出租户回落平台标题，不提示租户不存在（防探测）', () => {
    expect(loginBrandTitle(true, '示例第一医院（北院）')).toBe(
      '示例第一医院（北院）',
    );
    expect(loginBrandTitle(false, '临床科研数据一体化平台')).toBe(
      '临床科研数据一体化平台',
    );
    expect(loginBrandTitle(false, null)).toBe('临床科研数据一体化平台');
  });
});
