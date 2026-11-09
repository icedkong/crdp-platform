import { describe, expect, it } from 'vitest';

import { ACCESS_PERSIST_OPTIONS } from './access';

/**
 * E22-T11 / PT-SELF-05：会话令牌持久化防漂移锁定。
 *
 * 攻击面：accessToken（含租户视角令牌，≤2 小时有效）原 pick 进 localStorage——
 * SecureLS 的密钥内联在构建产物里，只算混淆不算加密，医院共享工位上关闭浏览器
 * 后令牌仍在盘上，TTL 内可被下一位使用者或本地恶意软件恢复并复用会话。
 *
 * 修复口径（本测试锁定；access.ts 的接线由 scripts/check_pentest_remediation_frontend.py
 * 静态断言 `persist: ACCESS_PERSIST_OPTIONS`，与测试互为防漂移）：
 * 1. access store 持久化载体必须是 sessionStorage（标签页内延续、关闭即清；
 *    同仓库 tabbar store 已有 per-store storage 覆盖先例）；
 * 2. lockScreenPassword（口令）与 refreshToken（enableRefreshToken 全局关闭，
 *    恒为 null）不得出现在 pick；
 * 3. accessToken / accessCodes 保留（刷新不掉线、按钮显隐不闪烁）。
 */
describe('access store 持久化安全配置', () => {
  it('持久化载体是 sessionStorage，不是 localStorage', () => {
    expect(ACCESS_PERSIST_OPTIONS.storage).toBe(globalThis.sessionStorage);
    expect(ACCESS_PERSIST_OPTIONS.storage).not.toBe(globalThis.localStorage);
  });

  it('pick 不含 lockScreenPassword 与 refreshToken', () => {
    expect(ACCESS_PERSIST_OPTIONS.pick).not.toContain('lockScreenPassword');
    expect(ACCESS_PERSIST_OPTIONS.pick).not.toContain('refreshToken');
  });

  it('pick 保留 accessToken 与 accessCodes（刷新不掉线）', () => {
    expect(ACCESS_PERSIST_OPTIONS.pick).toContain('accessToken');
    expect(ACCESS_PERSIST_OPTIONS.pick).toContain('accessCodes');
  });
});
