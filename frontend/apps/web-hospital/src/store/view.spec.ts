// E20-T04 / PLT-VIEW-002、003：医院端视角 store（换令牌装壳、倒计时到 0 先刷新实际状态、
// 一键退出与令牌失效落地退出页）
import type { TenantViewExchangeResult } from '@crdp/api';

import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const replaceMock = vi.fn();

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: replaceMock }),
}));

const enterMock = vi.fn();
const sessionMock = vi.fn();
const exitMock = vi.fn();

vi.mock('#/api', () => ({
  enterTenantViewByTicket: (...args: unknown[]) => enterMock(...args),
  exitTenantViewSession: (...args: unknown[]) => exitMock(...args),
  getTenantViewSession: (...args: unknown[]) => sessionMock(...args),
}));

// resetAllStores 会触发所有 pinia store 的 $reset（含本 store 的定时器清理），用真实实现。

function exchange(remainingSeconds = 300): TenantViewExchangeResult {
  return {
    expiresInSeconds: remainingSeconds,
    session: {
      accountDisplayName: '平台管理员（示例）',
      accountName: 'platform.admin',
      expiresAt: '2026-11-06T12:00:00+08:00',
      purpose: 'TROUBLESHOOT',
      remainingSeconds,
      sessionId: 7,
      sessionNo: 'a1b2c3d4e5f60718',
      startedAt: '2026-11-06T10:00:00+08:00',
      tenantCode: 'demoa',
      tenantId: 1,
      tenantName: '虚构示例医院甲',
    },
    token: 'view-token-1',
    tokenType: 'Bearer',
  };
}

async function setupStore() {
  const { useTenantViewStore } = await import('./view');
  return useTenantViewStore();
}

describe('医院端视角 store（E20-T04）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setActivePinia(createPinia());
  });

  it('凭据换取装壳：令牌进 accessStore、会话与剩余时长就位（PLT-VIEW-002）', async () => {
    enterMock.mockResolvedValue(exchange(300));
    const store = await setupStore();
    const { useAccessStore } = await import('@vben/stores');

    const session = await store.exchangeAndAdopt('ticket-1');

    expect(enterMock).toHaveBeenCalledWith('ticket-1');
    expect(useAccessStore().accessToken).toBe('view-token-1');
    expect(store.active).toBe(true);
    expect(session.sessionNo).toBe('a1b2c3d4e5f60718');
    expect(store.remainingSeconds).toBe(300);
  });

  it('倒计时每秒递减；到 0 不直接退出——先回服务端刷新（期间请求会滑动续期）', async () => {
    vi.useFakeTimers();
    try {
      enterMock.mockResolvedValue(exchange(2));
      sessionMock.mockResolvedValue(exchange(120).session);
      const store = await setupStore();
      await store.exchangeAndAdopt('ticket-1');

      vi.advanceTimersByTime(1000);
      expect(store.remainingSeconds).toBe(1);
      expect(sessionMock).not.toHaveBeenCalled();

      vi.advanceTimersByTime(1000);
      expect(store.remainingSeconds).toBe(0);
      // 刷新拿回新窗口：继续倒计时而不是落地退出
      await vi.advanceTimersByTimeAsync(0);
      expect(sessionMock).toHaveBeenCalledTimes(1);
      expect(store.active).toBe(true);
      expect(store.remainingSeconds).toBe(120);
    } finally {
      vi.useRealTimers();
    }
  });

  it('刷新发现会话已死：清令牌并落地退出页（EXPIRED）', async () => {
    vi.useFakeTimers();
    try {
      enterMock.mockResolvedValue(exchange(1));
      sessionMock.mockRejectedValue(new Error('40100'));
      const store = await setupStore();
      const { useAccessStore } = await import('@vben/stores');
      await store.exchangeAndAdopt('ticket-1');

      await vi.advanceTimersByTimeAsync(1500);
      await vi.advanceTimersByTimeAsync(0);

      expect(store.active).toBe(false);
      expect(useAccessStore().accessToken).toBeNull();
      expect(replaceMock).toHaveBeenCalledWith({
        path: '/view/exit',
        query: { reason: 'EXPIRED' },
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('一键退出：调退出接口（失败也落地）并清本端状态（PLT-VIEW-003）', async () => {
    exitMock.mockResolvedValue({ endMethod: 'LOGOUT', sessionId: 7 });
    const store = await setupStore();
    const { useAccessStore } = await import('@vben/stores');
    store.adopt(exchange(300).session);
    useAccessStore().setAccessToken('view-token-1');

    await store.exit('LOGOUT');

    expect(exitMock).toHaveBeenCalledTimes(1);
    expect(store.active).toBe(false);
    expect(useAccessStore().accessToken).toBeNull();
    expect(replaceMock).toHaveBeenCalledWith({
      path: '/view/exit',
      query: { reason: 'LOGOUT' },
    });
  });

  it('页面刷新后 rehydrate：按令牌找回会话；失败保持空（不误报视角模式）', async () => {
    sessionMock.mockResolvedValue(exchange(60).session);
    const store = await setupStore();
    await store.rehydrate();
    expect(store.active).toBe(true);
    expect(store.session?.sessionNo).toBe('a1b2c3d4e5f60718');

    sessionMock.mockRejectedValue(new Error('40100'));
    store.$reset();
    await store.rehydrate();
    expect(store.active).toBe(false);
  });
});
