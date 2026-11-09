import { describe, expect, it } from 'vitest';

import { resolveLoginRedirect } from './login-redirect';

/**
 * E22-T11 / PT-SELF-07：登录回跳目标只放行站内路径。
 * redirect 参数来自 URL 查询串（未认证用户可任意构造），守卫不能直接把它当
 * 跳转目标——外部绝对地址与协议相对形式一律回落首页，防止钓鱼跳转。
 */
describe('resolveLoginRedirect', () => {
  const fallback = '/dashboard';

  it('站内路径放行（含编码后的完整路径）', () => {
    expect(resolveLoginRedirect('/followup/patient/1001', fallback)).toBe(
      '/followup/patient/1001',
    );
    // encodeURIComponent 只编码一次：解码后 query 值里的 %2F 保持编码形态（路由器再解）
    expect(
      resolveLoginRedirect(
        encodeURIComponent('/followup/task?a=1&b=%2F'),
        fallback,
      ),
    ).toBe('/followup/task?a=1&b=%2F');
  });

  it('空值 / 空白回落 fallback', () => {
    expect(resolveLoginRedirect(undefined, fallback)).toBe(fallback);
    expect(resolveLoginRedirect(null, fallback)).toBe(fallback);
    expect(resolveLoginRedirect('', fallback)).toBe(fallback);
    expect(resolveLoginRedirect('   ', fallback)).toBe(fallback);
  });

  it('外部绝对地址回落 fallback（http / https / 其他协议）', () => {
    for (const bad of [
      'https://evil.com/phish',
      'http://evil.com',
      'HTTPS://EVIL.COM',
      'javascript:alert(1)',
      'data:text/html,<script>alert(1)</script>',
    ]) {
      expect(resolveLoginRedirect(bad, fallback)).toBe(fallback);
    }
  });

  it('协议相对与反斜杠变体回落 fallback', () => {
    for (const bad of [
      '//evil.com',
      String.raw`/\evil.com`,
      String.raw`\\evil.com`,
      encodeURIComponent('//evil.com'),
      encodeURIComponent(String.raw`/\evil.com`),
    ]) {
      expect(resolveLoginRedirect(bad, fallback)).toBe(fallback);
    }
  });

  it('首尾空白容忍：修剪后合法的站内路径放行', () => {
    expect(resolveLoginRedirect(' /followup/task ', fallback)).toBe(
      '/followup/task',
    );
  });

  it('不以斜杠开头的相对片段回落 fallback', () => {
    expect(resolveLoginRedirect('followup/patient', fallback)).toBe(fallback);
    expect(resolveLoginRedirect('dashboard', fallback)).toBe(fallback);
  });

  it('畸形百分号编码不抛异常，按不可解码原值处理', () => {
    expect(() => resolveLoginRedirect('/a%zz', fallback)).not.toThrow();
    expect(resolveLoginRedirect('/a%zz', fallback)).toBe('/a%zz');
    expect(resolveLoginRedirect('%zz', fallback)).toBe(fallback);
  });
});
