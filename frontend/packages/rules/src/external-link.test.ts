import { describe, expect, it, vi } from 'vitest';

import { isExternalHttpLink, openExternalLink } from './external-link';

/**
 * E22-T11 / PT-SELF-06：站内信等入口打开外部链接必须带 noopener（防反向标签钓鱼），
 * 且只放行 http(s)（大小写不敏感），javascript: / data: / 相对路径一律不交给
 * window.open（open 的第一个参数可被构造成伪协议执行）。
 */
describe('openExternalLink', () => {
  it('http(s) 链接以 _blank + noopener,noreferrer 打开', () => {
    const open = vi.fn();
    const ok = openExternalLink('https://partner.example.com/notice', open);
    expect(ok).toBe(true);
    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith(
      'https://partner.example.com/notice',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('大小写不敏感放行 HTTP(S)', () => {
    const open = vi.fn();
    expect(openExternalLink('HTTPS://EXAMPLE.COM/x', open)).toBe(true);
    expect(openExternalLink('Http://example.com/x', open)).toBe(true);
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('伪协议与相对路径不打开', () => {
    const open = vi.fn();
    for (const bad of [
      'javascript:alert(1)',
      'data:text/html,<script>alert(1)</script>',
      '//evil.com',
      '/internal/path',
      'relative/path',
      '',
    ]) {
      expect(openExternalLink(bad, open)).toBe(false);
    }
    expect(open).not.toHaveBeenCalled();
  });
});

describe('isExternalHttpLink', () => {
  it('仅 http(s) 绝对地址为真', () => {
    expect(isExternalHttpLink('https://a.com')).toBe(true);
    expect(isExternalHttpLink('http://a.com')).toBe(true);
    expect(isExternalHttpLink('HTTP://A.COM')).toBe(true);
    expect(isExternalHttpLink('javascript:alert(1)')).toBe(false);
    expect(isExternalHttpLink('//a.com')).toBe(false);
    expect(isExternalHttpLink('/a')).toBe(false);
    expect(isExternalHttpLink(undefined)).toBe(false);
  });
});
